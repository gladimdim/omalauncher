package dev.gladimdim.superdesktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.bridge.PromptEntry
import dev.gladimdim.superdesktop.live.SdLive
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/** The harness's prompts, newest first, in place of its terminal: a tap puts one in the prompt to edit or send again. */
@Composable
internal fun PromptHistoryPanel(
    b: Bridge,
    h: Harness,
    host: SdHost,
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    val context = LocalContext.current
    var prompts by remember(b.key, h.id) { mutableStateOf<List<PromptEntry>?>(null) }
    var error by remember(b.key, h.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(b.key, h.id) {
        try {
            prompts = SdLive.api(b).prompts(h.id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "Couldn't load the prompt history"
        }
    }
    val shape = host.shape(8.dp)
    Column(modifier.border(1.dp, c.border, shape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Heading("prompt history", host)
        val list = prompts
        when {
            error != null -> Label(error!!, host, c.red, 12, lines = 3)
            list == null -> Label("loading…", host, c.muted, 12)
            list.isEmpty() -> Label("No prompts yet.", host, c.muted, 12)
            else ->
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(list) { _, p ->
                        Row(Modifier.fillMaxWidth().clickable { onInsert(p.text) }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Label(p.text, host, c.foreground, 12, lines = 3)
                                p.at?.let { at -> whenAt(at)?.let { Label(it, host, c.muted, 10) } }
                            }
                            Label("copy", host, c.accent, 11, modifier = Modifier.clickable { copy(context, p.text) }.padding(start = 10.dp, top = 2.dp))
                        }
                    }
                }
        }
    }
}

private val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/** "Oct 19, 09:14" in the phone's zone, or null for a time that doesn't parse. */
private fun whenAt(at: String): String? = runCatching { OffsetDateTime.parse(at.trim()).atZoneSameInstant(ZoneId.systemDefault()).format(formatter) }.getOrNull()

private fun copy(
    context: Context,
    text: String,
) = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("prompt", text))
