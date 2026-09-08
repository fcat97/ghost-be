import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TrafficPane(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        LazyColumn {
            items(state.traffic) { event ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    val label = if (event.ruleMatched != null) {
                        "${event.method} ${event.url} -> ${event.ruleMatched} (${event.status})"
                    } else {
                        "${event.method} ${event.url} -> passthrough"
                    }
                    Text(label)
                    if (event.ruleMatched != null) {
                        Button(onClick = { state.createRuleFromTraffic(event) }) { Text("Create rule from this") }
                    }
                }
            }
        }
    }
}
