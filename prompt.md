# Task: Implement GhostBe — Android MITM Proxy Testing System

## Overview
Build a complete monorepo project called `ghost-be` that acts as a MITM (man-in-the-middle)
proxy system for testing Android apps on a local LAN. It has three components:
1. A Go backend service (MITM proxy + REST API)
2. A React web frontend (management UI)
3. A lightweight Android VPN app (Java, pure Views)

Work in the directory: /home/portonics/development/project/ghost-be/

---

## Monorepo Structure
Create the following layout:

```
ghost-be/
├── backend/
│   ├── cmd/ghostbe/main.go
│   ├── internal/
│   │   ├── proxy/        # MITM proxy core
│   │   ├── certs/        # Root CA + leaf cert signing
│   │   ├── rules/        # Rule matching engine
│   │   ├── api/          # chi router, REST + WebSocket
│   │   └── db/           # SQLite schema + queries
│   ├── go.mod            # module: github.com/ghostbe/backend
│   └── go.sum
├── frontend/
│   ├── src/
│   │   ├── pages/        # Traffic.tsx, Rules.tsx, Apps.tsx, Settings.tsx
│   │   ├── components/
│   │   └── hooks/        # useWebSocket.ts, useRules.ts, useTraffic.ts
│   ├── package.json
│   └── vite.config.ts
├── android/
│   └── app/src/main/
│       ├── java/com/ghostbe/
│       │   ├── MainActivity.java
│       │   ├── GhostVpnService.java
│       │   ├── AppListActivity.java
│       │   └── ConfigActivity.java
│       └── res/layout/
└── README.md
```

---

## Component 1: Go Backend

### Tech
- Language: Go
- Router: go-chi/chi v5
- DB: mattn/go-sqlite3
- WebSocket: gorilla/websocket
- TLS: standard crypto/tls + crypto/x509

### internal/certs
- On first run, generate a 4096-bit RSA root CA and save ca.crt + ca.key to ./data/
- Expose GET /api/ca to serve ca.crt as a downloadable file
- For each HTTPS hostname intercepted, dynamically sign a leaf certificate from the root CA

### internal/proxy
- HTTP forward proxy listening on configurable port (default 8877, read from env GHOSTBE_PORT)
- HTTP traffic: intercept request, check rules, log to DB, return custom response if rule matched, else forward to origin
- HTTPS traffic: handle CONNECT method → perform TLS termination using dynamically signed leaf cert → inspect plaintext request → check rules → forward or mock
- Read the request header X-GhostBe-App to identify the originating Android app (package name)
- After processing each request, publish a TrafficEvent to an in-memory broadcast channel

### internal/rules
- Rule struct: { ID, AppPackage, URLPattern, Method, ResponseStatus, ResponseBody, Enabled }
- URLPattern supports both glob (*, ?) and full regex
- Matching: iterate enabled rules for the given app package, return first match
- If matched: return synthetic HTTP response with the rule's status + body
- If no match: pass request through to origin

### internal/db — SQLite schema (auto-migrate on startup)
```sql
CREATE TABLE IF NOT EXISTS rules (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  app_package TEXT NOT NULL,
  url_pattern TEXT NOT NULL,
  method TEXT NOT NULL DEFAULT '*',
  response_status INTEGER NOT NULL DEFAULT 200,
  response_body TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS traffic_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
  app_package TEXT,
  method TEXT,
  url TEXT,
  request_headers TEXT,
  request_body TEXT,
  response_status INTEGER,
  response_body TEXT,
  latency_ms INTEGER,
  mocked INTEGER NOT NULL DEFAULT 0
);
```

### internal/api — REST Endpoints
All JSON unless noted:
- GET  /api/status              → { running: bool, port: int, ca_fingerprint: string }
- GET  /api/ca                  → serve ca.crt file (Content-Disposition: attachment)
- GET  /api/apps                → [{ package, request_count }]
- GET  /api/traffic?app=&page=&limit= → { items: [...], total: int }
- GET  /api/rules               → [Rule]
- POST /api/rules               → create Rule, return created Rule
- PUT  /api/rules/:id           → update Rule fields, return updated Rule
- DELETE /api/rules/:id         → 204 No Content
- PATCH /api/rules/:id/toggle   → flip enabled, return updated Rule
- GET  /ws/traffic              → WebSocket; broadcasts TrafficEvent JSON on each intercepted request

### cmd/ghostbe/main.go
- Parse config from env: GHOSTBE_PORT (default 8877), GHOSTBE_DATA_DIR (default ./data)
- Start DB, certs manager, rule engine, proxy, API server
- Serve compiled frontend from frontend/dist/ embedded with go:embed at route /
- Graceful shutdown on SIGINT/SIGTERM

---

## Component 2: React Web Frontend

### Tech
- Vite + React 18 + TypeScript
- TailwindCSS for styling
- react-router-dom v6 for routing
- No component library (build from scratch with Tailwind)

### Pages

#### / — Traffic Inspector
- Connect to ws://[host]/ws/traffic via WebSocket
- Display scrolling list of intercepted requests: method badge, URL, app package, status code, latency, mocked indicator
- Click a row to expand full detail panel: request headers, request body, response headers, response body (syntax highlighted)
- Filter bar: filter by app package (dropdown populated from /api/apps)
- Auto-scroll toggle

