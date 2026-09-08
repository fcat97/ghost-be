package ghostbe.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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

@Serializable
data class CreateRuleFileRequest(val path: String, val content: String)

// Guards against a path escaping the rules directory (e.g. "../secrets.yaml")
// or an absolute path -- the only paths this route should ever touch are
// plain filenames inside rulesDir.
private fun resolveRuleFilePath(rulesDir: Path, requestedPath: String): Path? {
    if (requestedPath.isBlank() || requestedPath.contains("..") || requestedPath.startsWith("/")) return null
    return rulesDir / requestedPath
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
}
