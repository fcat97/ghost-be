package ghostbe.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.FileSystem
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

    @Test
    fun `validates well-formed yaml without writing any file`() = testApp { client ->
        val response = client.post("/api/rules/validate") {
            setBody("rules:\n  - name: ok\n    match: { method: GET, path: /v1/x }\n    response: { file: r.json, status: 200 }\n")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `rejects malformed yaml on validate without writing any file`() = testApp { client ->
        val response = client.post("/api/rules/validate") {
            setBody("rules: [this is not: valid: yaml structure")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().isNotBlank())
    }

    @Test
    fun `creates a new rule file`() = testApp { client ->
        val response = client.post("/api/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"path":"new-rule.yaml","content":"rules:\n  - name: new-rule\n    match: { method: GET, path: /v1/new }\n    response: { file: responses/new.json, status: 200 }\n"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(FileSystem.SYSTEM.exists(rulesDir / "new-rule.yaml"))
        FileSystem.SYSTEM.delete(rulesDir / "new-rule.yaml")
    }

    @Test
    fun `rejects creating a rule file that already exists`() = testApp { client ->
        val response = client.post("/api/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"path":"existing.yaml","content":"rules: []"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `overwrites an existing rule file with valid content`() = testApp { client ->
        FileSystem.SYSTEM.write(rulesDir / "editable.yaml") { writeUtf8("rules: []") }
        val response = client.put("/api/rules/editable.yaml") {
            setBody("rules:\n  - name: edited\n    match: { method: GET, path: /v1/edited }\n    response: { file: r.json, status: 200 }\n")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val onDisk = FileSystem.SYSTEM.read(rulesDir / "editable.yaml") { readUtf8() }
        assertTrue(onDisk.contains("edited"))
        FileSystem.SYSTEM.delete(rulesDir / "editable.yaml")
    }

    @Test
    fun `rejects overwriting with malformed yaml and leaves the file untouched`() = testApp { client ->
        FileSystem.SYSTEM.write(rulesDir / "protected.yaml") { writeUtf8("rules: []") }
        val response = client.put("/api/rules/protected.yaml") {
            setBody("rules: [this is not: valid: yaml structure")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val onDisk = FileSystem.SYSTEM.read(rulesDir / "protected.yaml") { readUtf8() }
        assertEquals("rules: []", onDisk)
        FileSystem.SYSTEM.delete(rulesDir / "protected.yaml")
    }

    @Test
    fun `404s when editing a rule file that does not exist`() = testApp { client ->
        val response = client.put("/api/rules/does-not-exist.yaml") { setBody("rules: []") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `rejects a path escaping the rules directory`() = testApp { client ->
        val response = client.put("/api/rules/..%2F..%2Fetc%2Fpasswd") { setBody("rules: []") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `deletes a rule file`() = testApp { client ->
        FileSystem.SYSTEM.write(rulesDir / "deletable.yaml") { writeUtf8("rules: []") }
        val response = client.delete("/api/rules/deletable.yaml")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(!FileSystem.SYSTEM.exists(rulesDir / "deletable.yaml"))
    }

    @Test
    fun `404s when deleting a rule file that does not exist`() = testApp { client ->
        val response = client.delete("/api/rules/does-not-exist.yaml")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `toggles a rule's enabled flag and persists it`() = testApp { client ->
        FileSystem.SYSTEM.write(rulesDir / "toggleable.yaml") {
            writeUtf8("rules:\n  - name: toggle-me\n    match: { method: GET, path: /v1/x }\n    response: { file: r.json, status: 200 }\n")
        }
        val response = client.post("/api/rules/toggleable.yaml/toggle-me/toggle")
        assertEquals(HttpStatusCode.OK, response.status)
        val onDisk = FileSystem.SYSTEM.read(rulesDir / "toggleable.yaml") { readUtf8() }
        assertTrue(loadRuleFile(onDisk).rules.single { it.name == "toggle-me" }.enabled == false)
        FileSystem.SYSTEM.delete(rulesDir / "toggleable.yaml")
    }

    @Test
    fun `404s toggling a rule name that does not exist in the file`() = testApp { client ->
        FileSystem.SYSTEM.write(rulesDir / "toggleable2.yaml") { writeUtf8("rules: []") }
        val response = client.post("/api/rules/toggleable2.yaml/no-such-rule/toggle")
        assertEquals(HttpStatusCode.NotFound, response.status)
        FileSystem.SYSTEM.delete(rulesDir / "toggleable2.yaml")
    }
}
