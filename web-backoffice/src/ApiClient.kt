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
    return window.fetch(url, init).await<Response>()
}

object ApiClient {
    @OptIn(ExperimentalWasmJsInterop::class)
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
