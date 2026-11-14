package dev.gladimdim.tiling

import kotlin.math.abs

/** A rectangle of the screen, in pixels. */
data class Area(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun moved(
        dx: Int,
        dy: Int,
    ) = Area(left + dx, top + dy, right + dx, bottom + dy)
}

/** The screen edge the bar sits on. */
enum class Edge {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
    ;

    val vertical get() = this == LEFT || this == RIGHT
}

/** Where the bar is, [thickness] deep along its edge. */
fun barArea(
    width: Int,
    height: Int,
    edge: Edge,
    thickness: Int,
): Area =
    when (edge) {
        Edge.TOP -> Area(0, 0, width, thickness)
        Edge.BOTTOM -> Area(0, height - thickness, width, height)
        Edge.LEFT -> Area(0, 0, thickness, height)
        Edge.RIGHT -> Area(width - thickness, 0, width, height)
    }

/** What is left for windows: the screen without the bar, [gap] in from every side. */
fun workArea(
    width: Int,
    height: Int,
    edge: Edge,
    thickness: Int,
    gap: Int,
): Area {
    val left = if (edge == Edge.LEFT) thickness else 0
    val top = if (edge == Edge.TOP) thickness else 0
    val right = if (edge == Edge.RIGHT) width - thickness else width
    val bottom = if (edge == Edge.BOTTOM) height - thickness else height
    return Area(left + gap, top + gap, right - gap, bottom - gap)
}

/**
 * How one window divides what is left with the windows after it: the share it takes, and whether the side the split
 * runs along is turned from the one the space would pick (Hyprland's togglesplit).
 */
class Split(
    var ratio: Float = 0.5f,
    var turned: Boolean = false,
)

/** One step of the dwindle: the window's box, what is left for the others, and how it was cut. */
private class Cut(
    val box: Area,
    val rest: Area,
    /** Side by side (cut by a vertical line), not one above the other. */
    val sideBySide: Boolean,
    /** The room along the cut's axis that the ratio divides. */
    val room: Int,
)

private fun cut(
    rest: Area,
    split: Split,
    gap: Int,
): Cut {
    val sideBySide = (rest.width >= rest.height) != split.turned
    val ratio = split.ratio.coerceIn(MIN_RATIO, MAX_RATIO)
    return if (sideBySide) {
        val room = rest.width - gap
        val w = (room * ratio).toInt()
        Cut(Area(rest.left, rest.top, rest.left + w, rest.bottom), Area(rest.left + w + gap, rest.top, rest.right, rest.bottom), true, room)
    } else {
        val room = rest.height - gap
        val h = (room * ratio).toInt()
        Cut(Area(rest.left, rest.top, rest.right, rest.top + h), Area(rest.left, rest.top + h + gap, rest.right, rest.bottom), false, room)
    }
}

const val MIN_RATIO = 0.1f
const val MAX_RATIO = 0.9f

/**
 * Hyprland's dwindle layout, by order: each window takes its share of what is left (half, unless resized), cut across
 * the longer side (unless turned), and the last one takes the rest. [gap] is kept between neighbours.
 */
fun dwindle(
    area: Area,
    splits: List<Split>,
    gap: Int,
): List<Area> {
    val out = ArrayList<Area>(splits.size)
    var rest = area
    for (i in splits.indices) {
        if (i == splits.lastIndex) {
            out += rest
        } else {
            val cut = cut(rest, splits[i], gap)
            out += cut.box
            rest = cut.rest
        }
    }
    return out
}

/** With every window taking half. */
fun dwindle(
    area: Area,
    count: Int,
    gap: Int,
): List<Area> = dwindle(area, List(count) { Split() }, gap)

/**
 * Makes the window at [index] wider by [dx] and taller by [dy] pixels (narrower and shorter when negative) by moving
 * the cut that sets that size: its own, when it is cut that way from the windows after it, else the nearest cut
 * before it that is, which it then lies on the far side of. True when a cut moved.
 */
fun resize(
    area: Area,
    splits: List<Split>,
    gap: Int,
    index: Int,
    dx: Int,
    dy: Int,
): Boolean {
    if (index !in splits.indices) return false
    val cuts = ArrayList<Cut>()
    var rest = area
    for (i in 0 until splits.lastIndex) {
        val cut = cut(rest, splits[i], gap)
        cuts += cut
        rest = cut.rest
    }
    var moved = false
    for ((delta, sideBySide) in listOf(dx to true, dy to false)) {
        if (delta == 0) continue
        // Its own cut gives it the first share; one before it gives it (part of) the rest.
        val own = cuts.getOrNull(index)?.takeIf { it.sideBySide == sideBySide }
        val at = if (own != null) index else (index - 1 downTo 0).firstOrNull { cuts[it].sideBySide == sideBySide } ?: continue
        val room = cuts[at].room.takeIf { it > 0 } ?: continue
        val change = delta.toFloat() / room * if (own != null) 1 else -1
        val to = (splits[at].ratio + change).coerceIn(MIN_RATIO, MAX_RATIO)
        if (to != splits[at].ratio) {
            splits[at].ratio = to
            moved = true
        }
    }
    return moved
}

/** Turns the cut between the window at [index] and its neighbours: side by side becomes one above the other. */
fun turnSplit(
    splits: List<Split>,
    index: Int,
): Boolean {
    if (splits.size < 2 || index !in splits.indices) return false
    // The last window has no cut of its own: the one before it made its place.
    val at = if (index < splits.lastIndex) index else splits.lastIndex - 1
    splits[at].turned = !splits[at].turned
    return true
}

/** The window next to [from] towards ([dx], [dy]): the nearest one that lies that way, preferring one alongside it. */
fun neighbour(
    boxes: List<Area>,
    from: Int,
    dx: Int,
    dy: Int,
): Int? {
    val a = boxes.getOrNull(from) ?: return null
    var best: Int? = null
    var bestScore = Int.MAX_VALUE
    for ((i, b) in boxes.withIndex()) {
        if (i == from) continue
        // How far past this window's side the other one starts: it has to lie wholly that way.
        val along =
            when {
                dx > 0 -> b.left - a.right
                dx < 0 -> a.left - b.right
                dy > 0 -> b.top - a.bottom
                else -> a.top - b.bottom
            }
        if (along < 0) continue
        // By their top or left sides, so that of two windows stacked beside this one the upper (or left) one is next.
        val across = if (dx != 0) abs(b.top - a.top) else abs(b.left - a.left)
        // Sharing a side counts for more than being close: the one straight across wins over a nearer diagonal one.
        val overlaps = if (dx != 0) b.top < a.bottom && b.bottom > a.top else b.left < a.right && b.right > a.left
        val score = along + across + if (overlaps) 0 else 100_000
        if (score < bestScore) {
            bestScore = score
            best = i
        }
    }
    return best
}

/** The screen edge closest to a point: where a dragged bar lands. */
fun nearestEdge(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
): Edge {
    val distances = listOf(Edge.TOP to y, Edge.BOTTOM to height - y, Edge.LEFT to x, Edge.RIGHT to width - x)
    return distances.minBy { it.second }.first
}
