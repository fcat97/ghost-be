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
