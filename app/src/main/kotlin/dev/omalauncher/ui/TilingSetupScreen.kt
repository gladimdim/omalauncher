package dev.omalauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.gladimdim.tiling.Tiling
import dev.gladimdim.tiling.TilingKeyService
import dev.gladimdim.tiling.adb.LocalAdbHelper
import dev.gladimdim.tiling.adb.LocalAdbHelper.Problem
import dev.gladimdim.tiling.adb.LocalAdbHelper.Step
import dev.omalauncher.LocalNetwork
import dev.omalauncher.R
import dev.omalauncher.TilingSetupService
import kotlinx.coroutines.delay

// Fork: the tiling desktop's setup.

/** What stands between a fresh install and the desktop, in the order it is done. */
private enum class SetupStep(val title: Int) {
    Notifications(R.string.tiling_setup_notifications),
    LocalNetwork(R.string.tiling_setup_local_network),
    Developer(R.string.tiling_setup_developer),
    Debugging(R.string.tiling_setup_debugging),
    Pair(R.string.tiling_setup_pair),
    Helper(R.string.tiling_setup_helper),
    Keys(R.string.tiling_setup_keys),
}

/**
 * Gets the tiling desktop's helper running from the phone alone. The helper needs the shell user's rights, which an
 * app only comes by through Android's Wireless Debugging: the steps lead there, the first that is still open with
 * what to do, and each is ticked off as Android reports it done. The pairing code itself goes into a notification
 * ([TilingSetupService]); everything after it happens by itself.
 */
@Composable
fun TilingSetupScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notificationManager = remember { context.getSystemService(NotificationManager::class.java) }
    // Android's settings are read again every second and on coming back: nothing tells an app that they changed.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        LocalAdbHelper.init(context)
        while (true) {
            delay(1_000)
            tick++
        }
    }

    val helper = Tiling.helperConnected
    val keys = TilingKeyService.connected
    val paired = LocalAdbHelper.paired
    val notifications = remember(tick) { notificationManager.areNotificationsEnabled() }
    val localNetwork = remember(tick) { !LocalNetwork.missing(context) }
    val developer = remember(tick) { LocalAdbHelper.developerOptionsOn(context) }
    val debugging = remember(tick) { LocalAdbHelper.debuggingOn(context) }
    val maySwitch = remember(tick) { LocalAdbHelper.maySwitchDebugging(context) }

    // A step that was never open is left out: asking for nothing is not a step.
    val askedNotifications = remember { !notifications }
    val askedLocalNetwork = remember { !localNetwork }
    val done: (SetupStep) -> Boolean = { step ->
        when (step) {
            // Only the pairing code needs a notification.
            SetupStep.Notifications -> notifications || paired || helper
            SetupStep.LocalNetwork -> localNetwork || helper
            SetupStep.Developer -> developer || paired || helper
            // Once the launcher may switch it on itself, it being off is no longer the user's to mend.
            SetupStep.Debugging -> debugging || helper || (paired && maySwitch)
            SetupStep.Pair -> paired || helper
            SetupStep.Helper -> helper
            SetupStep.Keys -> keys
        }
    }
    val steps =
        SetupStep.entries.filter {
            when (it) {
                SetupStep.Notifications -> askedNotifications
                SetupStep.LocalNetwork -> askedLocalNetwork
                else -> true
            }
        }
    val current = steps.firstOrNull { !done(it) }

    // The code is waited for from the moment a pairing dialog can exist, so switching Wireless Debugging on and
    // pairing are one visit to Android's settings.
    val wantCode = Tiling.supported && !helper && !paired && notifications && localNetwork && developer
    LaunchedEffect(wantCode) { if (wantCode) TilingSetupService.start(context) else TilingSetupService.stop(context) }
    LifecycleResumeEffect(wantCode) {
        tick++
        // Again after it gave up waiting.
        if (wantCode) TilingSetupService.start(context)
        onPauseOrDispose {}
    }
    DisposableEffect(Unit) { onDispose { TilingSetupService.stop(context) } }

    // Paired on an earlier day (a restart since, or a new build of the app): the helper only has to be started.
    LaunchedEffect(paired, helper, debugging, maySwitch, localNetwork) {
        if (Tiling.supported && paired && !helper && localNetwork && (debugging || maySwitch)) LocalAdbHelper.startHelper(context)
    }

    // The helper switches the shortcuts on itself; Android's own switch is only shown when that has not worked.
    var keysByHand by remember { mutableStateOf(false) }
    LaunchedEffect(helper, keys) {
        keysByHand = false
        if (helper && !keys) {
            delay(10_000)
            keysByHand = true
        }
    }

    @SuppressLint("InlinedApi")
    val askNotifications =
        rememberAskPermission(
            Manifest.permission.POST_NOTIFICATIONS,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) { tick++ }
    val askLocalNetwork = rememberAskPermission(LocalNetwork.PERMISSION) { tick++ }

    OverlayPanel(stringResource(R.string.tiling_setup_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!Tiling.supported) {
                Note(stringResource(R.string.tiling_setup_unsupported), Palette.accent)
                return@Column
            }
            Note(stringResource(R.string.tiling_setup_intro), Palette.muted)
            steps.forEachIndexed { index, step ->
                val isDone = done(step)
                Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(
                        "${index + 1}. ${stringResource(step.title)}" + if (isDone) "  ✓" else "",
                        style =
                            Mono.copy(
                                color =
                                    when {
                                        isDone -> Palette.green
                                        step == current -> Palette.foreground
                                        else -> Palette.muted
                                    },
                                fontSize = TextSize.Item,
                            ),
                    )
                    if (step == current) {
                        when (step) {
                            SetupStep.Notifications -> {
                                Note(stringResource(R.string.tiling_setup_notifications_text), Palette.muted)
                                Actions {
                                    BracketAction(stringResource(R.string.tiling_setup_allow), Palette.accent) {
                                        // Before Android 13 there is no dialog, only the launcher's notification page.
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications() else openNotificationSettings(context)
                                    }
                                }
                            }
                            SetupStep.LocalNetwork -> {
                                Note(stringResource(R.string.tiling_setup_local_network_text), Palette.muted)
                                Actions { BracketAction(stringResource(R.string.tiling_setup_allow), Palette.accent, onClick = askLocalNetwork) }
                            }
                            SetupStep.Developer -> {
                                Note(stringResource(R.string.tiling_setup_developer_text), Palette.muted)
                                Actions { BracketAction(stringResource(R.string.tiling_setup_about_phone), Palette.accent) { openAboutPhone(context) } }
                            }
                            SetupStep.Debugging -> {
                                Note(stringResource(if (paired) R.string.tiling_setup_debugging_again_text else R.string.tiling_setup_debugging_text), Palette.muted)
                                Actions { BracketAction(stringResource(R.string.tiling_setup_open_developer), Palette.accent) { openWirelessDebugging(context) } }
                            }
                            SetupStep.Pair -> {
                                Note(stringResource(R.string.tiling_setup_pair_text), Palette.muted)
                                val (status, color) = pairStatus()
                                Note(stringResource(status), color)
                                Actions { BracketAction(stringResource(R.string.tiling_setup_open_developer), Palette.accent) { openWirelessDebugging(context) } }
                            }
                            SetupStep.Helper -> {
                                val problem = LocalAdbHelper.problem
                                when {
                                    LocalAdbHelper.step == Step.Starting -> Note(stringResource(R.string.tiling_setup_helper_starting), Palette.accent)
                                    problem != null -> Note(stringResource(helperProblem(problem)), Palette.red)
                                    else -> Note(stringResource(R.string.tiling_setup_helper_starting), Palette.accent)
                                }
                                if (!LocalAdbHelper.busy) {
                                    Actions {
                                        if (!debugging) BracketAction(stringResource(R.string.tiling_setup_open_developer), Palette.muted) { openWirelessDebugging(context) }
                                        BracketAction(stringResource(R.string.tiling_setup_try_again), Palette.accent) { LocalAdbHelper.startHelper(context) }
                                    }
                                }
                            }
                            SetupStep.Keys ->
                                if (keysByHand) {
                                    Note(stringResource(R.string.tiling_setup_keys_manual), Palette.muted)
                                    Actions {
                                        BracketAction(stringResource(R.string.keyboard_app_info), Palette.muted) { Tiling.openAppInfoSettings(context) }
                                        BracketAction(stringResource(R.string.tiling_setup_open_accessibility), Palette.accent) { Tiling.openAccessibilitySettings(context) }
                                    }
                                } else {
                                    Note(stringResource(R.string.tiling_setup_keys_starting), Palette.accent)
                                }
                        }
                    }
                }
            }
            if (current == null) {
                Note(stringResource(if (Tiling.keyboard) R.string.tiling_setup_done_keyboard else R.string.tiling_setup_done), Palette.green)
                // Only where the launcher's own pairing did it: a helper started from a computer changed none of this.
                if (paired) Note(stringResource(if (maySwitch) R.string.tiling_setup_done_restart else R.string.tiling_setup_done_restart_by_hand), Palette.muted)
            }
        }
    }
}

