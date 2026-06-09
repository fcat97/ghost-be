package proxy

import (
	"bufio"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/ghostbe/backend/internal/certs"
	"github.com/ghostbe/backend/internal/db"
	"github.com/ghostbe/backend/internal/rules"
)

type TrafficEvent struct {
	Timestamp      time.Time `json:"timestamp"`
	AppPackage     string    `json:"app_package"`
	Method         string    `json:"method"`
	URL            string    `json:"url"`
	ResponseStatus int       `json:"response_status"`
	LatencyMs      int64     `json:"latency_ms"`
	Mocked         bool      `json:"mocked"`
}

type Proxy struct {
	port         int
	db           *db.DB
	engine       *rules.Engine
	certMgr      *certs.Manager
	trafficChan  chan TrafficEvent
	tlsConfig    *tls.Config
	leafCertCache map[string]*tls.Certificate
}

func New(port int, database *db.DB, certMgr *certs.Manager) *Proxy {
	return &Proxy{
		port:           port,
		db:             database,
		certMgr:        certMgr,
		trafficChan:    make(chan TrafficEvent, 1000),
		leafCertCache:  make(map[string]*tls.Certificate),
	}
}

// TrafficChan returns the traffic event channel
func (p *Proxy) TrafficChan() chan TrafficEvent {
	return p.trafficChan
}

// UpdateRules updates the rules engine
func (p *Proxy) UpdateRules(newRules []db.Rule) {
	p.engine = rules.New(newRules)
}

// Start starts the proxy server
func (p *Proxy) Start() error {
	tlsConfig := &tls.Config{
		GetCertificate: func(hi *tls.ClientHelloInfo) (*tls.Certificate, error) {
			hostname := hi.ServerName
			if hostname == "" {
				hostname = "localhost"
			}

			// Check cache
			if cert, ok := p.leafCertCache[hostname]; ok {
				return cert, nil
			}

			// Generate new cert
			cert, err := p.certMgr.SignLeafCert(hostname)
			if err != nil {
				return nil, err
			}

			p.leafCertCache[hostname] = &cert
			return &cert, nil
		},
	}
	p.tlsConfig = tlsConfig

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", p.port),
		Handler: http.HandlerFunc(p.handleHTTP),
	}

	return server.ListenAndServe()
}

func (p *Proxy) handleHTTP(w http.ResponseWriter, r *http.Request) {
	appPackage := r.Header.Get("X-GhostBe-App")
	startTime := time.Now()

	// Handle CONNECT (HTTPS)
	if r.Method == http.MethodConnect {
		p.handleConnect(w, r, appPackage, startTime)
		return
	}

	// Handle regular HTTP
	p.forwardHTTP(w, r, appPackage, startTime)
}

func (p *Proxy) handleConnect(w http.ResponseWriter, r *http.Request, appPackage string, startTime time.Time) {
	// Establish connection to origin
	conn, err := net.Dial("tcp", r.RequestURI)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer conn.Close()

	// Hijack connection
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "hijacking not supported", http.StatusInternalServerError)
		return
	}

	clientConn, _, err := hijacker.Hijack()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer clientConn.Close()

	// Write 200 OK
	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	// Set up TLS on client side
	tlsConn := tls.Server(clientConn, p.tlsConfig)
	defer tlsConn.Close()

	// Read encrypted request from client
	reader := bufio.NewReader(tlsConn)
	req, err := http.ReadRequest(reader)
	if err != nil {
		return
	}

	// Add app package header
	req.Header.Set("X-GhostBe-App", appPackage)

	// Check rules
	matched := p.engine.Match(appPackage, req.Method, req.URL.String())
	var resp *http.Response

	if matched != nil {
		// Return mock response
		resp = &http.Response{
			StatusCode: matched.ResponseStatus,
			Status:     http.StatusText(matched.ResponseStatus),
			Header:     make(http.Header),
		}
		if matched.ResponseBody != nil {
			resp.Body = io.NopCloser(strings.NewReader(*matched.ResponseBody))
		}

		// Log traffic
		p.logTraffic(appPackage, req.Method, req.URL.String(), matched.ResponseStatus, true, time.Since(startTime))
	} else {
		// Forward to origin
		client := &http.Client{
			Timeout: 30 * time.Second,
		}
		req.RequestURI = ""
		resp, err = client.Do(req)
		if err != nil {
			return
		}

		// Log traffic
		p.logTraffic(appPackage, req.Method, req.URL.String(), resp.StatusCode, false, time.Since(startTime))
	}

	// Write response back to client
	resp.Write(tlsConn)

	// Publish event
	p.publishEvent(appPackage, req.Method, req.URL.String(), resp.StatusCode, time.Since(startTime), matched != nil)
}

func (p *Proxy) forwardHTTP(w http.ResponseWriter, r *http.Request, appPackage string, startTime time.Time) {
	// Build origin URL
	if r.RequestURI != "" && !strings.HasPrefix(r.RequestURI, "http") {
		scheme := "http"
		if r.TLS != nil {
			scheme = "https"
		}
		r.RequestURI = ""
		r.URL.Scheme = scheme
		r.URL.Host = r.Host
	}

	// Check rules
	matched := p.engine.Match(appPackage, r.Method, r.URL.String())

	if matched != nil {
		// Return mock response
		w.WriteHeader(matched.ResponseStatus)
		if matched.ResponseBody != nil {
			w.Write([]byte(*matched.ResponseBody))
		}

		// Log traffic
		p.logTraffic(appPackage, r.Method, r.URL.String(), matched.ResponseStatus, true, time.Since(startTime))

		// Publish event
		p.publishEvent(appPackage, r.Method, r.URL.String(), matched.ResponseStatus, time.Since(startTime), true)
		return
	}

	// Forward to origin
	client := &http.Client{
		Timeout: 30 * time.Second,
	}
	r.RequestURI = ""
	resp, err := client.Do(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// Copy headers
	for k, v := range resp.Header {
		w.Header()[k] = v
	}

	// Copy status and body
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)

	// Log traffic
	p.logTraffic(appPackage, r.Method, r.URL.String(), resp.StatusCode, false, time.Since(startTime))

	// Publish event
	p.publishEvent(appPackage, r.Method, r.URL.String(), resp.StatusCode, time.Since(startTime), false)
}

func (p *Proxy) logTraffic(appPackage, method, url string, status int, mocked bool, latency time.Duration) {
	entry := db.TrafficLog{
		AppPackage:     &appPackage,
		Method:         method,
		URL:            url,
		ResponseStatus: status,
		LatencyMs:      latency.Milliseconds(),
		Mocked:         mocked,
	}
	_ = p.db.LogTraffic(entry)
}

func (p *Proxy) publishEvent(appPackage, method, url string, status int, latency time.Duration, mocked bool) {
	select {
	case p.trafficChan <- TrafficEvent{
		Timestamp:      time.Now(),
		AppPackage:     appPackage,
		Method:         method,
		URL:            url,
		ResponseStatus: status,
		LatencyMs:      latency.Milliseconds(),
		Mocked:         mocked,
	}:
	default:
		// Channel full, drop event
	}
}
