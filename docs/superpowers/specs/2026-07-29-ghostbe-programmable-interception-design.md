# GhostBe: Programmable Network Interception for Android App Testing

**Date:** 2026-07-29
**Status:** Approved design, pending implementation plan

---

## 1. Context

GhostBe today is a three-part monorepo (Go backend, React UI, Android VPN app) that intercepts
HTTP/HTTPS traffic from selected Android apps and can return mocked responses. It works in
outline but not in practice, for three compounding reasons:

1. **The capture path is unreliable.** `internal/tun/handler.go` implements a hand-written
   TCP/IP stack in 812 lines. It has no retransmission, no receive-window accounting, no
   out-of-order reassembly, and no connection eviction. Control packets (SYN/ACK, ACK, RST,
   FIN, FIN/ACK) compute their TCP checksum over a zero-length slice — `buf[ipHdrLen:tcpHdrLen]`
   with both constants equal to 20 — so every checksum on a control packet is wrong. HTTP
   responses are capped at one 65535-byte read followed by FIN, so keep-alive is broken and
   large responses truncate.

2. **App attribution never worked.** `internal/proxy` reads an `X-GhostBe-App` header
   (`proxy.go:93`) that nothing in the codebase ever sets. Since `rules.Engine.Match` requires
   `rule.AppPackage == appPackage` (`rules.go:27`), no rule could ever match through the VPN path.

3. **The interface is wrong for the intended user.** Rules live in SQLite behind REST CRUD.
   The intended primary user is an AI agent automating developer and QA testing, and agents
   work far better with version-controlled files than with rows behind an API.

The database is empty (zero rules, zero traffic logs), so there is no migration burden.

## 2. What GhostBe becomes

**A programmable network layer for Android app testing.** Interception behaviour lives in
version-controlled files that an agent can author, run, and verify with no human in the loop.

The loop the product exists to close:

```
agent runs app  →  observes real traffic  →  writes a fixture
                        ↑                          ↓
                   asserts behaviour  ←  re-runs against fixture
```

Every item in this design either serves that loop or is explicitly out of scope.

Concretely, a developer or agent must be able to: describe a modified request or response in a
JSON file; express conditional behaviour in a short script; have GhostBe apply it to a real app
on a real device with no change to the app's source; and verify from CI that it happened.

## 3. Decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | **Files are canonical.** `fixtures/*.json` + `scripts/*.star`, hot-reloaded. SQLite keeps traffic logs only; the `rules` table is dropped. | Git provides versioning, review and rollback for free. Agents edit files directly rather than constructing API calls. |
| 2 | **Declarative JSON + Starlark escape hatch.** | JSON covers the common case. Starlark is Python-shaped (so agents write it fluently), sandboxed by default, deterministic, and bounded — no recursion and no unbounded loops, so a generated script cannot hang the proxy. |
| 3 | **gVisor netstack replaces the hand-rolled stack.** Pinned to `v0.0.0-20260701204157-69c2d17aea96`. | Measured link cost is +2.62 MB against a binary that is already 8.83 MB stripped; adding `-ldflags="-s -w"` recovers 5.24 MB, so the net binary shrinks. Buys retransmission, windowing, reassembly, keep-alive, MSS and UDP handling. `@latest` does not compile. |
| 4 | **TUN capture retained; per-app scoping retained.** | Works on any app without source changes, on real unrooted QA devices and CI emulators, for any protocol. A proxy setting (`VpnService.setHttpProxy`) would be silently bypassed by apps using raw sockets or QUIC. |
| 5 | **CLI is the agent and CI interface.** `ghostbe <subcommand>`, JSON output. | One surface serves agents and CI. An MCP server can wrap it later if wanted. |
| 6 | **Generated root CA + debug-variant network security config.** First-party apps are the supported path. | Works on unrooted devices and standard emulators, covers first-party *and* third-party hosts, and puts no production private key on a dev machine. |

### 3.1 Why TLS interception requires a trusted certificate

Recorded because it shaped decisions 4 and 6, and because it is a common misconception.

A tunnelling VPN forwards ciphertext; the TLS session stays end-to-end between app and origin,
so no certificate is involved. GhostBe must read and modify plaintext, which means terminating
TLS and performing its own handshake with the app while presenting itself as the origin host.
The app is the TLS client and therefore always the validator. No number of network hops moves
that check.

A publicly-trusted CA cannot help: it will only issue certificates for domains the requester
proves they control, and misissuance for arbitrary hosts is an industry-ending event
(DigiNotar 2011, Symantec 2017). Certificate Transparency logs every public certificate.

