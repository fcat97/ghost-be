# GhostBe

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

Add the interceptor to whichever `OkHttpClient` your app uses — typically
only in a debug or test build variant, since it adds a network hop to every
request:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(GhostBeInterceptor(baseUrl = "http://127.0.0.1:8787"))
    .build()
```

That's it on the app side. Everything else is configuring and running
`ghost-be`.

## Running ghost-be

`ghost-be` is a small command-line server you run on your machine (or a CI
runner) alongside the app you're testing:

```bash
ghost-be --port 8787 --rules ./rules
```

Both flags are optional (`8787` and `./rules` are the defaults).

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

`match` can also filter on specific query params or headers, and `path` can
be a pattern like `/v1/users/{id}` for matching a family of URLs. See the
[design spec](docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md#6-rule-configuration)
for the full format.

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

`ghost-be` runs the script (`python3` for `.py`, `node` for `.js`), passing
it the request as JSON on stdin, and expects a JSON response
(`{"status": ..., "headers": {...}, "body": "<base64>"}`) back on stdout.

## Project status

This is early — there's no published library or packaged binary yet, so for
now both pieces need to be built from source. See below.

## Building from source

This repo is built with the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/)
(`module.yaml`/`project.yaml`, no Gradle files to author directly).

| Module | What it is |
|---|---|
| [`client/`](client) | The `GhostBeInterceptor` library (Android) |
| [`server/`](server) | `ghost-be` itself (Kotlin/Native, Linux) |
| [`demo-app/`](demo-app) | A minimal Compose app for manually exercising `client/` |
| [`demo-backend/`](demo-backend) | A tiny Node "real backend" for the demo app to fall through to |

**Prerequisites:** a JDK, the Android SDK (`ANDROID_HOME` set) for
`client`/`demo-app`, Node.js for `demo-backend`, and Linux for building
`server`. The `kotlin`/`kotlin.bat` scripts in the repo root bootstrap the
toolchain itself on first use — nothing else to install.

```bash
export ANDROID_HOME=/path/to/Android/Sdk

./kotlin build              # build every module
./kotlin test                # run every module's tests
./kotlin build -m server     # build/test just one module
./kotlin run -m server -- --rules ./rules --port 8787
```

### Trying the demo end-to-end

```bash
# 1. Start the "real" backend
node demo-backend/server.js

# 2. Start ghost-be with a rules dir of your own (see "Writing rules" above)
./kotlin run -m server -- --rules ./demo-rules --port 8787

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
adb reverse tcp:8787 tcp:8787
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
