import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RulesPane(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(Palette.bgSecondary)) {
        PaneHeader(title = "Gateway Rules") {
            Button(
                onClick = { state.startCreating() },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.border, contentColor = Palette.textMain)
            ) { Text("+ New Rule") }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(state.rules) { file ->
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    file.rules.forEach { rule ->
                        RuleCard(
                            title = rule.name,
                            description = "${rule.method} ${rule.path}",
                            enabled = rule.enabled,
                            selected = state.editingPath == file.path,
                            onToggle = { state.toggleRule(file.path, rule.name) },
                            onEdit = { state.startEditing(file.path, file.content) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(file.path, color = Palette.textMuted)
                        Button(
                            onClick = { state.deleteRuleFile(file.path) },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.danger, contentColor = Palette.textMain)
                        ) { Text("Delete file") }
                    }
                }
            }
        }
    }
}

@Composable
fun RuleCard(
    title: String,
    description: String,
    enabled: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.bgPrimary, RoundedCornerShape(6.dp))
            .border(1.dp, if (selected) Palette.accent else Palette.border, RoundedCornerShape(6.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = Palette.textMain, fontWeight = FontWeight.Bold)
            Text(description, color = Palette.textMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.success)
            )
            Button(
                onClick = onEdit,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.border, contentColor = Palette.textMain)
            ) { Text("Edit") }
        }
    }
}

@Composable
fun PaneHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.bgCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Palette.textMain, fontWeight = FontWeight.Bold)
        actions()
    }
}