#### /rules — Rules Manager
- Fetch rules from GET /api/rules
- Group rules by app_package in collapsible sections
- Each rule row shows: URL pattern, method, response status, enabled toggle
- "New Rule" button → opens modal form with fields:
  - App Package (text input)
  - URL Pattern (text input with hint: supports * glob and regex)
  - Method (select: *, GET, POST, PUT, DELETE, PATCH)
  - Response Status (number input, default 200)
  - Response Body (textarea, JSON)
  - Enabled toggle
- Edit icon on each row → opens same modal pre-filled
- Delete icon with confirm dialog
- Enable/disable toggle calls PATCH /api/rules/:id/toggle

#### /apps — Apps Panel
- Fetch from GET /api/apps
- Card grid: app package name, request count badge
- Click card → navigates to / with that app pre-filtered

#### /settings — Settings
- Show proxy status (GET /api/status)
- Show proxy port
- Show CA fingerprint
- "Download CA Certificate" button → links to /api/ca
- Instructions for installing CA cert on Android (step-by-step text)

### src/hooks/useWebSocket.ts
- Generic hook: connect, auto-reconnect with exponential backoff, return { messages, status }

---

## Component 3: Android VPN App

### Tech
- Language: Java
- minSdk 21, targetSdk 34
- NO Jetpack Compose, NO Material Design library
- Only AndroidX core (androidx.core:core), appcompat for AppCompatActivity
- XML layouts only, basic View system
- Build system: Gradle (Kotlin DSL)

### ConfigActivity (launcher Activity)
- Layout: two EditText fields (Host IP, Port), a Connect/Disconnect Button, and a TextView link to AppListActivity
- On launch: read host + port from SharedPreferences and populate EditText fields
- "Connect" button:
  1. Save host + port to SharedPreferences
  2. Load checked apps from SharedPreferences
  3. Start GhostVpnService via VpnService.prepare() → startService()
- "Disconnect" button: stopService(GhostVpnService)
- Status TextView: shows "Connected" / "Disconnected" based on service state (use a BroadcastReceiver or static flag)

### AppListActivity
- RecyclerView with a custom adapter (no ListAdapter, keep it simple)
- Each row: app icon (ImageView), app name (TextView), package name (TextView), CheckBox
- Data source: PackageManager.getInstalledApplications(GET_META_DATA), filter to user apps (!(flags & FLAG_SYSTEM))
- On CheckBox change: save updated checked set to SharedPreferences as a JSON array string
- Pre-check boxes from saved SharedPreferences on load

### GhostVpnService
- Extends android.net.VpnService
- On startCommand:
  1. Build VPN interface:
     - addAddress("10.0.0.2", 24)
     - addRoute("0.0.0.0", 0)
     - setSession("GhostBe")
     - For each checked app package: addAllowedApplication(pkg)  ← only checked apps go through VPN
     - establish() → ParcelFileDescriptor tunFd
  2. Start a foreground notification (required for VpnService on Android 8+)
  3. Start two threads:
     - TUN reader thread: read IP packets from tunFd, parse TCP destination (host:port), open a Socket to the on-device mini-proxy (127.0.0.1:8788), relay bytes
     - Socket writer thread: read response bytes from mini-proxy socket, write back to tunFd

### On-device Mini HTTP Proxy (inside GhostVpnService or a helper class)
- ServerSocket on 127.0.0.1:8788
- For each accepted client connection (coming from TUN relay):
  - Read the first line of the HTTP request to determine if it is CONNECT (HTTPS) or plain HTTP
  - Plain HTTP: inject header `X-GhostBe-App: <package_name>` and forward full request to host proxy (host:port from config)
  - HTTPS CONNECT: forward the CONNECT request to host proxy, then relay bytes blindly in both directions (the host proxy handles TLS termination)
- package_name: use the app that owns the connection. Since addAllowedApplication restricts traffic to specific apps, iterate the checked apps list; for a single-app VPN session, use that app's package. For multi-app, use a best-effort UID lookup via /proc/net/tcp6 + PackageManager.getPackagesForUid().

### AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

<service
    android:name=".GhostVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:exported="false"
    android:foregroundServiceType="specialUse">
  <intent-filter>
    <action android:name="android.net.VpnService" />
  </intent-filter>
</service>
```

---

## Data Flow (reference)

```
Android app (checked) → TUN fd → On-device mini-proxy :8788
  → injects X-GhostBe-App header
  → TCP to host PC :8877 (GhostBe backend)
     → logs to SQLite
     → checks rules → match? return mock response : forward to origin
     → broadcasts TrafficEvent via WebSocket
        → Web UI updates traffic list in real time

HTTPS: Android app → TUN → mini-proxy → CONNECT to host :8877
  → host does TLS termination with dynamic leaf cert signed by root CA
  → inspects plaintext → rules check → log → forward or mock
```

---

## Constraints & Rules
- All code must compile and run without errors
- Backend: `go build ./...` must succeed
- Frontend: `npm run build` must succeed (output to frontend/dist/)
- Android: `./gradlew assembleDebug` must succeed
- The Go backend must embed frontend/dist using `//go:embed` so a single binary serves everything
- No placeholder / stub implementations — every feature listed must be functional
- Keep the Android APK small: do not add any dependency not listed above
- Use environment variables for backend config, not hardcoded values
- Add a root README.md with setup instructions for all three components
