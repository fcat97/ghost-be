# Web Backoffice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give ghost-be a manual-management web UI (rules CRUD + live traffic feed), served by ghost-be itself, built with Compose Multiplatform targeting `wasm-js`.

**Architecture:** A new Amper module `web-backoffice` (`wasm-js/app`, Compose Multiplatform) is built to static HTML/JS/Wasm and served by `server-shared`'s existing Ktor CIO server via a hand-rolled static-file route (Ktor's built-in `staticFiles` helper requires `java.io.File`, unavailable on Kotlin/Native — confirmed by spike, see Task 9). New server endpoints (`/api/rules*`, `/events/traffic`) sit alongside the existing `/intercept`. The frontend talks to them via the browser's native `fetch`/`EventSource` (via `kotlinx-browser`), not a Kotlin HTTP client library.

**Tech Stack:** Kotlin/Native (Ktor CIO server, `com.charleskorn.kaml` for YAML, `kotlinx.serialization` for JSON, `kotlinx.coroutines` `MutableSharedFlow` for the traffic broadcaster, `io.ktor:ktor-server-sse` for the SSE route) + Kotlin/Wasm (Compose Multiplatform Material3/Foundation, `kotlinx-browser` for `fetch`/`EventSource`), built with this repo's existing Amper toolchain (`./kotlin` wrapper, no Gradle).

**Spec:** `docs/superpowers/specs/2026-09-08-web-backoffice-design.md`

## Global Constraints

