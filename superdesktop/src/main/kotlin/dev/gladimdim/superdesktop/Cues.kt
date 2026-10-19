package dev.gladimdim.superdesktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

// What an agent's status says to the person looking: done (a flash and a buzz once), or waiting for them.

internal fun isBusy(status: String) = status == "WORKING" || status == "RUNNING"

internal fun isReady(status: String) = status == "IDLE" || status == "WAITING" || status == "FINISHED"

/** WAITING is the agent asking a question or for a permission. */
internal fun needsYou(status: String) = status == "WAITING"

/** How a status reads on a card: WAITING says what it means. */
internal fun statusLabel(status: String) = if (needsYou(status)) "NEEDS YOU" else status

/**
 * 1 right after [status] goes from working to ready, fading to 0 over 2.4 s, with one short buzz. Statuses seen
 * when the tile appears never count: only a change while it's on screen does.
 */
@Composable
internal fun rememberDoneFlash(
    key: Any,
    status: String,
): Float {
    val haptic = LocalHapticFeedback.current
    var last by remember(key) { mutableStateOf<String?>(null) }
    val flash = remember(key) { Animatable(0f) }
    LaunchedEffect(key, status) {
        val before = last
        last = status
        if (before != null && isBusy(before) && isReady(status)) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            flash.snapTo(1f)
            flash.animateTo(0f, tween(2400))
        }
    }
    return flash.value
}

/** A frame's color: the done flash over waiting's yellow over [normal]. */
internal fun cueBorder(
    status: String,
    flash: Float,
    normal: Color,
    host: SdHost,
): Color {
    val base = if (needsYou(status)) host.colors.yellow else normal
    return if (flash > 0f) lerp(base, host.colors.green, flash) else base
}
