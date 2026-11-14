package dev.gladimdim.tiling

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BindingsTest {
    @Test
    fun aLineBindsAKeyToWhatItOpens() {
        val bindings = parseBindings("super + shift + b = browser\nSUPER+RETURN=terminal\n")
        assertEquals("browser", bindings[Chord(KeyEvent.KEYCODE_B, shift = true)])
        assertEquals("terminal", bindings[Chord(KeyEvent.KEYCODE_ENTER)])
    }

    @Test
    fun commentsBlankLinesAndWhatCannotBeReadAreSkipped() {
        val bindings = parseBindings("# a comment\n\nsuper + shift + y = YouTube # the app\nshift + y = no super\nsuper + nokey = x\nsuper + a + b = two keys\nsuper + z =\n")
        assertEquals(mapOf(Chord(KeyEvent.KEYCODE_Y, shift = true) to "YouTube"), bindings)
    }

    @Test
    fun modifiersAndNamedKeysAreRead() {
        assertEquals(Chord(KeyEvent.KEYCODE_G, shift = true, alt = true), chordOf("super + shift + alt + g"))
        assertEquals(Chord(KeyEvent.KEYCODE_COMMA, ctrl = true), chordOf("Super + Ctrl + comma"))
        assertEquals(Chord(KeyEvent.KEYCODE_F5), chordOf("super+f5"))
        assertEquals(Chord(KeyEvent.KEYCODE_7), chordOf("super+7"))
        assertNull(chordOf("alt + tab"))
    }

    @Test
    fun theDefaultsAllParse() {
        val lines = DEFAULT_BINDINGS.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.count()
        assertEquals(lines, parseBindings(DEFAULT_BINDINGS).size)
    }
}
