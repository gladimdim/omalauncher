package dev.gladimdim.superdesktop.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalAnsiTest {
    private val palette = List(16) { Color(it, it, it) }
    private val fg = Color.White
    private val bg = Color.Black

    @Test fun plainDropsEscapes() {
        assertEquals("ok done", TerminalAnsi.plain("\u001b[1;32mok\u001b[0m done\u001b]0;title\u0007"))
    }

    @Test fun colorsAndInverse() {
        val text = AnsiRenderer(palette, fg, bg).render("\u001b[31mred\u001b[0m \u001b[7mpick\u001b[27m\nnext")
        assertEquals("red pick\nnext", text.text)
        val red = text.spanStyles.single { text.text.substring(it.start, it.end) == "red" }
        assertEquals(palette[1], red.item.color)
        // Inverse with default colors swaps them.
        val pick = text.spanStyles.single { text.text.substring(it.start, it.end) == "pick" }
        assertEquals(bg, pick.item.color)
        assertEquals(fg, pick.item.background)
    }

    @Test fun styleCarriesAcrossLinesAndCachedLinesStillMatch() {
        val r = AnsiRenderer(palette, fg, bg)
        val frame = "\u001b[38;5;196mhot\nstill hot\u001b[0m\ncold"
        val first = r.render(frame)
        val again = r.render(frame)
        assertEquals(first, again)
        val still = again.spanStyles.single { again.text.substring(it.start, it.end) == "still hot" }
        assertEquals(Color(255, 0, 0), still.item.color)
    }
}
