# iOS/Alamofire Interceptor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let iOS apps using Alamofire get the same request-mocking behavior Android apps already get from `client/`'s `GhostBeInterceptor`, distributed as a Swift Package.

**Architecture:** Extend `client/` (currently `kmp/lib`, `platforms: [android]`) to also target `iosArm64`/`iosSimulatorArm64`. Extract the protocol logic (envelope building, JSON encode/decode, mock-vs-passthrough decision) into shared Kotlin behind an `expect`/`actual` for the one platform-specific piece (the HTTP call to `ghost-be`). A new native Swift package (`ios/GhostBe/`) provides the actual `NSURLProtocol` subclass — required because Kotlin/Native cannot override `NSURLProtocol`'s class-side methods — calling into the shared Kotlin core via an `.xcframework`.

**Tech Stack:** Kotlin Multiplatform (Kotlin Toolchain / Amper, this repo's existing tool), Swift Package Manager, XCTest, GitHub Actions `macos-latest`.

**Spec:** [`docs/superpowers/specs/2026-09-07-ios-alamofire-interceptor-design.md`](../specs/2026-09-07-ios-alamofire-interceptor-design.md)

## Global Constraints

- No `RequestInterceptor` conformance — it cannot mock a response (spec §2).
- No Kotlin Swift export — classic Objective-C framework export only, for now (spec §4).
- Minimum deployment target iOS 13 (spec §10).
- No CocoaPods — SPM only (spec §10).
- Apple targets (iOS included) cannot be compiled or type-checked at all on a non-Apple host — confirmed empirically for both macOS and iOS earlier in this project. Every Kotlin/Native Apple-side step in this plan can *only* be validated via the `macos-latest` CI job introduced in Task 1; there is no local dev loop for this code. Expect multiple CI round-trips per task — this matched real experience building `server-macos` earlier in this project, and every Apple-interop guess made blind so far in this project's history has needed at least one correction once CI actually ran it.

---

### Task 1: Discover how Amper exports an iOS framework, and gate the rest of the plan on it

This project's distribution design (spec §8: prebuilt `.xcframework` + SPM `.binaryTarget`) assumes Amper's `kmp/lib` product type can produce a Swift-consumable `.framework` per Apple target. Neither Amper's own module-file reference nor its publishing guide documents this for Apple targets (only Maven publishing is documented) — this must be confirmed against the real tool before any other task in this plan is attempted, since if it's not possible, the whole distribution approach in the spec needs to be revisited.

**Files:**
- Create: `ios-framework-spike/module.yaml` (throwaway, deleted at the end of this task)
- Create: `ios-framework-spike/src/Placeholder.kt` (throwaway)
- Modify: `project.yaml` (add then remove `ios-framework-spike`)
- Create: `.github/workflows/ios-framework-spike.yml` (throwaway `workflow_dispatch` job, deleted at the end of this task)

**Interfaces:**
- Produces: the exact Amper invocation (module.yaml settings + CLI command) that yields a `.framework` per Apple target for a `kmp/lib` module, and the exact `xcodebuild -create-xcframework` invocation that combines them — both get written into Task 4/5 once confirmed. If no such invocation exists, this task's step 6 below applies instead.

- [ ] **Step 1: Create a minimal throwaway kmp/lib targeting both iOS platforms**

```yaml
# ios-framework-spike/module.yaml
product:
  type: kmp/lib
  platforms: [ iosArm64, iosSimulatorArm64 ]
```

```kotlin
// ios-framework-spike/src/Placeholder.kt
package spike

fun ping(): String = "pong"
```

Add `ios-framework-spike` to `project.yaml`'s `modules:` list.

- [ ] **Step 2: Add a throwaway CI job to build it and inspect the output**

```yaml
# .github/workflows/ios-framework-spike.yml
name: iOS Framework Spike

on:
  workflow_dispatch:

jobs:
  build:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build for both iOS targets
        run: ./kotlin build -m ios-framework-spike -v release
      - name: List everything produced under build/tasks
        run: find build/tasks -ipath "*ios-framework-spike*" | sort
      - name: Look for a .framework anywhere in the output
        run: find build/tasks -iname "*.framework" -o -iname "*.framework.zip"
```

- [ ] **Step 3: Commit, push, trigger via `gh workflow run "iOS Framework Spike" --ref master`, watch with `gh run watch <id> --exit-status`**

- [ ] **Step 4: Read the "List everything produced" and "Look for a .framework" step output**

If a `.framework` (or `.xcframework`) is present under `build/tasks/`, note its exact task-directory naming pattern (e.g. `_ios-framework-spike_linkIosArm64Release/...`) — this confirms the default `kmp/lib` build already produces one, and Task 4 uses that path pattern directly, one per Apple platform, combined via:

```bash
xcodebuild -create-xcframework \
  -framework build/tasks/_client_linkIosArm64Release/client.framework \
  -framework build/tasks/_client_linkIosSimulatorArm64Release/client.framework \
  -output GhostBe.xcframework
```

(adjust the two `-framework` paths to match whatever the real task-directory names turned out to be in step 4).

- [ ] **Step 5: If no `.framework` appeared, try common alternate settings before concluding it's unsupported**

Add each of these to `ios-framework-spike/module.yaml`'s `settings:` block, one at a time, re-running the Step 2 workflow after each, until one produces a `.framework`:

```yaml
settings:
  native:
    ios:
      framework:
        basename: GhostBeCore
```

```yaml
settings:
  kotlin:
    framework: enabled
```

(These are reasonable guesses based on how other Amper settings blocks are shaped in this repo — e.g. `settings.native.entryPoint` for apps — not confirmed API. Whichever, if any, actually produces a `.framework` in the workflow's "Look for a .framework" step output is the one to use in Task 4.)

- [ ] **Step 6: If nothing in Steps 2-5 produces a `.framework`, STOP**

Do not proceed to Task 2. Report back with: the exact `find build/tasks` output from a `release` build, and whatever `settings:` variants were tried. This means the spec's §8 distribution design (prebuilt `.xcframework` + SPM binary target) needs revisiting — likely alternatives are (a) Amper's raw `.klib` output plus a hand-written Objective-C bridging header, a much larger undertaking, or (b) revisiting Kotlin's experimental Swift export despite the spec's stated preference against it (spec §4). Either requires a design update before more implementation work.

- [ ] **Step 7: Clean up the throwaway spike (only after Step 4 or 5 succeeded)**

```bash
git rm -rf ios-framework-spike .github/workflows/ios-framework-spike.yml
# remove ios-framework-spike from project.yaml's modules list
git add project.yaml
git commit -m "chore: remove throwaway ios-framework-spike (framework export confirmed)"
git push origin master
```

---

### Task 2: Extract shared decision logic out of `GhostBeInterceptor`, move Android glue to `client/src@android`

Splits the current single-platform `GhostBeInterceptor.kt` into shared decision logic (usable by both Android and the future iOS surface) and Android-only glue (OkHttp `Interceptor` conformance, `android.util.Log`). This is pure Kotlin refactoring with zero Apple-platform dependency — fully testable on this machine, no CI needed. Adding the iOS platform list itself happens in Task 3 (this task keeps `client/module.yaml` at `platforms: [android]` throughout, since `expect`/`actual` only makes sense once a second platform exists — see Task 3).

**Files:**
- Create: `client/src/GhostBeResolver.kt` (new, shared)
- Modify: `client/src/Envelope.kt` (no content change — confirm it has zero Android-specific imports; it doesn't today)
- Move: `client/src/GhostBeInterceptor.kt` → `client/src@android/GhostBeInterceptor.kt`
- Move: `client/test/GhostBeInterceptorTest.kt` → `client/test@android/GhostBeInterceptorTest.kt`
- Test: `client/test/GhostBeResolverTest.kt` (new, shared — no Android/OkHttp dependency, just the decision logic)

**Interfaces:**
- Produces (used by Task 3's iOS surface and Task 2's own refactored `GhostBeInterceptor`):
  ```kotlin
  // client/src/GhostBeResolver.kt, package dev.yellowbytes.ghostbe.client
  sealed interface Decision {
      data class Mock(val status: Int, val headers: Map<String, String>, val body: ByteArray) : Decision
      object Passthrough : Decision
  }

  expect fun relayToGhostBe(url: String, requestBodyJson: String): String

  object GhostBeResolver {
      fun resolve(baseUrl: String, method: String, url: String, headers: Map<String, String>, body: ByteArray?): Decision
  }
  ```
- Consumes: `RequestEnvelope`/`ResponseEnvelope`/`.toJson()`/`.fromJson()` from the existing `client/src/Envelope.kt` (unchanged).

- [ ] **Step 1: Write the failing shared test**

```kotlin
// client/test/GhostBeResolverTest.kt
package dev.yellowbytes.ghostbe.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GhostBeResolverTest {
    @Test
    fun `resolves to Mock when the relay returns an intercepting response`() {
        val fakeRelay: (String, String) -> String = { _, _ ->
            """{"intercept":true,"status":201,"headers":{"Content-Type":"application/json"},"body":"eyJvayI6dHJ1ZX0="}"""
        }
        val decision = GhostBeResolver.resolveWith(fakeRelay, "GET", "http://x/v1/users/42", emptyMap(), null)
        val mock = assertIs<Decision.Mock>(decision)
        assertEquals(201, mock.status)
        assertEquals("""{"ok":true}""", mock.body.decodeToString())
    }

    @Test
    fun `resolves to Passthrough when the relay returns a non-intercepting response`() {
        val fakeRelay: (String, String) -> String = { _, _ -> """{"intercept":false}""" }
        val decision = GhostBeResolver.resolveWith(fakeRelay, "GET", "http://x/v1/other", emptyMap(), null)
        assertEquals(Decision.Passthrough, decision)
    }
}
```

Note this test calls `GhostBeResolver.resolveWith(relay, ...)` (an injectable-relay overload for testability) rather than `resolve(baseUrl, ...)` (the real one, which calls the platform `actual relayToGhostBe`) — Step 3 defines both.

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m client`
Expected: FAIL — `GhostBeResolver`/`Decision` don't exist yet (compile error, not a runtime test failure).

- [ ] **Step 3: Write `GhostBeResolver.kt`**

```kotlin
// client/src/GhostBeResolver.kt
package dev.yellowbytes.ghostbe.client

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

sealed interface Decision {
    data class Mock(val status: Int, val headers: Map<String, String>, val body: ByteArray) : Decision
    object Passthrough : Decision
}

expect fun relayToGhostBe(url: String, requestBodyJson: String): String

@OptIn(ExperimentalEncodingApi::class)
object GhostBeResolver {
    fun resolve(
        baseUrl: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): Decision = resolveWith(
        relay = { relayUrl, json -> relayToGhostBe(relayUrl, json) },
        method = method,
        url = url,
        headers = headers,
        body = body,
        interceptUrl = baseUrl.trimEnd('/') + "/intercept"
    )

    internal fun resolveWith(
        relay: (String, String) -> String,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        interceptUrl: String = "http://127.0.0.1:8787/intercept"
    ): Decision {
        val envelope = RequestEnvelope(
            method = method,
            url = url,
            headers = headers,
            body = body?.let { Base64.encode(it) }
        )
        val responsePayload = relay(interceptUrl, envelope.toJson())
        return when (val responseEnvelope = ResponseEnvelope.fromJson(responsePayload)) {
            is ResponseEnvelope.Passthrough -> Decision.Passthrough
            is ResponseEnvelope.Mock -> Decision.Mock(
                status = responseEnvelope.status,
                headers = responseEnvelope.headers,
                body = Base64.decode(responseEnvelope.body)
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m client`
Expected: PASS for both new `GhostBeResolverTest` cases. (`GhostBeInterceptorTest` will now fail to compile — expected, fixed in Step 5.)

- [ ] **Step 5: Move Android glue into `client/src@android`, refactor to delegate to `GhostBeResolver`**

```bash
mkdir -p client/src@android client/test@android
git mv client/src/GhostBeInterceptor.kt client/src@android/GhostBeInterceptor.kt
git mv client/test/GhostBeInterceptorTest.kt client/test@android/GhostBeInterceptorTest.kt
```

Rewrite `client/src@android/GhostBeInterceptor.kt`:

```kotlin
package dev.yellowbytes.ghostbe.client

import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

private val relayClient = OkHttpClient()

actual fun relayToGhostBe(url: String, requestBodyJson: String): String {
    val relayRequest = Request.Builder()
        .url(url)
        .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
        .build()
    return relayClient.newCall(relayRequest).execute().use { it.body!!.string() }
}

@OptIn(ExperimentalEncodingApi::class)
class GhostBeInterceptor(
    private val baseUrl: String = "http://127.0.0.1:8787"
) : Interceptor {

    private companion object {
        const val TAG = "GhostBe"
    }

    // android.util.Log is a stub under plain JVM unit tests (no Robolectric) and throws
    // "not mocked" -- logging is best-effort and never worth failing a request over.
    private fun logDebug(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestLabel = "${originalRequest.method} ${originalRequest.url}"
        val headers = originalRequest.headers.toMultimap().mapValues { it.value.joinToString(",") }
        val bodyBytes = originalRequest.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readByteArray()
        }

        val decision = try {
            GhostBeResolver.resolve(baseUrl, originalRequest.method, originalRequest.url.toString(), headers, bodyBytes)
        } catch (e: IOException) {
            logDebug("<- $requestLabel: ghost-be unreachable, passthrough")
            return chain.proceed(originalRequest)
        }

        return when (decision) {
            is Decision.Passthrough -> {
                logDebug("$requestLabel -> passthrough")
                chain.proceed(originalRequest)
            }
            is Decision.Mock -> {
                logDebug("$requestLabel -> intercepted -> ${decision.status}")
                buildResponse(originalRequest, decision)
            }
        }
    }

    private fun buildResponse(request: Request, mock: Decision.Mock): Response {
        val contentType = mock.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaType()

        val responseBuilder = Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(mock.status)
            .message(if (mock.status in 200..299) "OK" else "Error")
            .body(mock.body.toResponseBody(contentType))

        mock.headers.forEach { (key, value) -> responseBuilder.addHeader(key, value) }

        return responseBuilder.build()
    }
}
```

`client/test@android/GhostBeInterceptorTest.kt` needs no changes — it exercises `GhostBeInterceptor` through its public OkHttp-facing behavior, which is unchanged.

- [ ] **Step 6: Run the full client test suite**

Run: `./kotlin test -m client`
Expected: PASS — all of `GhostBeResolverTest`, `GhostBeInterceptorTest`, and the existing `EnvelopeTest`.

- [ ] **Step 7: Rebuild the AAR and republish to local Maven, confirm demo-app still resolves it**

```bash
./kotlin build -m client -v release
./kotlin publish mavenLocal -m client
./kotlin build -m demo-app -v debug
```
Expected: all three succeed (this is the same demo-app verification loop already established earlier in this project).

- [ ] **Step 8: Commit**

```bash
git add client/
git commit -m "refactor: extract GhostBeResolver, move Android glue to client/src@android"
```

---

### Task 3: Add iOS platforms, implement the `NSURLSession`-based relay, stand up `ios-check.yml`

This is the first task whose deliverable can only be validated on `macos-latest` CI (per Global Constraints). Uses whichever framework-export mechanism Task 1 confirmed.

**Files:**
- Modify: `client/module.yaml` (add `iosArm64`, `iosSimulatorArm64` to `platforms:`, plus whatever `settings:` Task 1 found)
- Create: `client/src@ios/GhostBeRelay.kt`
- Create: `.github/workflows/ios-check.yml`

**Interfaces:**
- Consumes: `expect fun relayToGhostBe(url: String, requestBodyJson: String): String` from Task 2.
- Produces: a working `actual` for iOS; `.github/workflows/ios-check.yml`, extended by every later task in this plan.

- [ ] **Step 1: Add the iOS platforms to `client/module.yaml`**

```yaml
product:
  type: kmp/lib
  platforms: [ android, iosArm64, iosSimulatorArm64 ]
```

(plus any `settings:` block Task 1 determined necessary for framework export)

- [ ] **Step 2: Write the `NSURLSession`-based relay actual**

```kotlin
// client/src@ios/GhostBeRelay.kt
package dev.yellowbytes.ghostbe.client

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.concurrent.AtomicReference
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.DISPATCH_TIME_FOREVER

@OptIn(ExperimentalForeignApi::class)
actual fun relayToGhostBe(url: String, requestBodyJson: String): String {
    val request = NSMutableURLRequest(NSURL(string = url))
    request.setHTTPMethod("POST")
    request.setValue("application/json", forHTTPHeaderField = "Content-Type")

    val bodyBytes = requestBodyJson.encodeToByteArray()
    val bodyData = bodyBytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bodyBytes.size.toULong())
    }
    request.setHTTPBody(bodyData)

    val resultRef = AtomicReference<String?>(null)
    val semaphore = dispatch_semaphore_create(0)

    val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, _, _ ->
        resultRef.value = data?.let { nsDataToString(it) } ?: "{\"intercept\":false}"
        dispatch_semaphore_signal(semaphore)
    }
    task.resume()
    dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)

    return resultRef.value ?: "{\"intercept\":false}"
}

@OptIn(ExperimentalForeignApi::class)
private fun nsDataToString(data: NSData): String {
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
    return bytes.decodeToString()
}
```

This is a best-effort first draft against Kotlin/Native's Foundation interop — the exact `NSData`/`NSMutableURLRequest` construction API (named-parameter constructors, `create(bytes:length:)` vs a different overload name) is exactly the kind of thing that has needed correction on the first CI attempt for every other Apple-interop file written in this project so far. Treat Step 4 (below) as the real verification, not this listing.

- [ ] **Step 3: Write `ios-check.yml`**

```yaml
# .github/workflows/ios-check.yml
name: iOS Check

on:
  workflow_dispatch:

jobs:
  build-and-test:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build client for iOS targets
        run: ./kotlin build -m client -v release
      - name: Run client tests (iosSimulatorArm64 runs under the simulator)
        run: ./kotlin test -m client
```

- [ ] **Step 4: Commit, push, trigger (`gh workflow run "iOS Check" --ref master`), watch, and fix compile errors as they appear**

This step is intentionally open-ended in iteration count — fix whatever the actual compiler error says (wrong overload name, wrong import, wrong nullability) and re-trigger, the same loop already used successfully for `server/src@apple/RuleWatcher.kt` earlier in this project (that took two CI round-trips; this file is more complex and may take more). Do not guess more than twice at the same error before re-reading the actual Kotlin/Native Foundation interop declarations (`~/.konan/kotlin-native-prebuilt-*/klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.Foundation`, greppable the same way the macOS `kevent` package was tracked down) rather than continuing to guess blind.

Expected end state: `iOS Check` run is green.

- [ ] **Step 5: No local commit step here beyond what Step 4 already pushed** — this task's "commit" is the sequence of fix-and-push iterations in Step 4 itself; the task is done when CI is green.

---

### Task 4: Add `GhostBeCore` (the Swift-facing object) and build the real `.xcframework`

**Files:**
- Create: `client/src@ios/GhostBeCore.kt`
- Modify: `.github/workflows/ios-check.yml` (add the `.xcframework` build + sanity check)

**Interfaces:**
- Consumes: `GhostBeResolver.resolve(...)` and `Decision` from Task 2.
- Produces: `GhostBeCore` (a Kotlin `object`, exported into the framework as an Objective-C-visible singleton `GhostBeCore.shared` from Swift), and `GhostBeDecision`/`GhostBeDecisionMock`/`GhostBeDecisionPassthrough` (the Objective-C-visible names Kotlin's default export naming produces for `Decision`/`Decision.Mock`/`Decision.Passthrough` — confirm the actual generated names against the framework's generated header in Task 4 Step 3, since Kotlin's ObjC export name-mangling for nested sealed types is another point that has been wrong on first guess for everything else Apple-related in this project).

- [ ] **Step 1: Write `GhostBeCore.kt`**

```kotlin
// client/src@ios/GhostBeCore.kt
package dev.yellowbytes.ghostbe.client

object GhostBeCore {
    private var baseUrl: String = "http://127.0.0.1:8787"

    fun configure(baseUrl: String) {
        this.baseUrl = baseUrl
    }

    fun resolve(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Decision =
        GhostBeResolver.resolve(baseUrl, method, url, headers, body)
}
```

- [ ] **Step 2: Extend `ios-check.yml` to build the `.xcframework`**

Using whatever exact commands Task 1 confirmed, e.g.:

```yaml
      - name: Build per-arch frameworks
        run: ./kotlin build -m client -v release
      - name: Combine into GhostBe.xcframework
        run: |
          xcodebuild -create-xcframework \
            -framework build/tasks/_client_linkIosArm64Release/client.framework \
            -framework build/tasks/_client_linkIosSimulatorArm64Release/client.framework \
            -output GhostBe.xcframework
      - name: Inspect the generated Objective-C header for GhostBeCore/Decision's exported names
        run: cat GhostBe.xcframework/ios-arm64/client.framework/Headers/client.h
      - name: Upload the xcframework as a workflow artifact for inspection
        uses: actions/upload-artifact@v4
        with:
          name: GhostBe.xcframework
          path: GhostBe.xcframework
```

(paths are placeholders for whatever Task 1 Step 4 actually found — update to match)

- [ ] **Step 3: Trigger, watch, download the workflow artifact, read the generated header**

```bash
gh workflow run "iOS Check" -R fcat97/ghost-be --ref master
# after it completes:
gh run download <run-id> -R fcat97/ghost-be -n GhostBe.xcframework -D /tmp/ghostbe-xcframework-check
cat /tmp/ghostbe-xcframework-check/ios-arm64/client.framework/Headers/client.h
```

Read the actual generated Objective-C interface for `GhostBeCore`/`Decision`/`Decision.Mock`/`Decision.Passthrough` — write down the real generated class/method names here for Task 5 to reference, rather than assuming Kotlin's default name-mangling convention (`ClientGhostBeCore`, `ClientDecisionMock`, etc. — the `client` module-name prefix Kotlin's ObjC export adds by default is exactly the kind of detail worth confirming, not assuming).

- [ ] **Step 4: Commit**

```bash
git add client/src@ios/GhostBeCore.kt .github/workflows/ios-check.yml
git commit -m "feat: add GhostBeCore Swift-facing object, build GhostBe.xcframework in CI"
git push origin master
```

---

### Task 5: Swift package — `GhostBeURLProtocol` and the `GhostBe.session(baseURL:)` factory

**Files:**
- Create: `Package.swift` (repo root)
- Create: `ios/GhostBe/Sources/GhostBe/GhostBeURLProtocol.swift`
- Create: `ios/GhostBe/Sources/GhostBe/GhostBe.swift`
- Modify: `.github/workflows/ios-check.yml` (add `swift build`)

**Interfaces:**
- Consumes: `GhostBeCore`/`Decision` exported names confirmed in Task 4 Step 3 (this task's code below uses placeholder guesses at those names — `GhostBeCore.shared`, `DecisionMock`, `DecisionPassthrough` — that must be corrected to match Task 4's findings before this compiles).
- Produces: `GhostBe.session(baseURL:) -> Session` — the public API consumers use.

- [ ] **Step 1: Write `Package.swift`**

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "GhostBe",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "GhostBe", targets: ["GhostBe"])
    ],
    dependencies: [
        .package(url: "https://github.com/Alamofire/Alamofire.git", from: "5.9.0")
    ],
    targets: [
        .binaryTarget(
            name: "GhostBeCoreFramework",
            // Local path for now (this task's CI verification) -- Task 6 replaces
            // this with a versioned release URL + checksum once a real tag exists.
            path: "GhostBe.xcframework"
        ),
        .target(
            name: "GhostBe",
            dependencies: [
                "GhostBeCoreFramework",
                .product(name: "Alamofire", package: "Alamofire")
            ],
            path: "ios/GhostBe/Sources/GhostBe"
        )
    ]
)
```

- [ ] **Step 2: Write `GhostBeURLProtocol.swift`**

```swift
// ios/GhostBe/Sources/GhostBe/GhostBeURLProtocol.swift
import Foundation
import GhostBeCoreFramework

final class GhostBeURLProtocol: URLProtocol {
    private var task: URLSessionDataTask?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let client = client, let url = request.url else { return }

        var headers: [String: String] = [:]
        request.allHTTPHeaderFields?.forEach { headers[$0.key] = $0.value }

        let decision = GhostBeCore.shared.resolve(
            method: request.httpMethod ?? "GET",
            url: url.absoluteString,
            headers: headers,
            body: request.httpBody != nil ? KotlinByteArray.from(data: request.httpBody!) : nil
        )

        if let mock = decision as? DecisionMock {
            let responseHeaders = mock.headers as? [String: String] ?? [:]
            let response = HTTPURLResponse(
                url: url,
                statusCode: Int(mock.status),
                httpVersion: "HTTP/1.1",
                headerFields: responseHeaders
            )!
            let data = mock.body.toData()
            client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client.urlProtocol(self, didLoad: data)
            client.urlProtocolDidFinishLoading(self)
            return
        }

        // Passthrough (DecisionPassthrough, or ghost-be unreachable): URLProtocol has
        // no built-in "proceed to the real network" the way OkHttp's chain.proceed()
        // does, so perform the real request ourselves and relay every callback back
        // through `client`.
        let realSession = URLSession(configuration: .default)
        task = realSession.dataTask(with: request) { data, response, error in
            if let error = error {
                client.urlProtocol(self, didFailWithError: error)
                return
            }
            if let response = response {
                client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            }
            if let data = data {
                client.urlProtocol(self, didLoad: data)
            }
            client.urlProtocolDidFinishLoading(self)
        }
        task?.resume()
    }

    override func stopLoading() {
        task?.cancel()
    }
}
```

`KotlinByteArray.from(data:)` and `.toData()` are placeholder names for whatever Data⇄Kotlin-`ByteArray` bridging Kotlin's Objective-C export actually generates (typically raw `NSData`-compatible or a `KotlinByteArray` wrapper type) — confirm the real generated signature against the header read in Task 4 Step 3 and adjust. Similarly `DecisionMock`/`DecisionPassthrough` are placeholder names for the confirmed export names.

- [ ] **Step 3: Write `GhostBe.swift`**

```swift
// ios/GhostBe/Sources/GhostBe/GhostBe.swift
import Alamofire
import Foundation
import GhostBeCoreFramework

public enum GhostBe {
    public static func session(baseURL: String = "http://127.0.0.1:8787") -> Session {
        GhostBeCore.shared.configure(baseUrl: baseURL)
        let configuration = URLSessionConfiguration.af.default
        configuration.protocolClasses = [GhostBeURLProtocol.self] + (configuration.protocolClasses ?? [])
        return Session(configuration: configuration)
    }
}
```

- [ ] **Step 4: Extend `ios-check.yml` to build the Swift package**

```yaml
      - name: Build the Swift package
        run: swift build --package-path .
```

(Runs after the `.xcframework` build/combine steps from Task 4, since `Package.swift`'s binary target reads from that local path.)

- [ ] **Step 5: Commit, push, trigger, watch, fix compile errors, repeat until green**

Same iteration discipline as Task 3 Step 4 — this is genuinely new surface area (first time this project's CI has run `swift build` at all) so budget for setup issues beyond just the interop-naming ones already flagged (e.g. confirming `xcodebuild`/`swift` versions preinstalled on `macos-latest` are compatible with `swift-tools-version:5.9`).

---

### Task 6: Swift tests for `GhostBeURLProtocol` against a stub `ghost-be`

**Files:**
- Create: `ios/GhostBe/Tests/GhostBeTests/GhostBeURLProtocolTests.swift`
- Modify: `Package.swift` (add the test target)
- Modify: `.github/workflows/ios-check.yml` (add `swift test`)

**Interfaces:**
- Consumes: `GhostBe.session(baseURL:)` from Task 5.

- [ ] **Step 1: Add the test target to `Package.swift`**

```swift
    targets: [
        // ...(existing targets from Task 5)...
        .testTarget(
            name: "GhostBeTests",
            dependencies: ["GhostBe"],
            path: "ios/GhostBe/Tests/GhostBeTests"
        )
    ]
```

- [ ] **Step 2: Write the failing test**

Launches a real `ghost-be`-shaped stub via a `python3` subprocess (preinstalled on `macos-latest`) serving one canned `/intercept` response, then asserts `GhostBe.session(...)` returns that mocked response instead of hitting anything real:

```swift
// ios/GhostBe/Tests/GhostBeTests/GhostBeURLProtocolTests.swift
import XCTest
import Alamofire
@testable import GhostBe

final class GhostBeURLProtocolTests: XCTestCase {
    private var stubProcess: Process!
    private let stubPort = 18787

    override func setUpWithError() throws {
        let script = """
        import http.server, json, base64

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.dumps({
                    "intercept": True,
                    "status": 201,
                    "headers": {"Content-Type": "application/json"},
                    "body": base64.b64encode(b'{"ok":true}').decode(),
                }).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(body)

        http.server.HTTPServer(("127.0.0.1", \(stubPort)), Handler).serve_forever()
        """
        stubProcess = Process()
        stubProcess.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        stubProcess.arguments = ["-c", script]
        try stubProcess.run()
        Thread.sleep(forTimeInterval: 0.5) // let the stub start listening
    }

    override func tearDownWithError() throws {
        stubProcess.terminate()
    }

    func testReturnsTheMockedResponseInsteadOfCallingTheRealBackend() throws {
        let session = GhostBe.session(baseURL: "http://127.0.0.1:\(stubPort)")
        let expectation = expectation(description: "response received")

        session.request("http://example.invalid/v1/users/42")
            .validate()
            .responseData { response in
                XCTAssertEqual(response.response?.statusCode, 201)
                XCTAssertEqual(response.data, Data(#"{"ok":true}"#.utf8))
                expectation.fulfill()
            }

        wait(for: [expectation], timeout: 5)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `swift test --package-path .` (locally impossible per Global Constraints — via `ios-check.yml`)
Expected: FAIL (compile error, since the test target doesn't exist in `Package.swift` until Step 1's edit lands, or a runtime failure if it does but the interop names from Task 5 are still wrong).

- [ ] **Step 4: Extend `ios-check.yml`**

```yaml
      - name: Run Swift tests
        run: swift test --package-path .
```

- [ ] **Step 5: Trigger, watch, fix, repeat until green**

- [ ] **Step 6: Commit**

```bash
git add Package.swift ios/GhostBe/Tests .github/workflows/ios-check.yml
git commit -m "test: add GhostBeURLProtocol XCTest against a stub ghost-be"
git push origin master
```

---

### Task 7: Wire into the real release workflow, update docs

**Files:**
- Modify: `.github/workflows/release.yml` (add a macOS job building the real `.xcframework`, zipping it, computing its checksum, attaching it to the release)
- Modify: `Package.swift` (swap the local-path `.binaryTarget` for a versioned URL + checksum)
- Modify: `README.md` (document the iOS install path)
- Modify: `AGENTS.md` (document the iOS integration steps, mirroring the existing Android section)

**Interfaces:**
- Consumes: everything from Tasks 1-6, now proven green in `ios-check.yml`.

- [ ] **Step 1: Add the release job**

```yaml
  build-ios-artifact:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run client tests (includes iOS-specific tests)
        run: ./kotlin test -m client
      - name: Build per-arch frameworks
        run: ./kotlin build -m client -v release
      - name: Combine into GhostBe.xcframework
        run: |
          xcodebuild -create-xcframework \
            -framework build/tasks/_client_linkIosArm64Release/client.framework \
            -framework build/tasks/_client_linkIosSimulatorArm64Release/client.framework \
            -output GhostBe.xcframework
      - name: Zip and checksum
        run: |
          zip -r GhostBe.xcframework.zip GhostBe.xcframework
          echo "checksum=$(swift package compute-checksum GhostBe.xcframework.zip)" >> "$GITHUB_OUTPUT"
        id: checksum
      - name: Upload release artifact
        uses: softprops/action-gh-release@v2
        with:
          files: GhostBe.xcframework.zip
    outputs:
      checksum: ${{ steps.checksum.outputs.checksum }}
```

(paths again match whatever Task 1/4 confirmed)

- [ ] **Step 2: Update `Package.swift`'s binary target**

```swift
.binaryTarget(
    name: "GhostBeCoreFramework",
    url: "https://github.com/fcat97/ghost-be/releases/download/<tag>/GhostBe.xcframework.zip",
    checksum: "<checksum from the release workflow output>"
)
```

Since the checksum depends on the release artifact that only exists after a real tag is pushed, this edit happens as a follow-up commit *after* the first real tagged release produces `GhostBe.xcframework.zip` and its checksum — not before. Document this ordering in the PR/commit description so it isn't missed.

- [ ] **Step 3: Update README.md**

Add an "iOS" subsection under "Using it in your app" (mirroring the existing Android AAR instructions), documenting `.package(url: "https://github.com/fcat97/ghost-be", from: "<version>")` and `GhostBe.session(baseURL:)`.

- [ ] **Step 4: Update AGENTS.md**

Add an iOS variant of step 2 ("Integrate the client interceptor into the target app"), mirroring the existing Android instructions but for Alamofire/SPM.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release.yml Package.swift README.md AGENTS.md
git commit -m "feat: wire iOS client into the release workflow, document SPM install"
git push origin master
```

Do not cut a new tag as part of this task — per this project's established pattern, tags are cut only on explicit user request.
