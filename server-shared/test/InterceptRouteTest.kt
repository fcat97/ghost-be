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

    private fun testApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { routing { interceptRoute({ rules }, resolver) } }
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
        application { routing { interceptRoute({ brokenRules }, resolver) } }
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
}
