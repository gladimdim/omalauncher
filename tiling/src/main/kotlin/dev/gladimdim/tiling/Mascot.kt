package dev.gladimdim.tiling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One harness on a paired computer, for the bar's mascot and status popover. */
data class TilingHarness(
    val pcName: String,
    val name: String,
    val agentType: String,
    val status: String,
    val live: Boolean,
)

/** Live summary of paired computers and their harnesses. */
data class MascotStatus(
    val harnesses: List<TilingHarness> = emptyList(),
    val liveComputers: Int = 0,
    val totalComputers: Int = 0,
) {
    val working: Int get() = harnesses.count { it.status == "WORKING" }
    val waiting: Int get() = harnesses.count { it.status == "WAITING" }
    val error: Int get() = harnesses.count { it.status == "ERROR" }
    val finished: Int get() = harnesses.count { it.status == "FINISHED" }

    val mood: Mood
        get() =
            when {
                error > 0 -> Mood.ERROR
                waiting > 0 -> Mood.WAITING
                working > 0 -> Mood.WORKING
                liveComputers > 0 -> Mood.IDLE
                else -> Mood.OFFLINE
            }

    enum class Mood { WORKING, WAITING, ERROR, IDLE, OFFLINE }
}

private val ACID_GREEN = Color(0xFF39FF14)
private val STATUS_YELLOW = Color(0xFFE0AF68)
private val STATUS_RED = Color(0xFFF7768E)

private fun moodColor(
    mood: MascotStatus.Mood,
    colors: TilingColors,
): Color =
    when (mood) {
        MascotStatus.Mood.WORKING -> ACID_GREEN
        MascotStatus.Mood.WAITING -> STATUS_YELLOW
        MascotStatus.Mood.ERROR -> STATUS_RED
        MascotStatus.Mood.IDLE -> colors.foreground
        MascotStatus.Mood.OFFLINE -> colors.muted
    }

private fun harnessColor(
    status: String,
    colors: TilingColors,
): Color =
    when (status) {
        "WORKING" -> ACID_GREEN
        "WAITING" -> STATUS_YELLOW
        "ERROR" -> STATUS_RED
        "FINISHED" -> colors.accent
        else -> colors.muted
    }

/** Compact thunderbolt mascot + active harness count for the tiling bar. */
@Composable
internal fun MascotBadge(
    host: TilingHost,
    vertical: Boolean,
) {
    val colors = host.colors
    val status = host.mascotStatus
    val tint = moodColor(status.mood, colors)
    val count =
        when {
            status.error > 0 -> status.error
            status.waiting > 0 -> status.waiting
            status.working > 0 -> status.working
            else -> 0
        }
    val text = TextStyle(fontFamily = host.font, fontSize = 12.sp, color = tint, fontWeight = FontWeight.Bold)
    val mod =
        Modifier
            .clickable { Tiling.toggleMascot() }
            .then(if (Tiling.mascotOpen) Modifier.background(colors.accent.copy(alpha = 0.18f)) else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp)

    if (vertical) {
        Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
            MascotIcon(tint, status.mood, Modifier.size(16.dp))
            if (count > 0) BasicText(count.toString(), style = text)
        }
    } else {
        Row(mod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MascotIcon(tint, status.mood, Modifier.size(16.dp))
            if (count > 0) BasicText(count.toString(), style = text)
        }
    }
}

