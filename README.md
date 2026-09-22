# GhostBe

```
█████▓▒░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░▒▓█████
█░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░█
█░░░░░░░░░░░░░░░░▒██▓░▒███▒░▒█░░░░░░░░░░░░░░░░░░░█
█░░░░░░░░░░░░░░▓████░░░░░░░░████░░░░░░░░░░░░░░░░░█
█░░░░░░░░░░░░████▒░░░░░░░░░░░░████▓░░░░░░░░░░░░░░█
█░░░░░░░░░░░░████▒░░░░░░░░░░░░████▓░░░░░░░░░░░░░░█
█░░░░░░░░░░░░░░███████░░░░░░░░░░░░░██░░░░░░░░░░░░█
█░░░░░░░░░░░░░░█▒▒░░░░░░░░░░░░░░░░░█▓██░░░░░░░░░░█
█░░░░░░░░░░░░░░█▒░░░░░░░░░░░░░░░░░░░░▒█░░░░░░░░░░█
█░░░░░░░░░░░░░░██░░░░░██░░░░░░██░░░░░▓█░░░░░░░░░░█
█░░░░░░░░░░░░█░▓█░░░░░██░░░░░░██░░░░░████░░░░░░░░█
█░░░░░░░░░░▓▒▓█░░░░░░░░░░░░░░░░░░░░░░░░█▒▓░░░░░░░█
█░░░░░░░░░░██░░░░░░░▓████████████▓▓░░░░░░██░░░░░░█
█░░░░░░░░░░██░░░░░░░██████████████▓░░░░░░██░░░░░░█
█░░░░░░░░██░░░░░░░░░░░██░░░░░░██░░░░░░░░░░░██░░░░█
█░░░░░░░░▒░▓▒█░░░░░░░░░░▓▓▓▓▓▓░░░░░░░░░█░████░░░░█
█░░░░█▓▓▒▒█████▒░░░░░░░░▓▓▓▓▒▓░░░░░░░▓▒▒▒▓█▓▓░░░░█
█░░░░██░░▒░▒▓▓▒░░░░░░░░░░░░░░░░░░░░░░██░░░░░░░░░░█
█░░░░██░░░░█░░▓░░░░░░░░░░░░░░░░░░░░▒▒▒█░░░░░░░░░░█
█░░░░█▒▓█░░░░░░░░░░░░░░░░░░░░░░░░░░██░░░░░░░░░░░░█
█░░░░░░████░░░░░░░░░░░░░░░░░░░░░████▓░░░░░░░░░░░░█
█░░░░░░░░██████░░░░░░░░░░░░░░░████▓░░░░░░░░░░░░░░█
█░░░░░░░░██████░░░░░░░░░░░░░░░████▓░░░░░░░░░░░░░░█
█░░░░░░░░░░░░████▓░░░░░░░▒██████░░░░░░░░░░░░░░░░░█
█░░░░░░░░░░░░░░░░░██████████░░░░░░░░░░░░░░░░░░░░░█
█░░░░░░░░░░░░░░░░░░░░░░░░░░░█░░░░░░░░░░░░░░░░░░░░█
███▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░▓███

 ██████╗ ██╗  ██╗ ██████╗ ███████╗████████╗   ██████╗ ███████╗
██╔════╝ ██║  ██║██╔═══██╗██╔════╝╚══██╔══╝   ██╔══██╗██╔════╝
██║  ███╗███████║██║   ██║███████╗   ██║█████╗██████╔╝█████╗
██║   ██║██╔══██║██║   ██║╚════██║   ██║╚════╝██╔══██╗██╔══╝
╚██████╔╝██║  ██║╚██████╔╝███████║   ██║      ██████╔╝███████╗
 ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚══════╝   ╚═╝      ╚═════╝ ╚══════╝
```

