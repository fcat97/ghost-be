package ghostbe.server

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

sealed interface ResolvedResponse {
    data class Success(val status: Int, val headers: Map<String, String>, val bodyBytes: ByteArray) : ResolvedResponse
    data class Failure(val ruleName: String, val message: String) : ResolvedResponse
}

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
    /**
     * Turns a single [ResponseSpec] into bytes. Deliberately takes one spec rather than the
     * whole [Rule]: a rule may carry a sequence of responses, and choosing between them is
     * the caller's job (see `pickVariant`), so resolving stays side-effect-free and has no
     * opinion about how many variants exist.
     */
    fun resolve(ruleName: String, spec: ResponseSpec, envelope: RequestEnvelope): ResolvedResponse {
        return when {
            spec.file != null -> resolveStatic(ruleName, spec)
            spec.script != null -> resolveScript(ruleName, spec, envelope)
            else -> ResolvedResponse.Failure(ruleName, "Rule has neither 'file' nor 'script' in its response")
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
