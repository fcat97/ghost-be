package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `throws with a clear message on malformed yaml`() {
        val error = assertFailsWith<IllegalArgumentException> {
            loadRuleFile("rules: [this is not: valid: yaml structure")
        }
        assertTrue(error.message?.isNotBlank() == true)
    }
}
