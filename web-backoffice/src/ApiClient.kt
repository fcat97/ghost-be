import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

@Serializable
data class RuleSummary(val name: String, val method: String, val path: String, val enabled: Boolean)

@Serializable
data class RuleFileSummary(val path: String, val content: String, val rules: List<RuleSummary>)

@Serializable
private data class CreateRuleFileRequest(val path: String, val content: String)

class ApiException(val status: Int, message: String) : Exception(message)

private val json = Json { ignoreUnknownKeys = true }

// Resolves with the HTTP status and response body -- callers decide whether a
// given status is expected (validate: caller wants to see a 400's message
// without throwing) or should throw (save/create/delete/toggle: a non-2xx
// means the action didn't happen and the caller needs to know that, not
// silently proceed as if it succeeded).
private suspend fun request(method: String, url: String, body: String? = null): Pair<Int, String> {
    return suspendCancellableCoroutine { continuation ->
        val xhr = XMLHttpRequest()
        xhr.open(method, url)
        xhr.onload = {
            continuation.resume(xhr.status.toInt() to xhr.responseText)
        }
        xhr.onerror = {
            continuation.resume(0 to "network error")
        }
        if (body != null) {
            xhr.setRequestHeader("Content-Type", "application/json")
            xhr.send(body)
        } else {
            xhr.send()
        }
    }
}

private suspend fun requestOrThrow(method: String, url: String, body: String? = null): String {
    val (status, text) = request(method, url, body)
    if (status !in 200..299) throw ApiException(status, text.ifBlank { "request failed ($status)" })
    return text
}

object ApiClient {
    suspend fun listRules(): List<RuleFileSummary> {
        val text = requestOrThrow("GET", "/api/rules")
        return json.decodeFromString(text)
    }

    suspend fun saveRule(path: String, content: String) {
        requestOrThrow("PUT", "/api/rules/$path", content)
    }

    suspend fun createRule(path: String, content: String) {
        val body = json.encodeToString(CreateRuleFileRequest(path, content))
        requestOrThrow("POST", "/api/rules", body)
    }

    suspend fun deleteRule(path: String) {
        requestOrThrow("DELETE", "/api/rules/$path")
    }

    suspend fun toggleRule(path: String, ruleName: String) {
        requestOrThrow("POST", "/api/rules/$path/$ruleName/toggle")
    }

    /** Returns null if the YAML is valid, or the server's parse error message if not. Never writes anything. */
    suspend fun validateRule(content: String): String? {
        val (status, text) = request("POST", "/api/rules/validate", content)
        return if (status in 200..299) null else text.ifBlank { "invalid rule YAML" }
    }
}
