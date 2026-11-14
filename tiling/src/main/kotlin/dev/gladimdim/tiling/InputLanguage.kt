package dev.gladimdim.tiling

import android.view.KeyEvent
import java.util.Locale

/**
 * A keyboard shortcut for a tiling/keyboard-mode action (such as switching input language).
 * When [keyCode] is 0, this is a modifier-only chord (e.g. Alt+Shift or Ctrl+Shift) that triggers
 * when the modifiers are pressed together and released without any other key pressed in between.
 */
data class KeyHotkey(
    val keyCode: Int = KeyEvent.KEYCODE_SPACE,
    val superKey: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    val isModifierOnly: Boolean get() = keyCode == 0

    fun matchesKey(event: KeyEvent): Boolean {
        if (isModifierOnly || event.keyCode != keyCode) return false
        return event.isMetaPressed == superKey &&
            event.isCtrlPressed == ctrl &&
            event.isAltPressed == alt &&
            event.isShiftPressed == shift
    }

    fun matchesModifiers(
        meta: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
    ): Boolean = this.superKey == meta && this.ctrl == ctrl && this.alt == alt && this.shift == shift

    fun format(): String {
        val parts = ArrayList<String>(5)
        if (superKey) parts += "super"
        if (ctrl) parts += "ctrl"
        if (alt) parts += "alt"
        if (shift) parts += "shift"
        if (keyCode != 0) parts += keyNameOf(keyCode)
        return parts.joinToString(" + ")
    }

    companion object {
        val DEFAULT_LANGUAGE = KeyHotkey(keyCode = KeyEvent.KEYCODE_SPACE, ctrl = true)

        val PRESETS: List<Pair<String, KeyHotkey?>> =
            listOf(
                "ctrl + space" to KeyHotkey(keyCode = KeyEvent.KEYCODE_SPACE, ctrl = true),
                "super + shift + space" to KeyHotkey(keyCode = KeyEvent.KEYCODE_SPACE, superKey = true, shift = true),
                "alt + shift" to KeyHotkey(keyCode = 0, alt = true, shift = true),
                "ctrl + shift" to KeyHotkey(keyCode = 0, ctrl = true, shift = true),
                "language switch" to KeyHotkey(keyCode = KeyEvent.KEYCODE_LANGUAGE_SWITCH),
                "off" to null,
            )

        fun isModifierKey(keyCode: Int): Boolean =
            when (keyCode) {
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                -> true
                else -> false
            }

        fun parse(raw: String?): KeyHotkey? {
            val text = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (text.isEmpty() || text == "off" || text == "none") return null
            if (text == "language switch" || text == "language_switch") {
                return KeyHotkey(keyCode = KeyEvent.KEYCODE_LANGUAGE_SWITCH)
            }
            var superKey = false
            var ctrl = false
            var alt = false
            var shift = false
            var keyCode = 0
            for (part in text.split('+').map { it.trim() }.filter { it.isNotEmpty() }) {
                when (part) {
                    "super", "meta", "win" -> superKey = true
                    "ctrl", "control" -> ctrl = true
                    "alt" -> alt = true
                    "shift" -> shift = true
                    "language_switch", "lang" -> {
                        if (keyCode != 0) return null
                        keyCode = KeyEvent.KEYCODE_LANGUAGE_SWITCH
                    }
                    "capslock", "caps_lock" -> {
                        if (keyCode != 0) return null
                        keyCode = KeyEvent.KEYCODE_CAPS_LOCK
                    }
                    else -> {
                        if (keyCode != 0) return null
                        keyCode = keyCodeOf(part) ?: return null
                    }
                }
            }
            if (keyCode == 0 && !superKey && !ctrl && !alt && !shift) return null
            if (keyCode == 0 && listOf(superKey, ctrl, alt, shift).count { it } < 2) {
                // Require at least two modifiers for a modifier-only combo so a single Shift/Ctrl isn't hijacked.
                return null
            }
            return KeyHotkey(keyCode, superKey, ctrl, alt, shift)
        }

        fun fromKeyEvent(event: KeyEvent): KeyHotkey? {
            if (isModifierKey(event.keyCode)) return null
            return KeyHotkey(
                keyCode = event.keyCode,
                superKey = event.isMetaPressed,
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
            )
        }

        private fun keyNameOf(keyCode: Int): String =
            when (keyCode) {
                KeyEvent.KEYCODE_SPACE -> "space"
                KeyEvent.KEYCODE_ENTER -> "enter"
                KeyEvent.KEYCODE_TAB -> "tab"
                KeyEvent.KEYCODE_DEL -> "backspace"
                KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
                KeyEvent.KEYCODE_COMMA -> "comma"
                KeyEvent.KEYCODE_PERIOD -> "period"
                KeyEvent.KEYCODE_MINUS -> "minus"
                KeyEvent.KEYCODE_EQUALS -> "equal"
                KeyEvent.KEYCODE_SEMICOLON -> "semicolon"
                KeyEvent.KEYCODE_APOSTROPHE -> "apostrophe"
                KeyEvent.KEYCODE_GRAVE -> "grave"
                KeyEvent.KEYCODE_MOVE_HOME -> "home"
                KeyEvent.KEYCODE_MOVE_END -> "end"
                KeyEvent.KEYCODE_PAGE_UP -> "pageup"
                KeyEvent.KEYCODE_PAGE_DOWN -> "pagedown"
                KeyEvent.KEYCODE_LANGUAGE_SWITCH -> "language switch"
                KeyEvent.KEYCODE_CAPS_LOCK -> "capslock"
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString()
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "f${keyCode - KeyEvent.KEYCODE_F1 + 1}"
                else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase(Locale.ROOT)
            }
    }
}

