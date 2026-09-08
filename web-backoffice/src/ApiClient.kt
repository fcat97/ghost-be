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

private val json = Json { ignoreUnknownKeys = true }

private suspend fun request(method: String, url: String, body: String? = null): String {
    return suspendCancellableCoroutine { continuation ->
        val xhr = XMLHttpRequest()
        xhr.open(method, url)
        xhr.onload = {
            continuation.resume(xhr.responseText)
        }
        xhr.onerror = {
            continuation.resume("")
        }
        if (body != null) {
            xhr.setRequestHeader("Content-Type", "application/json")
            xhr.send(body)
        } else {
            xhr.send()
        }
    }
}

object ApiClient {
    suspend fun listRules(): List<RuleFileSummary> {
        val text = request("GET", "/api/rules")
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
