package dev.gladimdim.superdesktop

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** What one SUPER DESKTOP page shows. */
@Immutable
data class PageConfig(
    /** Keys of the computers it shows; null for all of them, new ones included. */
    val computers: Set<String>? = null,
    /** The one harness it shows, or null for every harness of its computers. */
    val pinned: Pinned? = null,
) {
    @Immutable
    data class Pinned(
        val computer: String,
        val harness: String,
    )

    fun shows(computer: String) = computers == null || computer in computers
}

/** Every page's [PageConfig], keyed by the page's `id`. Plain preferences: nothing secret here. */
object SdPages {
    private const val FILE = "sd_pages"

    private val state = MutableStateFlow<Map<String, PageConfig>>(emptyMap())
    private var loaded = false

    val pages: StateFlow<Map<String, PageConfig>> get() = state

    @Synchronized fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        state.value = prefs.all.mapNotNull { (id, raw) -> runCatching { id to decode(JSONObject(raw as String)) }.getOrNull() }.toMap()
        loaded = true
    }

    @Synchronized fun set(
        context: Context,
        id: String,
        config: PageConfig,
    ) {
        load(context)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(id, encode(config).toString()).apply()
        state.value = state.value + (id to config)
    }

    /**
     * Writes a new SUPER DESKTOP page into the launcher's widgets folder, where the launcher picks it up.
     * It asks for [page]; a full page sends it on to the next page with room, or a new last page.
     */
    fun newPage(
        widgetsDir: File,
        id: String = newId(),
        page: Int = 2,
    ): String {
        write(widgetsDir, id, "superdesktop-$id.owl", page = page, size = "9x99", minSize = "4x4", kind = null)
        return id
    }

    /**
     * Writes a new terminal widget: 6x4 on [page], or the next page with room, resizable down to 3x2. It asks
     * which harness to lock to, unless [lockTo] already picks one.
     */
    fun newTerminalWidget(
        context: Context,
        widgetsDir: File,
        lockTo: PageConfig.Pinned? = null,
        id: String = newId(),
        page: Int = 1,
    ): String {
        if (lockTo != null) set(context, id, PageConfig(pinned = lockTo))
        write(widgetsDir, id, "superdesktop-terminal-$id.owl", page = page, size = "6x4", minSize = "3x2", kind = "terminal")
        return id
    }

    private fun write(
        widgetsDir: File,
        id: String,
        file: String,
        page: Int,
        size: String,
        minSize: String,
        kind: String?,
    ) {
        val what = if (kind == "terminal") "One SUPER DESKTOP terminal, live on the home screen." else "Your SUPER DESKTOP computers and their AI terminals, as a whole home page."
        val element = if (kind == null) "SuperDesktop { id: \"$id\" }" else "SuperDesktop { id: \"$id\"; kind: \"$kind\" }"
        File(widgetsDir, file).writeText(
            """
            |widget "SUPER DESKTOP" {
            |  page: $page
            |  size: $size
            |  minSize: $minSize
            |  padding: 0
            |
            |  meta { owl: 9; description: "$what"; version: "0.1"; license: "MIT"; category: "tools" }
            |
            |  // Made by SUPER DESKTOP. The id keys this widget's settings; remove the widget to remove it.
            |  $element
            |}
            |
            """.trimMargin(),
        )
    }

    /**
     * Writes a SUPER DESKTOP face: the dashboard as a whole home page left of page 1, on the wallpaper
     * behind a veil so text stays readable, ending above the navigation bar or the keyboard.
     */
    fun newFace(
        widgetsDir: File,
        id: String = newId(),
        page: Int = 0,
    ): String {
        File(widgetsDir, "superdesktop-face-$id.owl").writeText(
            """
            |face "SUPER DESKTOP" {
            |  meta { owl: 10; description: "Your SUPER DESKTOP computers and their AI terminals, as a whole home page."; version: "0.1"; license: "MIT"; category: "tools" }
            |
            |  page: $page
            |  padding: 0
            |  background: alpha(background, 0.82)
            |
            |  // Made by SUPER DESKTOP. The id keys this face's settings; remove it in settings › pages.
            |  Column {
            |    fill: true
            |    SuperDesktop { id: "$id"; weight: 1 }
            |    Spacer { height: screenInset }
            |  }
            |}
            |
            """.trimMargin(),
        )
        return id
    }

    fun newId() = "sd-" + Random.nextInt(0x1000000).toString(16).padStart(6, '0')

    private fun encode(c: PageConfig) =
        JSONObject().apply {
            c.computers?.let { put("computers", JSONArray(it.toList())) }
            c.pinned?.let { put("pinned", JSONObject().put("computer", it.computer).put("harness", it.harness)) }
        }

    private fun decode(o: JSONObject) =
        PageConfig(
            computers = o.optJSONArray("computers")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            pinned = o.optJSONObject("pinned")?.let { PageConfig.Pinned(it.getString("computer"), it.getString("harness")) },
        )
}
