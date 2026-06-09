package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/ghostbe/backend/internal/api"
	"github.com/ghostbe/backend/internal/certs"
	"github.com/ghostbe/backend/internal/db"
	"github.com/ghostbe/backend/internal/proxy"
	"github.com/ghostbe/backend/internal/tun"
)

func main() {
	// Load config from environment
	port := 8877
	if p := os.Getenv("GHOSTBE_PORT"); p != "" {
		if parsed, err := strconv.Atoi(p); err == nil {
			port = parsed
		}
	}

	dataDir := "./data"
	if d := os.Getenv("GHOSTBE_DATA_DIR"); d != "" {
		dataDir = d
	}

	// Create data directory
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		log.Fatalf("failed to create data directory: %v", err)
	}

	// Initialize database
	dbPath := dataDir + "/ghostbe.db"
	database, err := db.New(dbPath)
	if err != nil {
		log.Fatalf("failed to initialize database: %v", err)
	}
	defer database.Close()

	log.Printf("Database initialized: %s", dbPath)

	// Initialize certificate manager
	certMgr, err := certs.New(dataDir)
	if err != nil {
		log.Fatalf("failed to initialize certificates: %v", err)
	}

	log.Printf("Certificate manager initialized")
	log.Printf("CA Fingerprint: %s", certMgr.GetCAFingerprint())

	// Load initial rules
	initialRules, err := database.GetRules("")
	if err != nil {
		log.Fatalf("failed to load rules: %v", err)
	}

	// Initialize proxy
	pxy := proxy.New(port, database, certMgr)
	pxy.UpdateRules(initialRules)

	// Initialize API server
	apiServer := api.New(port+1, database, certMgr, pxy, dataDir)

	// Start proxy server
	go func() {
		log.Printf("Starting MITM proxy on :%d", port)
		if err := pxy.Start(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("proxy server error: %v", err)
		}
	}()

	// Start TUN server
	tunAddr := fmt.Sprintf("127.0.0.1:%d", port)
	tunServer := tun.New(port-1, tunAddr)
	go func() {
		log.Printf("Starting TUN server on :%d", port-1)
		if err := tunServer.Start(); err != nil {
			log.Fatalf("TUN server error: %v", err)
		}
	}()

	// Start API server
	apiPort := port + 1
	apiAddr := fmt.Sprintf(":%d", apiPort)
	apiHTTPServer := &http.Server{
		Addr:    apiAddr,
		Handler: apiServer,
	}

	go func() {
		log.Printf("Starting API server on :%d", apiPort)
		if err := apiHTTPServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("API server error: %v", err)
		}
	}()

	// Wait for shutdown signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	sig := <-sigChan
	log.Printf("Received signal: %v", sig)

	// Graceful shutdown
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := apiHTTPServer.Shutdown(ctx); err != nil {
		log.Printf("API server shutdown error: %v", err)
	}

	log.Println("Server stopped")
}
