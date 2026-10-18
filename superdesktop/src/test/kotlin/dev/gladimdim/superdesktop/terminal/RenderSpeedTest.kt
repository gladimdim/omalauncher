package dev.gladimdim.superdesktop.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/** A busy agent's frame: 300 colored lines, mostly unchanged from the last one. Guards against the line cache breaking. */
class RenderSpeedTest {
    private val palette = List(16) { Color(it * 10, it * 10, it * 10) }

    private fun frame(n: Int) =
        (0 until 300).joinToString("\n") { i ->
            "\u001b[38;5;${(i + n) % 256}m${i.toString().padStart(4)} \u001b[1m+\u001b[22m val x$i = render(\"line $i\") \u001b[48;2;30;60;30m // ${"·".repeat(40)}\u001b[0m"
        }

    @Test fun cachedFramesRenderFast() {
        val r = AnsiRenderer(palette, Color.White, Color.Black)
        val first = frame(0)
        val cold = time { r.render(first) }
        // The next frames change one line, as a streaming agent's do.
        val lines = first.split('\n').toMutableList()
        var warm = 0L
        repeat(20) { k ->
            lines[299] = "\u001b[32mstreaming $k\u001b[0m"
            val next = lines.joinToString("\n")
            warm += time { r.render(next) }
        }
        val perFrame = warm / 20
        println("ANSI render: cold ${cold / 1000} µs, cached ${perFrame / 1000} µs per frame")
        assertTrue("cached frames should be much cheaper than the first ($perFrame ns vs $cold ns)", perFrame < cold)
    }

    private inline fun time(block: () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return System.nanoTime() - t
    }
}
