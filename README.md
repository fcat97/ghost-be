# GhostBe - Android MITM Proxy Testing System

A complete monorepo project that acts as a MITM (man-in-the-middle) proxy system for testing Android apps on a local LAN. It consists of three main components:

1. **Go Backend** - MITM proxy service with REST API and WebSocket support
2. **React Frontend** - Web-based management and traffic inspection UI
3. **Android App** - VPN service for intercepting selected apps

## Project Structure

```
ghost-be/
├── backend/              # Go MITM proxy service
│   ├── cmd/ghostbe/      # Entry point
│   ├── internal/
│   │   ├── api/          # REST API + WebSocket
│   │   ├── certs/        # Root CA generation and management
│   │   ├── proxy/        # HTTP/HTTPS interception
│   │   ├── rules/        # Rule matching engine
│   │   └── db/           # SQLite integration
│   ├── go.mod
│   └── go.sum
├── frontend/             # React web UI
│   ├── src/
│   │   ├── pages/        # Traffic, Rules, Apps, Settings
│   │   ├── components/
│   │   ├── hooks/        # useWebSocket, useRules
│   │   ├── App.tsx
│   │   └── main.tsx
│   ├── package.json
│   ├── vite.config.ts
│   ├── tailwind.config.js
│   └── tsconfig.json
├── android/              # Android VPN app
│   ├── app/src/main/
│   │   ├── java/com/ghostbe/
│   │   ├── res/layout/
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── README.md
```

## Quick Start

### Prerequisites

- **Go 1.21+** - For backend
- **Node.js 18+** - For frontend
- **Android SDK** (optional) - For Android app development
- **Git** - For version control

### 1. Backend Setup

```bash
cd backend

# Download dependencies
go mod download

# Run the MITM proxy (will listen on port 8877 by default)
go run ./cmd/ghostbe/main.go
```

**Environment Variables:**
- `GHOSTBE_PORT` - Proxy port (default: 8877)
- `GHOSTBE_DATA_DIR` - Data directory for CA certificates and database (default: ./data)

The API server will run on `GHOSTBE_PORT + 1` (default 8878).

### 2. Frontend Setup

```bash
cd frontend

# Install dependencies
npm install

# Development server (proxies to localhost:8878)
npm run dev

# Production build
npm run build
```

Visit `http://localhost:5173` to access the UI.

## Features

### Backend

- **MITM Proxy**: Intercepts HTTP and HTTPS traffic from Android apps
- **Dynamic Certificate Signing**: Generates leaf certificates on-the-fly for HTTPS interception
- **Rule Engine**: Pattern-based traffic interception (glob and regex support)
- **REST API**: Full CRUD for rules, traffic inspection, and status
- **WebSocket**: Real-time traffic event streaming
- **SQLite Database**: Persistent storage for rules and traffic logs

### Frontend

- **Traffic Inspector**: Real-time view of intercepted requests with detailed inspection
- **Rules Manager**: Create, edit, delete, and toggle traffic rules per app
- **Apps Panel**: Overview of intercepted apps and their request counts
- **Settings**: CA certificate management and setup instructions

### Android App

- **VPN Service**: System-level traffic interception
- **App Selector**: Choose which apps to intercept
- **Mini Proxy**: Injects app identification headers and forwards to host proxy
- **Configuration**: Easy host/port setup via SharedPreferences
- **Minimal Dependencies**: Only AndroidX core and appcompat

## How It Works

### Data Flow

```
Android App → TUN Interface → Mini Proxy (8788)
  ↓
Injects X-GhostBe-App header
  ↓
Backend Proxy (8877) receives request
  ↓
Checks rules, logs to SQLite, broadcasts event via WebSocket
  ↓
Returns mocked response (if rule matches) or forwards to origin
```

### HTTPS Interception

1. TLS ClientHello arrives at proxy
2. Proxy extracts server name (SNI)
3. Root CA signs a leaf certificate dynamically
4. Proxy terminates TLS on client side (Android)
5. Plaintext HTTP/2 request is inspected
6. Rules are applied, event is broadcast
7. Response is sent back through proxy

## API Endpoints

### Status & Certs

- `GET /api/status` → `{ running, port, ca_fingerprint }`
- `GET /api/ca` → Download CA certificate (ca.crt)

### Traffic Management

- `GET /api/traffic?app=&page=&limit=` → Paginated traffic logs
- `GET /api/apps` → App statistics
- `GET /ws/traffic` → WebSocket for real-time events

### Rules CRUD

- `GET /api/rules` → List all rules
- `POST /api/rules` → Create rule
- `PUT /api/rules/:id` → Update rule
- `DELETE /api/rules/:id` → Delete rule
- `PATCH /api/rules/:id/toggle` → Toggle enabled

## Example Rule

```json
{
  "app_package": "com.example.app",
  "url_pattern": "*.api.example.com/*",
  "method": "POST",
  "response_status": 500,
  "response_body": "{\"error\": \"Server error\"}",
  "enabled": true
}
```

## Building

### Backend

```bash
cd backend
go build -o ghostbe ./cmd/ghostbe/
```

### Frontend

```bash
cd frontend
npm run build
# Output: frontend/dist/
```

### Android

```bash
cd android
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Development Notes

### Backend

- Uses **chi router** for REST endpoints
- **Gorilla WebSocket** for real-time events
- **SQLite** with automatic schema migration
- **Crypto/TLS** standard library for certificate handling

### Frontend

- Built with **Vite** for fast HMR development
- **React 18** with TypeScript
- **Tailwind CSS** for styling (no component library)
- **React Router v6** for client-side navigation

### Android

- Pure Java (no Kotlin, no Compose)
- XML layouts only
- Custom RecyclerView adapter for app selection
- VpnService with TUN/TAP interface
- JSON SharedPreferences for app selection storage

## Testing the System

1. **Start Backend**: `go run ./cmd/ghostbe/main.go`
2. **Start Frontend**: `npm run dev`
3. **Install Android APK** on device
4. **Download CA Certificate** from Settings page
5. **Install Certificate** on Android (Settings → Security)
6. **Configure Proxy**: Enter PC IP and port 8877
7. **Select Apps**: Choose which apps to intercept
8. **Connect**: Tap Connect button
9. **Monitor Traffic**: Watch real-time requests in web UI

## Troubleshooting

### Certificate Installation Fails

- Ensure CA certificate is downloaded (Settings → Download CA Certificate)
- Install as "System certificate" on some Android versions
- Verify fingerprint matches in Settings page

### No Traffic Showing

- Verify apps are selected in AppListActivity
- Check proxy IP and port configuration
- Ensure backend is running on correct port
- Check Android device firewall settings

### VPN Connection Fails

- Grant VPN permission when prompted
- Ensure "Select Apps" have been chosen
- Verify backend is reachable from device IP

## License

See LICENSE file for details.
