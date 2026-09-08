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

class RulesApiRouteTest {
    private val rulesDir = "test/fixtures/rules-api".toPath()

    private fun testApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { routing { rulesApiRoute(rulesDir) } }
        block(createClient { })
    }

    @Test
    fun `lists every rule file with a parsed summary`() = testApp { client ->
        val response = client.get("/api/rules")
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"path\":\"existing.yaml\""))
        assertTrue(text.contains("\"name\":\"get-user-42\""))
        assertTrue(text.contains("\"method\":\"GET\""))
        assertTrue(text.contains("\"path\":\"/v1/users/42\""))
        assertTrue(text.contains("\"enabled\":true"))
        assertTrue(text.contains("\"name\":\"checkout-fails\""))
        assertTrue(text.contains("\"enabled\":false"))
        assertTrue(text.contains("rules:")) // the raw content field round-tripped
    }
}