Since Android 7.0 (API 24), apps ignore user-installed CAs by default. The supported path is a
debug-variant network security config, which applies only when `android:debuggable="true"` and
therefore cannot leak into a release build:

```xml
<!-- app/src/debug/res/xml/network_security_config.xml -->
<network-security-config>
  <debug-overrides>
    <trust-anchors>
      <certificates src="system" />
      <certificates src="user" />
    </trust-anchors>
  </debug-overrides>
</network-security-config>
```

Certificate pinning defeats this and is out of scope; GhostBe detects and reports it rather
than failing opaquely.

## 4. Guiding principle: no silent misses

For a testing tool, the most expensive failure is ambiguity between "my fixture did not match"
and "the traffic was never captured". Both present as "it didn't work", and an agent will then
try to fix a fixture that was never broken.

Every design choice below that looks like extra work exists to serve this principle:
attribution is left empty rather than guessed; QUIC is actively rejected rather than dropped;
TLS rejections are logged as diagnosed flows; a fixture that fails to parse never replaces the
running config; and `ghostbe explain` reports why each fixture was rejected.

## 5. Roadmap

Dependency-ordered. Nothing above the capture path can be trusted while the capture path lies.

| Phase | Deliverable | Rationale for position |
|---|---|---|
| **0 — Safety net** | In-process test rig driving the full pipeline with no Android device. Build hygiene: `-ldflags="-s -w"`, untrack the committed 14 MB `backend/ghostbe` binary and add it to `.gitignore`. | Every later phase needs a way to be tested. Cheapest work that unblocks everything else. |
| **1 — Correct capture** | gVisor netstack replaces `internal/tun`. App attribution wired end-to-end. IPv6 and UDP handled. QUIC explicitly rejected. Loopback proxy hop removed. Cert setup documented and its failure detected. | Fixtures are meaningless if traffic silently escapes. Load-bearing. |
| **2 — Fixture engine** | `fixtures/*.json` + `scripts/*.star`, hot-reloaded. Request *and* response modification. Fault injection. Match-trace data recorded on every Flow. | The product. |
| **3 — Verification & CLI** | Full header/body capture. `ghostbe` subcommands with JSON output and CI-meaningful exit codes. | Closes the agent loop; makes CI possible. |
| **4 — Ship** | `go:embed` single binary. Traffic inspector UI. Multi-app attribution on Android. Docs rewrite. | Human-facing polish; deferrable without blocking agents. |

Phases 0–1 form one implementation plan. Phase 2 gets its own. Phases 3–4 are specified here in
enough detail to constrain earlier design decisions, and will be re-specified when reached.

## 6. Architecture (Phases 0–1)

### 6.1 The central seam: `Flow`

One type that everything is defined against. The capture path produces Flows; the fixture
engine transforms them; the store logs them; the CLI queries them.

```go
type Flow struct {
    ID       string
    App      string          // package name, or "" — never guessed
    Request  *Request        // method, url, headers, body
    Response *Response       // status, headers, body
    Matched  string          // fixture name; "" means nothing matched
    Action   Action          // passthrough | mocked | modified | faulted | failed
    Error    string          // TLS rejected, origin unreachable, script error
    Latency  time.Duration
}
```

This is what makes the system testable and the capture path replaceable. A fixture-engine test
constructs a `Flow` directly — no packets, no gVisor, no Android. Swapping the capture path
later changes only who calls `NewFlow`.

### 6.2 Package layout

`internal/tun` splits along its two real responsibilities, which is why the current single file
is hard to reason about:

```
internal/
  device/     framed protocol ↔ Android APK (wire format, versioning, control messages)
  netstack/   gVisor stack, TCP/UDP forwarders, QUIC rejection
  flow/       Flow, Request, Response — the seam
  proxy/      TLS termination + origin forwarding (existing, reworked entry point)
  certs/      unchanged
  db/         traffic logs only
  api/        REST + WebSocket for the UI
```

### 6.3 gVisor wiring

The Go side has no TUN file descriptor — it receives a framed packet stream over TCP from the
device. `channel.Endpoint` is the matching abstraction: `InjectInbound` for device-to-stack,
draining the endpoint queue for stack-to-device.

```go
s := stack.New(stack.Options{
    NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
    TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
})
ep := channel.New(512, 1500, "")
s.CreateNIC(nicID, ep)              // nicID allocated per connected device
s.SetPromiscuousMode(nicID, true)   // accept packets for any destination address
s.SetSpoofing(nicID, true)          // reply as the origin address
```

