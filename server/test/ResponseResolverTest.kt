package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import okio.Path.Companion.toPath

class ResponseResolverTest {
    private val rulesDir = "test/fixtures".toPath()
    private val resolver = ResponseResolver(rulesDir)
    private val envelope = RequestEnvelope("GET", "https://api.example.com/v1/users/42", emptyMap(), null)

    @Test
    fun `resolves a static file response`() {
        val rule = Rule(
            name = "get-user-42",
            match = MatchSpec("GET", path = "/v1/users/42"),
            response = ResponseSpec(file = "responses/user-42.json", status = 200, headers = mapOf("Content-Type" to "application/json"))
        )
        val result = resolver.resolve(rule, envelope)
        val success = assertIs<ResolvedResponse.Success>(result)
        assertEquals(200, success.status)
        assertEquals(mapOf("Content-Type" to "application/json"), success.headers)
        assertEquals("""{"id": 42, "name": "Ada Lovelace"}""", success.bodyBytes.decodeToString())
    }

    @Test
    fun `returns a failure when the static file is missing`() {
        val rule = Rule(
            name = "missing-file",
            match = MatchSpec("GET", path = "/v1/missing"),
            response = ResponseSpec(file = "responses/does-not-exist.json", status = 200)
        )
        val result = resolver.resolve(rule, envelope)
        val failure = assertIs<ResolvedResponse.Failure>(result)
        assertEquals("missing-file", failure.ruleName)
    }

    @Test
    fun `resolves a script response using an injected interpreter for testing`() {
        val resolver = ResponseResolver(rulesDir, interpreterFor = { ext -> if (ext == "sh") "sh" else defaultInterpreterFor(ext) })
        val rule = Rule(
            name = "dynamic-user",
            match = MatchSpec("GET", pathPattern = "/v1/users/{id}"),
            response = ResponseSpec(script = "scripts/echo_user.sh", status = 200)
        )
        val result = resolver.resolve(rule, envelope)
        val success = assertIs<ResolvedResponse.Success>(result)
        assertEquals(201, success.status)
        assertEquals("""{"ok":true}""", success.bodyBytes.decodeToString())
    }

    @Test
    fun `returns a failure when the script's extension has no configured interpreter`() {
        val rule = Rule(
            name = "unrecognized-ext",
            match = MatchSpec("GET", path = "/v1/x"),
            response = ResponseSpec(script = "scripts/does-not-matter.rb", status = 200)
        )
        val result = resolver.resolve(rule, envelope)
        val failure = assertIs<ResolvedResponse.Failure>(result)
        assertEquals("unrecognized-ext", failure.ruleName)
    }
}
