package dev.gladimdim.superdesktop.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.gladimdim.superdesktop.SdDrafts
import dev.gladimdim.superdesktop.SdStore
import dev.gladimdim.superdesktop.SdTheme
import dev.gladimdim.superdesktop.bridge.Attachment
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.bridge.MAX_ATTACHMENTS
import dev.gladimdim.superdesktop.bridge.MAX_ATTACHMENT_BYTES
import dev.gladimdim.superdesktop.bridge.newRequestId
import dev.gladimdim.superdesktop.live.SdLive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Android's share target: text, images and files from any app go to one harness on one of the paired computers.
 * Text fills the prompt, to send now or keep as that harness's draft; images (resized to 2048 px, re-encoded as
 * JPEG, which drops their metadata) and files go with the prompt, at most 4 and 16 MB, as the SUPER DESKTOP app sends them.
 */
class ShareActivity : ComponentActivity() {
    private class Target(
        val bridge: Bridge,
        val harness: Harness,
    )

    private var text by mutableStateOf("")
    private var items by mutableStateOf<List<Attachment>>(emptyList())
    private var targets by mutableStateOf<List<Target>?>(null)
    private var picked by mutableStateOf<Target?>(null)
    private var note by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        lifecycleScope.launch { load() }
        setContent { Screen() }
    }

    private suspend fun load() {
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> emptyList()
            }
        if (uris.size > MAX_ATTACHMENTS) note = "Only the first $MAX_ATTACHMENTS go with one prompt."
        items =
            withContext(Dispatchers.IO) {
                uris.take(MAX_ATTACHMENTS).mapNotNull { uri -> runCatching { read(uri) }.onFailure { note = "Couldn't read a shared item." }.getOrNull() }
            }
        if (items.sumOf { it.bytes.size.toLong() } > MAX_ATTACHMENT_BYTES) {
            note = "These items are over 16 MB together. Share fewer or smaller ones."
            items = emptyList()
        }
        // Every harness on every paired computer, newest computers' answers as they come.
        val bridges = withContext(Dispatchers.IO) { SdStore.load(applicationContext) }
        targets =
            withContext(Dispatchers.IO) {
                bridges
                    .map { b -> async { runCatching { SdLive.api(b).harnesses() }.getOrNull()?.harnesses.orEmpty().map { Target(b, it) } } }
                    .awaitAll()
                    .flatten()
            }
        if (bridges.isEmpty()) note = "Pair a computer in SD Launcher first: a SUPER DESKTOP page's ⚙ → pair a computer."
    }

    private fun read(uri: Uri): Attachment {
        val type = contentResolver.getType(uri).orEmpty()
        val name =
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "shared"
        if (type.startsWith("image/") && !type.contains("gif")) {
            // A bounded still JPEG: at most 2048 px on the long side, and no camera metadata.
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            val out = ByteArrayOutputStream()
            var quality = 88
            do {
                out.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                quality -= 10
            } while (out.size() > 2 * 1024 * 1024 && quality > 30)
            return Attachment(image = true, name = name.substringBeforeLast('.') + ".jpg", bytes = out.toByteArray())
        }
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return Attachment(image = false, name = name, bytes = bytes)
    }

    private fun send(t: Target) {
        busy = true
        lifecycleScope.launch {
            try {
                val api = SdLive.api(t.bridge)
                if (items.isEmpty()) api.sendKeys(t.harness.id, text, true) else api.attachmentPrompt(t.harness.id, text, items, newRequestId())
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = e.message ?: "Couldn't send"
                busy = false
            }
        }
    }

    private fun keepAsDraft(t: Target) {
        SdDrafts.put(applicationContext, t.bridge.key, t.harness.id, text)
        finish()
    }

    @Composable
    private fun Screen() {
        val c = SdTheme.colors
        Column(
            Modifier
                .fillMaxSize()
                .background(c.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label("SUPER DESKTOP", c.accent, 22, bold = true)
            Label("Share to a harness", c.foreground, 16)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = c.foreground, fontFamily = SdTheme.mono, fontSize = 13.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).border(1.dp, c.border).padding(10.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Label("prompt…", c.muted, 13)
                        inner()
                    }
                },
            )
            for (a in items) Label("${if (a.image) "image" else "file"} · ${a.name} · ${a.bytes.size / 1024} KB", c.muted, 12)
            note?.let { Label(it, c.red, 12, lines = 3) }
            Label("to", c.accent, 11)
            val list = targets
            if (list == null) {
                Label("reaching your computers…", c.muted, 12)
            } else if (list.isEmpty()) {
                Label("No harness is running on a reachable computer.", c.muted, 12)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.orEmpty(), key = { it.bridge.key + it.harness.id }) { t ->
                    val chosen = picked === t
                    Row(
                        Modifier.fillMaxWidth().border(if (chosen) 2.dp else 1.dp, if (chosen) c.accent else c.border).clickable { picked = t }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Label(t.harness.sessionTitle ?: t.harness.agentName, c.foreground, 13, bold = true)
                            Label(listOfNotNull(t.bridge.name, t.harness.directoryDisplay).joinToString(" · "), c.muted, 11)
                        }
                        Label(t.harness.status.lowercase(), c.muted, 11)
                    }
                }
            }
            val t = picked
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button("send", c.background, c.accent, filled = true, enabled = t != null && !busy && (text.isNotBlank() || items.isNotEmpty())) { t?.let(::send) }
                if (items.isEmpty()) Button("keep as draft", c.foreground, c.border, enabled = t != null && text.isNotBlank()) { t?.let(::keepAsDraft) }
                Button("cancel", c.foreground, c.border) { finish() }
            }
        }
    }

    @Composable
    private fun Label(
        text: String,
        color: Color,
        size: Int,
        bold: Boolean = false,
        lines: Int = 1,
    ) = BasicText(
        text,
        style = TextStyle(color = color, fontFamily = SdTheme.mono, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )

    @Composable
    private fun Button(
        text: String,
        color: Color,
        frame: Color,
        filled: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) = Box(
        Modifier
            .then(if (filled) Modifier.background(if (enabled) frame else frame.copy(alpha = 0.4f)) else Modifier.border(1.dp, frame))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) { Label(text, if (enabled) color else color.copy(alpha = 0.5f), 14) }
}
