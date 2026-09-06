# GhostBe Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `ghost-be` native CLI server: a Ktor/CIO HTTP server on Kotlin/Native (linuxX64) that loads YAML rule files, matches incoming request envelopes against them, and responds with either a mocked response (from a static file or a script subprocess) or a passthrough signal.

**Architecture:** A single `server` module (`linux/app`, `linuxX64`). Layered as: envelope JSON models → rule config models (loaded via `kaml`) → pure matching engine → response resolver (static file / script subprocess via `kommand`) → Ktor route → CLI entry point. Each layer is unit-testable without the layers above it; the route and entry point are the only pieces that need a running server (tested via Ktor's `testApplication` and a manual smoke check, respectively).

**Tech Stack:** Kotlin Multiplatform (Kotlin Toolchain, `module.yaml`/`project.yaml` — no Gradle), Kotlin/Native `linuxX64`, Ktor server + `ktor-server-cio`, `kotlinx.serialization` (JSON), `kaml` (YAML), `kotlinx.cinterop`/`platform.posix` (subprocess spawning via `fork`/`exec` — no third-party process library; see Task 6), `okio` (filesystem access, transitively available via Ktor).

**Spec:** `docs/superpowers/specs/2026-09-06-okhttp-interceptor-design.md`

## Global Constraints

- Server targets Linux only for v1: `product.type: linux/app`, `platforms: [linuxX64]` (spec §2, §3).
- No shared DTO/protocol module with the client — envelope JSON models defined here are server-only (spec §2, §5).
- Rule files are loaded once at startup; no hot-reload (spec §2, §6).
- Scripts run as subprocesses of the system's installed interpreter (`python3` for `.py`, `node` for `.js`); no embedded scripting runtime (spec §2, §7).
- Server binds to `127.0.0.1` only — no LAN/remote client support in v1 (spec §2).
- Matching is first-match-wins across rules sorted by filename then file order (spec §6).
- Error handling asymmetry: a *matched* rule that fails to resolve returns a loud `500` diagnostic; *no match* returns a normal passthrough signal; malformed rule YAML at startup is a fatal, non-zero-exit error (spec §8).

---

### Task 1: Module scaffold

**Files:**
- Create: `project.yaml`
- Create: `server/module.yaml`
- Create: `server/src/Main.kt`

**Interfaces:**
- Produces: a runnable `linux/app` module named `server`, entry point `ghostbe.server.main`.

- [ ] **Step 1: Write `project.yaml`**

```yaml
modules:
  - server
```

- [ ] **Step 2: Write `server/module.yaml`**

```yaml
product:
  type: linux/app
  platforms: [ linuxX64 ]

dependencies:
  - $ktor.server.core
  - $ktor.server.cio
  - $kotlinx.serialization.json
  - com.charleskorn.kaml:kaml:0.61.0

settings:
  ktor: enabled
  native:
    entryPoint: ghostbe.server.main
```

- [ ] **Step 3: Write a placeholder `server/src/Main.kt`**

```kotlin
package ghostbe.server

fun main() {
    println("ghost-be starting")
}
```

- [ ] **Step 4: Build and run it**

Run: `./kotlin run -m server`
Expected: prints `ghost-be starting` with no errors. If dependency resolution fails for `kaml` (version not found for `linuxX64`), check its published Maven coordinates/versions for the native target and adjust the version in `server/module.yaml` before continuing — Task 3 depends on it resolving on this target.

- [ ] **Step 5: Commit**

```bash
git add project.yaml server/module.yaml server/src/Main.kt
git commit -m "chore: scaffold ghost-be server module"
```

---

### Task 2: Request/response envelope models

**Files:**
- Create: `server/src/Envelope.kt`
- Test: `server/test/EnvelopeTest.kt`

**Interfaces:**
- Produces:
  - `data class RequestEnvelope(val method: String, val url: String, val headers: Map<String, String>, val body: String?)` — `body` is base64 or `null`.
  - `sealed interface ResponseEnvelope` with:
    - `data class Mock(val intercept: Boolean = true, val status: Int, val headers: Map<String, String>, val body: String) : ResponseEnvelope`
    - `data class Passthrough(val intercept: Boolean = false) : ResponseEnvelope`
  - `fun RequestEnvelope.Companion.fromJson(json: String): RequestEnvelope`
  - `fun ResponseEnvelope.toJson(): String`

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m server`
Expected: FAIL — `RequestEnvelope`, `ResponseEnvelope` not defined.

- [ ] **Step 3: Write the implementation**

```kotlin
package ghostbe.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.ExperimentalSerializationApi

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
data class RequestEnvelope(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null
) {
    companion object {
        fun fromJson(text: String): RequestEnvelope = json.decodeFromString(text)
    }
}

@Serializable
sealed interface ResponseEnvelope {
    @Serializable
    data class Mock(
        val intercept: Boolean = true,
        val status: Int,
        val headers: Map<String, String>,
        val body: String
    ) : ResponseEnvelope

    @Serializable
    data class Passthrough(val intercept: Boolean = false) : ResponseEnvelope
}

fun ResponseEnvelope.toJson(): String = when (this) {
    is ResponseEnvelope.Mock -> json.encodeToString(this)
    is ResponseEnvelope.Passthrough -> json.encodeToString(this)
}
```

Note: `ResponseEnvelope` is a sealed interface used only for internal construction and manual `toJson` dispatch here — it is not decoded polymorphically, so no `@JsonClassDiscriminator`/class-discriminator configuration is needed. Remove the unused `JsonClassDiscriminator`/`ExperimentalSerializationApi` imports if your editor flags them.

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m server`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add server/src/Envelope.kt server/test/EnvelopeTest.kt
git commit -m "feat: add request/response envelope JSON models"
```

---

### Task 3: Rule config models + YAML loading

**Files:**
- Create: `server/src/Rules.kt`
- Test: `server/test/RulesTest.kt`
- Test fixture: `server/test/fixtures/rules/basic.yaml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `data class MatchSpec(val method: String, val path: String? = null, val pathPattern: String? = null, val query: Map<String, String> = emptyMap(), val headers: Map<String, String> = emptyMap())`
  - `data class ResponseSpec(val file: String? = null, val script: String? = null, val status: Int, val headers: Map<String, String> = emptyMap())`
  - `data class Rule(val name: String, val match: MatchSpec, val response: ResponseSpec)`
  - `data class RuleFile(val rules: List<Rule>)`
  - `fun loadRuleFile(yaml: String): RuleFile`
  - `fun loadRulesFromDirectory(dir: okio.Path, fileSystem: okio.FileSystem = okio.FileSystem.SYSTEM): List<Rule>` — reads every `*.yaml`/`*.yml` file in `dir` sorted by filename, concatenates their rules in file order, throws `IllegalArgumentException` with the offending file name and parser message on the first parse failure.

- [ ] **Step 1: Write the fixture**

`server/test/fixtures/rules/basic.yaml`:

```yaml
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

  - name: dynamic-user
    match:
      method: GET
      pathPattern: "/v1/users/{id}"
    response:
      script: scripts/dynamic_user.py
      status: 200
```

- [ ] **Step 2: Write the failing test**

```kotlin
package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `throws with a clear message on malformed yaml`() {
        val error = assertFailsWith<IllegalArgumentException> {
            loadRuleFile("rules: [this is not: valid: yaml structure")
        }
        assert(error.message?.isNotBlank() == true)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./kotlin test -m server`
Expected: FAIL — `loadRuleFile`, `MatchSpec`, `ResponseSpec`, `Rule`, `RuleFile` not defined.

- [ ] **Step 4: Write the implementation**

```kotlin
package ghostbe.server

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path

@Serializable
data class MatchSpec(
    val method: String,
    val path: String? = null,
    val pathPattern: String? = null,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class ResponseSpec(
    val file: String? = null,
    val script: String? = null,
    val status: Int,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class Rule(
    val name: String,
    val match: MatchSpec,
    val response: ResponseSpec
)

@Serializable
data class RuleFile(val rules: List<Rule>)

fun loadRuleFile(yaml: String): RuleFile {
    return try {
        Yaml.default.decodeFromString(RuleFile.serializer(), yaml)
    } catch (e: Exception) {
        throw IllegalArgumentException("Failed to parse rule YAML: ${e.message}", e)
    }
}

fun loadRulesFromDirectory(dir: Path, fileSystem: FileSystem = FileSystem.SYSTEM): List<Rule> {
    val files = fileSystem.list(dir)
        .filter { it.name.endsWith(".yaml") || it.name.endsWith(".yml") }
        .sortedBy { it.name }

    return files.flatMap { path ->
        val text = fileSystem.read(path) { readUtf8() }
        try {
            loadRuleFile(text).rules
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("${path}: ${e.message}", e)
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./kotlin test -m server`
Expected: PASS (all three tests).

- [ ] **Step 6: Commit**

```bash
git add server/src/Rules.kt server/test/RulesTest.kt server/test/fixtures/rules/basic.yaml
git commit -m "feat: add rule config models and YAML loading"
```

---

### Task 4: Matching engine

**Files:**
- Create: `server/src/Matcher.kt`
- Test: `server/test/MatcherTest.kt`

**Interfaces:**
- Consumes: `RequestEnvelope` (Task 2), `Rule`/`MatchSpec` (Task 3).
- Produces: `fun matchRule(envelope: RequestEnvelope, rules: List<Rule>): Rule?` — returns the first rule (in list order) whose `match` is satisfied by `envelope`, or `null`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatcherTest {
    private fun rule(name: String, method: String, path: String? = null, pathPattern: String? = null,
                      query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()) =
        Rule(name, MatchSpec(method, path, pathPattern, query, headers), ResponseSpec(status = 200))

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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m server`
Expected: FAIL — `matchRule` not defined.

- [ ] **Step 3: Write the implementation**

```kotlin
package ghostbe.server

private fun parseUrl(url: String): Triple<String, Map<String, String>, Unit> {
    val withoutScheme = url.substringAfter("://")
    val pathAndQuery = withoutScheme.substringAfter('/', missingDelimiterValue = "")
    val fullPath = "/" + pathAndQuery.substringBefore('?')
    val queryString = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    val query = if (queryString.isEmpty()) emptyMap() else queryString.split('&').associate {
        val (k, v) = it.split('=', limit = 2).let { parts -> parts[0] to parts.getOrElse(1) { "" } }
        k to v
    }
    return Triple(fullPath, query, Unit)
}

private fun pathMatchesPattern(pattern: String, actual: String): Boolean {
    val patternSegments = pattern.trim('/').split('/')
    val actualSegments = actual.trim('/').split('/')
    if (patternSegments.size != actualSegments.size) return false
    return patternSegments.zip(actualSegments).all { (p, a) ->
        (p.startsWith("{") && p.endsWith("}")) || p == a
    }
}

fun matchRule(envelope: RequestEnvelope, rules: List<Rule>): Rule? {
    val (path, query, _) = parseUrl(envelope.url)
    val lowerCaseHeaders = envelope.headers.mapKeys { it.key.lowercase() }

    return rules.firstOrNull { rule ->
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m server`
Expected: PASS (all 7 tests).

- [ ] **Step 5: Commit**

```bash
git add server/src/Matcher.kt server/test/MatcherTest.kt
git commit -m "feat: add request matching engine"
```

---

### Task 5: Static file response resolution

**Files:**
- Create: `server/src/ResponseResolver.kt`
- Test: `server/test/ResponseResolverTest.kt`
- Test fixture: `server/test/fixtures/responses/user-42.json`

**Interfaces:**
- Consumes: `Rule`/`ResponseSpec` (Task 3).
- Produces:
  - `sealed interface ResolvedResponse` with `data class Success(val status: Int, val headers: Map<String, String>, val bodyBytes: ByteArray) : ResolvedResponse` and `data class Failure(val ruleName: String, val message: String) : ResolvedResponse`.
  - `class ResponseResolver(private val rulesDir: okio.Path, private val fileSystem: okio.FileSystem = okio.FileSystem.SYSTEM)` with `fun resolve(rule: Rule, envelope: RequestEnvelope): ResolvedResponse` — this task implements the `response.file` branch only; the `response.script` branch is added in Task 6 and should currently produce a `Failure` naming the rule if reached (so the class is safe to wire into the route before Task 6 lands, per the "each task independently testable" rule).

- [ ] **Step 1: Write the fixture**

`server/test/fixtures/responses/user-42.json`:

```json
{"id": 42, "name": "Ada Lovelace"}
```

- [ ] **Step 2: Write the failing test**

```kotlin
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
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./kotlin test -m server`
Expected: FAIL — `ResponseResolver`, `ResolvedResponse` not defined.

- [ ] **Step 4: Write the implementation**

```kotlin
package ghostbe.server

import okio.FileSystem
import okio.Path

sealed interface ResolvedResponse {
    data class Success(val status: Int, val headers: Map<String, String>, val bodyBytes: ByteArray) : ResolvedResponse
    data class Failure(val ruleName: String, val message: String) : ResolvedResponse
}

class ResponseResolver(
    private val rulesDir: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM
) {
    fun resolve(rule: Rule, envelope: RequestEnvelope): ResolvedResponse {
        val spec = rule.response
        return when {
            spec.file != null -> resolveStatic(rule.name, spec)
            spec.script != null -> resolveScript(rule.name, spec, envelope)
            else -> ResolvedResponse.Failure(rule.name, "Rule has neither 'file' nor 'script' in its response")
        }
    }

    private fun resolveStatic(ruleName: String, spec: ResponseSpec): ResolvedResponse {
        val path = rulesDir / spec.file!!
        if (!fileSystem.exists(path)) {
            return ResolvedResponse.Failure(ruleName, "Response file not found: $path")
        }
        return try {
            val bytes = fileSystem.read(path) { readByteArray() }
            ResolvedResponse.Success(spec.status, spec.headers, bytes)
        } catch (e: Exception) {
            ResolvedResponse.Failure(ruleName, "Failed to read response file $path: ${e.message}")
        }
    }

    private fun resolveScript(ruleName: String, spec: ResponseSpec, envelope: RequestEnvelope): ResolvedResponse {
        return ResolvedResponse.Failure(ruleName, "Script responses are not implemented yet")
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./kotlin test -m server`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add server/src/ResponseResolver.kt server/test/ResponseResolverTest.kt server/test/fixtures/responses/user-42.json
git commit -m "feat: add static file response resolution"
```

---

### Task 6: Script response resolution

**Files:**
- Create: `server/src/Process.kt`
- Modify: `server/src/ResponseResolver.kt` (replace the `resolveScript` stub from Task 5)
- Test: `server/test/ProcessTest.kt`
- Test: `server/test/ResponseResolverTest.kt` (add script cases)
- Test fixture: `server/test/fixtures/scripts/echo_user.sh`

**Interfaces:**
- Consumes: `ResolvedResponse`/`ResponseResolver` (Task 5), `RequestEnvelope` (Task 2).
- Produces:
  - `data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)`
  - `fun runProcess(command: String, arg: String, stdin: String): ProcessResult` — spawns `command arg` via `fork`/`exec`, writes `stdin` to the child's stdin then closes it, collects the child's stdout/stderr to completion, and returns its exit code. Lives in a new file, `server/src/Process.kt`, since it's POSIX interop with its own concerns, not response-resolution logic.
  - `ResponseResolver` constructor gains an optional `interpreterFor: (String) -> String? = ::defaultInterpreterFor` parameter (extension without the dot, e.g. `"py"`, returns the interpreter command or `null` if unrecognized), and `fun defaultInterpreterFor(extension: String): String? = when (extension) { "py" -> "python3"; "js" -> "node"; else -> null }`. This lets tests substitute a `sh`-based fixture instead of depending on `python3`/`node` being installed in CI, while production code keeps the spec's `.py`/`.js` defaults.

**Known limitation (documented, not fixed in v1):** `runProcess` writes all of `stdin` before reading any output, so it can deadlock if a script writes more than one OS pipe buffer (64KB on Linux) to stdout/stderr before finishing. This is fine for the small JSON responses this feature targets; a concurrent read/write implementation (e.g. via `poll`) would be needed to lift the limit and is out of scope for v1.

- [ ] **Step 1: Write the failing test for the process-spawning primitive**

```kotlin
package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessTest {
    @Test
    fun `runs a command feeds it stdin and captures stdout and exit code`() {
        // `cat` echoes stdin back to stdout unmodified — a minimal, always-available
        // way to verify the stdin-write / stdout-read plumbing without a real script.
        val result = runProcess(command = "cat", arg = "", stdin = "hello ghost-be")
        assertEquals(0, result.exitCode)
        assertEquals("hello ghost-be", result.stdout)
    }

    @Test
    fun `captures a nonzero exit code`() {
        // `false` ignores its argument and always exits 1.
        val result = runProcess(command = "false", arg = "", stdin = "")
        assertEquals(1, result.exitCode)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m server`
Expected: FAIL — `runProcess`, `ProcessResult` not defined.

- [ ] **Step 3: Write `server/src/Process.kt`**

```kotlin
package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.ptr
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import platform.posix.EXIT_FAILURE
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.execlp
import platform.posix.fork
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid
import platform.posix.write

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Spawns `command arg` via fork/exec, writes [stdin] to the child's stdin (closing it
 * afterwards so the child sees EOF), then reads the child's stdout/stderr to completion.
 *
 * Writes all of stdin before reading any output, so this can deadlock if the child
 * writes more than one pipe buffer (64KB on Linux) before this function starts
 * reading — acceptable for the small JSON payloads ghost-be scripts produce.
 */
@OptIn(ExperimentalForeignApi::class)
fun runProcess(command: String, arg: String, stdin: String): ProcessResult = memScoped {
    val stdinPipe = allocArray<IntVar>(2)
    val stdoutPipe = allocArray<IntVar>(2)
    val stderrPipe = allocArray<IntVar>(2)

    check(pipe(stdinPipe) == 0) { "pipe() failed for stdin" }
    check(pipe(stdoutPipe) == 0) { "pipe() failed for stdout" }
    check(pipe(stderrPipe) == 0) { "pipe() failed for stderr" }

    val stdinRead = stdinPipe[0]; val stdinWrite = stdinPipe[1]
    val stdoutRead = stdoutPipe[0]; val stdoutWrite = stdoutPipe[1]
    val stderrRead = stderrPipe[0]; val stderrWrite = stderrPipe[1]

    val pid = fork()
    check(pid >= 0) { "fork() failed" }

    if (pid == 0) {
        // Child: wire up the pipes and exec. No Kotlin heap allocation past this
        // point beyond what execlp/dup2/close themselves need — fork() only
        // duplicates the calling thread, so anything relying on other threads
        // or the GC running is unsafe here.
        dup2(stdinRead, 0)
        dup2(stdoutWrite, 1)
        dup2(stderrWrite, 2)
        close(stdinRead); close(stdinWrite)
        close(stdoutRead); close(stdoutWrite)
        close(stderrRead); close(stderrWrite)
        if (arg.isEmpty()) {
            execlp(command, command, null)
        } else {
            execlp(command, command, arg, null)
        }
        _exit(EXIT_FAILURE) // only reached if execlp failed
    }

    // Parent
    close(stdinRead)
    close(stdoutWrite)
    close(stderrWrite)

    val stdinBytes = stdin.encodeToByteArray()
    stdinBytes.usePinned { pinned ->
        var written = 0
        while (written < stdinBytes.size) {
            val n = write(stdinWrite, pinned.addressOf(written), (stdinBytes.size - written).toULong())
            if (n <= 0) break
            written += n.toInt()
        }
    }
    close(stdinWrite)

    fun readAll(fd: Int): String {
        val buffer = ByteArray(4096)
        val out = StringBuilder()
        while (true) {
            val n = buffer.usePinned { pinned -> read(fd, pinned.addressOf(0), buffer.size.toULong()) }
            if (n <= 0) break
            out.append(buffer.decodeToString(0, n.toInt()))
        }
        return out.toString()
    }

    val stdout = readAll(stdoutRead)
    val stderr = readAll(stderrRead)
    close(stdoutRead)
    close(stderrRead)

    val status = alloc<IntVar>()
    waitpid(pid, status.ptr, 0)
    val exitCode = (status.value shr 8) and 0xFF

    ProcessResult(exitCode, stdout, stderr)
}
```

`execlp` takes a fixed argument list rather than an array, which is why `arg` is passed as a single optional string here (sufficient for ghost-be's one-script-path-argument use case) instead of a general `List<String>` — if a future need arises for multiple arguments, switch to `execvp` with a `CPointerVar<ByteVar>` array built the same way `argv[]` is built in standard Kotlin/Native exec examples.

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m server`
Expected: PASS (both `ProcessTest` cases).

- [ ] **Step 5: Commit**

```bash
git add server/src/Process.kt server/test/ProcessTest.kt
git commit -m "feat: add POSIX fork/exec process runner"
```

- [ ] **Step 6: Write the script fixture**

`server/test/fixtures/scripts/echo_user.sh` (executable):

```sh
#!/bin/sh
read -r input
echo '{"status":201,"headers":{"Content-Type":"application/json"},"body":"eyJvayI6dHJ1ZX0="}'
```

Make it executable: `chmod +x server/test/fixtures/scripts/echo_user.sh`

- [ ] **Step 7: Write the failing test (append to `ResponseResolverTest`)**

```kotlin
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
```

- [ ] **Step 8: Run test to verify it fails**

Run: `./kotlin test -m server`
Expected: FAIL — `interpreterFor` constructor param and `defaultInterpreterFor` not defined; script resolution still returns the Task 5 stub failure.

- [ ] **Step 9: Replace `resolveScript` and update the constructor**

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun defaultInterpreterFor(extension: String): String? = when (extension) {
    "py" -> "python3"
    "js" -> "node"
    else -> null
}

@Serializable
private data class ScriptResult(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String)

class ResponseResolver(
    private val rulesDir: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val interpreterFor: (String) -> String? = ::defaultInterpreterFor
) {
    // resolve()/resolveStatic() unchanged from Task 5

    @OptIn(ExperimentalEncodingApi::class)
    private fun resolveScript(ruleName: String, spec: ResponseSpec, envelope: RequestEnvelope): ResolvedResponse {
        val scriptRelativePath = spec.script!!
        val extension = scriptRelativePath.substringAfterLast('.', missingDelimiterValue = "")
        val interpreter = interpreterFor(extension)
            ?: return ResolvedResponse.Failure(ruleName, "No interpreter configured for script extension '.$extension'")

        val scriptPath = (rulesDir / scriptRelativePath).toString()
        val envelopeJson = Json.encodeToString(RequestEnvelope.serializer(), envelope)

        return try {
            val result = runProcess(command = interpreter, arg = scriptPath, stdin = envelopeJson)

            if (result.exitCode != 0) {
                return ResolvedResponse.Failure(ruleName, "Script exited ${result.exitCode}: ${result.stderr}")
            }

            val scriptResult = Json.decodeFromString(ScriptResult.serializer(), result.stdout)
            val bodyBytes = Base64.decode(scriptResult.body)
            ResolvedResponse.Success(scriptResult.status, scriptResult.headers, bodyBytes)
        } catch (e: Exception) {
            ResolvedResponse.Failure(ruleName, "Script execution failed: ${e.message}")
        }
    }
}
```

- [ ] **Step 10: Run test to verify it passes**

Run: `./kotlin test -m server`
Expected: PASS (all `ResponseResolverTest` cases, including the two new ones).

- [ ] **Step 11: Commit**

```bash
git add server/src/ResponseResolver.kt server/test/ResponseResolverTest.kt server/test/fixtures/scripts/echo_user.sh
git commit -m "feat: add script subprocess response resolution"
```

---

### Task 7: `/intercept` Ktor route

**Files:**
- Create: `server/src/InterceptRoute.kt`
- Test: `server/test/InterceptRouteTest.kt`
- Modify: `server/module.yaml` (add `test-dependencies: [$ktor.server.testHost]` — needed for `testApplication`/`createClient`, not pulled in by Task 1's dependencies)

**Interfaces:**
- Consumes: `RequestEnvelope`/`ResponseEnvelope` (Task 2), `Rule` (Task 3), `matchRule` (Task 4), `ResponseResolver`/`ResolvedResponse` (Tasks 5–6).
- Produces: `fun Route.interceptRoute(rules: List<Rule>, resolver: ResponseResolver)` — a Ktor route extension registering `POST /intercept`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ghostbe.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class InterceptRouteTest {
    private val rulesDir = "test/fixtures".toPath()
    private val resolver = ResponseResolver(rulesDir)
    private val rules = loadRuleFile(
        """
        rules:
          - name: get-user-42
            match: { method: GET, path: /v1/users/42 }
            response: { file: responses/user-42.json, status: 200, headers: { Content-Type: application/json } }
        """.trimIndent()
    ).rules

    private fun testApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { routing { interceptRoute(rules, resolver) } }
        block(createClient { })
    }

    @Test
    fun `returns a mock response for a matched rule`() = testApp { client ->
        val response = client.post("/intercept") {
            contentType(ContentType.Application.Json)
            setBody("""{"method":"GET","url":"https://api.example.com/v1/users/42","headers":{},"body":null}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"intercept\":true"))
        assertTrue(text.contains("\"status\":200"))
    }

    @Test
    fun `returns passthrough for an unmatched request`() = testApp { client ->
        val response = client.post("/intercept") {
            contentType(ContentType.Application.Json)
            setBody("""{"method":"GET","url":"https://api.example.com/v1/other","headers":{},"body":null}""")
        }
        assertEquals("""{"intercept":false}""", response.bodyAsText())
    }

    // Uses testApplication directly (not the testApp helper above) because this test
    // needs its own rule set instead of the shared `rules`.
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `returns a 500 diagnostic when a matched rule fails to resolve`() = testApplication {
        val brokenRules = loadRuleFile(
            """
            rules:
              - name: broken
                match: { method: GET, path: /v1/broken }
                response: { file: responses/does-not-exist.json, status: 200 }
            """.trimIndent()
        ).rules
        application { routing { interceptRoute(brokenRules, resolver) } }
        val client = createClient { }
        val response = client.post("/intercept") {
            contentType(ContentType.Application.Json)
            setBody("""{"method":"GET","url":"https://api.example.com/v1/broken","headers":{},"body":null}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"status\":500"))
        // The diagnostic message (including the rule name "broken") is inside the
        // base64-encoded `body` field, not visible as plain text in the envelope --
        // decode it before asserting on its content.
        val bodyBase64 = Regex(""""body":"([^"]+)"""").find(text)!!.groupValues[1]
        val decodedBody = Base64.decode(bodyBase64).decodeToString()
        assertTrue(decodedBody.contains("broken"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./kotlin test -m server`
Expected: FAIL — `interceptRoute` not defined.

- [ ] **Step 3: Write the implementation**

```kotlin
package ghostbe.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
fun Route.interceptRoute(rules: List<Rule>, resolver: ResponseResolver) {
    post("/intercept") {
        val body = call.receiveText()
        val envelope = RequestEnvelope.fromJson(body)
        val rule = matchRule(envelope, rules)

        if (rule == null) {
            call.respondText(ResponseEnvelope.Passthrough().toJson(), ContentType.Application.Json)
            return@post
        }

        val resolved = resolver.resolve(rule, envelope)
        val responseEnvelope: ResponseEnvelope = when (resolved) {
            is ResolvedResponse.Success -> ResponseEnvelope.Mock(
                status = resolved.status,
                headers = resolved.headers,
                body = Base64.encode(resolved.bodyBytes)
            )
            is ResolvedResponse.Failure -> ResponseEnvelope.Mock(
                status = 500,
                headers = mapOf("Content-Type" to "application/json"),
                body = Base64.encode(
                    """{"error":"ghost-be rule '${resolved.ruleName}' failed: ${resolved.message}"}""".encodeToByteArray()
                )
            )
        }
        call.respondText(responseEnvelope.toJson(), ContentType.Application.Json)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./kotlin test -m server`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add server/src/InterceptRoute.kt server/test/InterceptRouteTest.kt
git commit -m "feat: add POST /intercept route with rule matching and error diagnostics"
```

---

### Task 8: CLI entry point

**Files:**
- Modify: `server/src/Main.kt`

**Interfaces:**
- Consumes: `loadRulesFromDirectory` (Task 3), `ResponseResolver` (Tasks 5–6), `interceptRoute` (Task 7).
- Produces: a runnable binary accepting `--port <n>` (default `8787`) and `--rules <dir>` (default `./rules`).

- [ ] **Step 1: Write the implementation**

```kotlin
package ghostbe.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import okio.Path.Companion.toPath
import kotlin.system.exitProcess

private fun parseArgs(args: Array<String>): Pair<Int, String> {
    var port = 8787
    var rulesDir = "./rules"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--rules" -> { rulesDir = args[i + 1]; i += 2 }
            else -> { i += 1 }
        }
    }
    return port to rulesDir
}

fun main(args: Array<String>) {
    val (port, rulesDirArg) = parseArgs(args)
    val rulesDir = rulesDirArg.toPath()

    val rules = try {
        loadRulesFromDirectory(rulesDir)
    } catch (e: IllegalArgumentException) {
        println("ghost-be: failed to load rules from $rulesDir")
        println(e.message)
        exitProcess(1)
    }

    println("ghost-be: loaded ${rules.size} rule(s) from $rulesDir")
    println("ghost-be: listening on http://127.0.0.1:$port")

    val resolver = ResponseResolver(rulesDir)
    embeddedServer(io.ktor.server.cio.CIO, port = port, host = "127.0.0.1") {
        routing {
            interceptRoute(rules, resolver)
        }
    }.start(wait = true)
}
```

- [ ] **Step 2: Manual smoke test**

Run: `./kotlin build -m server` then start it with a real rules directory (e.g. copy `server/test/fixtures/rules` and `server/test/fixtures/responses` to `./rules` and `./rules/responses` in the repo root), then:

```bash
mkdir -p rules/responses
cp server/test/fixtures/rules/basic.yaml rules/
cp server/test/fixtures/responses/user-42.json rules/responses/
./kotlin run -m server &
curl -s -X POST http://127.0.0.1:8787/intercept \
  -H 'Content-Type: application/json' \
  -d '{"method":"GET","url":"https://api.example.com/v1/users/42?active=true","headers":{"X-Feature-Flag":"beta"},"body":null}'
```

Expected: JSON response with `"intercept":true` and a base64 body that decodes to the fixture's `user-42.json` content. Stop the server afterward (`kill %1`) and remove the ad-hoc `rules/` directory (`rm -rf rules`) so it doesn't linger as untracked repo state.

- [ ] **Step 3: Commit**

```bash
git add server/src/Main.kt
git commit -m "feat: wire ghost-be CLI entry point with rule loading and HTTP server"
```
