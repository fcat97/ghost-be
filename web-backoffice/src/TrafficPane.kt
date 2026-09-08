import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun TrafficPane(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(Palette.consoleBg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.bgCard)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Live Request & Response Console", color = Palette.textMuted)
            Button(
                onClick = { state.clearTraffic() },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.border, contentColor = Palette.textMain)
            ) { Text("Clear logs") }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            items(state.traffic) { event ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    val (tag, tagColor) = if (event.ruleMatched != null) "IN " to Palette.accent else "SYS" to Palette.warning
                    Text(tag, color = tagColor, fontFamily = FontFamily.Monospace)
                    Text("  ", fontFamily = FontFamily.Monospace)
                    val label = if (event.ruleMatched != null) {
                        "${event.method} ${event.url} -> ${event.ruleMatched} (${event.status})"
                    } else {
                        "${event.method} ${event.url} -> passthrough"
                    }
                    val statusColor = when {
                        event.status == null -> Palette.textMuted
                        event.status in 200..299 -> Palette.success
                        else -> Palette.danger
                    }
                    Text(label, color = statusColor, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    if (event.ruleMatched != null) {
                        Button(
                            onClick = { state.createRuleFromTraffic(event) },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bgPrimary)
                        ) { Text("Create rule from this") }
                    }
                }
            }
        }
    }
}
