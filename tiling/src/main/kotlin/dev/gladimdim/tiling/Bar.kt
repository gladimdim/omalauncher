package dev.gladimdim.tiling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The bar, as Omarchy's waybar lays it out: workspaces at the start, the clock in the middle, the device's state at
 * the end. Dragging it with the mouse moves it to the nearest screen edge, and a right click to the next one; on a side
 * edge it stands upright.
 */
@Composable
internal fun Bar(host: TilingHost) {
    val colors = host.colors
    val vertical = Tiling.edge.vertical
    val view = LocalView.current
    val text = TextStyle(fontFamily = host.font, fontSize = 13.sp, color = colors.foreground, textAlign = TextAlign.Center)
    // Reads the windows' state, so the workspace marks follow it.
    val version = Tiling.version
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.92f))
            .pointerInput(Unit) {
                val origin = IntArray(2)
                detectDragGestures(
                    onDrag = { change, _ ->
                        view.getLocationInWindow(origin)
                        Tiling.barDragged(origin[0] + change.position.x, origin[1] + change.position.y)
                    },
                    onDragEnd = { Tiling.barDropped(cancelled = false) },
                    onDragCancel = { Tiling.barDropped(cancelled = true) },
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) Tiling.barToNextEdge()
                    }
                }
            },
    ) {
        val workspaces: @Composable () -> Unit = {
            // The start button, with Omarchy's mark as on its own bar: home's list of every app.
            Canvas(Modifier.clickable { Tiling.startMenu() }.padding(horizontal = 9.dp, vertical = 7.dp).size(15.dp)) {
                drawPath(omarchyMark(size.minDimension), if (Tiling.homeRaised) colors.accent else colors.foreground)
            }
            for (i in 0 until Tiling.WORKSPACES) {
                val here = i == Tiling.current
                val used = version >= 0 && Tiling.workspaces[i].isNotEmpty()
                // The first five always, as on Omarchy's bar; a later one while it has windows or is where we are.
                if (i >= Tiling.ALWAYS_SHOWN && !here && !used) continue
                BasicText(
                    (i + 1).toString(),
                    Modifier
                        .clickable { Tiling.switchTo(i) }
                        .then(if (here) Modifier.background(colors.accent.copy(alpha = 0.22f)) else Modifier)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    style =
                        text.copy(
                            color = if (here) colors.accent else if (used) colors.foreground else colors.muted,
                            fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                        ),
                )
            }
        }
        val scratchpad: @Composable () -> Unit = {
            // The scratchpad, while there is something on it.
            if (version >= 0 && Tiling.workspaces[Tiling.SCRATCH].isNotEmpty()) {
                BasicText(
                    "s",
                    Modifier
                        .clickable { Tiling.toggleScratch() }
                        .then(if (Tiling.scratchShown) Modifier.background(colors.accent.copy(alpha = 0.22f)) else Modifier)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    style = text.copy(color = if (Tiling.scratchShown) colors.accent else colors.foreground),
                )
            }
        }
        val status: @Composable () -> Unit = {
            Icon(colors.foreground, Modifier.clickable { Tiling.notifications() }) { bell(it) }
            Icon(if (BarStatus.bluetooth) colors.foreground else colors.muted, Modifier.clickable { Tiling.openSettings("Bluetooth", "android.settings.BLUETOOTH_SETTINGS") }) { bluetooth(it) }
            Icon(if (BarStatus.wifi) colors.foreground else colors.muted, Modifier.clickable { Tiling.openSettings("Wi-Fi", "android.settings.WIFI_SETTINGS") }) { wifi(it) }
            Icon(colors.foreground, Modifier.clickable { Tiling.settings() }) { gear(it) }
            BasicText(
                "?",
                Modifier.clickable { Tiling.toggleHelp() }.padding(horizontal = 7.dp, vertical = 4.dp),
                style = text.copy(color = if (Tiling.help) colors.accent else colors.muted),
            )
            if (BarStatus.battery >= 0) {
                BasicText(
                    if (vertical) BarStatus.battery.toString() else "${BarStatus.battery}%",
                    Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    style = text.copy(color = if (BarStatus.charging) colors.accent else colors.foreground),
                )
            }
        }
        if (vertical) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                workspaces()
                scratchpad()
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(BarStatus.hours, style = text)
                BasicText(BarStatus.minutes, style = text)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { status() }
        } else {
            Row(Modifier.align(Alignment.CenterStart).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                workspaces()
                scratchpad()
            }
            BasicText(BarStatus.time, Modifier.align(Alignment.Center), style = text)
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) { status() }
        }
    }
}

