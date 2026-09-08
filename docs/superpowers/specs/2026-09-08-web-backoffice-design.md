# GhostBe: Web Backoffice — Design

Status: approved for planning
Date: 2026-09-08

## 1. Purpose

Give ghost-be a manual-management web UI, served by `ghost-be` itself, so a
developer or QA tester can view/edit/toggle rules and watch live traffic
without hand-editing YAML files or reading stdout logs. Two connected views:

1. **Rules** — list every rule across the rules directory (name, method,
   path, on/off), edit a rule file's raw YAML, create a new rule, toggle a
   rule on/off without a full edit.
2. **Traffic** — a live feed of every request `/intercept` sees, as it
   happens, showing which rule matched (or "passthrough") and the mocked
   status. A traffic row that matched a rule can seed a new rule's editor
   with that request's method/path.

Built with Compose Multiplatform targeting `wasm-js` (module name
`web-backoffice`), using this repo's existing Amper toolchain — confirmed
hands-on feasible (see §7) rather than assumed.

## 2. Non-goals (v1)

- No authentication — matches ghost-be's existing no-cert/no-setup
  philosophy. Anyone who can reach the port can manage rules and see traffic,
  same trust boundary as `/intercept` itself today.
- No visibility into passthrough requests' real backend response.
  `/intercept` only ever sees the real backend's response when it *mocks* a
  request; for a passthrough, the client calls the real backend directly and
  ghost-be never learns what came back. The traffic view shows
  `method path → passthrough` with no status/body for those rows. Showing
  the real response would require the client to report it back separately —
  out of scope here.
- No traffic history/replay. A browser tab connecting to the traffic feed
  sees only requests from the moment it connects onward; nothing is
  buffered server-side for a later-connecting viewer.