/** Crisp vector silhouette of the SUPER DESKTOP thunderbolt mascot with mood-driven eyes. */
@Composable
private fun MascotIcon(
    tint: Color,
    mood: MascotStatus.Mood,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bolt =
            Path().apply {
                moveTo(w * 0.62f, h * 0.04f)
                lineTo(w * 0.14f, h * 0.56f)
                lineTo(w * 0.46f, h * 0.56f)
                lineTo(w * 0.34f, h * 0.96f)
                lineTo(w * 0.86f, h * 0.44f)
                lineTo(w * 0.54f, h * 0.44f)
                close()
            }
        drawPath(bolt, tint.copy(alpha = 0.22f))
        drawPath(bolt, tint, style = Stroke(width = w * 0.09f))

        // Eyes inside the upper torso of the bolt.
        val eyeY = h * 0.38f
        val leftX = w * 0.42f
        val rightX = w * 0.58f
        val eyeR = w * 0.055f
        val stroke = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
        when (mood) {
            MascotStatus.Mood.WORKING -> {
                // Winking: o -
                drawCircle(tint, radius = eyeR, center = Offset(leftX, eyeY))
                drawLine(tint, Offset(rightX - eyeR, eyeY), Offset(rightX + eyeR, eyeY), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            MascotStatus.Mood.WAITING -> {
                // Wide awake: o o
                drawCircle(tint, radius = eyeR, center = Offset(leftX, eyeY))
                drawCircle(tint, radius = eyeR, center = Offset(rightX, eyeY))
            }
            MascotStatus.Mood.ERROR -> {
                // Alert: x x
                for (cx in floatArrayOf(leftX, rightX)) {
                    drawLine(tint, Offset(cx - eyeR, eyeY - eyeR), Offset(cx + eyeR, eyeY + eyeR), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(tint, Offset(cx - eyeR, eyeY + eyeR), Offset(cx + eyeR, eyeY - eyeR), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            MascotStatus.Mood.IDLE, MascotStatus.Mood.OFFLINE -> {
                // Calm/sleeping: - -
                drawLine(tint, Offset(leftX - eyeR, eyeY), Offset(leftX + eyeR, eyeY), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(rightX - eyeR, eyeY), Offset(rightX + eyeR, eyeY), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

private fun asciiMascot(mood: MascotStatus.Mood): String {
    val eyes =
        when (mood) {
            MascotStatus.Mood.WORKING -> "    / o - /"
            MascotStatus.Mood.WAITING -> "    / o o /"
            MascotStatus.Mood.ERROR -> "    / x x /"
            MascotStatus.Mood.IDLE, MascotStatus.Mood.OFFLINE -> "    / - - /"
        }
    return listOf(
        "      /======/",
        "     /======/",
        eyes,
        "   /  \\_//",
        "  /       /___",
        " /===========/",
        " '----/=====/",
        "     /====/",
        "    /===/",
        "   /==/",
        "  /_/",
    ).joinToString("\n")
}

/** Popover card showing the ASCII thunderbolt mascot and live harness statuses. */
@Composable
internal fun MascotPanel(host: TilingHost) {
    if (!Tiling.mascotOpen) return
    val colors = host.colors
    val status = host.mascotStatus
    val tint = moodColor(status.mood, colors)
    val text = TextStyle(fontFamily = host.font, fontSize = 12.sp, color = colors.foreground)
    val quiet = text.copy(color = colors.muted)

    Row(
        Modifier
            .widthIn(min = 320.dp, max = 560.dp)
            .background(colors.background.copy(alpha = 0.94f))
            .border(2.dp, colors.border)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Tiling.toggleMascot() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            asciiMascot(status.mood),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 11.sp, color = tint, fontWeight = FontWeight.Bold),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val summary =
                when {
                    status.totalComputers == 0 -> "no paired computers"
                    status.liveComputers == 0 -> "offline (${status.totalComputers} paired)"
                    status.harnesses.isEmpty() -> "live · no active harnesses"
                    else ->
                        listOfNotNull(
                            status.working.takeIf { it > 0 }?.let { "$it working" },
                            status.waiting.takeIf { it > 0 }?.let { "$it waiting" },
                            status.error.takeIf { it > 0 }?.let { "$it error" },
                            status.finished.takeIf { it > 0 }?.let { "$it done" },
                        ).ifEmpty { listOf("${status.harnesses.size} idle") }.joinToString(" · ")
                }
            BasicText("SUPER DESKTOP", style = text.copy(color = tint, fontWeight = FontWeight.Bold))
            BasicText(summary, style = quiet)
            for (h in status.harnesses.take(8)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(harnessColor(h.status, colors), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        "${h.pcName} · ${h.name}",
                        Modifier.weight(1f),
                        style = text.copy(color = if (h.live) colors.foreground else colors.muted),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(h.status.lowercase(), style = text.copy(color = harnessColor(h.status, colors)))
                }
            }
            if (status.harnesses.size > 8) {
                BasicText("+${status.harnesses.size - 8} more", style = quiet)
            }
        }
    }
}
