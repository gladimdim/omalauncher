package dev.omalauncher.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gladimdim.tiling.KeyHotkey
import dev.gladimdim.tiling.Tiling
import dev.gladimdim.tiling.TilingKeyService
import dev.omalauncher.R

/** Keyboard mode & tiling desktop settings: input language hotkey, bar flag, and required permissions. */
@Composable
fun KeyboardSettingsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pickingHotkey by remember { mutableStateOf(false) }

    val currentHotkey = Tiling.languageHotkey
    val hotkeyLabel = currentHotkey?.format() ?: stringResource(R.string.keyboard_language_hotkey_off)

    OverlayPanel(stringResource(R.string.keyboard_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionsRow(
                Modifier.card(),
                actions = {
                    BracketAction(stringResource(R.string.keyboard_language_hotkey_change), Palette.accent) {
                        pickingHotkey = true
                    }
                },
            ) {
                Column {
                    BasicText(stringResource(R.string.keyboard_language_hotkey), style = Mono.copy(fontSize = TextSize.Item))
                    BasicText(hotkeyLabel, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
                }
            }

            SwitchRow(
                title = stringResource(R.string.keyboard_language_bar),
                status = stringResource(R.string.keyboard_language_bar_uses),
                on = Tiling.showLanguageInBar,
                turnOn = { Tiling.setShowLanguageInBar(context, true) },
                turnOff = { Tiling.setShowLanguageInBar(context, false) },
            )

            SwitchRow(
                title = stringResource(R.string.keyboard_auto_desktop),
                status = stringResource(R.string.keyboard_auto_desktop_uses),
                on = Tiling.autoEnableOnKeyboard,
                turnOn = { Tiling.setAutoEnableOnKeyboard(context, true) },
                turnOff = { Tiling.setAutoEnableOnKeyboard(context, false) },
            )

            val accessibilityOn = TilingKeyService.connected
            ActionsRow(
                Modifier.card(),
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BracketAction(stringResource(R.string.keyboard_app_info), Palette.muted) {
                            Tiling.openAppInfoSettings(context)
                        }
                        if (accessibilityOn) {
                            BracketAction(stringResource(R.string.linux_gpu_turn_off), Palette.red) {
                                Tiling.openAccessibilitySettings(context)
                            }
                        } else {
                            BracketAction(stringResource(R.string.linux_gpu_turn_on), Palette.accent) {
                                Tiling.openAccessibilitySettings(context)
                            }
                        }
                    }
                },
            ) {
                Column {
                    BasicText(stringResource(R.string.permissions_accessibility), style = Mono.copy(fontSize = TextSize.Item))
                    BasicText(
                        stringResource(if (accessibilityOn) R.string.permissions_on else R.string.permissions_off, stringResource(R.string.permissions_accessibility_uses)),
                        style = Mono.copy(color = if (accessibilityOn) Palette.green else Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }

            val helperOn = Tiling.helperConnected
            Column(Modifier.card()) {
                BasicText(stringResource(R.string.keyboard_helper_title), style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    stringResource(if (helperOn) R.string.keyboard_helper_running else R.string.keyboard_helper_stopped),
                    style = Mono.copy(color = if (helperOn) Palette.green else Palette.muted, fontSize = TextSize.Small),
                )
            }

            BasicText(stringResource(R.string.keyboard_accessibility_note), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            BasicText(stringResource(R.string.keyboard_conf_note), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }

    if (pickingHotkey) {
        HotkeyPickerOverlay(
            current = currentHotkey,
            onPick = { chosen ->
                Tiling.setLanguageHotkey(context, chosen)
                pickingHotkey = false
            },
            onDismiss = { pickingHotkey = false },
        )
    }
}

@Composable
private fun HotkeyPickerOverlay(
    current: KeyHotkey?,
    onPick: (KeyHotkey?) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    OverlayPanel(
        stringResource(R.string.keyboard_language_hotkey),
        onDismiss,
        contentWidth = ReadingWidth,
    ) {
        Column(
            Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val native = keyEvent.nativeKeyEvent
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_BACK || native.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
                        onDismiss()
                        return@onPreviewKeyEvent true
                    }
                    val captured = KeyHotkey.fromKeyEvent(native) ?: return@onPreviewKeyEvent false
                    onPick(captured)
                    true
                }
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(stringResource(R.string.keyboard_language_hotkey_prompt), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            for ((label, preset) in KeyHotkey.PRESETS) {
                val selected = preset == current
                val display = if (preset == null) stringResource(R.string.keyboard_language_hotkey_off) else label
                ActionsRow(
                    Modifier.card(),
                    actions = {
                        BracketAction(
                            stringResource(R.string.keyboard_language_hotkey_change),
                            if (selected) Palette.green else Palette.accent,
                        ) { onPick(preset) }
                    },
                ) {
                    BasicText(
                        display,
                        style = Mono.copy(color = if (selected) Palette.green else Palette.foreground, fontSize = TextSize.Item),
                    )
                }
            }
        }
    }
}
