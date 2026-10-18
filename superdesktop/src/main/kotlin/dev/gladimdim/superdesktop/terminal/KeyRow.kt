package dev.gladimdim.superdesktop.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.Action
import dev.gladimdim.superdesktop.SdHost

/** What each key types, as the SUPER DESKTOP app's keyboard panel sends it (PROTOCOL.md, `/keys`). */
internal val TERMINAL_KEYS =
    listOf(
        "esc" to "\u001b",
        "tab" to "\t",
        "⇧tab" to "\u001b[Z",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "←" to "\u001b[D",
        "→" to "\u001b[C",
        "^C" to "\u0003",
    )

/** Keys a phone keyboard lacks, for the active terminal; ⏎ submits, "done" lets the page go. */
@Composable
internal fun KeyRow(
    host: SdHost,
    onKey: (text: String, enter: Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) = Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    for ((label, text) in TERMINAL_KEYS) Action(label, host) { onKey(text, false) }
    Action("⏎", host) { onKey("", true) }
    Action("done", host, strong = true, onClick = onDone)
}
