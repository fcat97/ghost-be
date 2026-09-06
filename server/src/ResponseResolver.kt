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
