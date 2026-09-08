import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.w3c.dom.EventSource
import kotlin.js.ExperimentalWasmJsInterop

@Serializable
data class TrafficEvent(val method: String, val url: String, val ruleMatched: String?, val status: Int?)

private val trafficJson = Json { ignoreUnknownKeys = true }

class AppState(private val scope: CoroutineScope) {
    var rules by mutableStateOf<List<RuleFileSummary>>(emptyList())
        private set

    var editingPath by mutableStateOf<String?>(null)
    var editingContent by mutableStateOf("")
    var editingIsNew by mutableStateOf(false)
    var editingError by mutableStateOf<String?>(null)

    fun refreshRules() {
        scope.launch {
            rules = ApiClient.listRules()
        }
    }

    fun startEditing(path: String, content: String) {
        editingPath = path
        editingContent = content
        editingIsNew = false
        editingError = null
    }

    fun startCreating(prefillContent: String = "") {
        editingPath = ""
        editingContent = prefillContent
        editingIsNew = true
        editingError = null
    }

    fun cancelEditing() {
        editingPath = null
    }

    fun saveEditing() {
        val path = editingPath ?: return
        scope.launch {
            try {
                if (editingIsNew) {
                    ApiClient.createRule(path, editingContent)
                } else {
                    ApiClient.saveRule(path, editingContent)
                }
                editingPath = null
                refreshRules()
            } catch (e: Throwable) {
                editingError = e.message ?: "save failed"
            }
        }
    }

    fun deleteRuleFile(path: String) {
        scope.launch {
            ApiClient.deleteRule(path)
            refreshRules()
        }
    }

    fun toggleRule(path: String, ruleName: String) {
        scope.launch {
            ApiClient.toggleRule(path, ruleName)
            refreshRules()
        }
    }

    var traffic by mutableStateOf<List<TrafficEvent>>(emptyList())
        private set

    @OptIn(ExperimentalWasmJsInterop::class)
    fun connectTrafficFeed() {
        val source = EventSource("/events/traffic")
        source.onmessage = { messageEvent ->
            val event = trafficJson.decodeFromString<TrafficEvent>(messageEvent.data.toString())
            traffic = (traffic + event).takeLast(500)
            null
        }
    }

    fun createRuleFromTraffic(event: TrafficEvent) {
        startCreating(
            prefillContent = "rules:\n  - name: new-rule\n    match: { method: ${event.method}, path: ${pathOf(event.url)} }\n    response: { file: responses/new.json, status: 200 }\n"
        )
    }

    private fun pathOf(url: String): String {
        val withoutScheme = url.substringAfter("://")
        val afterHost = withoutScheme.substringAfter('/', missingDelimiterValue = "")
        return "/" + afterHost.substringBefore('?')
    }
}
