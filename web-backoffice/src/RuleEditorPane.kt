import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RuleEditorPane(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(Palette.bgSecondary)) {
        PaneHeader(title = if (state.editingPath.isNullOrEmpty() && state.editingIsNew) "new_rule.yaml" else (state.editingPath ?: "no file selected")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(text = "Discard", onClick = { state.cancelEditing() })
                AppButton(text = "Validate", onClick = { state.validateEditing() })
                AppButton(
                    text = "Apply & Save",
                    onClick = { state.saveEditing() },
                    containerColor = Palette.accent,
                    contentColor = Palette.bgPrimary
                )
            }
        }

        if (state.editingPath == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Select a rule to edit, or click New Rule", color = Palette.textMuted)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                if (state.editingIsNew) {
                    BasicTextField(
                        value = state.editingPath ?: "",
                        onValueChange = { state.editingPath = it },
                        textStyle = TextStyle(color = Palette.textMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }
                state.editingError?.let { Text(it, color = Palette.danger, modifier = Modifier.padding(bottom = 8.dp)) }
                state.validationMessage?.let {
                    Text(it, color = if (state.validationOk) Palette.success else Palette.danger, modifier = Modifier.padding(bottom = 8.dp))
                }
                BasicTextField(
                    value = state.editingContent,
                    onValueChange = { state.editingContent = it },
                    textStyle = TextStyle(color = Palette.textMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    visualTransformation = YamlSyntaxHighlighter,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Palette.consoleBg)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