Each connected device gets its own NIC ID and `channel.Endpoint` on a shared stack, so two QA
devices can be attached at once without their flows colliding.

`tcp.NewForwarder` yields a `net.Conn` per flow with the original destination available from
`r.ID()`. `udp.NewForwarder` handles DNS and generalizes the current port-53 special case.

Deleted outright: `sendSYNACK`, `sendACK`, `sendRST`, `sendFIN`, `sendFINACK`,
`sendDataPacket`, `writeResponse`, `checksum`, `tcpChecksummed`, `tcpChecksummedWithData`,
`pseudoRand`, `extractSNIFromBytes`, `forwardDNS`, and the `conns` map.

IPv6 is registered from the start. The current code returns early on any non-IPv4 packet
(`handler.go:110`), and Android frequently prefers IPv6 — that is a silent miss.

### 6.4 The loopback proxy hop is removed

The largest structural simplification, wider in effect than the netstack swap itself. Today
`internal/tun` dials `127.0.0.1:8877` and speaks HTTP `CONNECT` to its own process
(`handler.go:334`) — a full proxy protocol round-trip over loopback used purely as an internal
API. With a `net.Conn` from the forwarder, the MITM code is called directly:

```
before:  packet → hand-rolled TCP → net.Dial(127.0.0.1:8877)
                → CONNECT + parse → http.Server → TLS → origin

after:   packet → gvisor forwarder → net.Conn → proxy.Handle(conn, dst, app)
                → TLS → origin
```

Consequences: no CONNECT construction or parsing, no loopback hop, and no `X-GhostBe-App`
header — attribution comes from the forwarder closure instead.

Port 8877 survives as an **optional standalone HTTP proxy listener**, which is what CI wants
for `emulator -http-proxy 10.0.2.2:8877`. The emulator path therefore comes nearly free.

### 6.5 App attribution

Attribution comes from the device connection, not a header. When several apps are selected, the
rule is **never guess**:

- Android resolves the owning UID via `ConnectivityManager.getConnectionOwnerUid` (API 29+),
  maps it through `PackageManager.getPackagesForUid`, and answers a control-channel query from
  the backend.
- If that lookup fails, or on API < 29, the flow records `App: ""`.

A wrong attribution is worse than an empty one: a fixture keyed on `com.a` firing on `com.b`'s
traffic is an expensive bug. Empty is honest and visible.

Full multi-app attribution polish is Phase 4; Phase 1 delivers the mechanism and the
degradation.

### 6.6 Device protocol

Control messages need a channel, and the current `[length][packet]` framing has no room:

```
[4-byte length][1-byte type][payload]
  type 0x00 = IP packet (v4 or v6)
  type 0x01 = control message (JSON)
```

The handshake exchanges a protocol version first, so a stale APK fails loudly instead of
silently corrupting the stream — approximately what the current unversioned protocol would do.

### 6.7 Failure visibility

Every failure produces a logged `Flow` with a diagnosis rather than a dropped connection.

| Condition | Reported as |
|---|---|
| App rejects our certificate (TLS alert `bad_certificate` / `certificate_unknown`) | `Error: "com.x rejected cert for api.y.com — pinning, or debug-overrides missing?"` |
| Origin unreachable | Flow with status 502 and the dial error |
| App uses QUIC | UDP/443 rejected via ICMP to force TCP fallback, and counted in `ghostbe status` |
| Starlark raises | Flow with the traceback; fail-open or fail-closed configurable per fixture |
| Nothing matched | `Matched: ""` — distinguishable from never-captured |

## 7. Fixture engine (Phase 2)

### 7.1 File format

```json
{
  "name": "login-500",
  "enabled": true,
  "priority": 10,
  "match": {
    "app": "com.example.app",
    "method": "POST",
    "url": "*/api/v1/login*",
    "headers": { "Authorization": "Bearer *" }
  },
  "respond": {
    "status": 500,
    "body": { "error": "server_error" },
    "delay_ms": 1500
  }
}
```

Five mutually-exclusive verbs. `match` filters, the verb decides behaviour.

| Verb | Origin contacted | Purpose |
|---|---|---|
| `respond` | No | Mock outright |
| `modify_request` | Yes, rewritten | Change what the backend receives |
| `modify_response` | Yes | Rewrite the real response |
| `fault` | Connection killed | Reset mid-body, hang until timeout, drip slowly |
| `passthrough` | Yes, untouched | Carve an exception out of a broader match |

`modify_request` and `modify_response` default to **JSON merge patch** (RFC 7386): named fields
change, everything else passes through untouched.

