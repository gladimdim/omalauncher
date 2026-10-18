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
     * It asks for page 2 like the default one; a full page sends it on to the next page with room, or a new last page.
     */
    fun newPage(
        widgetsDir: File,
        id: String = newId(),
    ): String {
        File(widgetsDir, "superdesktop-$id.owl").writeText(
            """
            |widget "SUPER DESKTOP" {
            |  page: 2
            |  size: 9x99
            |  minSize: 4x4
            |  padding: 0
            |
            |  meta { owl: 9; description: "Your SUPER DESKTOP computers and their AI terminals, as a whole home page."; version: "0.1"; license: "MIT"; category: "tools" }
            |
            |  // Made by "new SUPER DESKTOP page". The id keys this page's settings; remove the widget to remove the page.
            |  SuperDesktop { id: "$id" }
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