GhostBe lets you control what response your Android app sees for any given
HTTP request — so you can test how your app behaves against a 500 error, an
empty list, a slow network, or a payload your real backend doesn't produce
yet — without touching your backend and without installing any certificate
on the device.

It works at the app level, not the network level: a small OkHttp
interceptor in your app asks a local server, `ghost-be`, what to do with
each request. If you've configured `ghost-be` to intercept it, your app gets
the response you configured back. Otherwise, your app just calls the real
endpoint like normal.

```
Your app
  -> OkHttp client (GhostBeInterceptor installed)
  -> asks ghost-be, running locally, what to do with this request
       - a rule matches    -> ghost-be sends back the response you configured
       - no rule matches   -> ghost-be says "pass this through"
  -> your app gets the configured response, or calls the real endpoint
```

## Why

- **No certificates.** Talks to `ghost-be` over plain HTTP on localhost —
  nothing to install or trust on the device.
- **No backend changes.** Mock a response your real API can't produce yet,
  or reproduce a bug that only happens on a specific error response.
- **Plain text config.** Rules live in YAML files and point at either a
  static JSON file or a script — no code changes needed to add a new mock.
- **Safe to leave wired in.** If `ghost-be` isn't running, your app just
  talks to the real backend — nothing breaks.

## Using it in your app

### Android

Download the client `.aar` from the [latest Release](../../releases/latest)
(asset named `ghost-be-client-<tag>.aar`) and drop it into your app
module's `libs/` directory. A raw local `.aar` doesn't carry its own
dependency metadata, so declare its runtime dependencies alongside it —
same variant as the AAR itself (debug/test only, see below):

```kotlin
// app build.gradle.kts
dependencies {
    debugImplementation(files("libs/ghost-be-client-<tag>.aar"))
    debugImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
```

(Replace `<tag>` with the release tag you downloaded; check the exact
runtime dependency versions in that tag's `client-android/module.yaml` if you're
not on the latest release.)

Add the interceptor to whichever `OkHttpClient` your app uses — typically
only in a debug or test build variant:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(GhostBeInterceptor(baseUrl = "http://127.0.0.1:44678"))
    .build()
```

### iOS

Add this repo as a Swift Package dependency (Xcode: File → Add Package
Dependencies…, or in `Package.swift`):

```swift
.package(url: "https://github.com/fcat97/ghost-be", from: "0.1.14")
```

Then use `GhostBe.session(...)` instead of building your own Alamofire
`Session` — it's a normal `Session`, pre-configured to ask `ghost-be`
about every request:

```swift
import GhostBe

let session = GhostBe.session() // defaults to http://127.0.0.1:44678
session.request("https://api.example.com/v1/users/42")
    .responseDecodable(of: User.self) { response in
        // ...
    }
```

### Flutter

Add this repo as a git dependency in `pubspec.yaml`:

```yaml
dependencies:
  ghost_be:
    git:
      url: https://github.com/fcat97/ghost-be
      path: client-flutter
```

Add the interceptor to whichever `Dio` instance your app uses:

```dart
import 'package:dio/dio.dart';
import 'package:ghost_be/ghost_be.dart';

final dio = Dio()..interceptors.add(GhostBeInterceptor(baseUrl: 'http://127.0.0.1:44678'));
```

That's it on the app side. Everything else is configuring and running
`ghost-be`.

### Testing on a physical device over Wi-Fi

For a QA tester with no cable and no code access, all three clients accept
the server address via a deep link instead of a hardcoded `baseUrl`:

1. Start `ghost-be --host 0.0.0.0` and find the machine's LAN IP
   (`ipconfig getifaddr en0` on macOS, `hostname -I` on Linux).
2. Send the tester a link: `myapp://open?ghostBe=192.168.1.5:44678`.
3. Tapping it points every request for that run at that address.

For step 3 to work, wire the app's deep-link handler to `GhostBe` (add a
bare scheme if it has none):

```kotlin
// Android
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    GhostBe.captureFromIntent(intent)
}
```

