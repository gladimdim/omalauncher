package dev.gladimdim.superdesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The tile's few controls, drawn like the launcher's own: mono text, thin frames, the theme's colors.

@Composable
internal fun Label(
    text: String,
    host: SdHost,
    color: Color,
    size: Int,
    bold: Boolean = false,
    lines: Int = 1,
    modifier: Modifier = Modifier,
) = BasicText(
    text,
    modifier,
    style = TextStyle(color = color, fontFamily = host.mono, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    maxLines = lines,
    overflow = TextOverflow.Ellipsis,
)

/** A framed text button; [strong] fills it with [color]. */
@Composable
internal fun Action(
    text: String,
    host: SdHost,
    color: Color = host.colors.accent,
    strong: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = host.shape(6.dp)
    val c = if (enabled) color else host.colors.muted
    Box(
        Modifier
            .then(if (strong) Modifier.background(c, shape) else Modifier.border(1.dp, if (enabled) host.colors.border else c.copy(alpha = 0.4f), shape))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Label(text, host, if (strong) host.colors.background else c, 12) }
}

/** A section's small heading, as in the launcher's settings. */
@Composable
internal fun Heading(
    text: String,
    host: SdHost,
) = Label(text.uppercase(), host, host.colors.accent, 11, modifier = Modifier.padding(top = 6.dp))

/** A row that toggles, with a square box like the launcher's checkboxes. */
@Composable
internal fun Choice(
    text: String,
    host: SdHost,
    checked: Boolean,
    note: String? = null,
    onClick: () -> Unit,
) {
    val c = host.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).border(1.dp, if (checked) c.accent else c.border).padding(3.dp)) {
            if (checked) Box(Modifier.size(8.dp).background(c.accent))
        }
        Spacer(Modifier.width(10.dp))
        Label(text, host, c.foreground, 13, modifier = Modifier.weight(1f))
        note?.let { Label(it, host, c.muted, 11) }
    }
}

/**
 * A one-line text field. While it has the keyboard the launcher lifts the tile above it, and a tap
 * outside the tile takes it away ([SdHost.fieldFocused]).
 */
@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    host: SdHost,
    placeholder: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    val c = host.colors
    val focus = LocalFocusManager.current
    val shape = host.shape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = c.foreground, fontFamily = host.mono, fontSize = 13.sp),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Send else ImeAction.Done),
        keyboardActions = KeyboardActions(onAny = { onDone?.invoke() ?: focus.clearFocus() }),
        modifier =
            modifier
                .onFocusChanged { if (it.isFocused) host.fieldFocused { focus.clearFocus() } else host.fieldUnfocused() }
                .border(1.dp, c.border, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Label(placeholder, host, c.muted, 13)
                inner()
            }
        },
    )
}
