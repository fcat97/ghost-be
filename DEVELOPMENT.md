# Development & Build Guide

## Quick Build Verification

### Backend Build

```bash
cd backend
go build -o ghostbe ./cmd/ghostbe/
# Binary: backend/ghostbe (14MB)
```

### Frontend Build

```bash
cd frontend
npm install
npm run build
# Output: frontend/dist/
```

### Android Build

Requires:
- Android SDK (API 33+)
- Java 17 (Java 21 has jlink incompatibility with compileSdk 33)

From project root:

```bash
cd android
# Switch to Java 17 (if using sdkman):
sdk use java 17.0.9-jbr

./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk (3.1 MB)
```

**Note:** The Gradle wrapper is configured for compileSdk 33 with androidx.core 1.10.1. To upgrade to compileSdk 34, you'll need AGP 8.2.0+ and must use Java 17 (Java 21 jlink issues remain unresolved).

## Running Locally

### 1. Start Backend

```bash
cd backend
./ghostbe
```

The proxy will:
- Listen on port 8877 for traffic
- Listen on port 8878 for API/WebSocket
- Create `./data/` directory with SQLite database and CA certificates

### 2. Start Frontend (dev mode)

In another terminal:

```bash
cd frontend
npm run dev
```

Visit http://localhost:5173

### 3. Test API Endpoints

In another terminal:

```bash
# Check status
curl http://localhost:8878/api/status

# Get CA certificate
curl http://localhost:8878/api/ca -o ca.crt

# Create a test rule
curl -X POST http://localhost:8878/api/rules \
  -H "Content-Type: application/json" \
  -d '{
    "app_package": "com.test.app",
    "url_pattern": "*.example.com/*",
    "method": "*",
    "response_status": 200,
    "response_body": "{\"mocked\": true}",
    "enabled": true
  }'

# View WebSocket traffic (requires WebSocket client)
wscat -c ws://localhost:8878/ws/traffic
```

## Project Status

✅ Backend:     Compiles and runs
✅ Frontend:    Builds to dist/
✅ Android:     Code complete (requires Android SDK for build)
✅ README:      Comprehensive setup guide
✅ .gitignore:  Complete for Go, Node, Android stack

## Architecture

### Data Flow

```
Android Device (VPN)
  ↓
Mini HTTP Proxy (8788)
  ↓
Backend MITM Proxy (8877)
  ├→ Checks rules
  ├→ Logs to SQLite
  ├→ Publishes event via WebSocket
  └→ Returns mock or forwards to origin
  ↓
React Frontend (5173)
  ├→ Traffic inspector with real-time updates
  ├→ Rules management UI
  └→ App selection and settings
```

### Components

1. **Backend (Go)**
   - MITM HTTP/HTTPS proxy with TLS termination
   - Dynamic leaf certificate signing from root CA
   - Rule-based traffic mocking
   - SQLite database for rules and traffic logs
   - REST API + WebSocket for frontend communication

2. **Frontend (React + Vite)**
   - Real-time traffic inspection
   - Rule creation and management
   - App statistics and filtering
   - Settings with CA certificate download
   - Built entirely with Tailwind CSS

3. **Android (Java)**
   - VPN service for system-level interception
   - App selection with RecyclerView
   - Configuration via SharedPreferences
   - On-device mini proxy for header injection
   - Packet routing through TUN/TAP interface

## Development Workflows

### Adding a New API Endpoint

1. Add handler in `backend/internal/api/api.go`
2. Add route in `api.Server.Router()`
3. Create corresponding frontend hook if needed
4. Test with curl or browser

### Adding a New Frontend Page

1. Create `.tsx` file in `frontend/src/pages/`
2. Add route in `frontend/src/App.tsx`
3. Add navigation link in header
4. Use hooks for API communication

### Debugging

Backend:
```bash
cd backend
go run -v ./cmd/ghostbe/
```

Frontend:
```bash
cd frontend
npm run dev  # HMR enabled
# Browser DevTools for debugging
```

## Known Limitations

- Android app requires API 21+ (Android 5.0)
- HTTPS interception requires CA certificate installation
- Only tested on Linux/macOS (Windows support may need adjustments)
- Android mini-proxy uses first selected app package (multi-app support limited)
- WebSocket broadcasts to all clients (no app-specific filtering yet)

## Next Steps (Future Enhancements)

- [ ] Embed frontend build in Go binary with go:embed
- [ ] Add request/response body diff view
- [ ] Add rule templates/presets
- [ ] Add export/import for rules
- [ ] Add request replay functionality
- [ ] Improve Android packet parsing for more accurate app identification
- [ ] Add dashboard with statistics charts
- [ ] Support for proxy chaining
- [ ] Request filtering and search

