package dev.gladimdim.superdesktop

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAG = "SuperDesktop"

/**
 * A SUPER DESKTOP page: one tile, usually filling a home page. [id] keys the page's
 * settings. For now a placeholder that shows the tile fills its page, scrolls, types
 * and follows the theme.
 */
@Composable
fun SuperDesktopTile(
    id: String?,
    host: SdHost,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    DisposableEffect(id) {
        Log.d(TAG, "page $id composed")
        onDispose { Log.d(TAG, "page $id disposed") }
    }
    BoxWithConstraints(modifier.padding(12.dp)) {
        val size = "${maxWidth.value.toInt()}×${maxHeight.value.toInt()} dp"
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SUPER DESKTOP", host, c.accent, 15, bold = true)
                Spacer(Modifier.weight(1f))
                Text("${id ?: "no id"} · $size", host, c.muted, 11)
            }
            Prompt(host)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PLACEHOLDER) { h -> Card(h, host) }
            }
        }
    }
}

private class Placeholder(val name: String, val status: String, val model: String, val folder: String)

private val PLACEHOLDER =
    List(50) { i ->
        Placeholder(
            name = listOf("Claude Code", "Codex", "OpenCode", "Pi")[i % 4],
            status = listOf("WORKING", "IDLE", "WAITING", "FINISHED")[i % 4],
            model = listOf("opus · high", "gpt-5 · medium", "sonnet · low", "kimi · —")[i % 4],
            folder = "~/GitHub/project-${i + 1}",
        )
    }

@Composable
private fun Card(
    h: Placeholder,
    host: SdHost,
) {
    val c = host.colors
    val dot =
        when (h.status) {
            "WORKING" -> c.accent
            "WAITING" -> c.yellow
            "FINISHED" -> c.green
            else -> c.muted
        }
    val shape = host.shape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface.copy(alpha = 0.6f), shape)
            .border(1.dp, c.border, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(h.name, host, c.foreground, 13, bold = true)
            Spacer(Modifier.weight(1f))
            Text(h.status, host, dot, 11)
        }
        Text(h.model, host, c.muted, 11)
        Text(h.folder, host, c.muted, 11)
    }
}

@Composable
private fun Prompt(host: SdHost) {
    val c = host.colors
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    val shape = host.shape(8.dp)
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = c.foreground, fontFamily = host.mono, fontSize = 13.sp),
        cursorBrush = SolidColor(c.accent),
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) host.fieldFocused { focus.clearFocus() } else host.fieldUnfocused() }
                .border(1.dp, c.border, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) Text("prompt…", host, c.muted, 13)
                inner()
            }
        },
    )
}

@Composable
private fun Text(
    text: String,
    host: SdHost,
    color: androidx.compose.ui.graphics.Color,
    size: Int,
    bold: Boolean = false,
) = androidx.compose.foundation.text.BasicText(
    text,
    style =
        TextStyle(
            color = color,
            fontFamily = host.mono,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
    maxLines = 1,
)
