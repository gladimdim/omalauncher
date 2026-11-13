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
 * Hyprland's dwindle layout, by order: each window takes half of what is left, split across its longer side, and
 * the last one takes the rest. [gap] is kept between neighbours.
 */
fun dwindle(
    area: Area,
    count: Int,
    gap: Int,
): List<Area> {
    val out = ArrayList<Area>(count)
    var rest = area
    for (i in 0 until count) {
        if (i == count - 1) {
            out += rest
        } else if (rest.width >= rest.height) {
            val w = (rest.width - gap) / 2
            out += Area(rest.left, rest.top, rest.left + w, rest.bottom)
            rest = Area(rest.left + w + gap, rest.top, rest.right, rest.bottom)
        } else {
            val h = (rest.height - gap) / 2
            out += Area(rest.left, rest.top, rest.right, rest.top + h)
            rest = Area(rest.left, rest.top + h + gap, rest.right, rest.bottom)
        }
    }
    return out
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
