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
    val response: ResponseSpec,
    val enabled: Boolean = true
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
    return Yaml.default.encodeToString(RuleFile.serializer(), ruleFile)
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
