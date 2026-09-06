@file:OptIn(okhttp3.ExperimentalOkHttpApi::class)

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
