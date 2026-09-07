# Agent instructions: integrating ghost-be into an Android project

This file is for an agent that has been asked to wire `ghost-be` into some
*other* Android project (not this repo) and drive an end-to-end test with
it. It assumes you're working inside that target project's checkout, with
this repo (or its README) available for reference.

Read `README.md` in this repo first for the conceptual overview and rule
format — this file only adds the step-by-step workflow.

## 1. Install the `ghost-be` binary from GitHub

`ghost-be` releases are tagged `v*` on GitHub and each attaches a prebuilt
binary for Linux, Windows, and macOS (Apple Silicon). Fetch the one for
your own OS:

```bash
owner=fcat97
repo=ghost-be
tag=$(curl -fsS "https://api.github.com/repos/$owner/$repo/releases/latest" | jq -r .tag_name)

case "$(uname -s)" in
  Linux)  asset=ghost-be-linux-x64;       out=ghost-be ;;
  Darwin) asset=ghost-be-macos-arm64;     out=ghost-be ;;
  *)      asset=ghost-be-windows-x64.exe; out=ghost-be.exe ;;
esac

curl -fsSL -o "$out" "https://github.com/$owner/$repo/releases/download/$tag/$asset"
chmod +x "$out"
```

Verify it runs: `./$out --help` or just start it (see step 3) and check
for the "listening on" log line. Keep the binary somewhere durable in the
target project (e.g. `tools/$out`) rather than a temp dir, since you'll
restart it repeatedly while iterating.

## 2. Integrate the client interceptor into the target app

Download the client `.aar` for the same tag you fetched the binary from,
and drop it into the target app module's `libs/` directory:

```bash
mkdir -p app/libs
curl -fsSL -o "app/libs/ghost-be-client-$tag.aar" \
  "https://github.com/$owner/$repo/releases/download/$tag/ghost-be-client-$tag.aar"
```

A raw local `.aar` doesn't carry its own dependency metadata, so declare
its runtime dependencies alongside it in the target app's Gradle build
(check `client/module.yaml` in this repo at the same tag if these versions
have moved on):

```kotlin
// app build.gradle.kts
dependencies {
    debugImplementation(files("libs/ghost-be-client-$tag.aar"))
    debugImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
```

Find where the target app builds its `OkHttpClient` (search for
`OkHttpClient.Builder()` or wherever its DI module provides one) and add
the interceptor there, gated so it only compiles into debug builds:

```kotlin
val clientBuilder = OkHttpClient.Builder()
if (BuildConfig.DEBUG) {
    clientBuilder.addInterceptor(GhostBeInterceptor(baseUrl = "http://127.0.0.1:8787"))
}
val client = clientBuilder.build()
```

If the app already conditionally adds debug-only interceptors (e.g. a
logging interceptor), follow that existing pattern instead of introducing a
new one. `debugImplementation` already keeps `GhostBeInterceptor` out of
release builds, so the `BuildConfig.DEBUG` guard is only needed if the same
source set is shared across build types.

On a physical device or emulator, make sure port 8787 actually reaches your
machine — see this repo's README section "Trying the demo end-to-end" for
the `10.0.2.2` vs `adb reverse` guidance.

## 3. Create rules for the target project

Make a rules directory in the target project (e.g. `ghost-be-rules/`).
Don't invent endpoints — find the real ones by grepping the target app's
networking code (Retrofit interfaces, API service classes, GraphQL
documents, etc.) for the paths and methods you need to mock.

For each behavior you want to test, write one rule:

```yaml
# ghost-be-rules/checkout.yaml
rules:
  - name: checkout-fails
    match:
      method: POST
      path: /v1/checkout
    response:
      file: responses/checkout-500.json
      status: 500
      headers: { Content-Type: application/json }
```

```json
// ghost-be-rules/responses/checkout-500.json
{ "error": "payment_declined" }
```

Start with the specific error/edge-case responses the task actually calls
for (empty list, 500, malformed payload, slow response via a script — see
README "Dynamic responses with a script") rather than mocking every
endpoint speculatively.

## 4. Run the end-to-end test loop

```bash
# terminal 1: start ghost-be pointed at the rules you just wrote (the
# binary from step 1 -- ghost-be or ghost-be.exe depending on your OS)
./ghost-be --port 8787 --rules ./ghost-be-rules

# terminal 2: build and launch the target app on a connected device/emulator
./gradlew :app:installDebug
adb shell am start -n <package>/<launch-activity>
```

Drive the app to the screen/flow that hits the mocked endpoint and confirm
it shows the behavior the rule encodes (error state, empty state, etc).

`ghost-be` hot-reloads its rules directory — to test a different scenario,
edit the rule's `status`/`file`/`headers` (or swap which response JSON it
points at) and re-trigger the same request in the app; no restart of
`ghost-be` or the app is needed. This is the fast iteration loop for
covering multiple response variants of the same endpoint:

1. Edit `ghost-be-rules/responses/<file>.json` or the rule's `status` field
2. Re-run the request from the app (re-tap the button, pull to refresh, etc.)
3. Confirm the app's new behavior matches the edited response
4. Repeat for the next scenario

When done, stop `ghost-be` (Ctrl-C) and confirm the app falls through to
the real backend with no rule matching (`intercept: false` passthrough) —
this is the "nothing breaks if ghost-be isn't running" guarantee and is
worth checking as the final step of the test.
