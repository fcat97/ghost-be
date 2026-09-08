package ghostbe.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

class WebStaticRouteTest {
    private val webDist = "test/fixtures/web-static".toPath()

    private fun testApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { routing { webStaticRoute(webDist) } }
        block(createClient { })
    }

    @Test
    fun `serves index html at the root`() = testApp { client ->
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        assertEquals(true, response.bodyAsText().contains("ghost-be backoffice"))
    }

    @Test
    fun `serves a named asset with the wasm content type`() = testApp { client ->
        val response = client.get("/app.wasm")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType("application", "wasm"), response.contentType()?.withoutParameters())
    }

    @Test
    fun `404s an unknown asset`() = testApp { client ->
        val response = client.get("/does-not-exist.js")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `rejects a path escaping webDist`() = testApp { client ->
        val response = client.get("/..%2F..%2Fetc%2Fpasswd")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
