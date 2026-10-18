package dev.gladimdim.superdesktop.pair

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gladimdim.superdesktop.SdStore
import dev.gladimdim.superdesktop.SdTheme
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.BridgeApi
import dev.gladimdim.superdesktop.bridge.Invitation
import dev.gladimdim.superdesktop.bridge.PairException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pairs the launcher with a SUPER DESKTOP computer: the invitation (pasted, or a superdesktop://pair
 * link), a code to compare on the computer, and the computer's approval. Nothing is trusted before
 * the owner approves there.
 */
class PairingActivity : ComponentActivity() {
    private sealed interface Step {
        data class Enter(
            val text: String,
            val error: String? = null,
        ) : Step

        data object Connecting : Step

        data class Approve(
            val computer: String,
            val code: String,
        ) : Step

        data class Paired(
            val computer: String,
        ) : Step
    }

    private var step by mutableStateOf<Step>(Step.Enter(""))
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        take(intent)
        setContent { Screen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** A link only fills the field: pairing starts when the user taps connect. */
    private fun take(intent: Intent?) {
        val link = intent?.dataString ?: return
        if (step !is Step.Approve) step = Step.Enter(link)
    }

    private fun pair(text: String) {
        val invitation =
            try {
                Invitation.parse(text)
            } catch (e: Exception) {
                step = Step.Enter(text, e.message ?: "That isn't a SUPER DESKTOP invitation")
                return
            }
        step = Step.Connecting
        job?.cancel()
        job =
            lifecycleScope.launch {
                try {
                    val api = BridgeApi("https://${invitation.host}:${invitation.port}", "", invitation.pin)
                    val info = api.ping()
                    val (requestId, code) = api.requestPairing(invitation.secret, "SD Launcher · ${Build.MANUFACTURER} ${Build.MODEL}")
                    step = Step.Approve(info.hostname, code)
                    val until = System.currentTimeMillis() + 120_000
                    var token: String? = null
                    while (token == null && System.currentTimeMillis() < until) {
                        delay(1000)
                        val result = api.pollPairing(requestId)
                        when (result.optString("status")) {
                            "paired" -> token = result.optString("token").takeIf { it.isNotBlank() }
                            "denied" -> throw PairException("The computer denied this device.")
                        }
                    }
                    if (token == null) throw PairException("Approval timed out. Ask the computer for a new invitation.")
                    val bridge = Bridge(invitation.host, invitation.port, token, invitation.pin, info.bridgeId, info.hostname, info.addresses)
                    withContext(Dispatchers.IO) { SdStore.save(applicationContext, bridge) }
                    step = Step.Paired(info.hostname)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    step = Step.Enter(text, e.message ?: "Pairing failed")
                }
            }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Label("SUPER DESKTOP", c.accent, 22, bold = true)
            when (val s = step) {
                is Step.Enter -> {
                    Label("Pair a computer", c.foreground, 16)
                    Label(
                        "On the computer, open SUPER DESKTOP → Settings → Android and show the pairing code, " +
                            "or run `super-desktop connection invite`. Scan its QR with the camera, or paste the " +
                            "superdesktop://pair link or the invitation here.",
                        c.muted,
                        13,
                    )
                    BasicTextField(
                        value = s.text,
                        onValueChange = { step = Step.Enter(it) },
                        textStyle = TextStyle(color = c.foreground, fontFamily = SdTheme.mono, fontSize = 12.sp),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).border(1.dp, c.border).padding(10.dp),
                    )
                    s.error?.let { Label(it, c.red, 13) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button("paste", c.foreground, c.border) {
                            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            clip?.getItemAt(0)?.coerceToText(this@PairingActivity)?.toString()?.let { step = Step.Enter(it) }
                        }
                        Button("connect", c.background, c.accent, filled = true) { pair(s.text) }
                    }
                }
                Step.Connecting -> Label("Reaching the computer…", c.muted, 14)
                is Step.Approve -> {
                    Label("Approve this device on ${s.computer}", c.foreground, 16)
                    Label("Check that the computer shows the same code, then approve it there.", c.muted, 13)
                    Label(s.code.chunked(3).joinToString(" "), c.accent, 40, bold = true)
                    Label("Waiting for approval…", c.muted, 13)
                }
                is Step.Paired -> {
                    Label("Paired with ${s.computer}", c.green, 16)
                    Label("Its harnesses now show on your SUPER DESKTOP page.", c.muted, 13)
                    Button("done", c.background, c.accent, filled = true) { finish() }
                }
            }
        }
    }

    @Composable
    private fun Label(
        text: String,
        color: Color,
        size: Int,
        bold: Boolean = false,
    ) = BasicText(
        text,
        style = TextStyle(color = color, fontFamily = SdTheme.mono, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )

    @Composable
    private fun Button(
        text: String,
        color: Color,
        frame: Color,
        filled: Boolean = false,
        onClick: () -> Unit,
    ) = Box(
        Modifier
            .then(if (filled) Modifier.background(frame) else Modifier.border(1.dp, frame))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) { Label(text, color, 14) }
}
