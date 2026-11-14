package dev.gladimdim.tiling

import android.view.KeyEvent

/** A key with the modifiers held beside Super. */
data class Chord(
    val keyCode: Int,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val ctrl: Boolean = false,
)

/**
 * The keys that open an app, as the file `tiling.conf` gives them: a line is `super + shift + b = browser`. What is
 * opened is a kind of app (see [ROLES]), `terminal`, `settings`, an app's name as the app finder shows it, or its
 * package. Blank lines and lines starting with # are skipped, and so is a line that cannot be read.
 */
fun parseBindings(text: String): Map<Chord, String> {
    val out = LinkedHashMap<Chord, String>()
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        val at = line.indexOf('=')
        if (at <= 0) continue
        val target = line.substring(at + 1).trim()
        val chord = chordOf(line.substring(0, at)) ?: continue
        if (target.isNotEmpty()) out[chord] = target
    }
    return out
}

/** `super + shift + b` as a [Chord]; null without Super, without exactly one key, or with a name that is no key. */
fun chordOf(keys: String): Chord? {
    var superHeld = false
    var shift = false
    var alt = false
    var ctrl = false
    var key: Int? = null
    for (part in keys.lowercase().split('+').map { it.trim() }.filter { it.isNotEmpty() }) {
        when (part) {
            "super", "meta", "win" -> superHeld = true
            "shift" -> shift = true
            "alt" -> alt = true
            "ctrl", "control" -> ctrl = true
            else -> {
                if (key != null) return null
                key = keyCodeOf(part) ?: return null
            }
        }
    }
    return if (superHeld && key != null) Chord(key, shift, alt, ctrl) else null
}

/** A key by the name it has in the file: a letter, a digit, or one of the named keys. */
fun keyCodeOf(name: String): Int? =
    when {
        name.length == 1 && name[0] in 'a'..'z' -> KeyEvent.KEYCODE_A + (name[0] - 'a')
        name.length == 1 && name[0] in '0'..'9' -> KeyEvent.KEYCODE_0 + (name[0] - '0')
        else -> NAMED_KEYS[name]
    }

private val NAMED_KEYS =
    mapOf(
        "return" to KeyEvent.KEYCODE_ENTER,
        "enter" to KeyEvent.KEYCODE_ENTER,
        "space" to KeyEvent.KEYCODE_SPACE,
        "backspace" to KeyEvent.KEYCODE_DEL,
        "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
        "tab" to KeyEvent.KEYCODE_TAB,
        "comma" to KeyEvent.KEYCODE_COMMA,
        "period" to KeyEvent.KEYCODE_PERIOD,
        "minus" to KeyEvent.KEYCODE_MINUS,
        "equal" to KeyEvent.KEYCODE_EQUALS,
        "semicolon" to KeyEvent.KEYCODE_SEMICOLON,
        "apostrophe" to KeyEvent.KEYCODE_APOSTROPHE,
        "grave" to KeyEvent.KEYCODE_GRAVE,
        "home" to KeyEvent.KEYCODE_MOVE_HOME,
        "end" to KeyEvent.KEYCODE_MOVE_END,
        "pageup" to KeyEvent.KEYCODE_PAGE_UP,
        "pagedown" to KeyEvent.KEYCODE_PAGE_DOWN,
    ) + (1..12).associate { "f$it" to KeyEvent.KEYCODE_F1 + it - 1 }

/** Kinds of app a binding can name instead of one app: the one Android has for it is opened. */
val ROLES =
    mapOf(
        "browser" to "android.intent.category.APP_BROWSER",
        "files" to "android.intent.category.APP_FILES",
        "calendar" to "android.intent.category.APP_CALENDAR",
        "email" to "android.intent.category.APP_EMAIL",
        "music" to "android.intent.category.APP_MUSIC",
        "calculator" to "android.intent.category.APP_CALCULATOR",
        "maps" to "android.intent.category.APP_MAPS",
        "photos" to "android.intent.category.APP_GALLERY",
        "messages" to "android.intent.category.APP_MESSAGING",
        "contacts" to "android.intent.category.APP_CONTACTS",
    )

/**
 * For the kinds of app that open a sort of link, a link of that sort: the app Android opens it with is the one the
 * user picked for the job (the default browser, mail app, …), which the category alone does not tell.
 */
val ROLE_LINKS =
    mapOf(
        "browser" to "https://example.com/",
        "email" to "mailto:",
        "maps" to "geo:0,0",
        "messages" to "smsto:",
    )

/** What `tiling.conf` starts as: Omarchy's application keys, with the apps a tablet has for them. */
val DEFAULT_BINDINGS =
    """
    # Keys that open an app as a window on the tiling desktop, as Omarchy's Super+Shift+letter keys do.
    #
    #   keys = what to open
    #
    # What to open is a kind of app (browser, files, calendar, email, music, calculator, maps, photos, messages,
    # contacts), terminal, settings, an app's name as the app finder shows it, or its package name.
    # The desktop's own keys (see "show keyboard shortcuts" in the app finder) can't be given away here.
    # Android keeps some keys for itself; on HyperOS: super with a bare arrow, super+tab, super+d, super+l, super+slash.

    super + return = terminal
    super + shift + return = browser
    super + shift + b = browser
    super + shift + f = files
    super + shift + c = calendar
    super + shift + e = email
    super + shift + m = music
    super + shift + q = calculator
    super + shift + s = maps
    super + shift + p = photos
    super + shift + n = Notes
    super + shift + y = YouTube
    super + shift + g = Signal
    super + shift + alt + g = WhatsApp
    super + shift + o = Obsidian
    super + shift + a = ChatGPT
    super + shift + x = X
    """.trimIndent() + "\n"