```json
{
  "name": "premium-user",
  "match": { "url": "*/api/me" },
  "modify_response": {
    "merge": { "user": { "tier": "premium", "credits": 9999 } }
  }
}
```

`json_patch` (RFC 6902) is available for array operations and removals. Full replacement is
`respond`.

Both verbs also accept `set_headers` and `remove_headers`; `modify_request` additionally accepts
`set_url` for redirecting a call to a different host or path. `delay_ms` is accepted by every
verb that produces a response — `respond`, `modify_response` and `fault` — and is applied before
the response reaches the app.

`fault` covers behaviour that cannot be tested against a real backend — a connection dying
halfway through a response body is where a meaningful class of app crashes lives:

```json
{
  "name": "checkout-drops",
  "match": { "url": "*/api/checkout*" },
  "fault": { "kind": "reset", "after_bytes": 512 }
}
```

`kind` is one of `reset` (TCP RST; `after_bytes` optional, default 0 for an immediate reset),
`hang` (accept the request, never respond — the app hits its own timeout), or `drip`
(`bytes_per_second` throttles the body to test slow-network handling).

### 7.2 Matching semantics

The current engine tries glob, then regex, then exact match against the same string
(`rules.go:51`). A pattern valid as both behaves in whichever way the author did not intend.
Replaced with explicit intent:

- `url` is always a glob. `url_regex` is always a regex. Never both, never inferred.
- Every specified condition ANDs. Unspecified means don't care.
- Evaluation order is `priority` descending, then filename ascending. Fully deterministic.
- First match wins.

### 7.3 Starlark

A fixture opts into a script, so the cheap `match` filter runs first and the interpreter only
sees flows that already qualify:

```json
{
  "name": "flaky-payments",
  "match": { "url": "*/api/pay*" },
  "script": "flaky-payments.star"
}
```

```python
def on_request(req, ctx):
    if ctx.state.get("kill_switch"):
        req.respond(status = 503, json = {"error": "maintenance"})

def on_response(req, res, ctx):
    n = ctx.state.get("attempts", 0) + 1
    ctx.state["attempts"] = n
    if n <= 2:
        res.status = 503
        res.json = {"error": "upstream busy"}
        res.delay_ms = 1500
```

Both hooks are optional. `ctx.state` is a per-script dict persisting across requests, which is
what makes retry, rate-limit and circuit-breaker scenarios expressible.

The sandbox exposes exactly `ctx.state` and `print` (routed into the traffic log). No
filesystem, no network, no clock — determinism is deliberate. Because `state` makes behaviour
sequence-dependent, **`ghostbe clear` resets script state alongside traffic**, or a second test
run inherits the first run's counters.

### 7.4 Hot reload

`fsnotify` watches `fixtures/` and `scripts/`. On change: parse all, validate, swap atomically.

**A file that fails validation never replaces the running config.** The last-good set stays
live and the parse error surfaces in `ghostbe status` and the UI. An agent mid-edit writing
invalid JSON must not silently disable interception.

### 7.5 Match tracing

The matcher records its rejection reasons on the Flow in Phase 2; the `ghostbe explain` command
that renders them arrives with the rest of the CLI in Phase 3. Until then the same data is
readable through the API and the UI.

```
$ ghostbe explain flow_01HQ8
POST https://api.example.com/api/v1/login   app=com.example.app

  login-500       ✗ header Authorization: "Bearer *" did not match "Basic dXNl…"
  premium-user    ✗ url glob "*/api/me" did not match
  flaky-payments  ✗ url glob "*/api/pay*" did not match
  legacy-login    ⊘ disabled

  → no fixture matched, forwarded to origin (200, 143ms)
```

The highest-leverage feature for agent workflows: it converts "guess and re-run" into a
readable answer, and is cheap because the matcher already computes exactly this information.

## 8. CLI (Phase 3)

Subcommands of the same binary, so the single-binary goal holds. The CLI is a thin HTTP client
against the running server's API port, which means it works from a CI runner or another machine
on the LAN, and there is one code path rather than two. Target address comes from
`GHOSTBE_ADDR` or `--addr`.

```
ghostbe serve                                  # run the interception server (default)
ghostbe status [--json]                        # ports, CA fingerprint, fixture load errors,
                                               #   connected devices, QUIC-rejected count
ghostbe doctor [--app com.x]                   # end-to-end setup validation
ghostbe traffic [--app] [--url] [--since] [--limit] [--json]
ghostbe explain <flow-id>
ghostbe await '<METHOD> <url-glob>' [--app] [--timeout 30s]
ghostbe fixture list [--json]
ghostbe fixture add <file>                     # validate, then install
ghostbe fixture toggle <name> --on|--off
ghostbe fixture validate [path]
ghostbe clear [--traffic] [--state]            # default: both
ghostbe ca [--out ca.crt]
```

