import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun RulesPane(state: AppState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { state.startCreating() }) { Text("New rule") }

        LazyColumn {
            items(state.rules) { file ->
                Column {
                    file.rules.forEach { rule ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { state.toggleRule(file.path, rule.name) }
                            )
                            Text("${rule.method} ${rule.path} (${rule.name})")
                            Button(onClick = { state.startEditing(file.path, file.content) }) { Text("Edit") }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(file.path)
                        Button(onClick = { state.deleteRuleFile(file.path) }) { Text("Delete file") }
                    }
                }
            }
        }

        if (state.editingPath != null) {
            if (state.editingIsNew) {
                TextField(
                    value = state.editingPath ?: "",
                    onValueChange = { state.editingPath = it },
                    label = { Text("File name (e.g. checkout.yaml)") }
                )
            }
            TextField(
                value = state.editingContent,
                onValueChange = { state.editingContent = it },
                label = { Text("Rule YAML") }
            )
            state.editingError?.let { Text(it) }
            Row {
                Button(onClick = { state.saveEditing() }) { Text("Save") }
                Button(onClick = { state.cancelEditing() }) { Text("Cancel") }
            }
        }
    }
}
