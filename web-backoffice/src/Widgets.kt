import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ButtonShape = RoundedCornerShape(4.dp)

/** Small, rectangular button (little rounding) -- matches the reference design's compact controls. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Palette.border,
    contentColor: Color = Palette.textMain,
    small: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = if (small) PaddingValues(horizontal = 8.dp, vertical = 3.dp) else PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        elevation = null
    ) {
        Text(text, fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Icon-only edit button (the rule card's edit action). Drawn as a small vector
 * pencil via Canvas rather than a Unicode glyph -- the Wasm/Skia text renderer
 * doesn't ship symbol/emoji glyphs, so a character like "✎" renders as a
 * missing-glyph box (confirmed via an actual screenshot: this was the tofu box
 * seen in place of the intended pencil icon).
 */
@Composable
fun EditIconButton(onClick: () -> Unit, tint: Color = Palette.textMuted) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val stroke = 1.6.dp.toPx()
            drawLine(
                color = tint,
                start = Offset(size.width * 0.1f, size.height * 0.9f),
                end = Offset(size.width * 0.75f, size.height * 0.25f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = tint,
                start = Offset(size.width * 0.75f, size.height * 0.25f),
                end = Offset(size.width * 0.95f, size.height * 0.05f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = tint,
                start = Offset(size.width * 0.05f, size.height * 0.95f),
                end = Offset(size.width * 0.25f, size.height * 0.95f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/** A Switch scaled down to match the reference design's compact 36x20 toggle. */
@Composable
fun SmallSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(checkedTrackColor = Palette.success),
        modifier = Modifier.height(20.dp).width(36.dp)
    )
}

/** A 1dp divider line, used between panes instead of a border on every side. */
@Composable
fun VDivider(color: Color = Palette.border) {
    Box(Modifier.width(1.dp).fillMaxHeight().background(color))
}

@Composable
fun HDivider(color: Color = Palette.border) {
    Box(Modifier.height(1.dp).fillMaxWidth().background(color))
}
