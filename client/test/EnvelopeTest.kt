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
