# VPN Service Debug Logging Guide

## Overview
The VPN service now includes comprehensive logging to help debug where requests are getting stuck. All logs are tagged with `GhostVpnService` for easy filtering.

## Viewing Logs

### Using Android Studio
1. Open Logcat (View → Tool Windows → Logcat)
2. Filter by tag: `GhostVpnService`
3. Or filter by log level: `Debug`, `Info`, `Error`

### Using ADB Command Line
```bash
# View all VPN service logs
adb logcat -s "GhostVpnService"

# View all debug logs only
adb logcat -s "GhostVpnService" "*:D"

# View with timestamp
adb logcat -v time -s "GhostVpnService"

# Save logs to file
adb logcat -s "GhostVpnService" > vpn_logs.txt
```

## Request Flow and Logging Points

The request flow should appear in logs as follows:

### 1. Service Startup
```
D/GhostVpnService: === VPN Service Starting ===
D/GhostVpnService: Backend: 192.168.1.100:8877
D/GhostVpnService: Selected apps: 2
D/GhostVpnService:   - com.example.app1
D/GhostVpnService:   - com.example.app2
D/GhostVpnService: Starting mini proxy on port 8788...
I/GhostVpnService: startMiniProxy: Server listening on port 8788
D/GhostVpnService: Setting up VPN...
D/GhostVpnService: setupVPN: Creating VPN interface...
D/GhostVpnService: setupVPN: Added app com.example.app1
D/GhostVpnService: setupVPN: Added app com.example.app2
D/GhostVpnService: setupVPN: TUN interface established successfully
D/GhostVpnService: setupVPN: TUN reader and writer threads started
```

### 2. Packet Reception
```
D/GhostVpnService: tunReader: Starting packet reader...
D/GhostVpnService: tunReader: Received 10 packets
D/GhostVpnService: tunReader: Received 20 packets
...
```

### 3. Packet Parsing and Routing
```
D/GhostVpnService: routePacket: TCP 10.0.0.1:12345 -> 8.8.8.8:443
D/GhostVpnService: routePacket: Routing to proxy (port 443)
D/GhostVpnService: routePacket: Sent 512 bytes to proxy
```

### 4. Mini Proxy Client Connection
```
D/GhostVpnService: startMiniProxy: Waiting for client connection...
I/GhostVpnService: startMiniProxy: Client connected from 127.0.0.1
D/GhostVpnService: handleMiniProxyClient: Processing request...
D/GhostVpnService: handleMiniProxyClient: First line: GET / HTTP/1.1
D/GhostVpnService: handleMiniProxyClient: App package: com.example.app1
```

### 5. Backend Connection
```
D/GhostVpnService: handleMiniProxyClient: Connecting to backend 192.168.1.100:8877
I/GhostVpnService: handleMiniProxyClient: Connected to backend
D/GhostVpnService: handleMiniProxyClient: Starting client-to-server relay...
D/GhostVpnService: handleMiniProxyClient: Starting server-to-client relay...
```

### 6. Data Relay
```
D/GhostVpnService: relay: Relay completed, total bytes: 2048
D/GhostVpnService: handleMiniProxyClient: Client-to-server relay completed
D/GhostVpnService: handleMiniProxyClient: Server-to-client relay completed
D/GhostVpnService: handleMiniProxyClient: Request completed
```

### 7. Service Shutdown
```
D/GhostVpnService: === VPN Service Stopping ===
D/GhostVpnService: onDestroy: TUN file descriptor closed
D/GhostVpnService: onDestroy: Mini proxy socket closed
D/GhostVpnService: onDestroy: TUN reader thread stopped
D/GhostVpnService: onDestroy: TUN writer thread stopped
D/GhostVpnService: onDestroy: Mini proxy thread stopped
I/GhostVpnService: VPN Service destroyed
```

## Debugging Checklist

### Request Stuck at VPN Startup
- Check: `=== VPN Service Starting ===`
- Check: Backend IP and port are correct
- Check: `Setting up VPN...` appears
- Check: `TUN interface established successfully` appears
- Check: `Server listening on port 8788` appears

**If stuck here**: VPN setup is blocked (permissions issue?)

### Request Stuck During Packet Reception
- Check: `tunReader: Starting packet reader...` appears
- Check: `tunReader: Received X packets` is incrementing
- If no packets received: App not routing traffic through VPN

**If stuck here**: Apps not sending traffic through VPN (check app selection)

### Request Stuck During Packet Parsing
- Check: `routePacket: TCP` messages appear with IP/port info
- Check: Port is 80 or 443 (other ports are ignored)
- Check: `Routing to proxy` message appears

**If stuck here**: Packets not parsed correctly or wrong port

### Request Stuck During Proxy Connection
- Check: `Client connected from` message appears
- Check: `Processing request...` message appears
- Check: `First line:` shows HTTP request header

**If stuck here**: Proxy connection issue

### Request Stuck During Backend Connection
- Check: `Connecting to backend` message appears
- Check: `Connected to backend` appears
- Check: Backend IP/port is correct
- Check: Backend is actually running and accepting connections

**If stuck here**: Backend is unreachable or not accepting connections

### Request Stuck During Relay
- Check: `relay: Relay completed` appears with byte count
- If relay doesn't complete: Data not flowing between client and backend

**If stuck here**: Network issue or backend closed connection

## Common Issues

### No logs appearing at all
- Check: VPN service is actually running
- Try: `adb shell am start -n com.ghostbe/.MainActivity`
- Try: Click Connect button again

### Lots of "Port X not intercepted" messages
- This is expected for non-80/443 traffic
- Only HTTP/HTTPS (80/443) are intercepted

### "Failed to connect to proxy" error
- Mini proxy server not running
- Check: `Server listening on port 8788` appears early in logs

### "Failed to connect to backend" error
- Backend not running or unreachable
- Check: Backend IP and port are correct
- Check: Backend is actually listening on that port

## Log Levels

- `D` (Debug): Detailed flow information, useful for tracing execution
- `I` (Info): Important milestones (service started, connected to backend)
- `W` (Warning): Issues that don't stop execution (failed to add app)
- `E` (Error): Critical failures (TUN setup failed, backend unreachable)

Filter by level:
```bash
adb logcat -s "GhostVpnService" -v briefwithlinenum | grep -E "^I|^E"
```
