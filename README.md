# GhostBe

GhostBe lets you control what HTTP response an Android app sees for any
given request — without touching the app's real backend or installing any
certificates on the device. Instead of intercepting traffic at the network
level, an OkHttp interceptor in the app relays each request to a small local
server, which either returns a mocked response (from a static file or a
script) or tells the app to call the real endpoint.

Built with the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/) — no
Gradle files to author directly, just `module.yaml`/`project.yaml`.

## How it works

```
App code
  -> OkHttp client (GhostBeInterceptor installed)
  -> POST /intercept on ghost-be (plain HTTP, localhost)
       - rule matches   -> ghost-be returns a mocked response
       - no rule matches -> ghost-be tells the client to pass through
  -> mocked response returned to the app, OR the real endpoint is called
```

Full design rationale and the wire protocol are in
[`docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md`](docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md).

## Modules

| Module | Type | Platform | What it is |
|---|---|---|---|
| [`client/`](client) | `kmp/lib` | Android | `GhostBeInterceptor` — the OkHttp interceptor apps depend on |
| [`server/`](server) | `linux/app` | Kotlin/Native (`linuxX64`) | `ghost-be` — the CLI server that matches requests to rules and returns mock/passthrough responses |
| [`demo-app/`](demo-app) | `android/app` | Android | Minimal Compose app exercising `client/` against a real device/emulator, for manual testing |
| [`demo-backend/`](demo-backend) | — | Node.js | Single-file "real backend" the demo app calls when ghost-be passes a request through |

## Prerequisites

- A JDK (Kotlin Toolchain bootstraps its own compiler, but needs a JDK on `PATH`)
- Android SDK, with `ANDROID_HOME` set (needed for `client`/`demo-app`, not for `server`)
- Node.js (only for running `demo-backend`)
- Linux, for building/running `server` (`linuxX64` is the only target for v1)

The `kotlin` (Linux/macOS) and `kotlin.bat` (Windows) scripts in the repo
root are self-bootstrapping wrappers — they download the pinned Kotlin
Toolchain version on first use.

## Building and testing

```bash
export ANDROID_HOME=/path/to/Android/Sdk

./kotlin build              # build every module
./kotlin build -m server    # build just one module
./kotlin test               # run every module's tests
./kotlin test -m client     # run just one module's tests
```

## Running the demo end-to-end

**1. Start demo-backend** (the "real" endpoint):
```bash
node demo-backend/server.js
```

**2. Set up a rules directory for ghost-be.** Rules are plain YAML files
matching requests (method/path/query/headers) to a static-file or script
response — see the spec's §6 for the full format. For a quick manual test:
```bash
mkdir -p demo-rules/responses
cat > demo-rules/basic.yaml <<'EOF'
rules:
  - name: mock-user-42
    match:
      method: GET
      path: /v1/users/42
    response:
      file: responses/user-42.json
      status: 200
      headers: { Content-Type: application/json }
EOF
cat > demo-rules/responses/user-42.json <<'EOF'
{"id": 42, "name": "Ada Lovelace (mocked by ghost-be)"}
EOF
```

**3. Start ghost-be:**
```bash
./kotlin run -m server -- --rules ./demo-rules --port 8787
```
(`--port` and `--rules` are both optional; they default to `8787` and
`./rules`.)

**4. Install and launch demo-app on a connected device/emulator:**
```bash
export ANDROID_SERIAL=emulator-5554   # if more than one device is attached
./kotlin run -m demo-app
```

**5. On an emulator, tunnel its localhost to the host:**
```bash
adb reverse tcp:3000 tcp:3000
adb reverse tcp:8787 tcp:8787
```
The emulator's usual `10.0.2.2` host-loopback alias *should* work with no
setup at all — but if it times out on TCP connects (some host firewall
configurations block it even though ICMP/ping still succeeds), `adb
reverse` is the reliable fallback: demo-app is wired to `127.0.0.1`, which
after `adb reverse` resolves to the host machine's `127.0.0.1`. These
mappings don't survive an emulator restart, so re-run them after a cold
boot.

**Try it:** tap "Fetch User" with ghost-be running — you should see the
mocked Ada Lovelace response. Stop ghost-be and tap again — the interceptor
silently falls through to demo-backend's real response instead.

## Design docs

- [`docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md`](docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md) — architecture, wire format, rule config, error handling
- [`docs/superpowers/plans/2026-09-06-ghostbe-server.md`](docs/superpowers/plans/2026-09-06-ghostbe-server.md) — server implementation plan
- [`docs/superpowers/plans/2026-09-06-ghostbe-client.md`](docs/superpowers/plans/2026-09-06-ghostbe-client.md) — client implementation plan
