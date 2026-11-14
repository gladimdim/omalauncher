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
    fun resizingMovesTheWindowsOwnCut() {
        val splits = List(3) { Split() }
        // The first window is cut side by side from the rest: 100 wider out of 990 of room.
        assertEquals(true, resize(screen, splits, 10, 0, 100, 0))
        val boxes = dwindle(screen, splits, 10)
        assertEquals(Area(0, 0, 595, 600), boxes[0])
        assertEquals(605, boxes[1].left)
        // It has no cut above or below it: nothing to make it taller with.
        assertEquals(false, resize(screen, splits, 10, 0, 0, 50))
    }

    @Test
    fun resizingAWindowOnTheFarSideMovesTheCutBeforeIt() {
        val splits = List(3) { Split() }
        // The last window's width is set by the first one's cut: wider means that cut moves left.
        assertEquals(true, resize(screen, splits, 10, 2, 99, 0))
        val boxes = dwindle(screen, splits, 10)
        assertEquals(396, boxes[0].right)
        assertEquals(406, boxes[2].left)
        // And its height by the second one's, which it lies below: taller means that one gets shorter.
        assertEquals(true, resize(screen, splits, 10, 2, 0, 59))
        assertEquals(236, dwindle(screen, splits, 10)[1].bottom)
    }

    @Test
    fun aCutStopsBeforeItSqueezesAWindowOut() {
        val splits = List(2) { Split() }
        resize(screen, splits, 10, 0, 5000, 0)
        assertEquals(MAX_RATIO, splits[0].ratio)
        assertEquals(false, resize(screen, splits, 10, 0, 100, 0))
    }

    @Test
    fun turningASplitStacksWhatWasSideBySide() {
        val splits = List(2) { Split() }
        assertEquals(true, turnSplit(splits, 1))
        val boxes = dwindle(screen, splits, 10)
        assertEquals(Area(0, 0, 1000, 295), boxes[0])
        assertEquals(Area(0, 305, 1000, 600), boxes[1])
        assertEquals(false, turnSplit(List(1) { Split() }, 0))
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
