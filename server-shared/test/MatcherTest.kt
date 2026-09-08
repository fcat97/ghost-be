package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatcherTest {
    private fun rule(name: String, method: String, path: String? = null, pathPattern: String? = null,
                      query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(),
                      enabled: Boolean = true) =
        Rule(name, MatchSpec(method, path, pathPattern, query, headers), ResponseSpec(status = 200), enabled)

    private fun envelope(method: String, url: String, headers: Map<String, String> = emptyMap()) =
        RequestEnvelope(method, url, headers, null)

    @Test
    fun `matches on exact path`() {
        val rules = listOf(rule("r1", "GET", path = "/v1/users/42"))
        val result = matchRule(envelope("GET", "https://api.example.com/v1/users/42"), rules)
        assertEquals("r1", result?.name)
    }

    @Test
    fun `does not match a different method`() {
        val rules = listOf(rule("r1", "GET", path = "/v1/users/42"))
        assertNull(matchRule(envelope("POST", "https://api.example.com/v1/users/42"), rules))
    }

    @Test
    fun `matches pathPattern with a param segment`() {
        val rules = listOf(rule("r1", "GET", pathPattern = "/v1/users/{id}"))
        val result = matchRule(envelope("GET", "https://api.example.com/v1/users/99"), rules)
        assertEquals("r1", result?.name)
    }

    @Test
    fun `requires only the listed query params to match ignoring extras`() {
        val rules = listOf(rule("r1", "GET", path = "/v1/users/42", query = mapOf("active" to "true")))
        val result = matchRule(
            envelope("GET", "https://api.example.com/v1/users/42?active=true&extra=1"),
            rules
        )
        assertEquals("r1", result?.name)
    }

    @Test
    fun `does not match when a required query param has a different value`() {
        val rules = listOf(rule("r1", "GET", path = "/v1/users/42", query = mapOf("active" to "true")))
        assertNull(matchRule(envelope("GET", "https://api.example.com/v1/users/42?active=false"), rules))
    }

    @Test
    fun `requires only the listed headers to match ignoring extras`() {
        val rules = listOf(rule("r1", "GET", path = "/v1/users/42", headers = mapOf("X-Feature-Flag" to "beta")))
        val result = matchRule(
            envelope("GET", "https://api.example.com/v1/users/42", headers = mapOf("X-Feature-Flag" to "beta", "Accept" to "*/*")),
            rules
        )
        assertEquals("r1", result?.name)
    }

    @Test
    fun `first match wins when multiple rules could match`() {
        val rules = listOf(
            rule("first", "GET", path = "/v1/users/42"),
            rule("second", "GET", path = "/v1/users/42")
        )
        assertEquals("first", matchRule(envelope("GET", "https://api.example.com/v1/users/42"), rules)?.name)
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(matchRule(envelope("GET", "https://api.example.com/v1/other"), emptyList()))
    }

    @Test
    fun `skips a disabled rule even if it would otherwise match`() {
        val rules = listOf(
            rule("disabled-one", "GET", path = "/v1/users/42", enabled = false),
            rule("enabled-one", "GET", path = "/v1/users/42")
        )
        assertEquals("enabled-one", matchRule(envelope("GET", "https://api.example.com/v1/users/42"), rules)?.name)
    }
}
