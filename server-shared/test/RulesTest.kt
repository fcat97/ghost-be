package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class RulesTest {
    private val fixture = """
        rules:
          - name: get-user-42
            match:
              method: GET
              path: /v1/users/42
              query: { active: "true" }
              headers: { X-Feature-Flag: "beta" }
            response:
              file: responses/user-42.json
              status: 200
              headers: { Content-Type: application/json }
    """.trimIndent()

    @Test
    fun `parses a rule with static file response`() {
        val ruleFile = loadRuleFile(fixture)
        assertEquals(1, ruleFile.rules.size)
        val rule = ruleFile.rules[0]
        assertEquals("get-user-42", rule.name)
        assertEquals("GET", rule.match.method)
        assertEquals("/v1/users/42", rule.match.path)
        assertEquals(mapOf("active" to "true"), rule.match.query)
        assertEquals(mapOf("X-Feature-Flag" to "beta"), rule.match.headers)
        assertEquals("responses/user-42.json", rule.response.file)
        assertEquals(200, rule.response.status)
    }

    @Test
    fun `parses a rule with script response and pathPattern`() {
        val fixtureWithScript = """
            rules:
              - name: dynamic-user
                match:
                  method: GET
                  pathPattern: "/v1/users/{id}"
                response:
                  script: scripts/dynamic_user.py
                  status: 200
        """.trimIndent()
        val rule = loadRuleFile(fixtureWithScript).rules[0]
        assertEquals("/v1/users/{id}", rule.match.pathPattern)
        assertEquals("scripts/dynamic_user.py", rule.response.script)
    }

    @Test
    fun `defaults enabled to true when the key is absent`() {
        val rule = loadRuleFile(fixture).rules[0]
        assertEquals(true, rule.enabled)
    }

    @Test
    fun `parses an explicit enabled false`() {
        val fixtureDisabled = """
            rules:
              - name: get-user-42
                match: { method: GET, path: /v1/users/42 }
                response: { file: responses/user-42.json, status: 200 }
                enabled: false
        """.trimIndent()
        val rule = loadRuleFile(fixtureDisabled).rules[0]
        assertEquals(false, rule.enabled)
    }

    @Test
    fun `round-trips a parsed rule file back to equivalent yaml`() {
        val original = loadRuleFile(fixture)
        val rendered = renderRuleFile(original)
        val reparsed = loadRuleFile(rendered)
        assertEquals(original, reparsed)
    }

    @Test
    fun `renders only the keys the rule actually set`() {
        // The toggle endpoint rewrites a whole file through renderRuleFile, so anything
        // emitted here lands in the user's YAML. Defaulted keys must stay out.
        val rendered = renderRuleFile(loadRuleFile(fixture))
        assertFalse(rendered.contains("null"), "rendered YAML leaked a defaulted key:\n$rendered")
        assertTrue(rendered.contains("name: \"get-user-42\""))
        assertTrue(rendered.contains("file: \"responses/user-42.json\""))
    }

    @Test
    fun `loads the rule files shipped in the repo unchanged`() {
        // The real regression canary: these are files that exist on disk today, plus the
        // demo rules users copy as a starting point. They must keep loading untouched.
        // Tests run with server-shared/ as the working directory, as the other
        // fixture-reading tests in this module assume.
        val shipped = listOf("test/fixtures/rules", "test/fixtures/rules-api", "../demo-rules")
        for (dir in shipped) {
            val rules = loadRulesFromDirectory(dir.toPath())
            assertTrue(rules.isNotEmpty(), "no rules loaded from $dir")
            assertTrue(rules.all { it.scenario == null }, "$dir should be all-baseline")
        }
    }

    @Test
    fun `defaults scenario to null when the key is absent`() {
        // Backward compatibility: every rule file written before scenarios existed must
        // keep parsing and must keep behaving as baseline.
        assertEquals(null, loadRuleFile(fixture).rules[0].scenario)
    }

    @Test
    fun `parses an explicit scenario tag`() {
        val tagged = """
            rules:
              - name: checkout-declined
                scenario: checkout-fails
                match: { method: POST, path: /v1/checkout }
                response: { file: responses/declined.json, status: 402 }
        """.trimIndent()
        assertEquals("checkout-fails", loadRuleFile(tagged).rules[0].scenario)
    }

    @Test
    fun `round-trips a rule carrying a scenario tag`() {
        val tagged = """
            rules:
              - name: checkout-declined
                scenario: checkout-fails
                match: { method: POST, path: /v1/checkout }
                response: { file: responses/declined.json, status: 402 }
        """.trimIndent()
        val original = loadRuleFile(tagged)
        assertEquals(original, loadRuleFile(renderRuleFile(original)))
        assertTrue(renderRuleFile(original).contains("scenario: \"checkout-fails\""))
    }

    @Test
    fun `still rejects an unknown key`() {
        // Confirms adding fields did not loosen parsing into accepting typos.
        assertFailsWith<IllegalArgumentException> {
            loadRuleFile(
                """
                rules:
                  - name: typo
                    scenarios: checkout-fails
                    match: { method: GET, path: /v1/x }
                    response: { file: r.json, status: 200 }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `throws with a clear message on malformed yaml`() {
        val error = assertFailsWith<IllegalArgumentException> {
            loadRuleFile("rules: [this is not: valid: yaml structure")
        }
        assertTrue(error.message?.isNotBlank() == true)
    }
}
