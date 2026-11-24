package dev.gladimdim.superdesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
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
        contentAlignment = Alignment.Center,
    ) {
        // A fixed line box: symbols the mono font lacks (⌨ ⚙ ⇄ ↗) come from a fallback font with taller
        // metrics, which would otherwise make that button taller than its neighbours.
        BasicText(
            text,
            style =
                TextStyle(
                    color = if (strong) host.colors.background else c,
                    fontFamily = host.mono,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                ),
            maxLines = 1,
        )
    }
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

internal fun harnessDraftShortcuts(agentType: String?): List<Pair<String, Boolean>> =
    when (agentType?.trim()?.lowercase()) {
        "claude" -> listOf("@" to false, "/" to false, "/compact" to true, "/clear" to true, "/model" to true, "/resume" to true)
        "codex" -> listOf("@" to false, "/" to false, "/model" to true, "/compact" to true, "/diff" to true, "/new" to true)
        "antigravity", "agy" -> listOf("@" to false, "/" to false, "/resume" to true, "/model" to true, "/compact" to true)
        "opencode" -> listOf("@" to false, "/" to false, "/models" to true, "/sessions" to true, "/compact" to true, "/undo" to true)
        "grok" -> listOf("/" to false, "/minimal" to true, "/model" to true, "/resume" to true, "/clear" to true)
        "hermes" -> listOf("/" to false, "/model" to true, "/new" to true, "/queue " to true, "/status" to true)
        "pi" -> listOf("@" to false, "/" to false, "/model" to true, "/tree" to true, "/compact" to true, "/resume" to true)
        "openclaw" -> listOf("/" to false, "/status" to true, "/model" to true, "/new" to true)
        "aider" -> listOf("/" to false, "/add " to true, "/diff" to true, "/undo" to true, "/run " to true, "/test " to true)
        "crush" -> listOf("/" to false, "/model" to true, "/sessions" to true)
        "reasonix" -> listOf("/" to false, "/model" to true, "/compact" to true)
        "dsh" -> listOf("/" to false, "/settings" to true, "/model" to true)
        "goose" -> listOf("/" to false, "/mode" to true, "/plan" to true, "/clear" to true)
        "amp" -> listOf("@" to false, "/" to false, "/model" to true, "/new" to true, "/compact" to true)
        "copilot" -> listOf("@" to false, "/" to false, "/model" to true, "/diff" to true, "/compact" to true)
        else -> emptyList()
    }

/** Per-harness quick shortcuts while drafting a prompt; tokens append and commands fill empty drafts. */
@Composable
internal fun HarnessDraftKeys(
    agentType: String?,
    prompt: String,
    host: SdHost,
    onChange: (String) -> Unit,
) {
    val entries = harnessDraftShortcuts(agentType)
    if (entries.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((text, emptyOnly) in entries) {
            val label = text.trim()
            Action(label, host, enabled = !emptyOnly || prompt.isEmpty()) {
                onChange(if (emptyOnly) text else prompt + text)
            }
        }
    }
}

private val WAITING_APPROVALS =
    listOf(
        Triple("Yes", "y", true),
        Triple("No", "n", true),
        Triple("1", "1", true),
        Triple("2", "2", true),
        Triple("3", "3", true),
        Triple("↑", "\u001b[A", false),
        Triple("↓", "\u001b[B", false),
        Triple("⏎", "", true),
        Triple("esc", "\u001b", false),
    )

/** Contextual 1-tap approval bar shown immediately when an AI harness pauses in WAITING state. */
@Composable
internal fun WaitingApprovalRow(
    status: String,
    agentType: String?,
    host: SdHost,
    onKey: (text: String, enter: Boolean) -> Unit,
) {
    val normalized = agentType?.trim()?.lowercase()
    if (!needsYou(status) || normalized in setOf("shell", "bash", "zsh", "fish", "sh")) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, text, enter) in WAITING_APPROVALS) {
            Action(label, host, color = host.colors.yellow, strong = label == "Yes") { onKey(text, enter) }
        }
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

/**
 * Turns typing on and off for a terminal: on shows the key row and opens the phone keyboard on [prompt],
 * off closes both. Filled while typing.
 */
@Composable
internal fun KeyboardButton(
    host: SdHost,
    active: Boolean,
    prompt: FocusRequester?,
    onActive: (Boolean) -> Unit,
) {
    val focus = LocalFocusManager.current
    var open by remember { mutableStateOf(false) }
    // The prompt may only appear once typing is on, so it's focused after that composition.
    LaunchedEffect(open, active) {
        if (open && active) runCatching { prompt?.requestFocus() }
        open = false
    }
    Action("⌨", host, strong = active) {
        if (active) {
            focus.clearFocus()
            onActive(false)
        } else {
            onActive(true)
            open = true
        }
    }
}
