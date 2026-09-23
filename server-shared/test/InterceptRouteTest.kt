package ghostbe.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path.Companion.toPath

class InterceptRouteTest {
    private val rulesDir = "test/fixtures".toPath()
    private val resolver = ResponseResolver(rulesDir)
    private val rules = loadRuleFile(
        """
        rules:
          - name: get-user-42
            match: { method: GET, path: /v1/users/42 }
            response: { file: responses/user-42.json, status: 200, headers: { Content-Type: application/json } }
        """.trimIndent()
    ).rules

    private fun testApp(
        rules: List<Rule> = this.rules,
        session: TestSession = TestSession(),
        block: suspend (io.ktor.client.HttpClient) -> Unit
    ) = testApplication {
        application { routing { interceptRoute({ rules }, resolver, session) } }
        block(createClient { })
    }

    @Test
    fun `returns a mock response for a matched rule`() = testApp { client ->
        val response = client.post("/intercept") {
            contentType(ContentType.Application.Json)
            setBody("""{"method":"GET","url":"https://api.example.com/v1/users/42","headers":{},"body":null}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"intercept\":true"))
        assertTrue(text.contains("\"status\":200"))
    }

    @Test
    fun `returns passthrough for an unmatched request`() = testApp { client ->
        val response = client.post("/intercept") {
            contentType(ContentType.Application.Json)
            setBody("""{"method":"GET","url":"https://api.example.com/v1/other","headers":{},"body":null}""")
        }
        assertEquals("""{"intercept":false}""", response.bodyAsText())
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `returns a 500 diagnostic when a matched rule fails to resolve`() = testApplication {
        val brokenRules = loadRuleFile(
            """
            rules:
              - name: broken
                match: { method: GET, path: /v1/broken }
                response: { file: responses/does-not-exist.json, status: 200 }
            """.trimIndent()
        ).rules
        application { routing { interceptRoute({ brokenRules }, resolver, TestSession()) } }
        val client = createClient { }
        val response = client.post("/intercept") {
            contentType(ContentType.Application.Json)
            setBody("""{"method":"GET","url":"https://api.example.com/v1/broken","headers":{},"body":null}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"status\":500"))
        val bodyBase64 = Regex(""""body":"([^"]+)"""").find(text)!!.groupValues[1]
        val decodedBody = Base64.decode(bodyBase64).decodeToString()
        assertTrue(decodedBody.contains("broken"))
    }

    private suspend fun io.ktor.client.HttpClient.intercept(method: String, url: String, body: String? = null) =
        post("/intercept") {
            contentType(ContentType.Application.Json)
            val bodyJson = if (body == null) "null" else "\"$body\""
            setBody("""{"method":"$method","url":"$url","headers":{},"body":$bodyJson}""")
        }

    @Test
    fun `a scenario activated mid-run applies to the very next request`() {
        // The core correctness property: no sleep, no polling, and nothing written to disk.
        val session = TestSession()
        val scenarioRules = loadRuleFile(
            """
            rules:
              - name: checkout-ok
                match: { method: POST, path: /v1/checkout }
                response: { file: responses/user-42.json, status: 200 }
              - name: checkout-declined
                scenario: checkout-fails
                match: { method: POST, path: /v1/checkout }
                response: { file: responses/user-42.json, status: 402 }
            """.trimIndent()
        ).rules

        testApp(rules = scenarioRules, session = session) { client ->
            assertTrue(client.intercept("POST", "https://api.example.com/v1/checkout").bodyAsText().contains("\"status\":200"))
            session.setScenarios(setOf("checkout-fails"))
            assertTrue(client.intercept("POST", "https://api.example.com/v1/checkout").bodyAsText().contains("\"status\":402"))
            session.setScenarios(emptySet())
            assertTrue(client.intercept("POST", "https://api.example.com/v1/checkout").bodyAsText().contains("\"status\":200"))
        }
    }

    @Test
    fun `driving scenarios and sequences never writes to the rules directory`() {
        // Guards the "ephemeral" half of the contract: a future refactor that made
        // scenario activation persist through the rules API would break a user's repo,
        // and this is the test that would catch it.
        val rulesFile = "test/fixtures/rules-api/existing.yaml".toPath()
        val before = FileSystem.SYSTEM.read(rulesFile) { readByteArray() }

        val session = TestSession()
        testApp(session = session) { client ->
            session.setScenarios(setOf("anything"))
            client.intercept("GET", "https://api.example.com/v1/users/42")
            session.reset()
        }

        assertTrue(
            before.contentEquals(FileSystem.SYSTEM.read(rulesFile) { readByteArray() }),
            "driving test state rewrote a rule file on disk"
        )
    }

    @Test
    fun `a response sequence advances per call and the last entry sticks`() {
        val session = TestSession()
        val sequenceRules = loadRuleFile(
            """
            rules:
              - name: order-status
                match: { method: GET, path: /v1/order/1 }
                responses:
                  - { file: responses/user-42.json, status: 500 }
                  - { file: responses/user-42.json, status: 503 }
                  - { file: responses/user-42.json, status: 200 }
            """.trimIndent()
        ).rules

        testApp(rules = sequenceRules, session = session) { client ->
            val seen = (0..4).map {
                val text = client.intercept("GET", "https://api.example.com/v1/order/1").bodyAsText()
                Regex(""""status":(\d+)""").find(text)!!.groupValues[1].toInt()
            }
            assertEquals(listOf(500, 503, 200, 200, 200), seen)

            // Reset rewinds the sequence.
            session.reset()
            val text = client.intercept("GET", "https://api.example.com/v1/order/1").bodyAsText()
            assertEquals(500, Regex(""""status":(\d+)""").find(text)!!.groupValues[1].toInt())
        }
    }

    @Test
    fun `each matched call counts exactly one hit`() {
        // Catches an increment leaking into matchRule or resolve, which would double-count.
        val session = TestSession()
        testApp(session = session) { client ->
            client.intercept("GET", "https://api.example.com/v1/users/42")
            client.intercept("GET", "https://api.example.com/v1/users/42")
        }
        assertEquals(2, session.snapshot().hits["get-user-42"])
    }

    @Test
    fun `a passthrough is journalled but counts no hit`() {
        val session = TestSession()
        testApp(session = session) { client ->
            client.intercept("GET", "https://api.example.com/v1/other")
        }
        val entry = session.snapshot().journal.single()
        assertEquals(null, entry.matchedRule)
        assertEquals(null, entry.status)
        assertEquals("/v1/other", entry.path)
        assertTrue(session.snapshot().hits.isEmpty())
    }

    @Test
    fun `a matched call is journalled with its rule name status and decoded body`() {
        val session = TestSession()
        val payload = """{"card":"4242"}"""
        testApp(session = session) { client ->
            client.intercept("GET", "https://api.example.com/v1/users/42", Base64.encode(payload.encodeToByteArray()))
        }
        val entry = session.snapshot().journal.single()
        assertEquals("get-user-42", entry.matchedRule)
        assertEquals(200, entry.status)
        assertEquals("/v1/users/42", entry.path)
        assertEquals(payload, entry.body)
    }

    @Test
    fun `a rule that fails to resolve still advances its counter`() {
        val session = TestSession()
        val brokenRules = loadRuleFile(
            """
            rules:
              - name: broken
                match: { method: GET, path: /v1/broken }
                response: { file: responses/does-not-exist.json, status: 200 }
            """.trimIndent()
        ).rules
        testApp(rules = brokenRules, session = session) { client ->
            client.intercept("GET", "https://api.example.com/v1/broken")
        }
        assertEquals(1, session.snapshot().hits["broken"])
        assertEquals(500, session.snapshot().journal.single().status)
    }

    @Test
    fun `broadcasts a traffic event for a matched rule`() = runBlocking {
        withTimeout(5000) {
            val received = async { TrafficBroadcaster.events.first() }
            yield()
            testApp { client ->
                client.post("/intercept") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"method":"GET","url":"https://api.example.com/v1/users/42","headers":{},"body":null}""")
                }
            }
            val event = received.await()
            assertEquals("GET", event.method)
            assertEquals("get-user-42", event.ruleMatched)
            assertEquals(200, event.status)
        }
    }

    @Test
    fun `broadcasts a traffic event with a null ruleMatched for passthrough`() = runBlocking {
        withTimeout(5000) {
            val received = async { TrafficBroadcaster.events.first() }
            yield()
            testApp { client ->
                client.post("/intercept") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"method":"GET","url":"https://api.example.com/v1/other","headers":{},"body":null}""")
                }
            }
            val event = received.await()
            assertEquals(null, event.ruleMatched)
            assertEquals(null, event.status)
        }
    }
}
