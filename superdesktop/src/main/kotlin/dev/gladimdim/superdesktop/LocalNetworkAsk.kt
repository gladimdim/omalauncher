package dev.gladimdim.superdesktop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/**
 * Android 17 drops the launcher's connections to 192.168.x and other local addresses until it holds
 * ACCESS_LOCAL_NETWORK: a PC on the same Wi-Fi then only times out. While a PC is paired and the permission is
 * missing, one tile at a time shows a popup with the button that asks for it.
 */
internal object LocalNetworkAsk {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The tile that shows the popup; the others leave it to that one. */
    var owner by mutableStateOf<Any?>(null)

    /** "Not now" hides the popup until the launcher starts again. */
    var dismissed by mutableStateOf(false)

    fun missing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED
}

/** Asks for local network access; [onResult] gets whether it's granted now. */
@Composable
internal fun rememberLocalNetworkRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onResult(granted)
            // After two refusals Android answers no without asking; the switch is then in the app's own settings.
            if (!granted && context.activity()?.shouldShowRequestPermissionRationale(LocalNetworkAsk.PERMISSION) == false) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    return { request.launch(LocalNetworkAsk.PERMISSION) }
}

@Composable
internal fun LocalNetworkPrompt(host: SdHost) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(LocalNetworkAsk.missing(context)) }
    if (!missing) return
    // Granted from the app's settings, the launcher only learns of it by looking again.
    LaunchedEffect(Unit) {
        while (missing) {
            delay(2_000)
            missing = LocalNetworkAsk.missing(context)
        }
    }
    val me = remember { Any() }
    SideEffect { if (LocalNetworkAsk.owner == null) LocalNetworkAsk.owner = me }
    DisposableEffect(me) { onDispose { if (LocalNetworkAsk.owner === me) LocalNetworkAsk.owner = null } }
    val bridges by SdStore.bridges.collectAsState()
    if (LocalNetworkAsk.owner !== me || LocalNetworkAsk.dismissed || bridges.isNullOrEmpty()) return

    val ask = rememberLocalNetworkRequest { missing = !it }
    val c = host.colors
    Dialog(onDismissRequest = { LocalNetworkAsk.dismissed = true }) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .background(c.background, host.shape(12.dp)).border(1.dp, c.focusBorder, host.shape(12.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Label("Allow local network access", host, c.accent, 15, bold = true, lines = 2)
            Label(
                "Android keeps SD Launcher off your Wi-Fi until you allow it, so PCs on the same network don't answer " +
                    "(\"No answer in time\"). Tailscale addresses still work.",
                host, c.foreground, 12, lines = 6,
            )
            Label(
                "Press the button below and choose Allow. If Android doesn't ask, it opens SD Launcher's settings: " +
                    "turn on local network access under Permissions.",
                host, c.muted, 11, lines = 6,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("allow local network access", host, strong = true, onClick = ask)
                Action("not now", host, color = c.muted) { LocalNetworkAsk.dismissed = true }
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
