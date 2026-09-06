# GhostBe: OkHttp Interceptor + Native Mock Server — Design

Status: approved for planning
Date: 2026-09-06
Branch: `feat/okhttp-interceptor`

## 1. Purpose

GhostBe lets a developer control what response an Android app sees for any
given HTTP request, without touching the app's real backend or installing
any certificates on the device. It replaces the project's previous approach
(a Go-based network-level MITM proxy using a gVisor netstack and TLS
termination) with an app-level design:

1. A Kotlin Multiplatform library, initially targeting Android, that
   developers add as a dependency and hook into their `OkHttpClient` as a
   standard OkHttp `Interceptor`.
2. A Kotlin/Native CLI tool (`ghost-be`), targeting Linux for v1, that
   developers run on their host machine or CI pipeline runner.
3. For every request, the interceptor first asks `ghost-be` whether it wants
   to intercept the call. If configured to intercept, `ghost-be`'s response is
   delivered to the app instead of the real one. Otherwise the app calls the
   real endpoint itself.
4. Client-to-server communication is plain HTTP (no TLS, no certificate
   installation on the device).
5. `ghost-be` is configured via plain-text YAML rule files that match
   requests (by URL/path/params/headers) to either a static response file or
   a script (Python/Node) that computes the response dynamically.

The previous Go/React/Android implementation has been removed from this
branch (preserved on `master` and `feat/capture-path-0-1`). This is a
from-scratch rebuild.

## 2. Non-goals (v1)

- No macOS/Windows native builds of `ghost-be` (Linux only).
- No iOS/other KMP targets for the client library (Android only).
- No shared DTO/protocol module between client and server — the wire format
  is plain JSON, each side handles it independently. The purpose of this
  project is to let developers see how their app behaves under different
  responses; the client/server coupling should stay minimal.
- No hot-reload of rule files (restart `ghost-be` to pick up config changes).
- No automated end-to-end test against a real Android device/emulator; a
  documented manual checklist covers this instead.
- No embedded scripting runtime — scripts run as subprocesses via the
  system's installed `python3`/`node`.
- No support for physical devices reaching `ghost-be` over LAN in v1 — client
  and server run on the same machine (e.g. emulator + host, or an
  instrumented/unit test process talking to `127.0.0.1`).
- No production-safe runtime toggle — the interceptor library is meant to be
  wired into debug/test build variants only (e.g. `debugImplementation`),
  never shipped in a release build.

## 3. Repository layout

Using the Kotlin Toolchain (`module.yaml`/`project.yaml`), not Gradle:

```
project.yaml
client/                 # kmp/lib, platforms: [android]
  module.yaml
  src/...
server/                 # linux/app, platforms: [linuxX64]
  module.yaml
  src/...
```

Two independent modules, no shared module between them. `project.yaml`:

```yaml
modules:
  - client
  - server
```

`server/module.yaml` (indicative — exact dependency coordinates to be
confirmed during implementation):

```yaml
product:
  type: linux/app
  platforms: [ linuxX64 ]

dependencies:
  - $ktor.server.core
  - $ktor.server.cio      # CIO engine supports Kotlin/Native targets
  - # YAML parsing library (multiplatform/native-capable) — TBD in plan

settings:
  ktor: enabled
  native:
    entryPoint: ghostbe.server.MainKt.main
```

`client/module.yaml` (indicative):

```yaml
product:
  type: kmp/lib
  platforms: [ android ]

dependencies:
  - $libs.okhttp
```

## 4. Request flow

```
App code
  → OkHttp client (GhostBeInterceptor installed)
  → interceptor builds an envelope from the outgoing Request
  → POST http://127.0.0.1:<port>/intercept  (to ghost-be)
      ├─ ghost-be reachable, rule matches   → mock response envelope returned
      ├─ ghost-be reachable, no rule matches → passthrough signal returned
      └─ ghost-be unreachable (refused/timeout) → treated as passthrough
  → on mock: interceptor builds an OkHttp Response from the envelope and
    returns it up the chain (chain.proceed is never called)
  → on passthrough (either kind): interceptor calls chain.proceed(request)
    to hit the real endpoint
```

The interceptor is installed by the developer like any other OkHttp
interceptor:

```kotlin
OkHttpClient.Builder()
    .addInterceptor(GhostBeInterceptor(baseUrl = "http://127.0.0.1:8787"))
    .build()
```

Wiring this only into debug/test build variants (not release) is the
developer's responsibility; the library itself does not gate on build type.

## 5. Wire format

Plain JSON over HTTP, no shared code between client and server for these
shapes — each side owns its own (de)serialization.

Request envelope (body of `POST /intercept`):

```json
{
  "method": "GET",
  "url": "https://api.example.com/v1/users/42?active=true",
  "headers": { "Authorization": "Bearer ...", "Accept": "application/json" },
  "body": "base64-or-null"
}
```

