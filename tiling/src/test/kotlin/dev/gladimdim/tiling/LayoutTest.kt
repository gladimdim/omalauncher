package dev.gladimdim.tiling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutTest {
    private val screen = Area(0, 0, 1000, 600)

    @Test
    fun oneWindowFillsTheArea() {
        assertEquals(listOf(screen), dwindle(screen, 1, 10))
    }

    @Test
    fun windowsSplitTheLongerSideAndKeepTheGap() {
        val boxes = dwindle(screen, 3, 10)
        assertEquals(Area(0, 0, 495, 600), boxes[0])
        // What is left is taller than wide, so it splits top and bottom.
        assertEquals(Area(505, 0, 1000, 295), boxes[1])
        assertEquals(Area(505, 305, 1000, 600), boxes[2])
    }

    @Test
    fun noWindowsNoBoxes() {
        assertEquals(emptyList<Area>(), dwindle(screen, 0, 10))
    }

    @Test
    fun focusMovesToTheWindowAlongside() {
        val boxes = dwindle(screen, 3, 10)
        assertEquals(1, neighbour(boxes, 0, 1, 0))
        assertEquals(2, neighbour(boxes, 1, 0, 1))
        assertEquals(0, neighbour(boxes, 2, -1, 0))
        assertEquals(1, neighbour(boxes, 2, 0, -1))
        assertNull(neighbour(boxes, 0, -1, 0))
        assertNull(neighbour(boxes, 0, 0, 1))
    }

    @Test
    fun theBarTakesItsEdgeFromTheWorkArea() {
        assertEquals(Area(8, 38, 992, 592), workArea(1000, 600, Edge.TOP, 30, 8))
        assertEquals(Area(38, 8, 992, 592), workArea(1000, 600, Edge.LEFT, 30, 8))
        assertEquals(Area(970, 0, 1000, 600), barArea(1000, 600, Edge.RIGHT, 30))
        assertEquals(Area(0, 570, 1000, 600), barArea(1000, 600, Edge.BOTTOM, 30))
    }

    @Test
    fun aDraggedBarLandsOnTheNearestEdge() {
        assertEquals(Edge.TOP, nearestEdge(500f, 20f, 1000, 600))
        assertEquals(Edge.BOTTOM, nearestEdge(500f, 590f, 1000, 600))
        assertEquals(Edge.LEFT, nearestEdge(10f, 300f, 1000, 600))
        assertEquals(Edge.RIGHT, nearestEdge(980f, 300f, 1000, 600))
    }
}