```swift
// iOS
func application(_ app: UIApplication, open url: URL, options: ...) -> Bool {
    GhostBe.captureFromURL(url)
    return true
}
```

```dart
// Flutter
AppLinks().uriLinkStream.listen(GhostBe.captureFromUri);
```

## Running ghost-be

`ghost-be` is a small command-line server you run on your machine (or a CI
runner) alongside the app you're testing:

```bash
ghost-be --port 44678 --rules ./rules
```

Both flags are optional (`44678` and `./rules` are the defaults). Run
`ghost-be --help` for the rest, including `--host` for LAN access and
`--journal-size` for how many requests to retain for
[test assertions](#driving-tests-from-maestro).

### Web backoffice

`ghost-be` serves a web UI automatically at `http://<host>:<port>/`
alongside `/intercept` — no separate process. It's a two-pane UI: a rules
list (view, create, edit raw YAML, toggle on/off, delete) next to a live
traffic feed of every request `/intercept` sees, with a "Create rule from
this" action on any matched-traffic row.

The downloadable release archives (see "Installing a release" below)
bundle this UI's built assets in a `web-backoffice/` folder next to the
`ghost-be` binary, and it's found automatically by the binary's own
location on disk — no build step, no flags, works regardless of your
current working directory. If you're building `ghost-be` from source
instead, build the UI first (`./kotlin build -m web-backoffice -v
release`) and either keep the default relative dev path or point
`--web-dist <dir>` at wherever you built it.

### Writing rules

Rules live in YAML files under your rules directory. Each rule says which
requests it matches, and what to send back:

```yaml
# rules/users.yaml
rules:
  - name: user-not-found
    match:
      method: GET
      path: /v1/users/42
    response:
      file: responses/user-42-404.json
      status: 404
      headers: { Content-Type: application/json }
```

```json
// rules/responses/user-42-404.json
{ "error": "not found" }
```

Requests that don't match any rule are passed straight through to your real
backend — you only need a rule for the cases you actually want to mock.

`ghost-be` watches your rules directory and reloads automatically whenever a
file changes — no restart needed after editing a rule or adding a new one. A
rule file that's briefly invalid mid-edit just gets skipped (with a message
on stdout); `ghost-be` keeps serving the last good set of rules until the
file is valid again.