/** A line icon on a 24 unit grid, drawn by [draw] into a path. */
@Composable
private fun Icon(
    color: Color,
    modifier: Modifier,
    draw: Path.(Float) -> Unit,
) {
    Canvas(modifier.padding(horizontal = 7.dp, vertical = 6.dp).size(16.dp)) {
        val unit = size.minDimension / 24f
        val path = Path().apply { draw(unit) }
        drawPath(path, color, style = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun Path.wifi(u: Float) {
    // Three arcs over a dot.
    for ((i, r) in listOf(17f, 11f, 5f).withIndex()) {
        val y = 20f - i * 0.5f
        arcTo(androidx.compose.ui.geometry.Rect(center = Offset(12f * u, y * u), radius = r * u), 225f, 90f, forceMoveTo = true)
    }
    moveTo(12f * u, 19.5f * u)
    lineTo(12f * u, 20f * u)
}

private fun Path.bluetooth(u: Float) {
    moveTo(6.5f * u, 7.5f * u)
    lineTo(17f * u, 16.5f * u)
    lineTo(12f * u, 21.5f * u)
    lineTo(12f * u, 2.5f * u)
    lineTo(17f * u, 7.5f * u)
    lineTo(6.5f * u, 16.5f * u)
}

private fun Path.bell(u: Float) {
    moveTo(5f * u, 17.5f * u)
    lineTo(19f * u, 17.5f * u)
    lineTo(17.5f * u, 15f * u)
    lineTo(17.5f * u, 10f * u)
    cubicTo(17.5f * u, 2.5f * u, 6.5f * u, 2.5f * u, 6.5f * u, 10f * u)
    lineTo(6.5f * u, 15f * u)
    close()
    moveTo(10.5f * u, 21f * u)
    lineTo(13.5f * u, 21f * u)
}

private fun Path.gear(u: Float) {
    // A hub, a rim and eight teeth.
    addOval(androidx.compose.ui.geometry.Rect(center = Offset(12f * u, 12f * u), radius = 3f * u))
    addOval(androidx.compose.ui.geometry.Rect(center = Offset(12f * u, 12f * u), radius = 7f * u))
    for (i in 0 until 8) {
        val angle = Math.PI * i / 4
        val cos = kotlin.math.cos(angle).toFloat()
        val sin = kotlin.math.sin(angle).toFloat()
        moveTo((12f + 7f * cos) * u, (12f + 7f * sin) * u)
        lineTo((12f + 10f * cos) * u, (12f + 10f * sin) * u)
    }
}

/**
 * Omarchy's mark, the glyph U+E900 of its icon font (MIT, Copyright (c) David Heinemeier Hansson), as that font's
 * outline: only moves and horizontal and vertical lines, on a 1024 grid with y upwards.
 */
private const val OMARCHY_MARK =
    "M70 70H549V0H0V1024H1024V0H626V70H954V954H70ZM884 140H140V884H551V814H210V210H814V814H736V884H884Z" +
        "M471 140H549V70H471ZM473 954H551V884H473ZM0 551H210V473H0Z"

private fun omarchyMark(size: Float): Path {
    val path = Path()
    val scale = size / 1024f
    var x = 0f
    var y = 0f
    for (step in Regex("[MHVZ][^MHVZ]*").findAll(OMARCHY_MARK)) {
        val numbers = step.value.drop(1).trim().split(' ').filter { it.isNotEmpty() }.map { it.toFloat() }
        when (step.value[0]) {
            'M' -> {
                x = numbers[0]
                y = numbers[1]
                path.moveTo(x * scale, (1024f - y) * scale)
            }
            'H' -> {
                x = numbers[0]
                path.lineTo(x * scale, (1024f - y) * scale)
            }
            'V' -> {
                y = numbers[0]
                path.lineTo(x * scale, (1024f - y) * scale)
            }
            else -> path.close()
        }
    }
    return path
}
