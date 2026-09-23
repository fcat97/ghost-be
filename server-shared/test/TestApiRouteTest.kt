package ghostbe.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class TestApiRouteTest {
    private val rules = loadRuleFile(
        """
        rules:
          - name: checkout-ok
            match: { method: POST, path: /v1/checkout }
            response: { file: r.json, status: 200 }
          - name: checkout-declined
            scenario: checkout-fails
            match: { method: POST, path: /v1/checkout }
            response: { file: r.json, status: 402 }
          - name: cart-empty
            scenario: empty-cart
            match: { method: GET, path: /v1/cart }
            response: { file: r.json, status: 200 }
        """.trimIndent()
    ).rules

    private fun testApp(
        session: TestSession = TestSession(),
        block: suspend (io.ktor.client.HttpClient) -> Unit
    ) = testApplication {
        application { routing { testApiRoute(session, { rules }) } }
        block(createClient { })
    }

    @Test
    fun `state on a fresh session reports nothing active and the declared scenarios`() = testApp { client ->
        val text = client.get("/api/test/state").bodyAsText()
        assertTrue(text.contains(""""active":[]"""), text)
        assertTrue(text.contains(""""available":["checkout-fails","empty-cart"]"""), text)
    }

    @Test
    fun `putting scenarios replaces the active set rather than merging`() = testApp { client ->
        client.put("/api/test/scenarios") { setBody("""{"active":["checkout-fails","empty-cart"]}""") }
        val replaced = client.put("/api/test/scenarios") { setBody("""{"active":["empty-cart"]}""") }
        assertEquals(HttpStatusCode.OK, replaced.status)
        assertTrue(replaced.bodyAsText().contains(""""active":["empty-cart"]"""))
    }

    @Test
    fun `putting an unknown scenario is rejected and leaves state untouched`() {
        val session = TestSession()
        testApp(session) { client ->
            client.put("/api/test/scenarios") { setBody("""{"active":["checkout-fails"]}""") }
            val response = client.put("/api/test/scenarios") { setBody("""{"active":["chekout-fails"]}""") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            // The error names what IS available, since a typo is the likely cause.
            assertTrue(response.bodyAsText().contains("checkout-fails"))
            // The earlier activation survived: validation happens before mutation.
            assertEquals(setOf("checkout-fails"), session.activeScenarios)
        }
    }

    @Test
    fun `a malformed request body is rejected`() = testApp { client ->
        val response = client.put("/api/test/scenarios") { setBody("not json at all") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `posting a scenario activates it additively`() = testApp { client ->
        client.post("/api/test/scenarios/checkout-fails")
        val response = client.post("/api/test/scenarios/empty-cart")
        assertTrue(response.bodyAsText().contains(""""active":["checkout-fails","empty-cart"]"""))
    }

    @Test
    fun `posting an unknown scenario is a 404`() = testApp { client ->
        assertEquals(HttpStatusCode.NotFound, client.post("/api/test/scenarios/nope").status)
    }

    @Test
    fun `deleting an inactive or unknown scenario still succeeds`() = testApp { client ->
        // Teardown must never fail, including after a rules edit removed the scenario.
        assertEquals(HttpStatusCode.OK, client.delete("/api/test/scenarios/checkout-fails").status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/test/scenarios/never-existed").status)
    }

    @Test
    fun `the journal is queryable over http and the count survives a limit`() {
        val session = TestSession()
        repeat(3) {
            session.record(
                RequestEnvelope("POST", "https://api.example.com/v1/checkout", emptyMap(), null),
                "/v1/checkout", "checkout-ok", 200
            )
        }
        session.record(RequestEnvelope("GET", "https://api.example.com/v1/cart", emptyMap(), null), "/v1/cart", null, null)

        testApp(session) { client ->
            val filtered = client.get("/api/test/journal?path=/v1/checkout").bodyAsText()
            assertTrue(filtered.contains(""""count":3"""), filtered)

            val limited = client.get("/api/test/journal?path=/v1/checkout&limit=1").bodyAsText()
            assertTrue(limited.contains(""""count":3"""), "limit must not change the count: $limited")

            assertEquals("3", client.get("/api/test/journal/count?path=/v1/checkout").bodyAsText())
            assertEquals("4", client.get("/api/test/journal/count").bodyAsText())
            assertEquals("0", client.get("/api/test/journal/count?method=DELETE").bodyAsText())
        }
    }

    @Test
    fun `an unparseable limit is clamped rather than failing the query`() {
        val session = TestSession()
        session.record(RequestEnvelope("GET", "https://api.example.com/v1/cart", emptyMap(), null), "/v1/cart", null, null)
        testApp(session) { client ->
            val response = client.get("/api/test/journal?limit=abc")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(""""count":1"""))
        }
    }

    @Test
    fun `reset clears everything and reports the empty state`() {
        val session = TestSession()
        session.activate("checkout-fails")
        session.nextHit("checkout-ok")
        session.record(RequestEnvelope("GET", "https://api.example.com/v1/cart", emptyMap(), null), "/v1/cart", null, null)

        testApp(session) { client ->
            val response = client.post("/api/test/reset")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains(""""active":[]"""), text)
            assertTrue(text.contains(""""hits":{}"""), text)
            assertTrue(text.contains(""""size":0"""), text)
        }
    }

    @Test
    fun `the control API is reachable with the static catch-all registered`() = testApplication {
        // webStaticRoute installs get("/{path...}"), so a regression in route ordering
        // would silently turn the whole control API into 404s.
        application {
            routing {
                testApiRoute(TestSession(), { rules })
                webStaticRoute("test/fixtures/web-static".toPath())
            }
        }
        val response = createClient { }.get("/api/test/state")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(""""available""""))
    }
}