`match` can also filter on specific query params or headers, and `path` can
be a pattern like `/v1/users/{id}` for matching a family of URLs. See the
[design spec](docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md#6-rule-configuration)
for the full format.

### Scenarios and response sequences

Two optional fields turn a set of rules into a multi-step journey. Both are
additive — every rule file written without them keeps working unchanged.

A **`scenario`** tag makes a rule dormant until something activates that
scenario. A rule without one is *baseline*: always active. When a scenario is
active, its rules beat baseline rules for the same endpoint, regardless of the
order they appear in:

```yaml
rules:
  # Baseline: always active. Your happy path lives here once.
  - name: checkout-ok
    match: { method: POST, path: /v1/checkout }
    response: { file: responses/checkout-ok.json, status: 200 }

  # Dormant until "checkout-fails" is activated, and then it wins.
  - name: checkout-declined
    scenario: checkout-fails
    match: { method: POST, path: /v1/checkout }
    response: { file: responses/declined.json, status: 402 }
```

So each test switches on only the one thing it wants broken, instead of
maintaining a whole parallel copy of your mocks. Several scenarios can be
active at once — activate `checkout-fails` and `slow-network` together and each
tagged rule fires for its own endpoint.

**`responses`** (plural) replaces `response` with an ordered list, served one
per matching call. The last entry sticks, so a sequence never runs out — which
matters because you rarely know exactly how many times an app will poll or
retry:

```yaml
  - name: order-status
    match:
      method: GET
      pathPattern: "/v1/orders/{id}"
    responses:
      - { file: responses/order-pending.json, status: 200 }   # 1st call
      - { file: responses/order-pending.json, status: 200 }   # 2nd call
      - { file: responses/order-done.json, status: 200 }      # 3rd call onwards
```

A rule must declare exactly one of `response` or `responses`; declaring both is
rejected at load time rather than resolved by a silent precedence rule.

[`demo-rules/journey.yaml`](demo-rules/journey.yaml) is a runnable example of
both fields together.

### Dynamic responses with a script

For responses that depend on the request (rather than always returning the
same file), point a rule at a script instead of a file:

```yaml
  - name: dynamic-user
    match:
      method: GET
      pathPattern: "/v1/users/{id}"
    response:
      script: scripts/dynamic_user.py
      status: 200
```

`ghost-be` runs the script (`python3` for `.py`, `node` for `.js`), writing
the request as JSON to its stdin and expecting a JSON response back on
stdout. A non-zero exit code, or stdout that doesn't parse as expected,
becomes a `500` response describing the failure — the app still gets *a*
response, it just won't be the one your script intended.

For the exact request/response JSON shapes and a full worked example, see
[`demo-rules/scripts/dynamic_user.py`](demo-rules/scripts/dynamic_user.py)
(wired up by [`demo-rules/dynamic.yaml`](demo-rules/dynamic.yaml)) — its
comments document the contract, and it's a real script you can run.

## Driving tests from Maestro

Scenarios and sequences describe a journey; this is how a test drives it. You
write an ordinary [Maestro](https://maestro.dev) flow, and `ghost-be` supplies
the data — so you can test a whole journey end to end with no real backend.

Maestro runs on your machine and drives the app as a black box, so its
`runScript` steps reach `ghost-be` on `127.0.0.1` directly. Only the *app*
needs `adb reverse tcp:44678 tcp:44678`. Nothing changes in your app code
beyond the interceptor you already added.

Copy the [`maestro/`](maestro) scripts (bundled in every release archive, next
to the binary) alongside your flows:

```yaml
appId: com.example.app
---
# Clears active scenarios, call counters and the journal. Always go first:
# without it a sequence resumes where the last flow left it.
- runScript: ghost-be/reset.js

- launchApp:
    clearState: true
- tapOn: "Checkout"
- assertVisible: "Order confirmed"

# Assert the app really sent the request, not just that the screen changed.
- runScript:
    file: ghost-be/verify.js
    env: { METHOD: POST, PATH: /v1/checkout, COUNT: "1" }
- assertTrue: ${output.ghostBeVerifyOk == 'true'}

# One call switches which mock is live, effective on the app's very next
# request — no sleep, and nothing is written to your rules directory.
- runScript:
    file: ghost-be/scenario.js
    env: { SCENARIOS: checkout-fails }

- launchApp:
    clearState: true
- tapOn: "Checkout"
- assertVisible: "Your card was declined."
```

| Script | `env` | What it does |
|---|---|---|
| `reset.js` | — | Clears scenarios, counters and journal |
| `scenario.js` | `SCENARIOS` | Makes exactly those scenarios active (comma-separated; empty string clears) |
| `verify.js` | `METHOD`, `PATH`, `COUNT`, `BODY_CONTAINS` | Asserts on what the app sent; sets `output.ghostBeVerifyOk` |
| `journal.js` | `METHOD`, `PATH`, `BODY_CONTAINS`, `LIMIT` | Prints matching requests for debugging; never fails a flow |

All four take an optional `GHOST_BE_URL` (default `http://127.0.0.1:44678`).

Two details that are easy to get wrong:

- **Write `${output.ghostBeVerifyOk == 'true'}`, not `${output.ghostBeVerifyOk}`.**
  Values on `output` cross step boundaries as strings, and a non-empty string is
  truthy — so the bare form passes even when the check failed.
- **There's one script per action rather than one helper library**, because
  Maestro keeps only the `output` object between steps. Function declarations
  don't survive, so a `ghostBe.verify(...)` helper defined in one step wouldn't
  exist in the next.

[`maestro/example-flow.yaml`](maestro/example-flow.yaml) is a complete flow
covering a happy path, a failure branch and a polling step, driving
[`demo-rules/journey.yaml`](demo-rules/journey.yaml).

### What's ephemeral, and what isn't

Everything a flow changes — active scenarios, call counters, the journal —
lives in memory only. Your rules directory is **never written to**, so a
crashed test can't leave your repo dirty, and every change takes effect before
the app's next request with nothing to poll or sleep on.

That also means it's all gone when `ghost-be` restarts, and that `reset.js`
is what separates one flow from the next.

### The control API

The scripts are a thin wrapper over plain HTTP, so any framework can drive
`ghost-be` the same way:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/test/state` | Active and available scenarios, hit counts, journal size |
| `PUT` | `/api/test/scenarios` | `{"active":["a","b"]}` — full replacement |
| `POST` | `/api/test/scenarios/{name}` | Activate one |
| `DELETE` | `/api/test/scenarios/{name}` | Deactivate one |
| `GET` | `/api/test/journal` | `{count, entries}`; filters `method`, `path`, `bodyContains`, `limit` |
| `GET` | `/api/test/journal/count` | The count alone, as a bare integer |
| `POST` | `/api/test/reset` | Clear everything |

Every mutation returns the full new state, so a flow can set and assert in one
call. Deactivation and reset are idempotent and never fail. `PUT
/api/test/scenarios` is the exception: it rejects a name no loaded rule
declares, with `400` and the list of names that do exist — a silently-ignored
typo is the easiest way to end up with a green test that asserts nothing.

`bodyContains` matches the **decoded** request body, so you filter on the
payload your app sends rather than its base64 encoding.

`--journal-size <n>` sets how many requests are retained (default 500); the
oldest are dropped once it's full.

### One device per instance

Scenarios and counters are global to a `ghost-be` process, so two devices
pointed at one instance will consume each other's sequence steps. Run one
`ghost-be` per device — on separate ports — when testing in parallel.

### A note on `--host 0.0.0.0`

Binding to the LAN (as the Wi-Fi testing path above suggests) also exposes
`/api/test/*` and the request journal, and the journal retains request headers
— including `Authorization`. That's no worse than `/api/rules`, which can
already rewrite your rule files over the same connection, but it's worth
knowing. The default `127.0.0.1` bind is all Maestro ever needs.

## Project status

Tagged releases (`v*`) attach a prebuilt `ghost-be` archive for Linux
(`ghost-be-linux-x64.tar.gz`), Windows (`ghost-be-windows-x64.zip`), and
macOS Apple Silicon (`ghost-be-macos-arm64.tar.gz`), plus the `client`
library's `.aar`, to the corresponding [GitHub Release](../../releases)
(see "Using it in your app" above) — no build-from-source needed for any
of them. Each archive bundles the server binary together with the
web-backoffice UI's built assets, in a `web-backoffice/` folder next to
the binary, so extracting the archive is the only setup step. Neither the iOS nor the Flutter package has a release artifact of
its own — Swift Package Manager and `pub` both resolve straight from this
repo's `vX.Y.Z` git tags, and Xcode/`dart pub get` build from source. If
you're working from an untagged commit, or want to build any piece
yourself, see below.

## Building from source

The Kotlin/Native and Android pieces are built with the
[Kotlin Toolchain](https://kotlin-toolchain.org/dev/) (`module.yaml`/
`project.yaml`, no Gradle files to author directly). The iOS and Flutter
clients are separate, plain packages — a Swift Package (`Package.swift`
at the repo root, built with `swift build`/`swift test`) and a Dart
package (`client-flutter/pubspec.yaml`, built with `dart pub get`/
`dart test`) respectively — neither involves Kotlin/KMP at all.

| Module | What it is |
|---|---|
| [`client-android/`](client-android) | The `GhostBeInterceptor` library (Android) |
| [`client-ios/`](client-ios) | The iOS client (Swift Package, Alamofire-based — see "Using it in your app" above) |
| [`client-flutter/`](client-flutter) | The Flutter client (Dart package, dio-based — see "Using it in your app" above) |
| [`server-shared/`](server-shared) | `ghost-be`'s shared implementation (Kotlin/Native library, `linuxX64` + `mingwX64` + `macosArm64`) |
| [`server-linux/`](server-linux) | The Linux `ghost-be` executable — thin wrapper around `server-shared/`'s entry point |
| [`server-windows/`](server-windows) | The Windows `ghost-be` executable — same, cross-compiled for `mingwX64` |
| [`server-macos/`](server-macos) | The macOS (Apple Silicon) `ghost-be` executable — same, for `macosArm64` |
| [`demo-app/`](demo-app) | A minimal Compose app for manually exercising `client-android/` (consumes it from local Maven, not as a project dependency — see below) |
| [`demo-backend/`](demo-backend) | A tiny Node "real backend" for the demo app to fall through to |

**Prerequisites:** a JDK, the Android SDK (`ANDROID_HOME` set) for
`client-android`/`demo-app`, and Node.js for `demo-backend`. `server-linux` and
`server-windows` cross-compile from any host — no Docker or Windows
machine needed. `server-macos` needs a real Mac (Apple's SDK can't be
bundled the way mingw-w64 is); CI builds it on a `macos-latest` runner.
The `kotlin`/`kotlin.bat` scripts in the repo root bootstrap the
toolchain itself on first use — nothing else to install.

```bash
export ANDROID_HOME=/path/to/Android/Sdk

./kotlin build                     # build every module
./kotlin test                       # run every module's tests
./kotlin build -m server-linux       # build/test just one module
./kotlin run -m server-linux -- --rules ./rules --port 44678
```

### Trying the demo end-to-end

`demo-app` depends on `dev.yellowbytes.ghostbe:client:0.1.0` resolved from
your local Maven repository (`~/.m2/repository`), the same way a real
consumer would depend on it — not on `client-android/` as an in-repo
project. So any time you change `client-android/` and want `demo-app` to
pick it up, publish it locally first:

```bash
# 0. Publish client to local Maven (repeat after any change to client-android/)
./kotlin publish mavenLocal -m client-android

# 1. Start the "real" backend
node demo-backend/server.js

# 2. Start ghost-be with a rules dir of your own (see "Writing rules" above)
./kotlin run -m server-linux -- --rules ./demo-rules --port 44678

# 3. Install and launch demo-app on a connected device/emulator
export ANDROID_SERIAL=emulator-5554   # if more than one device is attached
./kotlin run -m demo-app
```

On an emulator, its usual `10.0.2.2` host-loopback alias should reach
`demo-backend`/`ghost-be` with no setup. If it times out on TCP connects
(some host firewalls block it even though ping still works), fall back to
tunneling through adb instead:

```bash
adb reverse tcp:3000 tcp:3000
adb reverse tcp:44678 tcp:44678
```

(demo-app is wired to `127.0.0.1`, which these mappings redirect to the
host.) These don't survive an emulator restart, so re-run them after a cold
boot.

Tap "Fetch User" with ghost-be running to see a mocked response; stop
ghost-be and tap again to see the interceptor fall through to the real
demo-backend response instead.

## Design docs

- [`docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md`](docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md) — architecture, wire format, rule config, error handling
- [`docs/superpowers/plans/2026-09-06-ghostbe-server.md`](docs/superpowers/plans/2026-09-06-ghostbe-server.md) — server implementation plan
- [`docs/superpowers/plans/2026-09-06-ghostbe-client.md`](docs/superpowers/plans/2026-09-06-ghostbe-client.md) — client implementation plan

## License

[MIT](LICENSE)
