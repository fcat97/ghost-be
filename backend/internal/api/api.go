package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/gorilla/websocket"

	"github.com/ghostbe/backend/internal/certs"
	"github.com/ghostbe/backend/internal/db"
	"github.com/ghostbe/backend/internal/proxy"
)

type Server struct {
	router      chi.Router
	db          *db.DB
	certMgr     *certs.Manager
	proxy       *proxy.Proxy
	port        int
	dataDir     string
	caFile      string
	upgrader    websocket.Upgrader
	subscribers map[chan proxy.TrafficEvent]bool
}

type StatusResponse struct {
	Running        bool   `json:"running"`
	Port           int    `json:"port"`
	CAFingerprint  string `json:"ca_fingerprint"`
}

type AppStats struct {
	Package      string `json:"package"`
	RequestCount int    `json:"request_count"`
}

type TrafficResponse struct {
	Items []db.TrafficLog `json:"items"`
	Total int            `json:"total"`
}

func New(port int, database *db.DB, certMgr *certs.Manager, pxy *proxy.Proxy, dataDir string) *Server {
	r := chi.NewRouter()
	s := &Server{
		router:      r,
		db:          database,
		certMgr:     certMgr,
		proxy:       pxy,
		port:        port,
		dataDir:     dataDir,
		caFile:      filepath.Join(dataDir, "ca.crt"),
		upgrader:    websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }},
		subscribers: make(map[chan proxy.TrafficEvent]bool),
	}

	// Set up routes
	r.Get("/api/status", s.handleStatus)
	r.Get("/api/ca", s.handleCA)
	r.Get("/api/apps", s.handleApps)
	r.Get("/api/traffic", s.handleTraffic)
	r.Get("/api/rules", s.handleGetRules)
	r.Post("/api/rules", s.handleCreateRule)
	r.Put("/api/rules/{id}", s.handleUpdateRule)
	r.Delete("/api/rules/{id}", s.handleDeleteRule)
	r.Patch("/api/rules/{id}/toggle", s.handleToggleRule)
	r.Get("/ws/traffic", s.handleWebSocket)

	// Start traffic broadcaster
	go s.broadcastTraffic()

	return s
}

func (s *Server) Router() chi.Router {
	return s.router
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	resp := StatusResponse{
		Running:       true,
		Port:          s.port,
		CAFingerprint: s.certMgr.GetCAFingerprint(),
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func (s *Server) handleCA(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Disposition", "attachment; filename=ca.crt")
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeFile(w, r, s.caFile)
}

func (s *Server) handleApps(w http.ResponseWriter, r *http.Request) {
	stats, err := s.db.GetAppStats()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	apps := make([]AppStats, 0)
	for pkg, count := range stats {
		apps = append(apps, AppStats{
			Package:      pkg,
			RequestCount: count,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(apps)
}

func (s *Server) handleTraffic(w http.ResponseWriter, r *http.Request) {
	appPackage := r.URL.Query().Get("app")
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))

	if page < 1 {
		page = 1
	}
	if limit < 1 || limit > 100 {
		limit = 20
	}

	offset := (page - 1) * limit

	logs, total, err := s.db.GetTraffic(appPackage, limit, offset)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if logs == nil {
		logs = make([]db.TrafficLog, 0)
	}

	resp := TrafficResponse{
		Items: logs,
		Total: total,
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func (s *Server) handleGetRules(w http.ResponseWriter, r *http.Request) {
	appPackage := r.URL.Query().Get("app")
	rules, err := s.db.GetRules(appPackage)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if rules == nil {
		rules = make([]db.Rule, 0)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(rules)
}

func (s *Server) handleCreateRule(w http.ResponseWriter, r *http.Request) {
	var rule db.Rule
	if err := json.NewDecoder(r.Body).Decode(&rule); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	created, err := s.db.CreateRule(rule)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Update proxy rules
	s.updateProxyRules()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(created)
}

func (s *Server) handleUpdateRule(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.Atoi(chi.URLParam(r, "id"))

	var rule db.Rule
	if err := json.NewDecoder(r.Body).Decode(&rule); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	rule.ID = id
	updated, err := s.db.UpdateRule(rule)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Update proxy rules
	s.updateProxyRules()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(updated)
}

func (s *Server) handleDeleteRule(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.Atoi(chi.URLParam(r, "id"))

	if err := s.db.DeleteRule(id); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Update proxy rules
	s.updateProxyRules()

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleToggleRule(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.Atoi(chi.URLParam(r, "id"))

	updated, err := s.db.ToggleRule(id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Update proxy rules
	s.updateProxyRules()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(updated)
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()

	// Create channel for this subscriber
	eventChan := make(chan proxy.TrafficEvent, 10)
	s.subscribers[eventChan] = true
	defer func() {
		delete(s.subscribers, eventChan)
		close(eventChan)
	}()

	// Send events to WebSocket
	for event := range eventChan {
		if err := conn.WriteJSON(event); err != nil {
			return
		}
	}
}

func (s *Server) broadcastTraffic() {
	for event := range s.proxy.TrafficChan() {
		for subscriber := range s.subscribers {
			select {
			case subscriber <- event:
			default:
				// Subscriber channel full, skip
			}
		}
	}
}

func (s *Server) updateProxyRules() {
	rules, _ := s.db.GetRules("")
	s.proxy.UpdateRules(rules)
}

// ServeHTTP implements http.Handler for embedding frontend
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.router.ServeHTTP(w, r)
}