@Composable
private fun Note(
    text: String,
    color: Color,
) = BasicText(text, style = Mono.copy(color = color, fontSize = TextSize.Small))

@Composable
private fun Actions(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

/** Where the pairing stands, as far as the launcher can see it from outside Android's settings. */
private fun pairStatus(): Pair<Int, Color> =
    when {
        LocalAdbHelper.step == Step.Pairing -> R.string.tiling_setup_pairing to Palette.accent
        LocalAdbHelper.problem == Problem.WrongCode -> R.string.tiling_setup_pair_wrong to Palette.red
        LocalAdbHelper.problem == Problem.KeyRejected -> R.string.tiling_setup_pair_again to Palette.red
        LocalAdbHelper.problem == Problem.Failed || LocalAdbHelper.problem == Problem.NoPairingDialog -> R.string.tiling_setup_pair_failed to Palette.red
        LocalAdbHelper.pairingPort != null -> R.string.tiling_setup_pair_found to Palette.accent
        else -> R.string.tiling_setup_pair_waiting to Palette.muted
    }

private fun helperProblem(problem: Problem): Int =
    when (problem) {
        Problem.DebuggingOff -> R.string.tiling_setup_debugging_again_text
        Problem.HelperSilent -> R.string.tiling_setup_helper_silent
        else -> R.string.tiling_setup_helper_failed
    }

private fun open(
    context: Context,
    intent: Intent,
): Boolean = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

private fun openNotificationSettings(context: Context) {
    open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

/** Where Build number is, somewhere below: no phone has a way straight to it. */
private fun openAboutPhone(context: Context) {
    if (!open(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))) open(context, Intent(Settings.ACTION_SETTINGS))
}

/**
 * Developer options, scrolled to Wireless debugging where the phone's Settings follows Android's own in that. They
 * do not open at all before Build number was tapped.
 */
private fun openWirelessDebugging(context: Context) {
    val developer = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
    if (!open(context, developer)) open(context, Intent(Settings.ACTION_SETTINGS))
}