- `url` is the full original request URL (scheme, host, path, query) — this
  is what rules match against.
- `body` is base64-encoded to safely carry arbitrary binary payloads inside
  JSON; `null`/absent for bodyless requests (e.g. GET).

Response from `ghost-be` — a mock:

```json
{ "intercept": true, "status": 200, "headers": { "Content-Type": "application/json" }, "body": "base64" }
```

or a passthrough:

```json
{ "intercept": false }
```

An unreachable `ghost-be` (connection refused, timeout) is handled entirely
client-side and never produces a passthrough JSON body — the interceptor
just catches the connection failure and calls `chain.proceed(request)`.

## 6. Rule configuration

Rules live in YAML file(s) under a `rules/` directory next to wherever
`ghost-be` is run (path configurable via CLI flag, default `./rules`). All
files are loaded once at startup; changing them requires restarting
`ghost-be`.

```yaml
rules:
  - name: get-user-42
    match:
      method: GET
      path: /v1/users/42
      query: { active: "true" }
      headers: { X-Feature-Flag: "beta" }
    response:
      file: responses/user-42.json
      status: 200
      headers: { Content-Type: application/json }

  - name: dynamic-user
    match:
      method: GET
      pathPattern: "/v1/users/{id}"
    response:
      script: scripts/dynamic_user.py
      status: 200
```

Matching semantics:

- `method` and (`path` or `pathPattern`) are required; `query` and `headers`
  are optional maps where only the listed keys must match (extra
  query params/headers on the real request are ignored).
- `pathPattern` supports `{name}` segments, primarily so scripts can read
  path parameters out of the envelope's URL.
- Rules are evaluated in order across all loaded files (files sorted by
  filename, rules within a file in file order); the first match wins.
- A request matching no rule at all is a passthrough — this is the expected,
  common case for most traffic.

## 7. Response resolution

- **Static file** (`response.file`): the file's raw bytes become the
  response body verbatim (any content type, typically JSON). Status and
  headers come from the rule, not the file.
- **Script** (`response.script`): `ghost-be` spawns the interpreter implied
  by the script's extension (`python3` for `.py`, `node` for `.js`) as a
  subprocess per matching request. The request envelope's JSON is written to
  the subprocess's stdin. The subprocess must print a single JSON object to
  stdout: `{ "status": <int>, "headers": {...}, "body": "<base64>" }`.
  Nonzero exit code or unparseable stdout is a script error (see §8).

## 8. Error handling

| Situation | Behavior |
|---|---|
| No rule matches | `{"intercept": false}` — normal passthrough |
| Rule matches, static file missing/unreadable | `intercept: true, status: 500`, diagnostic JSON body naming the rule and the failure |
| Rule matches, script exits nonzero or prints unparseable stdout | Same as above — loud 500 with diagnostic body (rule name, stderr tail, exit code) |
| Malformed rule YAML at startup | `ghost-be` refuses to start; prints offending file + parse error; exits non-zero |
| Client cannot reach `ghost-be` at all | Interceptor logs a warning and calls `chain.proceed(request)` — silent passthrough, never fails the app's request |

The asymmetry here is deliberate: a *matched* rule that fails to produce a
response fails loudly, because the developer explicitly configured an
intercept for that request and a silent fallback to the real endpoint would
mask a broken fixture. An *unreachable* `ghost-be` is treated as "no
interception configured" rather than an error, so leaving the interceptor
wired in debug builds is never unsafe when `ghost-be` isn't running.

## 9. Testing strategy

- **server module**: unit tests for the matching engine (envelope + in-memory
  rule set → expected match/no-match), covering path patterns, query/header
  subset matching, and first-match-wins ordering with no file I/O. Ktor
  test-host integration tests exercising `/intercept` end-to-end against a
  temporary `rules/` dir with real static-file and script fixtures, including
  the 500-diagnostic path for a deliberately broken rule.
- **client module**: unit tests for `GhostBeInterceptor` using OkHttp's
  `MockWebServer` standing in for `ghost-be` — envelope construction
  (method/url/headers/body, base64 body encoding), mock-response handling,
  passthrough on `intercept: false`, and passthrough on connection failure
  (server not started).
- **Manual/e2e**: a README-documented checklist using a sample Android app
  and a locally running `ghost-be`, rather than an automated instrumented
  test — disproportionate effort for v1 given the native CLI target.

## 10. Open items for the implementation plan

- Exact YAML parsing library choice for Kotlin/Native (linuxX64) — needs a
  library that supports the native target, not just JVM.
- Exact process-spawning API for Kotlin/Native Linux (POSIX fork/exec via
  cinterop, or a multiplatform process library if one fits).
- CLI flag surface for `ghost-be` (port, rules directory path, log level).
- Whether `ghost-be` needs a health-check/version endpoint for tooling.