/** Active input language for the bar: short [code] (e.g. "UA") and regional [flag] emoji (e.g. "🇺🇦"). */
data class InputLanguageInfo(
    val tag: String,
    val code: String,
    val flag: String,
) {
    fun display(vertical: Boolean): String =
        when {
            flag.isEmpty() -> code
            vertical -> flag
            else -> "$flag $code"
        }

    companion object {
        private val LANGUAGE_TO_REGION =
            mapOf(
                "uk" to "UA",
                "en" to "US",
                "de" to "DE",
                "es" to "ES",
                "fr" to "FR",
                "it" to "IT",
                "pl" to "PL",
                "pt" to "PT",
                "zh" to "CN",
                "ja" to "JP",
                "ko" to "KR",
                "ar" to "SA",
                "tr" to "TR",
                "nl" to "NL",
                "cs" to "CZ",
                "sk" to "SK",
                "sv" to "SE",
                "no" to "NO",
                "nb" to "NO",
                "da" to "DK",
                "fi" to "FI",
                "ro" to "RO",
                "hu" to "HU",
                "bg" to "BG",
                "el" to "GR",
                "he" to "IL",
                "iw" to "IL",
                "hi" to "IN",
                "id" to "ID",
                "in" to "ID",
                "vi" to "VN",
                "th" to "TH",
                "ru" to "RU",
                "lt" to "LT",
                "lv" to "LV",
                "et" to "EE",
                "ka" to "GE",
            )

        fun fromLocale(raw: String?): InputLanguageInfo? {
            val cleaned = raw?.trim()?.replace('_', '-') ?: return null
            if (cleaned.isEmpty()) return null
            val parts = cleaned.split('-').filter { it.isNotEmpty() }
            val lang = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: return null
            val region =
                parts.drop(1).firstOrNull { it.length == 2 && it[0].isLetter() && it[1].isLetter() }?.uppercase(Locale.ROOT)
                    ?: LANGUAGE_TO_REGION[lang]
            val code = region ?: lang.take(2).uppercase(Locale.ROOT)
            val flag = region?.let(::flagEmoji) ?: ""
            return InputLanguageInfo(tag = cleaned, code = code, flag = flag)
        }

        private fun flagEmoji(region: String): String {
            if (region.length != 2) return ""
            val a = region[0].uppercaseChar()
            val b = region[1].uppercaseChar()
            if (a !in 'A'..'Z' || b !in 'A'..'Z') return ""
            val base = 0x1F1E6
            return String(Character.toChars(base + (a - 'A'))) + String(Character.toChars(base + (b - 'A')))
        }
    }
}
