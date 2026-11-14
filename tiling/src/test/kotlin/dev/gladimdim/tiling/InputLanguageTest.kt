package dev.gladimdim.tiling

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputLanguageTest {
    @Test
    fun parsesAndFormatsHotkeys() {
        val ctrlSpace = KeyHotkey.parse("ctrl + space")!!
        assertEquals(KeyEvent.KEYCODE_SPACE, ctrlSpace.keyCode)
        assertTrue(ctrlSpace.ctrl)
        assertFalse(ctrlSpace.superKey)
        assertEquals("ctrl + space", ctrlSpace.format())

        val superShiftSpace = KeyHotkey.parse("Super + Shift + Space")!!
        assertEquals("super + shift + space", superShiftSpace.format())

        val altShift = KeyHotkey.parse("alt + shift")!!
        assertTrue(altShift.isModifierOnly)
        assertTrue(altShift.alt && altShift.shift)
        assertEquals("alt + shift", altShift.format())

        val langKey = KeyHotkey.parse("language switch")!!
        assertEquals(KeyEvent.KEYCODE_LANGUAGE_SWITCH, langKey.keyCode)
        assertEquals("language switch", langKey.format())
    }

    @Test
    fun rejectsOffAndSingleModifierOnly() {
        assertNull(KeyHotkey.parse("off"))
        assertNull(KeyHotkey.parse("none"))
        assertNull(KeyHotkey.parse(""))
        assertNull(KeyHotkey.parse("shift"))
        assertNull(KeyHotkey.parse("ctrl"))
    }

    @Test
    fun mapsLocalesToFlagsAndCodes() {
        val uk = InputLanguageInfo.fromLocale("uk")!!
        assertEquals("UA", uk.code)
        assertEquals("🇺🇦", uk.flag)
        assertEquals("🇺🇦 UA", uk.display(vertical = false))
        assertEquals("🇺🇦", uk.display(vertical = true))

        val enGb = InputLanguageInfo.fromLocale("en_GB")!!
        assertEquals("GB", enGb.code)
        assertEquals("🇬🇧", enGb.flag)

        val en = InputLanguageInfo.fromLocale("en-US")!!
        assertEquals("US", en.code)
        assertEquals("🇺🇸", en.flag)
    }
}