- No authentication on any new endpoint or the served UI — matches ghost-be's existing no-cert/no-setup trust model (spec §2).
- Rule edits are raw YAML text end-to-end (`GET`/`PUT`/`POST /api/rules*` bodies are raw file content, never structured JSON forms) — spec §5/§6.
- The traffic feed (`GET /events/traffic`) is live-only: no server-side replay buffer, a client connecting mid-session sees nothing until the next request (spec §2/§5).
- `Rule.enabled` defaults to `true` and must not break parsing of any existing hand-written rule file with no `enabled` key (spec §4).
- All new Kotlin/Native code must compile for `linuxX64`, `mingwX64`, and `macosArm64` (server-shared's existing `platforms:` list) — avoid any JVM-only API (`java.io.*`, etc.).
- Packaging the built `web-backoffice` output into the *released* `ghost-be` binaries (`release.yml`) is explicitly **out of scope for this plan** — see the note at the end. This plan delivers a fully working dev-time flow (`./kotlin run -m server-linux -- --web-dist <path>`); shipping it in tagged releases is a follow-up.

---

## Task 1: `Rule.enabled` field + `Matcher.kt` filtering

**Files:**
- Modify: `server-shared/src/Rules.kt:25-30` (the `Rule` data class)
- Modify: `server-shared/src/Matcher.kt:24-43` (`matchRule`)
- Modify: `server-shared/test/RulesTest.kt` (parsing test)
- Modify: `server-shared/test/MatcherTest.kt` (matching test)

**Interfaces:**
- Produces: `Rule.enabled: Boolean` (default `true`) — every later task that reads/writes a `Rule` relies on this field existing.
- Produces: `matchRule(envelope: RequestEnvelope, rules: List<Rule>): Rule?` now skips disabled rules internally (signature unchanged).

- [ ] **Step 1: Write the failing tests**

Add to `server-shared/test/RulesTest.kt`:

```kotlin
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
```

Add to `server-shared/test/MatcherTest.kt` (the `rule(...)` test helper needs an `enabled` param first):

```kotlin
    private fun rule(name: String, method: String, path: String? = null, pathPattern: String? = null,
                      query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(),
                      enabled: Boolean = true) =
        Rule(name, MatchSpec(method, path, pathPattern, query, headers), ResponseSpec(status = 200), enabled)
```

and a new test:

```kotlin
    @Test
    fun `skips a disabled rule even if it would otherwise match`() {
        val rules = listOf(
            rule("disabled-one", "GET", path = "/v1/users/42", enabled = false),
            rule("enabled-one", "GET", path = "/v1/users/42")
        )
        assertEquals("enabled-one", matchRule(envelope("GET", "https://api.example.com/v1/users/42"), rules)?.name)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./kotlin test -m server-shared`
Expected: `RulesTest` fails to compile/fails assertions (no `enabled` property yet), `MatcherTest` fails the same way (constructor mismatch / new test fails).

- [ ] **Step 3: Implement**

In `server-shared/src/Rules.kt`, change:

```kotlin
@Serializable
data class Rule(
    val name: String,
    val match: MatchSpec,
    val response: ResponseSpec,
    val enabled: Boolean = true
)
```

In `server-shared/src/Matcher.kt`, change the `matchRule` function to filter first:

```kotlin
fun matchRule(envelope: RequestEnvelope, rules: List<Rule>): Rule? {
    val (path, query) = parseUrl(envelope.url)
    val lowerCaseHeaders = envelope.headers.mapKeys { it.key.lowercase() }

    return rules.filter { it.enabled }.firstOrNull { rule ->
        val match = rule.match
        if (!match.method.equals(envelope.method, ignoreCase = true)) return@firstOrNull false

        val pathOk = when {
            match.path != null -> match.path == path
            match.pathPattern != null -> pathMatchesPattern(match.pathPattern, path)
            else -> false
        }
        if (!pathOk) return@firstOrNull false

        val queryOk = match.query.all { (k, v) -> query[k] == v }
        if (!queryOk) return@firstOrNull false

        match.headers.all { (k, v) -> lowerCaseHeaders[k.lowercase()] == v }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./kotlin test -m server-shared`
Expected: PASS (all `RulesTest` and `MatcherTest` cases)

- [ ] **Step 5: Commit**

```bash
git add server-shared/src/Rules.kt server-shared/src/Matcher.kt server-shared/test/RulesTest.kt server-shared/test/MatcherTest.kt
git commit -m "feat: add Rule.enabled, skip disabled rules when matching"
```

---

## Task 2: `renderRuleFile` — YAML encoder (round-trips with `loadRuleFile`)

**Files:**
- Modify: `server-shared/src/Rules.kt` (add function after `loadRuleFile`)
- Modify: `server-shared/test/RulesTest.kt` (round-trip test)

**Interfaces:**
- Consumes: `Rule`, `RuleFile` from Task 1.
- Produces: `renderRuleFile(ruleFile: RuleFile): String` — Task 7 (toggle endpoint) rewrites a rule file with this.

- [ ] **Step 1: Write the failing test**

Add to `server-shared/test/RulesTest.kt`:

```kotlin
    @Test
    fun `round-trips a parsed rule file back to equivalent yaml`() {
        val original = loadRuleFile(fixture)
        val rendered = renderRuleFile(original)
        val reparsed = loadRuleFile(rendered)
        assertEquals(original, reparsed)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m server-shared`
Expected: FAIL with "unresolved reference: renderRuleFile"

- [ ] **Step 3: Implement**

In `server-shared/src/Rules.kt`, add after `loadRuleFile`:

```kotlin
fun renderRuleFile(ruleFile: RuleFile): String {
    return Yaml.default.encodeToString(RuleFile.serializer(), ruleFile)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m server-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server-shared/src/Rules.kt server-shared/test/RulesTest.kt
git commit -m "feat: add renderRuleFile, the encode-side counterpart of loadRuleFile"
```

---

## Task 3: `TrafficEvent` + `TrafficBroadcaster`

**Files:**
- Create: `server-shared/src/TrafficBroadcaster.kt`
- Create: `server-shared/test/TrafficBroadcasterTest.kt`
- Modify: `server-shared/module.yaml` (add `kotlinx-coroutines-core` dependency)

**Interfaces:**
- Produces: `data class TrafficEvent(val method: String, val url: String, val ruleMatched: String?, val status: Int?)`
- Produces: `object TrafficBroadcaster { val events: SharedFlow<TrafficEvent>; fun publish(event: TrafficEvent) }` — Task 4 (`InterceptRoute`) calls `publish`, Task 8 (`TrafficSseRoute`) collects `events`.

- [ ] **Step 1: Add the dependency**

In `server-shared/module.yaml`, add to `dependencies:`:

```yaml
  - org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
```

- [ ] **Step 2: Write the failing test**

Create `server-shared/test/TrafficBroadcasterTest.kt`:

```kotlin
package ghostbe.server

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class TrafficBroadcasterTest {
    @Test
    fun `delivers a published event to a subscriber`() = runBlocking {
        val received = async { TrafficBroadcaster.events.first() }
        yield() // let the async block above reach first()'s subscription point before we publish
        TrafficBroadcaster.publish(TrafficEvent("GET", "https://api.example.com/v1/users/42", "get-user-42", 200))
        val event = received.await()
        assertEquals("get-user-42", event.ruleMatched)
        assertEquals(200, event.status)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./kotlin test -m server-shared`
Expected: FAIL with "unresolved reference: TrafficEvent" / "TrafficBroadcaster"

- [ ] **Step 4: Implement**

Create `server-shared/src/TrafficBroadcaster.kt`:

```kotlin
package ghostbe.server

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

@Serializable
data class TrafficEvent(
    val method: String,
    val url: String,
    val ruleMatched: String?,
    val status: Int?
)

object TrafficBroadcaster {
    private val _events = MutableSharedFlow<TrafficEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TrafficEvent> = _events

    fun publish(event: TrafficEvent) {
        _events.tryEmit(event)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./kotlin test -m server-shared`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server-shared/src/TrafficBroadcaster.kt server-shared/test/TrafficBroadcasterTest.kt server-shared/module.yaml
git commit -m "feat: add TrafficEvent + TrafficBroadcaster for the live traffic feed"
```

---

## Task 4: `InterceptRoute.kt` broadcasts traffic events

**Files:**
- Modify: `server-shared/src/InterceptRoute.kt`
- Modify: `server-shared/test/InterceptRouteTest.kt`

**Interfaces:**
- Consumes: `TrafficBroadcaster.publish(TrafficEvent)` from Task 3.

- [ ] **Step 1: Write the failing tests**

Add to `server-shared/test/InterceptRouteTest.kt` (needs new imports: `kotlinx.coroutines.async`, `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.runBlocking`, `kotlinx.coroutines.yield`):

```kotlin
    @Test
    fun `broadcasts a traffic event for a matched rule`() = runBlocking {
        val received = async { TrafficBroadcaster.events.first() }
        yield()
        testApp { client ->
            client.post("/intercept") {
                contentType(ContentType.Application.Json)
                setBody("""{"method":"GET","url":"https://api.example.com/v1/users/42","headers":{},"body":null}""")
            }
        }
        val event = received.await()
        assertEquals("GET", event.method)
        assertEquals("get-user-42", event.ruleMatched)
        assertEquals(200, event.status)
    }

    @Test
    fun `broadcasts a traffic event with a null ruleMatched for passthrough`() = runBlocking {
        val received = async { TrafficBroadcaster.events.first() }
        yield()
        testApp { client ->
            client.post("/intercept") {
                contentType(ContentType.Application.Json)
                setBody("""{"method":"GET","url":"https://api.example.com/v1/other","headers":{},"body":null}""")
            }
        }
        val event = received.await()
        assertEquals(null, event.ruleMatched)
        assertEquals(null, event.status)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./kotlin test -m server-shared`
Expected: FAIL (timeout waiting on `received.await()` — no event is ever published yet)

- [ ] **Step 3: Implement**

In `server-shared/src/InterceptRoute.kt`, publish before each response:

```kotlin
fun Route.interceptRoute(currentRules: () -> List<Rule>, resolver: ResponseResolver) {
    post("/intercept") {
        val body = call.receiveText()
        val envelope = RequestEnvelope.fromJson(body)
        val requestLabel = "${envelope.method} ${envelope.url}"
        val rule = matchRule(envelope, currentRules())

        if (rule == null) {
            println("ghost-be: $requestLabel -> passthrough")
            TrafficBroadcaster.publish(TrafficEvent(envelope.method, envelope.url, ruleMatched = null, status = null))
            call.respondText(ResponseEnvelope.Passthrough().toJson(), ContentType.Application.Json)
            return@post
        }

        val resolved = resolver.resolve(rule, envelope)
        val responseEnvelope: ResponseEnvelope = when (resolved) {
            is ResolvedResponse.Success -> {
                println("ghost-be: $requestLabel -> intercept (rule '${rule.name}') -> ${resolved.status}")
                ResponseEnvelope.Mock(
                    status = resolved.status,
                    headers = resolved.headers,
                    body = Base64.encode(resolved.bodyBytes)
                )
            }
            is ResolvedResponse.Failure -> {
                println("ghost-be: $requestLabel -> intercept (rule '${resolved.ruleName}' failed: ${resolved.message}) -> 500")
                ResponseEnvelope.Mock(
                    status = 500,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = Base64.encode(
                        """{"error":"ghost-be rule '${resolved.ruleName}' failed: ${resolved.message}"}""".encodeToByteArray()
                    )
                )
            }
        }
        val statusForEvent = (responseEnvelope as? ResponseEnvelope.Mock)?.status
        TrafficBroadcaster.publish(TrafficEvent(envelope.method, envelope.url, ruleMatched = rule.name, status = statusForEvent))
        call.respondText(responseEnvelope.toJson(), ContentType.Application.Json)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./kotlin test -m server-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server-shared/src/InterceptRoute.kt server-shared/test/InterceptRouteTest.kt
git commit -m "feat: broadcast a TrafficEvent for every /intercept call"
```

---

## Task 5: `GET /api/rules` (list + summarize)

**Files:**
- Create: `server-shared/src/RulesApiRoute.kt`
- Create: `server-shared/test/RulesApiRouteTest.kt`
- Create fixtures under: `server-shared/test/fixtures/rules-api/` (see Step 1)

**Interfaces:**
- Consumes: `loadRuleFile`, `Rule`, `RuleFile` (Task 1/2).
- Produces: `fun Route.rulesApiRoute(rulesDir: Path, fileSystem: FileSystem = FileSystem.SYSTEM)` — Task 6/7 add more routes to this same function; Task 9 registers it in `Main.kt`.
- Produces (public data classes, reused by Task 6/7 and read by the frontend in Task 11): `RuleSummary(name, method, path, enabled)`, `RuleFileSummary(path, content, rules: List<RuleSummary>)`.

- [ ] **Step 1: Write the failing test**

Create the fixture directory used by this whole file's tests — `server-shared/test/fixtures/rules-api/existing.yaml`:

```yaml
rules:
  - name: get-user-42
    match: { method: GET, path: /v1/users/42 }
    response: { file: responses/user-42.json, status: 200 }
  - name: checkout-fails
    match: { method: POST, path: /v1/checkout }
    response: { file: responses/checkout-500.json, status: 500 }
    enabled: false
```

Create `server-shared/test/RulesApiRouteTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m server-shared`
Expected: FAIL with "unresolved reference: rulesApiRoute"

- [ ] **Step 3: Implement**

Create `server-shared/src/RulesApiRoute.kt`:

```kotlin
package ghostbe.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

@Serializable
data class RuleSummary(val name: String, val method: String, val path: String, val enabled: Boolean)

@Serializable
data class RuleFileSummary(val path: String, val content: String, val rules: List<RuleSummary>)

private val json = Json { encodeDefaults = true }

private fun summarize(fileName: String, content: String): RuleFileSummary {
    val ruleFile = loadRuleFile(content)
    val summaries = ruleFile.rules.map { rule ->
        RuleSummary(
            name = rule.name,
            method = rule.match.method,
            path = rule.match.path ?: rule.match.pathPattern ?: "",
            enabled = rule.enabled
        )
    }
    return RuleFileSummary(fileName, content, summaries)
}

fun Route.rulesApiRoute(rulesDir: Path, fileSystem: FileSystem = FileSystem.SYSTEM) {
    get("/api/rules") {
        val files = fileSystem.list(rulesDir)
            .filter { it.name.endsWith(".yaml") || it.name.endsWith(".yml") }
            .sortedBy { it.name }
        val summaries = files.map { path ->
            val content = fileSystem.read(path) { readUtf8() }
            summarize(path.name, content)
        }
        call.respondText(json.encodeToString(summaries), ContentType.Application.Json)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m server-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server-shared/src/RulesApiRoute.kt server-shared/test/RulesApiRouteTest.kt server-shared/test/fixtures/rules-api/
git commit -m "feat: add GET /api/rules (list rule files with a parsed summary)"
```

---

## Task 6: `PUT /api/rules/{path}`, `POST /api/rules`, `DELETE /api/rules/{path}`

**Files:**
- Modify: `server-shared/src/RulesApiRoute.kt`
- Modify: `server-shared/test/RulesApiRouteTest.kt`

**Interfaces:**
- Produces (added to the same `rulesApiRoute` function from Task 5): the three new routes below. Also produces `resolveRuleFilePath(rulesDir, requestedPath): Path?` (private helper), and `@Serializable data class CreateRuleFileRequest(val path: String, val content: String)` — the wire shape Task 11's frontend `POST /api/rules` call must send.

Each test in this task creates its own file(s) under `rulesDir` and deletes them in the test body (matching this codebase's existing filesystem-test convention — see `server-shared/test@linux/RuleWatcherTest.kt`), so tests don't interfere with each other or with Task 5's fixture.

- [ ] **Step 1: Write the failing tests**

Add to `server-shared/test/RulesApiRouteTest.kt` (needs `import okio.FileSystem`):

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./kotlin test -m server-shared`
Expected: FAIL (no `put`/`post`/`delete` handlers registered yet — 404/Method Not Allowed on all of them)

- [ ] **Step 3: Implement**

In `server-shared/src/RulesApiRoute.kt`, add imports `io.ktor.server.request.*` and `kotlinx.serialization.decodeFromString`, then extend the file:

```kotlin
@Serializable
data class CreateRuleFileRequest(val path: String, val content: String)

// Guards against a path escaping the rules directory (e.g. "../secrets.yaml")
// or an absolute path -- the only paths this route should ever touch are
// plain filenames inside rulesDir.
private fun resolveRuleFilePath(rulesDir: Path, requestedPath: String): Path? {
    if (requestedPath.isBlank() || requestedPath.contains("..") || requestedPath.startsWith("/")) return null
    return rulesDir / requestedPath
}
```

and add these three route blocks inside `rulesApiRoute`'s body, after the existing `get("/api/rules")`:

```kotlin
    put("/api/rules/{path}") {
        val requestedPath = call.parameters["path"]!!
        val resolved = resolveRuleFilePath(rulesDir, requestedPath)
        if (resolved == null) {
            call.respondText("invalid path", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@put
        }
        if (!fileSystem.exists(resolved)) {
            call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@put
        }
        val content = call.receiveText()
        try {
            loadRuleFile(content)
        } catch (e: IllegalArgumentException) {
            call.respondText(e.message ?: "invalid rule YAML", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@put
        }
        fileSystem.write(resolved) { writeUtf8(content) }
        call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
    }

    post("/api/rules") {
        val request = json.decodeFromString<CreateRuleFileRequest>(call.receiveText())
        val resolved = resolveRuleFilePath(rulesDir, request.path)
        if (resolved == null) {
            call.respondText("invalid path", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@post
        }
        if (fileSystem.exists(resolved)) {
            call.respondText("already exists", ContentType.Text.Plain, HttpStatusCode.Conflict)
            return@post
        }
        try {
            loadRuleFile(request.content)
        } catch (e: IllegalArgumentException) {
            call.respondText(e.message ?: "invalid rule YAML", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@post
        }
        fileSystem.write(resolved) { writeUtf8(request.content) }
        call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.Created)
    }

    delete("/api/rules/{path}") {
        val requestedPath = call.parameters["path"]!!
        val resolved = resolveRuleFilePath(rulesDir, requestedPath)
        if (resolved == null || !fileSystem.exists(resolved)) {
            call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@delete
        }
        fileSystem.delete(resolved)
        call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./kotlin test -m server-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server-shared/src/RulesApiRoute.kt server-shared/test/RulesApiRouteTest.kt
git commit -m "feat: add rule file create/edit/delete endpoints"
```

---

## Task 7: `POST /api/rules/{path}/{ruleName}/toggle`

**Files:**
- Modify: `server-shared/src/RulesApiRoute.kt`
- Modify: `server-shared/test/RulesApiRouteTest.kt`

**Interfaces:**
- Consumes: `renderRuleFile` (Task 2), `resolveRuleFilePath` (Task 6).
- Produces: the toggle route, added to the same `rulesApiRoute` function.

- [ ] **Step 1: Write the failing tests**

Add to `server-shared/test/RulesApiRouteTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./kotlin test -m server-shared`
Expected: FAIL (no route matches `POST /api/rules/{path}/{ruleName}/toggle`)

- [ ] **Step 3: Implement**

Add inside `rulesApiRoute`, after the `delete(...)` block from Task 6:

```kotlin
    post("/api/rules/{path}/{ruleName}/toggle") {
        val requestedPath = call.parameters["path"]!!
        val ruleName = call.parameters["ruleName"]!!
        val resolved = resolveRuleFilePath(rulesDir, requestedPath)
        if (resolved == null || !fileSystem.exists(resolved)) {
            call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@post
        }
        val content = fileSystem.read(resolved) { readUtf8() }
        val ruleFile = loadRuleFile(content)
        if (ruleFile.rules.none { it.name == ruleName }) {
            call.respondText("rule not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@post
        }
        val toggled = ruleFile.rules.map { rule ->
            if (rule.name == ruleName) rule.copy(enabled = !rule.enabled) else rule
        }
        fileSystem.write(resolved) { writeUtf8(renderRuleFile(RuleFile(toggled))) }
        call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./kotlin test -m server-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server-shared/src/RulesApiRoute.kt server-shared/test/RulesApiRouteTest.kt
git commit -m "feat: add rule enable/disable toggle endpoint"
```

---

## Task 8: `GET /events/traffic` (SSE)

**Files:**
- Create: `server-shared/src/TrafficSseRoute.kt`
- Create: `server-shared/test/TrafficSseRouteTest.kt`
- Modify: `server-shared/module.yaml` (add `$ktor.server.sse`)

**Interfaces:**
- Consumes: `TrafficBroadcaster.events` (Task 3).
- Produces: `fun Route.trafficSseRoute()` — registered in `Main.kt` in Task 9. Application-level requirement: `install(SSE)` must be called once on the `Application` before this route is reachable (wired in Task 9).

- [ ] **Step 1: Add the dependency**

In `server-shared/module.yaml`, add to `dependencies:`:

```yaml
  - $ktor.server.sse
```

- [ ] **Step 2: Write the failing test**

Create `server-shared/test/TrafficSseRouteTest.kt`:

```kotlin
package ghostbe.server

import io.ktor.client.plugins.sse.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sse.SSE
import io.ktor.server.testing.*
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

class TrafficSseRouteTest {
    @Test
    fun `streams a published traffic event as an SSE message`() = testApplication {
        application {
            install(SSE)
            routing { trafficSseRoute() }
        }
        val client = createClient { install(io.ktor.client.plugins.sse.SSE) }
        client.sse("/events/traffic") {
            TrafficBroadcaster.publish(TrafficEvent("GET", "https://api.example.com/v1/users/42", "get-user-42", 200))
            val event = incoming.first()
            assertEquals(true, event.data?.contains("get-user-42"))
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./kotlin test -m server-shared`
Expected: FAIL with "unresolved reference: trafficSseRoute" (and possibly a missing `ktor-client-sse` test dependency — if so, add `- $ktor.client.core` and `- $ktor.client.sse` to `test-dependencies:` in `server-shared/module.yaml` alongside the existing `$ktor.server.testHost`)

- [ ] **Step 4: Implement**

Create `server-shared/src/TrafficSseRoute.kt`:

```kotlin
package ghostbe.server

import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

fun Route.trafficSseRoute() {
    sse("/events/traffic") {
        TrafficBroadcaster.events.collect { event ->
            send(data = json.encodeToString(event))
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./kotlin test -m server-shared`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server-shared/src/TrafficSseRoute.kt server-shared/test/TrafficSseRouteTest.kt server-shared/module.yaml
git commit -m "feat: add GET /events/traffic SSE route"
```

---

## Task 9: Static file serving + `Main.kt` wiring

**Files:**
- Create: `server-shared/src/WebStaticRoute.kt`
- Create: `server-shared/test/WebStaticRouteTest.kt`
- Create fixtures: `server-shared/test/fixtures/web-static/index.html`, `server-shared/test/fixtures/web-static/app.wasm`
- Modify: `server-shared/src/Main.kt`

**Interfaces:**
- Produces: `fun Route.webStaticRoute(webDist: Path, fileSystem: FileSystem = FileSystem.SYSTEM)`.
- Consumes: `rulesApiRoute` (Task 5-7), `trafficSseRoute` (Task 8).

Note on why this route is hand-rolled instead of using Ktor's `staticFiles(...)`: that helper takes a `java.io.File`, which does not exist on Kotlin/Native (`server-shared` targets `linuxX64`/`mingwX64`/`macosArm64`, not the JVM) — confirmed by spiking it during planning; `staticFiles`/`java.io.File` are both unresolved references on these targets.

- [ ] **Step 1: Write the failing test**

Create fixtures:

```bash
mkdir -p server-shared/test/fixtures/web-static
echo '<html><body>ghost-be backoffice</body></html>' > server-shared/test/fixtures/web-static/index.html
echo 'not-real-wasm-bytes' > server-shared/test/fixtures/web-static/app.wasm
```

Create `server-shared/test/WebStaticRouteTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m server-shared`
Expected: FAIL with "unresolved reference: webStaticRoute"

- [ ] **Step 3: Implement**

Create `server-shared/src/WebStaticRoute.kt`:

```kotlin
package ghostbe.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import okio.FileSystem
import okio.Path

private fun contentTypeFor(fileName: String): ContentType = when {
    fileName.endsWith(".html") -> ContentType.Text.Html
    fileName.endsWith(".js") || fileName.endsWith(".mjs") -> ContentType("application", "javascript")
    fileName.endsWith(".wasm") -> ContentType("application", "wasm")
    fileName.endsWith(".css") -> ContentType.Text.CSS
    else -> ContentType.Application.OctetStream
}

fun Route.webStaticRoute(webDist: Path, fileSystem: FileSystem = FileSystem.SYSTEM) {
    get("/{path...}") {
        val requestedSegments = call.parameters.getAll("path").orEmpty()
        val requestedPath = if (requestedSegments.isEmpty()) "index.html" else requestedSegments.joinToString("/")
        if (requestedPath.contains("..")) {
            call.respondText("invalid path", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@get
        }
        val filePath = webDist / requestedPath
        if (!fileSystem.exists(filePath) || fileSystem.metadata(filePath).isDirectory) {
            call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@get
        }
        val bytes = fileSystem.read(filePath) { readByteArray() }
        call.respondBytes(bytes, contentTypeFor(filePath.name))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m server-shared`
Expected: PASS

- [ ] **Step 5: Wire everything into `Main.kt`**

In `server-shared/src/Main.kt`, add a `--web-dist` flag (default points at the dev build-output path from Task 10/§3 of the spec) and register the three new route groups. Update `USAGE`:

```kotlin
private const val USAGE = """Usage: ghost-be [options]

Options:
  --port <port>     Port to listen on (default: 44678)
  --rules <dir>     Directory of rule .yaml files to watch (default: ./rules)
  --host <addr>     Address to bind (default: 127.0.0.1; use 0.0.0.0 to allow
                    connections from other devices on the LAN)
  --web-dist <dir>  Directory of the built web-backoffice static files
                    (default: web-backoffice/build/tasks/_web-backoffice_buildWasmJsAppWasmJsRelease)
  -h, --help        Show this help and exit

Docs: https://github.com/fcat97/ghost-be#readme"""
```

Update `ParsedArgs.Run` and `parseArgs`:

```kotlin
private sealed interface ParsedArgs {
    data object Help : ParsedArgs
    data class Run(val port: Int, val rulesDir: String, val host: String, val webDist: String) : ParsedArgs
}

private fun parseArgs(args: Array<String>): ParsedArgs {
    var port = 44678
    var rulesDir = "./rules"
    var host = "127.0.0.1"
    var webDist = "web-backoffice/build/tasks/_web-backoffice_buildWasmJsAppWasmJsRelease"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-h", "--help" -> return ParsedArgs.Help
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--rules" -> { rulesDir = args[i + 1]; i += 2 }
            "--host" -> { host = args[i + 1]; i += 2 }
            "--web-dist" -> { webDist = args[i + 1]; i += 2 }
            else -> { i += 1 }
        }
    }
    return ParsedArgs.Run(port, rulesDir, host, webDist)
}
```

Update `main`:

```kotlin
fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    if (parsed is ParsedArgs.Help) {
        println(USAGE)
        return
    }
    val (port, rulesDirArg, host, webDistArg) = parsed as ParsedArgs.Run
    val rulesDir = rulesDirArg.toPath()
    val webDist = webDistArg.toPath()

    println(BANNER)
    val rulesRef = AtomicReference(loadRulesOrExit(rulesDir))
    println("ghost-be: loaded ${rulesRef.value.size} rule(s) from $rulesDir")
    if (!FileSystem.SYSTEM.exists(webDist)) {
        println("ghost-be: web-backoffice UI not found at $webDist -- build it with './kotlin build -m web-backoffice -v release', or point --web-dist at an existing build")
    }
    println("ghost-be: listening on http://$host:$port")

    Worker.start(name = "rule-watcher").execute(TransferMode.SAFE, { rulesDir to rulesRef }) { (dir, ref) ->
        watchRulesDirectory(dir) {
            try {
                val reloaded = loadRulesFromDirectory(dir)
                ref.value = reloaded
                println("ghost-be: reloaded ${reloaded.size} rule(s) from $dir")
            } catch (e: IllegalArgumentException) {
                println("ghost-be: rule reload failed, keeping previous rules")
                println(e.message)
            }
        }
    }

    val resolver = ResponseResolver(rulesDir)
    embeddedServer(CIO, port = port, host = host) {
        install(io.ktor.server.sse.SSE)
        routing {
            interceptRoute({ rulesRef.value }, resolver)
            rulesApiRoute(rulesDir)
            trafficSseRoute()
            webStaticRoute(webDist)
        }
    }.start(wait = true)
}
```

(`install` needs `import io.ktor.server.application.install` added to `Main.kt`'s imports; the `io.ktor.server.sse.SSE` reference above can also be imported at the top as `import io.ktor.server.sse.SSE` and used as plain `install(SSE)` — either is fine, keep it consistent with the rest of the file's import style.)

- [ ] **Step 6: Run the full server-shared suite once more**

Run: `./kotlin test -m server-shared`
Expected: PASS (all tests from Tasks 1-9)

- [ ] **Step 7: Commit**

```bash
git add server-shared/src/WebStaticRoute.kt server-shared/src/Main.kt server-shared/test/WebStaticRouteTest.kt server-shared/test/fixtures/web-static/
git commit -m "feat: serve the web-backoffice static build, wire up all new routes in Main.kt"
```

---

## Task 10: `web-backoffice` module scaffold

**Files:**
- Create: `web-backoffice/module.yaml`
- Create: `web-backoffice/src/Main.kt`
- Modify: `project.yaml` (add `web-backoffice` to `modules:`)

**Interfaces:**
- Produces: a buildable `web-backoffice` module with a placeholder `App()` composable — Tasks 11-13 replace/extend `Main.kt`'s body.

This module has no automated test framework in this repo yet (per spec §9) — this task's acceptance bar is "it builds and renders", checked by building it and (optionally) loading it in a browser, not a `kotlin.test` assertion.

- [ ] **Step 1: Add the module to `project.yaml`**

In `project.yaml`, add `web-backoffice` to `modules:` (alphabetical, matching the existing ordering):

```yaml
modules:
  - client-android
  - demo-app
  - server-linux
  - server-macos
  - server-shared
  - server-windows
  - web-backoffice
```

- [ ] **Step 2: Create the module**

Create `web-backoffice/module.yaml`:

```yaml
product:
  type: wasm-js/app

dependencies:
  - $compose.foundation
  - $compose.material3
  - $compose.ui

settings:
  compose: enabled
```

Create `web-backoffice/src/Main.kt`:

```kotlin
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@Composable
fun App() {
    Text("ghost-be backoffice")
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App() }
}
```

- [ ] **Step 3: Build it**

Run: `./kotlin build -m web-backoffice -v release`
Expected: `✓ Compilation successful for 'web-backoffice' [wasmJs]`, `Build successful`

- [ ] **Step 4: Commit**

```bash
git add project.yaml web-backoffice/
git commit -m "feat: scaffold the web-backoffice Compose Multiplatform module"
```

---

## Task 11: `ApiClient.kt` — talking to the server from the browser

**Files:**
- Create: `web-backoffice/src/ApiClient.kt`
- Modify: `web-backoffice/module.yaml` (add `kotlinx-browser`, `kotlinx-coroutines-core`, enable `kotlin.serialization`)

**Interfaces:**
- Produces: `@Serializable data class RuleSummary(name, method, path, enabled)`, `@Serializable data class RuleFileSummary(path, content, rules: List<RuleSummary>)` — mirrors the server's DTOs from Task 5 (this repo's existing convention is that each client independently declares its own copy of the wire shape rather than sharing a DTO module — see how `RequestEnvelope`/`ResponseEnvelope` are separately declared in `client-android`, `client-ios`, and `client-flutter`).
- Produces: `object ApiClient { suspend fun listRules(): List<RuleFileSummary>; suspend fun saveRule(path: String, content: String); suspend fun createRule(path: String, content: String); suspend fun deleteRule(path: String); suspend fun toggleRule(path: String, ruleName: String) }` — consumed by Task 12/13's UI code.

- [ ] **Step 1: Add dependencies and enable serialization**

In `web-backoffice/module.yaml`:

```yaml
product:
  type: wasm-js/app

dependencies:
  - $compose.foundation
  - $compose.material3
  - $compose.ui
  - org.jetbrains.kotlinx:kotlinx-browser:0.5.0
  - org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0

settings:
  compose: enabled
  kotlin:
    serialization: json
```

- [ ] **Step 2: Implement**

Create `web-backoffice/src/ApiClient.kt`:

```kotlin
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.ExperimentalWasmJsInterop

@Serializable
data class RuleSummary(val name: String, val method: String, val path: String, val enabled: Boolean)

@Serializable
data class RuleFileSummary(val path: String, val content: String, val rules: List<RuleSummary>)

@Serializable
private data class CreateRuleFileRequest(val path: String, val content: String)

private val json = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalWasmJsInterop::class)
private suspend fun request(method: String, url: String, body: String? = null): Response {
    val init = if (body != null) RequestInit(method = method, body = body.toJsString()) else RequestInit(method = method)
    return window.fetch(url, init).await()
}

object ApiClient {
    suspend fun listRules(): List<RuleFileSummary> {
        val response = request("GET", "/api/rules")
        val text = response.text().await<JsString>().toString()
        return json.decodeFromString(text)
    }

    suspend fun saveRule(path: String, content: String) {
        request("PUT", "/api/rules/$path", content)
    }

    suspend fun createRule(path: String, content: String) {
        val body = json.encodeToString(CreateRuleFileRequest(path, content))
        request("POST", "/api/rules", body)
    }

    suspend fun deleteRule(path: String) {
        request("DELETE", "/api/rules/$path")
    }

    suspend fun toggleRule(path: String, ruleName: String) {
        request("POST", "/api/rules/$path/$ruleName/toggle")
    }
}
```

- [ ] **Step 3: Build it**

Run: `./kotlin build -m web-backoffice -v release`
Expected: `Build successful`. If `await<Response>()`/`await<JsString>()`'s type argument is rejected by the compiler as unnecessary or insufficient, adjust based on the exact error — this exact call shape (explicit type argument on `.await<T>()`) was confirmed working against this repo's installed toolchain (Amper 0.12.0) during planning; a different toolchain version may need the type argument removed or a JS-only overload instead.

- [ ] **Step 4: Commit**

```bash
git add web-backoffice/module.yaml web-backoffice/src/ApiClient.kt
git commit -m "feat: add ApiClient for the web-backoffice frontend to talk to /api/rules"
```

---

## Task 12: Rules pane

**Files:**
- Create: `web-backoffice/src/AppState.kt`
- Create: `web-backoffice/src/RulesPane.kt`
- Modify: `web-backoffice/src/Main.kt`

**Interfaces:**
- Consumes: `ApiClient` (Task 11).
- Produces: `class AppState { var rules: List<RuleFileSummary>; var editingPath: String?; var editingContent: String; fun refreshRules() }` (a plain observable-state holder using Compose `mutableStateOf`), `@Composable fun RulesPane(state: AppState)` — Task 13 places this next to `TrafficPane` in `App()`.

- [ ] **Step 1: Implement `AppState`**

Create `web-backoffice/src/AppState.kt`:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AppState(private val scope: CoroutineScope) {
    var rules by mutableStateOf<List<RuleFileSummary>>(emptyList())
        private set

    var editingPath by mutableStateOf<String?>(null)
    var editingContent by mutableStateOf("")
    var editingIsNew by mutableStateOf(false)
    var editingError by mutableStateOf<String?>(null)

    fun refreshRules() {
        scope.launch {
            rules = ApiClient.listRules()
        }
    }

    fun startEditing(path: String, content: String) {
        editingPath = path
        editingContent = content
        editingIsNew = false
        editingError = null
    }

    fun startCreating(prefillContent: String = "") {
        editingPath = ""
        editingContent = prefillContent
        editingIsNew = true
        editingError = null
    }

    fun cancelEditing() {
        editingPath = null
    }

    fun saveEditing() {
        val path = editingPath ?: return
        scope.launch {
            try {
                if (editingIsNew) {
                    ApiClient.createRule(path, editingContent)
                } else {
                    ApiClient.saveRule(path, editingContent)
                }
                editingPath = null
                refreshRules()
            } catch (e: Throwable) {
                editingError = e.message ?: "save failed"
            }
        }
    }

    fun deleteRuleFile(path: String) {
        scope.launch {
            ApiClient.deleteRule(path)
            refreshRules()
        }
    }

    fun toggleRule(path: String, ruleName: String) {
        scope.launch {
            ApiClient.toggleRule(path, ruleName)
            refreshRules()
        }
    }
}
```

- [ ] **Step 2: Implement `RulesPane`**

Create `web-backoffice/src/RulesPane.kt`:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun RulesPane(state: AppState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { state.startCreating() }) { Text("New rule") }

        LazyColumn {
            items(state.rules) { file ->
                items(file.rules) { rule ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { state.toggleRule(file.path, rule.name) }
                        )
                        Text("${rule.method} ${rule.path} (${rule.name})")
                        Button(onClick = { state.startEditing(file.path, file.content) }) { Text("Edit") }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(file.path)
                    Button(onClick = { state.deleteRuleFile(file.path) }) { Text("Delete file") }
                }
            }
        }

        if (state.editingPath != null) {
            if (state.editingIsNew) {
                TextField(
                    value = state.editingPath ?: "",
                    onValueChange = { state.editingPath = it },
                    label = { Text("File name (e.g. checkout.yaml)") }
                )
            }
            TextField(
                value = state.editingContent,
                onValueChange = { state.editingContent = it },
                label = { Text("Rule YAML") }
            )
            state.editingError?.let { Text(it) }
            Row {
                Button(onClick = { state.saveEditing() }) { Text("Save") }
                Button(onClick = { state.cancelEditing() }) { Text("Cancel") }
            }
        }
    }
}
```

- [ ] **Step 3: Wire it into `Main.kt`**

Replace `web-backoffice/src/Main.kt` with:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@Composable
fun App() {
    val state = remember { AppState(CoroutineScope(Dispatchers.Default)) }
    remember { state.refreshRules(); true }
    Column {
        RulesPane(state)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App() }
}
```

- [ ] **Step 4: Build it**

Run: `./kotlin build -m web-backoffice -v release`
Expected: `Build successful`

- [ ] **Step 5: Commit**

```bash
git add web-backoffice/src/AppState.kt web-backoffice/src/RulesPane.kt web-backoffice/src/Main.kt
git commit -m "feat: add the rules pane (list, create, edit, toggle, delete)"
```

---

## Task 13: Traffic pane + SSE client + final wiring

**Files:**
- Create: `web-backoffice/src/TrafficPane.kt`
- Modify: `web-backoffice/src/AppState.kt` (add traffic list + "create rule from this")
- Modify: `web-backoffice/src/Main.kt` (place both panes side by side)
- Modify: `README.md` (mention the web backoffice)

**Interfaces:**
- Consumes: `RuleFileSummary`/`AppState` (Task 12).
- Produces: `@Composable fun TrafficPane(state: AppState)`.

- [ ] **Step 1: Extend `AppState` with the traffic list and SSE subscription**

In `web-backoffice/src/AppState.kt`, add imports `org.w3c.dom.EventSource`, `kotlinx.serialization.decodeFromString`, `kotlinx.serialization.Serializable`, `kotlinx.serialization.json.Json`, `kotlin.js.ExperimentalWasmJsInterop`, and:

```kotlin
@Serializable
data class TrafficEvent(val method: String, val url: String, val ruleMatched: String?, val status: Int?)

private val trafficJson = Json { ignoreUnknownKeys = true }
```

then add to the `AppState` class body:

```kotlin
    var traffic by mutableStateOf<List<TrafficEvent>>(emptyList())
        private set

    @OptIn(ExperimentalWasmJsInterop::class)
    fun connectTrafficFeed() {
        val source = EventSource("/events/traffic")
        source.onmessage = { messageEvent ->
            val event = trafficJson.decodeFromString<TrafficEvent>(messageEvent.data.toString())
            traffic = (traffic + event).takeLast(500)
            null
        }
    }

    fun createRuleFromTraffic(event: TrafficEvent) {
        startCreating(
            prefillContent = "rules:\n  - name: new-rule\n    match: { method: ${event.method}, path: ${pathOf(event.url)} }\n    response: { file: responses/new.json, status: 200 }\n"
        )
    }

    private fun pathOf(url: String): String {
        val withoutScheme = url.substringAfter("://")
        val afterHost = withoutScheme.substringAfter('/', missingDelimiterValue = "")
        return "/" + afterHost.substringBefore('?')
    }
```

- [ ] **Step 2: Implement `TrafficPane`**

Create `web-backoffice/src/TrafficPane.kt`:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TrafficPane(state: AppState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn {
            items(state.traffic) { event ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    val label = if (event.ruleMatched != null) {
                        "${event.method} ${event.url} -> ${event.ruleMatched} (${event.status})"
                    } else {
                        "${event.method} ${event.url} -> passthrough"
                    }
                    Text(label)
                    if (event.ruleMatched != null) {
                        Button(onClick = { state.createRuleFromTraffic(event) }) { Text("Create rule from this") }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire both panes into `Main.kt`**

Replace `web-backoffice/src/Main.kt` with:

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@Composable
fun App() {
    val state = remember { AppState(CoroutineScope(Dispatchers.Default)) }
    remember {
        state.refreshRules()
        state.connectTrafficFeed()
        true
    }
    Row {
        RulesPane(state)
        TrafficPane(state)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App() }
}
```

No further `AppState` changes are needed here beyond Step 1 — `createRuleFromTraffic` already calls `startCreating(prefillContent = ...)` directly.

- [ ] **Step 4: Build it**

Run: `./kotlin build -m web-backoffice -v release`
Expected: `Build successful`

- [ ] **Step 5: Manual verification**

Run the server pointed at the built UI and click through both panes:

```bash
./kotlin run -m server-linux -- --rules ./demo-rules --web-dist web-backoffice/build/tasks/_web-backoffice_buildWasmJsAppWasmJsRelease
```

Open `http://127.0.0.1:44678/` in a browser and confirm:
- The rules pane lists every rule in `./demo-rules` with correct method/path/on-off state.
- Toggling a rule's switch flips it in the underlying `.yaml` file (check on disk) and the UI reflects it after the refresh.
- Editing a rule's YAML and saving persists it; saving deliberately broken YAML shows the server's error message inline instead of silently failing.
- Creating a new rule file works and the new rule appears in the list.
- Sending a request that hits `/intercept` (e.g. via `curl` or the demo app) makes a new row appear in the traffic pane within a second or two, with no page refresh.
- "Create rule from this" on a matched-traffic row opens the create form pre-filled with that request's method/path.

- [ ] **Step 6: Update README**

Add a short section to `README.md` after "Running ghost-be" (see existing section structure) documenting: the UI is served automatically at `http://<host>:<port>/` once `web-backoffice` is built (`./kotlin build -m web-backoffice -v release`), and `--web-dist` lets you point at a different build output.

- [ ] **Step 7: Commit**

```bash
git add web-backoffice/src/TrafficPane.kt web-backoffice/src/AppState.kt web-backoffice/src/Main.kt README.md
git commit -m "feat: add the traffic pane, SSE live feed, and create-rule-from-traffic"
```

---

## Follow-up (explicitly not covered by this plan)

Packaging the built `web-backoffice` output into the *tagged-release* `ghost-be` binaries (currently `.github/workflows/release.yml` uploads each platform binary as a single bare file, e.g. `ghost-be-linux-x64`) needs its own decision — bundling a directory of static assets alongside a single-file binary asset is a release-asset-format change that affects existing install instructions (`AGENTS.md`'s fetch script expects one file per platform). This plan only delivers the dev-time flow (`--web-dist`, defaulting to the local build path). Shipping the web UI in tagged releases should be scoped as a small follow-up spec once this feature has landed and been used for a while.
