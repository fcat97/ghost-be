import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

// No Compose Multiplatform library provides YAML syntax highlighting on wasmJs
// today (checked: Sora Editor is Android-only, compose-code-editor's js target
// is old Compose HTML not Foundation, and compose-codeview -- the one library
// confirmed to support wasmJs with Foundation/Material3 -- has no YAML lexer).
// A hand-rolled VisualTransformation is the standard, dependency-free technique
// for this: it's pure androidx.compose.ui.text, identical on every target.
private val keyRegex = Regex("""^(\s*-?\s*)([A-Za-z0-9_.\-]+)(\s*:)""")
private val commentRegex = Regex("""#.*$""")
private val stringRegex = Regex(""""[^"]*"|'[^']*'""")
private val numberRegex = Regex("""(?<![\w.-])-?\d+(\.\d+)?(?![\w.-])""")

object YamlSyntaxHighlighter : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = buildAnnotatedString {
            append(text.text)
            var offset = 0
            for (line in text.text.split("\n")) {
                val commentMatch = commentRegex.find(line)
                val codeEnd = commentMatch?.range?.first ?: line.length
                val codePart = line.substring(0, codeEnd)

                keyRegex.find(codePart)?.groups?.get(2)?.let { key ->
                    addStyle(SpanStyle(color = Palette.accent), offset + key.range.first, offset + key.range.last + 1)
                }
                for (m in stringRegex.findAll(codePart)) {
                    addStyle(SpanStyle(color = Palette.success), offset + m.range.first, offset + m.range.last + 1)
                }
                for (m in numberRegex.findAll(codePart)) {
                    addStyle(SpanStyle(color = Palette.warning), offset + m.range.first, offset + m.range.last + 1)
                }
                if (commentMatch != null) {
                    addStyle(
                        SpanStyle(color = Palette.textMuted),
                        offset + commentMatch.range.first,
                        offset + commentMatch.range.last + 1
                    )
                }
                offset += line.length + 1
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}
