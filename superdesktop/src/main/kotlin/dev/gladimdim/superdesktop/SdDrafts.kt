package dev.gladimdim.superdesktop

import android.content.Context

/**
 * Half-typed prompts, per computer and harness, kept across pages, restarts and failed sends. Never sent by
 * themselves. Plain preferences: a draft is what's on screen, not a secret.
 */
object SdDrafts {
    private const val FILE = "sd_drafts"
    private val memory = HashMap<String, String>()

    private fun key(
        computer: String,
        harness: String,
    ) = "$computer/$harness"

    @Synchronized fun get(
        context: Context,
        computer: String,
        harness: String,
    ): String {
        val k = key(computer, harness)
        return memory.getOrPut(k) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(k, "") ?: "" }
    }

    @Synchronized fun put(
        context: Context,
        computer: String,
        harness: String,
        text: String,
    ) {
        val k = key(computer, harness)
        if (memory[k] == text) return
        memory[k] = text
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply { if (text.isEmpty()) remove(k) else putString(k, text) }.apply()
    }
}
