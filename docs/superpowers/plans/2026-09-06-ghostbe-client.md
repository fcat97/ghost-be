# GhostBe Client Interceptor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `client` KMP library: an OkHttp `Interceptor` that relays each outgoing request to a locally-running `ghost-be` server as a JSON envelope, and either returns the mocked response it gets back or lets the real request proceed.

**Architecture:** A single `client` module (`kmp/lib`, Android only). Two files: envelope JSON encode/decode (independent of the server's own envelope models — no shared code, per spec §5), and the `GhostBeInterceptor` itself. All tests run against OkHttp's `MockWebServer` standing in for `ghost-be`, so this module needs no running server to develop or test.

**Tech Stack:** Kotlin Multiplatform (Kotlin Toolchain, `module.yaml`/`project.yaml` — no Gradle), Android target, OkHttp (interceptor API + `MockWebServer` for tests), `kotlinx.serialization` (JSON).

**Spec:** `docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md`

## Global Constraints

- Client targets Android only for v1: `product.type: kmp/lib`, `platforms: [android]` (spec §2, §3).
- No shared DTO/protocol module with the server — this module defines its own envelope models (spec §2, §5).
- Client and server run on the same machine; the interceptor talks to a fixed `baseUrl` (default `http://127.0.0.1:8787`), no discovery (spec §2, §4).
- The interceptor is meant for debug/test build variants only; it does not gate on build type itself — that's the consuming app's responsibility (spec §2, §4).
- If `ghost-be` is unreachable (connection refused/timeout), the interceptor silently calls `chain.proceed(request)` — this must never fail the app's request (spec §8).
- `intercept: false` (no matching rule) also results in `chain.proceed(request)` — same code path as the unreachable case, once the envelope round-trip is done (spec §4).

---

### Task 1: Module scaffold

**Files:**
- Modify: `project.yaml` (add `client` to the module list)
- Create: `client/module.yaml`
- Create: `client/src/GhostBeInterceptor.kt`

**Interfaces:**
- Produces: an `android`-only `kmp/lib` module named `client`.

- [ ] **Step 1: Update `project.yaml`**

```yaml
modules:
  - server
  - client
```

- [ ] **Step 2: Write `client/module.yaml`**

```yaml
product:
  type: kmp/lib
  platforms: [ android ]

dependencies:
  - $libs.okhttp
  - $kotlinx.serialization.json

test-dependencies:
  - $libs.okhttp.mockwebserver

settings:
  android:
    namespace: dev.yellobytes.ghostbe.client
```

- [ ] **Step 3: Write a placeholder `client/src/GhostBeInterceptor.kt`**

```kotlin
package dev.yellobytes.ghostbe.client

class GhostBeInterceptor(private val baseUrl: String = "http://127.0.0.1:8787")
```

- [ ] **Step 4: Build it**

Run: `./kotlin build -m client`
Expected: builds with no errors. If `$libs.okhttp`/`$libs.okhttp.mockwebserver` don't resolve as catalog aliases in this toolchain version, add explicit Maven coordinates (`com.squareup.okhttp3:okhttp:<version>` / `com.squareup.okhttp3:mockwebserver:<version>`) to `client/module.yaml` instead — every later task in this plan depends on both resolving for the `android` target.

- [ ] **Step 5: Commit**

```bash
git add project.yaml client/module.yaml client/src/GhostBeInterceptor.kt
git commit -m "chore: scaffold ghost-be client module"
```

---

### Task 2: Envelope JSON models

**Files:**
- Create: `client/src/Envelope.kt`
- Test: `client/test/EnvelopeTest.kt`

**Interfaces:**
- Produces:
  - `data class RequestEnvelope(val method: String, val url: String, val headers: Map<String, String>, val body: String?)`
  - `sealed interface ResponseEnvelope` with `data class Mock(val intercept: Boolean = true, val status: Int, val headers: Map<String, String>, val body: String) : ResponseEnvelope` and `data class Passthrough(val intercept: Boolean = false) : ResponseEnvelope`
  - `fun RequestEnvelope.toJson(): String`
  - `fun ResponseEnvelope.Companion.fromJson(json: String): ResponseEnvelope` — inspects the decoded `intercept` field to pick `Mock` vs `Passthrough`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.yellobytes.ghostbe.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnvelopeTest {
    @Test
    fun `encodes a request envelope to json`() {
        val envelope = RequestEnvelope(
            method = "GET",
            url = "https://api.example.com/v1/users/42?active=true",
            headers = mapOf("Accept" to "application/json"),
            body = null
        )
        val json = envelope.toJson()
        assert(json.contains("\"method\":\"GET\""))
        assert(json.contains("\"url\":\"https://api.example.com/v1/users/42?active=true\""))
    }

    @Test
    fun `decodes a mock response envelope`() {
        val json = """{"intercept":true,"status":200,"headers":{"Content-Type":"application/json"},"body":"eyJvayI6dHJ1ZX0="}"""
        val envelope = ResponseEnvelope.fromJson(json)
        val mock = assertIs<ResponseEnvelope.Mock>(envelope)
        assertEquals(200, mock.status)
        assertEquals(mapOf("Content-Type" to "application/json"), mock.headers)
        assertEquals("eyJvayI6dHJ1ZX0=", mock.body)
    }

    @Test
    fun `decodes a passthrough response envelope`() {
        val envelope = ResponseEnvelope.fromJson("""{"intercept":false}""")
        assertIs<ResponseEnvelope.Passthrough>(envelope)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m client`
Expected: FAIL — `RequestEnvelope`, `ResponseEnvelope` not defined.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.yellobytes.ghostbe.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.encodeToString

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class RequestEnvelope(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null
)

fun RequestEnvelope.toJson(): String = json.encodeToString(this)

@Serializable
sealed interface ResponseEnvelope {
    @Serializable
    data class Mock(
        val intercept: Boolean = true,
        val status: Int,
        val headers: Map<String, String>,
        val body: String
    ) : ResponseEnvelope

    @Serializable
    data class Passthrough(val intercept: Boolean = false) : ResponseEnvelope

    companion object
}

fun ResponseEnvelope.Companion.fromJson(text: String): ResponseEnvelope {
    val element = json.parseToJsonElement(text).jsonObject
    val intercept = element["intercept"]?.jsonPrimitive?.boolean ?: false
    return if (intercept) {
        json.decodeFromJsonElement(ResponseEnvelope.Mock.serializer(), element)
    } else {
        ResponseEnvelope.Passthrough()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m client`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add client/src/Envelope.kt client/test/EnvelopeTest.kt
git commit -m "feat: add client-side envelope JSON models"
```

---

### Task 3: `GhostBeInterceptor`

**Files:**
- Modify: `client/src/GhostBeInterceptor.kt`

**Interfaces:**
- Consumes: `RequestEnvelope`/`ResponseEnvelope` (Task 2).
- Produces: `class GhostBeInterceptor(baseUrl: String = "http://127.0.0.1:8787") : okhttp3.Interceptor` implementing `intercept(chain: Interceptor.Chain): Response`.

- [ ] **Step 1: Write the implementation**

```kotlin
package dev.yellobytes.ghostbe.client

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

@OptIn(ExperimentalEncodingApi::class)
class GhostBeInterceptor(
    baseUrl: String = "http://127.0.0.1:8787"
) : Interceptor {

    private val interceptUrl = baseUrl.trimEnd('/') + "/intercept"
    private val relayClient = OkHttpClient()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val envelope = buildEnvelope(originalRequest)

        val relayRequest = Request.Builder()
            .url(interceptUrl)
            .post(envelope.toJson().toRequestBody("application/json".toMediaType()))
            .build()

        val responseEnvelope: ResponseEnvelope = try {
            relayClient.newCall(relayRequest).execute().use { relayResponse ->
                ResponseEnvelope.fromJson(relayResponse.body!!.string())
            }
        } catch (e: IOException) {
            // ghost-be is not reachable (not running, wrong port, etc). Treat this
            // exactly like "no rule matched" and fall through to the real endpoint.
            ResponseEnvelope.Passthrough()
        }

        return when (responseEnvelope) {
            is ResponseEnvelope.Passthrough -> chain.proceed(originalRequest)
            is ResponseEnvelope.Mock -> buildResponse(originalRequest, responseEnvelope)
        }
    }

    private fun buildEnvelope(request: Request): RequestEnvelope {
        val headers = request.headers.toMultimap().mapValues { it.value.joinToString(",") }
        val bodyBase64 = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            Base64.encode(buffer.readByteArray())
        }
        return RequestEnvelope(
            method = request.method,
            url = request.url.toString(),
            headers = headers,
            body = bodyBase64
        )
    }

    private fun buildResponse(request: Request, mock: ResponseEnvelope.Mock): Response {
        val contentType = mock.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaType()

        val responseBuilder = Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(mock.status)
            .message(if (mock.status in 200..299) "OK" else "Error")
            .body(Base64.decode(mock.body).toResponseBody(contentType))

        mock.headers.forEach { (key, value) -> responseBuilder.addHeader(key, value) }

        return responseBuilder.build()
    }
}
```

`toResponseBody` is OkHttp 4.x's Kotlin extension function for building a `ResponseBody` from a `ByteArray` (`okhttp3.ResponseBody.Companion.toResponseBody`, imported at the top of the file alongside `toMediaType`/`toRequestBody`) — the pre-4.x `ResponseBody.create(contentType, bytes)` static factory is deprecated in favor of it.

- [ ] **Step 2: Build it**

Run: `./kotlin build -m client`
Expected: builds with no errors (fix the `ResponseBody` API call per the note above if it doesn't match the pinned OkHttp version).

- [ ] **Step 3: Commit**

```bash
git add client/src/GhostBeInterceptor.kt
git commit -m "feat: add GhostBeInterceptor relaying requests to ghost-be"
```

---

### Task 4: Interceptor behavior tests

**Files:**
- Create: `client/test/GhostBeInterceptorTest.kt`

**Interfaces:**
- Consumes: `GhostBeInterceptor` (Task 3).

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.yellobytes.ghostbe.client

import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request

class GhostBeInterceptorTest {
    private val ghostBe = MockWebServer()
    private val realBackend = MockWebServer()

    @AfterTest
    fun tearDown() {
        ghostBe.shutdown()
        realBackend.shutdown()
    }

    private fun clientPointedAt(baseUrl: String) = OkHttpClient.Builder()
        .addInterceptor(GhostBeInterceptor(baseUrl = baseUrl))
        .build()

    @Test
    fun `returns the mocked response when ghost-be intercepts`() {
        ghostBe.start()
        ghostBe.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"intercept":true,"status":201,"headers":{"Content-Type":"application/json"},"body":"eyJvayI6dHJ1ZX0="}""")
                .build()
        )

        val client = clientPointedAt(ghostBe.url("/").toString())
        val request = Request.Builder().url(realBackend.url("/v1/users/42")).build()
        val response = client.newCall(request).execute()

        assertEquals(201, response.code)
        assertEquals("""{"ok":true}""", response.body!!.string())
        assertEquals(0, realBackend.requestCount) // never called the real backend
    }

    @Test
    fun `calls the real backend when ghost-be passes through`() {
        ghostBe.start()
        ghostBe.enqueue(MockResponse.Builder().code(200).body("""{"intercept":false}""").build())
        realBackend.start()
        realBackend.enqueue(MockResponse.Builder().code(200).body("real response").build())

        val client = clientPointedAt(ghostBe.url("/").toString())
        val request = Request.Builder().url(realBackend.url("/v1/users/42")).build()
        val response = client.newCall(request).execute()

        assertEquals("real response", response.body!!.string())
        assertEquals(1, realBackend.requestCount)
    }

    @Test
    fun `calls the real backend when ghost-be is unreachable`() {
        // ghost-be is never started, so its url points at a closed port.
        realBackend.start()
        realBackend.enqueue(MockResponse.Builder().code(200).body("real response").build())

        val client = clientPointedAt("http://127.0.0.1:1") // port 1 refuses connections
        val request = Request.Builder().url(realBackend.url("/v1/users/42")).build()
        val response = client.newCall(request).execute()

        assertEquals("real response", response.body!!.string())
        assertEquals(1, realBackend.requestCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails or passes for the wrong reason**

Run: `./kotlin test -m client`
Expected: if Task 3 was implemented correctly, these should already PASS — this task exists to lock the interceptor's observable behavior in place with its own test cycle, per the plan's task-boundary rule (a reviewer could reject the behavior even if the code compiles). If any test FAILS, fix `GhostBeInterceptor` (not the test) until all three pass — the three cases directly encode the spec's §4/§8 behavior.

- [ ] **Step 3: Run test to verify it passes**

Run: `./kotlin test -m client`
Expected: PASS (all three tests).

- [ ] **Step 4: Commit**

```bash
git add client/test/GhostBeInterceptorTest.kt
git commit -m "test: cover GhostBeInterceptor mock/passthrough/unreachable behavior"
```
