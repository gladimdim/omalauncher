package dev.gladimdim.superdesktop.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

// Ported from the SUPER DESKTOP app's TerminalAnsi.kt (see bridge/SOURCE.md): the same SGR subset
// tmux capture-pane -e produces, drawn as Compose spans in the launcher theme's 16 colors.

/** tmux capture-pane -e styling: colors (16, 256 and true color), bold and inverse. */
internal object TerminalAnsi {
    data class Style(
        val foreground: Color? = null,
        val background: Color? = null,
        val bold: Boolean = false,
        val inverse: Boolean = false,
    )

    data class Run(
        val text: String,
        val style: Style,
    )

    private val escapes = Regex("\u001b\\[[0-?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)")

    /** [value] without escapes: what the terminal shows, for measuring and copying. */
    fun plain(value: String) = value.replace(escapes, "")

    /** Appends [value]'s runs starting from [initial] and returns the style in effect at its end. */
    fun parseInto(
        value: String,
        initial: Style,
        palette: List<Color>,
        runs: MutableList<Run>,
    ): Style {
        var style = initial
        var start = 0
        for (match in escapes.findAll(value)) {
            if (match.range.first > start) runs += Run(value.substring(start, match.range.first), style)
            val escape = match.value
            if (escape.startsWith("\u001b[") && escape.endsWith('m')) {
                val codes = escape.substring(2, escape.length - 1).split(';').map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 }
                var i = 0
                while (i < codes.size) {
                    when (val code = codes[i++]) {
                        0 -> style = Style()
                        1 -> style = style.copy(bold = true)
                        22 -> style = style.copy(bold = false)
                        7 -> style = style.copy(inverse = true)
                        27 -> style = style.copy(inverse = false)
                        39 -> style = style.copy(foreground = null)
                        49 -> style = style.copy(background = null)
                        in 30..37 -> style = style.copy(foreground = palette[code - 30])
                        in 40..47 -> style = style.copy(background = palette[code - 40])
                        in 90..97 -> style = style.copy(foreground = palette[code - 90 + 8])
                        in 100..107 -> style = style.copy(background = palette[code - 100 + 8])
                        38, 48 -> {
                            val color =
                                when (codes.getOrNull(i++)) {
                                    5 -> codes.getOrNull(i++)?.let { indexed(it, palette) }
                                    2 -> {
                                        val rgb = codes.drop(i).take(3)
                                        i += 3
                                        if (rgb.size == 3 && rgb.all { it in 0..255 }) Color(rgb[0], rgb[1], rgb[2]) else null
                                    }
                                    else -> null
                                }
                            if (color != null) style = if (code == 38) style.copy(foreground = color) else style.copy(background = color)
                        }
                    }
                }
            }
            start = match.range.last + 1
        }
        if (start < value.length) runs += Run(value.substring(start), style)
        return style
    }

    private fun indexed(
        index: Int,
        palette: List<Color>,
    ): Color? =
        when (index) {
            in 0..15 -> palette[index]
            in 16..231 -> {
                val n = index - 16
                fun component(v: Int) = if (v == 0) 0 else 55 + v * 40
                Color(component(n / 36), component(n / 6 % 6), component(n % 6))
            }
            in 232..255 -> (8 + (index - 232) * 10).let { Color(it, it, it) }
            else -> null
        }
}

/**
 * Parses frames into styled text. A frame is mostly the previous frame's lines, so each line's runs
 * are cached by its text and the style at its start; only new lines run the escape regex.
 */
