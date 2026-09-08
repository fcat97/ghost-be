import androidx.compose.foundation.layout.Row
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
    Row {
        RulesPane(state, modifier = Modifier.weight(1f))
        TrafficPane(state, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App() }
}
