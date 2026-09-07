# Agent instructions: integrating ghost-be into an Android project

This file is for an agent that has been asked to wire `ghost-be` into some
*other* Android project (not this repo) and drive an end-to-end test with
it. It assumes you're working inside that target project's checkout, with
this repo (or its README) available for reference.

Read `README.md` in this repo first for the conceptual overview and rule
format — this file only adds the step-by-step workflow.

## 1. Install the `ghost-be` binary from GitHub

`ghost-be` releases are tagged `v*` on GitHub and each attaches a prebuilt
Linux binary asset named `ghost-be-linux-x64`. Fetch the latest one:

```bash
owner=fcat97
repo=ghost-be

tag=$(curl -fsS "https://api.github.com/repos/$owner/$repo/releases/latest" | jq -r .tag_name)
curl -fsSL -o ghost-be \
  "https://github.com/$owner/$repo/releases/download/$tag/ghost-be-linux-x64"
chmod +x ghost-be
```

Verify it runs: `./ghost-be --help` or just start it (see step 3) and check
for the "listening on" log line. Keep the binary somewhere durable in the
target project (e.g. `tools/ghost-be`) rather than a temp dir, since you'll
restart it repeatedly while iterating.

## 2. Integrate the client interceptor into the target app

Add the JitPack repository and the debug-only dependency to the target
app's Gradle build (exact snippet in this repo's README under "Using it in
your app"):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// app build.gradle.kts
dependencies {
    debugImplementation("com.github.fcat97:ghost-be:<tag>")
}
```

Use the same `<tag>` you fetched in step 1 so the client and server versions
match.

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
# terminal 1: start ghost-be pointed at the rules you just wrote
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
