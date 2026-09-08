import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
}