Exit codes are the CI contract: `await` returns 1 on timeout, `doctor` and `fixture validate`
return non-zero on any failed check.

`ghostbe doctor` checks, in order: server reachable; CA present (prints fingerprint); a device
is connected and which apps it declared; fixtures loaded with a count and any load errors; the
most recent TLS rejection, if any, with the network-security-config hint; and the QUIC rejection
count, warning when it is high enough to suggest traffic is being missed.

## 9. Error handling

- **Origin failures** surface as a logged Flow with 502 and the dial error, never a silent drop.
- **Starlark errors** default to fail-open (the flow proceeds to origin untouched) with the
  traceback recorded on the Flow. A fixture may set `"on_error": "fail_closed"` to return 500
  instead, for tests that must not pass by accident.
- **Fixture load errors** never take down the running config (§7.4).
- **Device disconnect** tears down that device's flows and its gVisor NIC; script state
  persists, since it is keyed per script and reset explicitly.
- **Body size** is capped at 1 MB for logging and modification. Larger bodies stream through
  untouched and the Flow is marked `body_truncated`. This bound exists so a large download
  cannot exhaust memory.

## 10. Testing strategy

| Layer | Approach | Requires |
|---|---|---|
| Fixture engine, matcher, merge-patch, Starlark | Construct `Flow` values directly, assert on the transformed result. The bulk of the suite. | Nothing |
| Device protocol | Fake device over `net.Pipe`, inject recorded IP packets, assert on emitted Flows. Uses real gVisor — the stack is not mocked. | Nothing |
| End-to-end in-process | `httptest.NewTLSServer` as origin with a CA-signed leaf, real gVisor stack, fake device. Exercises TLS termination and the full path. | Nothing |
| Matcher regression corpus | Golden fixture files with expected match/no-match against recorded Flows. | Nothing |
| Android app | Not unit tested. Covered by `ghostbe doctor` plus a manual smoke checklist. | Device |

The whole suite runs with `go test ./...` and no device, emulator, or network access. Explicit
non-goal: testing gVisor itself.

## 11. Out of scope

Deliberately excluded, each because it does not serve the agent loop:

- **Rules CRUD UI and the `rules` SQLite table.** Files plus the CLI replace them. The UI keeps
  only the traffic inspector — a human watching what the agent did.
- **MCP server.** The CLI is the interface; MCP can wrap it later.
- **Certificate pinning bypass** (Frida key extraction). Detected and reported, not defeated.
- **Third-party app interception.** Requires rooted devices; documented as unsupported.
- **WebSocket and gRPC interception**, proxy chaining, and request replay.

## 12. Known limitations and risks

- **HTTP/2 to the app.** GhostBe terminates TLS and speaks HTTP/1.1 to the app while Go's
  client may speak h2 to the origin. Apps relying on h2-specific behaviour may differ from
  production. Accepted for now; revisit if it causes a real failure.
- **Certificate pinning** blocks interception entirely. Mitigated only by detection.
- **gVisor is pinned to a pseudo-version.** `@latest` does not compile (a stray `package bridge`
  test file inside `pkg/tcpip/stack`). Upgrades are manual and must be verified by building.
- **Starlark `state` makes runs order-dependent** by design. Mitigated by `ghostbe clear`
  resetting it; CI should call it between cases.
- **Multi-app attribution requires API 29+.** Below that, flows are attributed as `""`.
- **QUIC rejection depends on apps honouring fallback.** An app that hard-requires HTTP/3 will
  fail rather than downgrade. It will be visibly counted, not silently missed.

## 13. Appendix: measurements

Measured on this machine, Go 1.26, stripped with `-ldflags="-s -w"`:

| Build | Size |
|---|---|
| Baseline Go binary (`fmt` only) | 1.51 MB |
| Baseline + gVisor netstack (39 packages: ipv4, tcp, udp, channel, gonet) | 4.13 MB |
| **gVisor link cost** | **+2.62 MB** |
| Current `ghostbe`, stripped | 8.83 MB |
| Current `ghostbe`, as committed to git | 14.07 MB |

The gVisor *module* is 69 MB on disk because it ships the entire gVisor sandbox and kernel;
`pkg/tcpip` is 4 MB of that. The link cost is what matters, and it is less than half of what
`-s -w` alone recovers from the current binary.
