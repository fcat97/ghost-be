package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvelopeTest {
    @Test
    fun `decodes a request envelope from json`() {
        val json = """
            {"method":"GET","url":"https://api.example.com/v1/users/42?active=true","headers":{"Accept":"application/json"},"body":null}
        """.trimIndent()
        val envelope = RequestEnvelope.fromJson(json)
        assertEquals("GET", envelope.method)
        assertEquals("https://api.example.com/v1/users/42?active=true", envelope.url)
        assertEquals(mapOf("Accept" to "application/json"), envelope.headers)
        assertEquals(null, envelope.body)
    }

    @Test
    fun `encodes a mock response envelope to json`() {
        val envelope: ResponseEnvelope = ResponseEnvelope.Mock(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = "eyJvayI6dHJ1ZX0="
        )
        val json = envelope.toJson()
        assertEquals(true, json.contains("\"intercept\":true"))
        assertEquals(true, json.contains("\"status\":200"))
    }

    @Test
    fun `encodes a passthrough envelope to json`() {
        val envelope: ResponseEnvelope = ResponseEnvelope.Passthrough()
        assertEquals("""{"intercept":false}""", envelope.toJson())
    }
}
