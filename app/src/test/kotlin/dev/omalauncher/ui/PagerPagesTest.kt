package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerPagesTest {
    @Test fun bothAddAnEmptyPageAtEachEnd() {
        assertEquals(listOf(0, 1, 2, 3), pagerPages(listOf(1, 2), lastEmpty = false, emptyPageLeft = true, emptyPageRight = true))
    }

    @Test fun anEmptyLastPageIsTheOneAfter() {
        assertEquals(listOf(0, 1, 2), pagerPages(listOf(1, 2), lastEmpty = true, emptyPageLeft = true, emptyPageRight = true))
    }

    @Test fun theFacePageGoesLeftOfTheLeftmostFace() {
        assertEquals(listOf(-2, -1, 0, 1, 2), pagerPages(listOf(-1, 0, 1), lastEmpty = false, emptyPageLeft = true, emptyPageRight = true))
    }

    @Test fun withoutEitherOnlyHomesOwnPages() {
        assertEquals(listOf(1, 2), pagerPages(listOf(1, 2), lastEmpty = false, emptyPageLeft = false, emptyPageRight = false))
        assertEquals(listOf(0, 1), pagerPages(listOf(0, 1), lastEmpty = false, emptyPageLeft = false, emptyPageRight = false))
    }

    // Carrying a tile with home.empty-page-right off: a page to drop it on after the last, and nothing added before page 1.
    @Test fun carryingAddsOnlyThePageAfterTheLast() {
        assertEquals(listOf(1, 2, 3), pagerPages(listOf(1, 2), lastEmpty = false, emptyPageLeft = false, emptyPageRight = true))
    }

    @Test fun anEmptyHomeKeepsItsOnePage() {
        assertEquals(listOf(1), pagerPages(listOf(1), lastEmpty = true, emptyPageLeft = false, emptyPageRight = false))
    }
}