- No structured/form-based rule editing. Rules are edited as raw YAML text
  (a file's full content in, full content out) — no per-field form UI, no
  method dropdown/path input/etc.
- No code-editor niceties (syntax highlighting, YAML linting-as-you-type).
  Plain multiline text field; validation happens on save.
- No editing of a rule's linked response file/script content from the UI —
  only the rule YAML itself. Editing `responses/*.json` or `scripts/*.py`
  stays a filesystem operation.
- No mechanism for the released `ghost-be` binary to auto-update the web UI
  independently of the server binary — they ship and version together.

## 3. Repository layout

New module alongside the existing ones, added to `project.yaml`:

```
web-backoffice/
  module.yaml
  src/
    Main.kt           # ComposeViewport entry point
    ...
```

`web-backoffice/module.yaml`:

```yaml
product:
  type: wasm-js/app

dependencies:
  - $compose.foundation
  - $compose.material3
  - $compose.ui

settings:
  compose: enabled
```

Built with `./kotlin build -m web-backoffice -v release`, producing a
static `index.html` + `.mjs`/`.wasm` bundle under
`build/tasks/_web-backoffice_buildWasmJsAppWasmJsRelease/` (confirmed path,
see §7). `server-shared`'s Ktor server serves that directory as static files
at `GET /`, alongside the existing `/intercept` route and the new API routes
below — one process, one port, reachable over LAN exactly the same way
`--host 0.0.0.0` already makes `/intercept` reachable today.

The server needs to know where that build output lives at runtime. For a
from-source dev loop, a `--web-dist <path>` flag (defaulting to the known
build-output path) is enough. For a *released* `ghost-be` binary, the built
web UI needs to ship alongside the binary in the release artifact (e.g. a
`web/` sibling directory, or embedded as resources) — the exact packaging
mechanism is left to the implementation plan, not fixed here.

## 4. Rule schema change

`Rules.kt`'s `Rule` gains one field:

```kotlin
@Serializable
data class Rule(
    val name: String,
    val match: MatchSpec,
    val response: ResponseSpec,
    val enabled: Boolean = true
)
```

Backward compatible — existing hand-written rule files with no `enabled` key
keep working, default on. `Matcher.kt`'s `matchRule` filters to
`rule.enabled` before checking method/path/query/headers, so toggling a rule
off makes it invisible to matching without deleting it.

## 5. Server API surface

New file `server-shared/src/WebBackofficeRoute.kt`, kept separate from
`InterceptRoute.kt`:

| Route | Purpose |
|---|---|
| `GET /` + static assets | Serves the built `web-backoffice` UI |
| `GET /api/rules` | Lists every `.yaml`/`.yml` file in the rules directory: `[{path, content, rules: [{name, method, path, enabled}]}]`. `content` is the raw file text — what the editor edits and PUTs back. `rules` is a small read-only summary parsed server-side, so the UI can render a flat rule list (method/path/on-off) without shipping a YAML parser to Wasm; it is display-only and never sent back on save. |
| `PUT /api/rules/{path}` | Overwrites that file with the given raw text. Validated with the existing `loadRuleFile` parser before writing — on failure, `400` with the parser's error message, file on disk untouched. Existing `RuleWatcher` hot-reload picks up a successful write exactly as it does a hand-edit. |
| `POST /api/rules` | Creates a new file: `{path, content}`. `409` if the path already exists. |
| `DELETE /api/rules/{path}` | Deletes the file. `404` if it doesn't exist. |
| `POST /api/rules/{path}/{ruleName}/toggle` | Flips just that rule's `enabled` field and rewrites the file server-side, using the existing `Rule`/`RuleFile` model (not a text hack). This is what the list view's on/off switch calls. `404` if the file or rule name doesn't exist. |
| `GET /events/traffic` | SSE stream (`text/event-stream`), chosen over WebSocket because the feed is purely one-directional (server → browser) and the browser's built-in `EventSource` handles reconnection automatically. Each event is one JSON line per `/intercept` call: `{method, url, ruleMatched, status}` (`ruleMatched: null`, `status: null` for passthrough). Live-only, no replay — a client connecting mid-session sees nothing until the next request. |

`InterceptRoute.kt` gains one line after resolving each request: publish the
event to a `MutableSharedFlow<TrafficEvent>` that the SSE route collects
per-connection. No persistence, no buffering beyond the flow's own dispatch.

## 6. Web frontend architecture

Two panes in one screen, no routing/multi-page, no auth:

- **Rules pane** — one row per rule, flattened across files from the
  `rules` summary in `GET /api/rules`: name, method+path, on/off switch
  (calls the toggle endpoint), edit icon. Edit opens a plain multiline text
  field pre-filled with that file's raw `content`; Save calls
  `PUT /api/rules/{path}`, surfacing the server's validation error inline on
  failure. "New rule" opens the same editor empty, backed by
  `POST /api/rules` once a path is chosen.
- **Traffic pane** — a live-appending list fed by the browser's `EventSource`
  (via `kotlinx-browser`) connected to `GET /events/traffic`. Each row: time,
  method, path, matched rule or "passthrough", status. A row that matched a
  rule has a "Create rule from this" action that pre-fills the New-rule
  editor with a boilerplate YAML snippet using that request's method+path
  and a placeholder response.

State: a single plain Kotlin class (no DI framework needed at this scale)
holding `MutableState`s for the rules list (re-fetched after any mutation)
and the traffic list (appended to as SSE events arrive, capped at a
reasonable client-side rendering limit — e.g. the last 500 rows — purely so
a long-open browser tab doesn't grow its own list unbounded; unrelated to
the server having no replay buffer).

## 7. Feasibility spike (already done)

Before committing to this architecture, a scratch Amper module was built
and run to verify Amper (v0.12.0, this repo's installed toolchain version)
actually supports Compose Multiplatform for web, since prior research
turned up conflicting/stale signals on this:

- `product.type: wasm-js/app` (note the hyphens — not `wasmJs/app`) is a
  valid, working product type.
- `settings.compose: enabled` plus `$compose.foundation`/`$compose.material3`/
  `$compose.ui` dependencies resolve and compile.
- `androidx.compose.ui.window.ComposeViewport` (the current API — the older
  `CanvasBasedWindow` is superseded) mounts a real Compose UI to the DOM.
- Both `./kotlin build` (debug) and `./kotlin build -v release` succeed, the
  release task being `buildWasmJsAppWasmJsRelease`, producing a static
  `index.html` + `.mjs`/`.wasm` output directory.
- The built output was served over plain HTTP and loaded in a real
  (headless Chromium via Playwright) browser: the Compose `Text("hello from
  ghost-be web ui")` rendered correctly on the canvas with zero page errors.

This confirms the architecture in §3 is buildable on the exact toolchain
version this repo already uses — no separate Gradle setup needed alongside
Amper, contrary to what pre-spike research suggested might be necessary.

## 8. Error handling

- Malformed YAML on `PUT`/`POST /api/rules*` → `400` with `loadRuleFile`'s
  existing parser error message, file untouched.
- `PUT`/`DELETE`/toggle on an unknown path or rule name → `404`.
- `POST /api/rules` on an existing path → `409`.
- SSE client disconnect (tab closed) → the subscriber is dropped from the
  `SharedFlow` collectors when Ktor's SSE support detects the closed
  connection; no additional cleanup logic.
- Missing/misconfigured `--web-dist` path → a clear startup log message
  (same style as the existing "rules directory not found" check), not a
  silent 404.

## 9. Testing

- `WebBackofficeRoute.kt`: Ktor `testHost`-based tests (same pattern as
  `InterceptRouteTest.kt`) — create/edit/delete/toggle round-trips against a
  temp rules directory, plus the validation-rejection and 404/409 cases.
- `Matcher.kt`: extend `MatcherTest` with a case for a disabled rule being
  skipped.
- SSE broadcast: a test that fires a request through `/intercept` while
  subscribed to `/events/traffic` and asserts the expected JSON event
  arrives (Ktor's test client supports SSE).
- `web-backoffice`: the first UI code in this repo — no existing Compose
  Multiplatform test pattern to follow. Realistic v1 scope is smoke-level:
  confirm it builds, plus a small number of Compose UI tests for the two
  panes' core interactions (toggle a rule, save an edit, see a traffic row
  appear) if `compose.uiTest` proves practical for `wasm-js` (needs its own
  quick hands-on check at implementation time, same as §7's spike). If
  Compose UI testing isn't practical yet on this target, fall back to manual
  verification (build it, open it in a browser, click through) as the v1
  acceptance bar — the same bar `demo-app` already relies on today.
