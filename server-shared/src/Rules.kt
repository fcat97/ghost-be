package ghostbe.server

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path

/**
 * Encoding-side YAML, used only by [renderRuleFile].
 *
 * `Yaml.default` encodes defaults, so rendering a rule file back out would write every
 * key the user left unset -- `pathPattern: null`, `script: null`, `enabled: true` and so
 * on. That matters because `POST /api/rules/{path}/{ruleName}/toggle` rewrites the user's
 * whole file through this path, so anything emitted here ends up in their YAML.
 * Decoding deliberately keeps using `Yaml.default` (strict about unknown keys).
 */
private val renderYaml = Yaml(configuration = YamlConfiguration(encodeDefaults = false))

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
    val response: ResponseSpec,
    val enabled: Boolean = true,
    // New fields go after `enabled`: MatcherTest constructs Rule positionally.
    /** Inert until this scenario is activated; null means "baseline", always active. */
    val scenario: String? = null
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

fun renderRuleFile(ruleFile: RuleFile): String {
    return renderYaml.encodeToString(RuleFile.serializer(), ruleFile)
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
