import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@Composable
fun App() {
    val state = remember { AppState(CoroutineScope(Dispatchers.Default)) }
    remember {
        state.refreshRules()
        state.connectTrafficFeed()
        true
    }
    Column(modifier = Modifier.fillMaxSize().background(Palette.bgPrimary)) {
        HeaderBar()
        HDivider()
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.weight(0.55f)) {
                RulesPane(state, modifier = Modifier.weight(0.45f))
                VDivider()
                RuleEditorPane(state, modifier = Modifier.weight(0.55f))
            }
            HDivider()
            TrafficPane(state, modifier = Modifier.weight(0.45f))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App() }
}