internal class AnsiRenderer(
    private val palette: List<Color>,
    private val foreground: Color,
    private val background: Color,
) {
    private data class Key(
        val start: TerminalAnsi.Style,
        val line: String,
    )

    private class Entry(
        val runs: List<TerminalAnsi.Run>,
        val end: TerminalAnsi.Style,
    )

    private var entries = HashMap<Key, Entry>()

    /** Styled lines of the last [renderLines], by their key: an unchanged line keeps its instance, so a list can skip it. */
    private var lineTexts = HashMap<Key, AnnotatedString>()

    /**
     * [lines] styled one by one. A line seen in the last frame with the same text and starting style returns the
     * same [AnnotatedString] instance as then, so only new lines cost anything to draw.
     */
    fun renderLines(lines: List<String>): List<AnnotatedString> = renderLinesRaw(lines.map(Glyphs::drawable))

    private fun renderLinesRaw(lines: List<String>): List<AnnotatedString> {
        val next = HashMap<Key, Entry>(entries.size.coerceAtLeast(16))
        val nextTexts = HashMap<Key, AnnotatedString>(lineTexts.size.coerceAtLeast(16))
        var style = TerminalAnsi.Style()
        val out = ArrayList<AnnotatedString>(lines.size)
        for (line in lines) {
            val key = Key(style, line)
            val entry =
                next[key] ?: entries[key] ?: run {
                    val runs = ArrayList<TerminalAnsi.Run>()
                    Entry(runs, TerminalAnsi.parseInto(line, style, palette, runs))
                }
            next[key] = entry
            val text = nextTexts[key] ?: lineTexts[key] ?: buildAnnotatedString { appendRuns(entry.runs) }
            nextTexts[key] = text
            out += text
            style = entry.end
        }
        entries = next
        lineTexts = nextTexts
        return out
    }

    private fun AnnotatedString.Builder.appendRuns(runs: List<TerminalAnsi.Run>) {
        for (r in runs) {
            val s = r.style
            val fg = if (s.inverse) s.background ?: background else s.foreground
            val bg = if (s.inverse) s.foreground ?: foreground else s.background
            if (fg == null && bg == null && !s.bold) {
                append(r.text)
            } else {
                withStyle(SpanStyle(color = fg ?: Color.Unspecified, background = bg ?: Color.Unspecified, fontWeight = if (s.bold) FontWeight.Bold else null)) { append(r.text) }
            }
        }
    }

    fun render(value: String): AnnotatedString {
        val next = HashMap<Key, Entry>(entries.size.coerceAtLeast(16))
        var style = TerminalAnsi.Style()
        return buildAnnotatedString {
            for ((n, line) in value.split('\n').withIndex()) {
                if (n > 0) append('\n')
                val key = Key(style, line)
                val entry =
                    next[key] ?: entries[key] ?: run {
                        val runs = ArrayList<TerminalAnsi.Run>()
                        Entry(runs, TerminalAnsi.parseInto(line, style, palette, runs))
                    }
                next[key] = entry
                appendRuns(entry.runs)
                style = entry.end
            }
            entries = next
        }
    }
}

/**
 * Swaps symbols the phone has no font for (Claude Code's ⏵⏵ shows as boxes) for close ones it can draw.
 * Only characters the phone lacks are swapped; the answer is asked once per character.
 */
internal object Glyphs {
    private val substitutes =
        mapOf(
            '⏵' to '▸', '⏴' to '◂', '⏶' to '▴', '⏷' to '▾', '⏩' to '»', '⏪' to '«', '⏫' to '▲', '⏬' to '▼',
            '⏺' to '●', '⏹' to '■', '⏸' to '‖', '⏯' to '▶', '⏳' to '⌛', '⏱' to '◷', '⏲' to '◷', '⎿' to '└', '⎯' to '─',
        )
    private val paint by lazy { android.graphics.Paint().apply { typeface = android.graphics.Typeface.MONOSPACE } }
    private val known = HashMap<Char, Boolean>()

    private fun missing(c: Char): Boolean =
        synchronized(known) { known.getOrPut(c) { runCatching { !paint.hasGlyph(c.toString()) }.getOrDefault(true) } }

    fun drawable(line: String): String {
        if (line.none { it in substitutes }) return line
        return buildString(line.length) { for (c in line) append(substitutes[c]?.takeIf { missing(c) } ?: c) }
    }
}
