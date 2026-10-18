package dev.gladimdim.superdesktop

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import kotlin.concurrent.thread

/**
 * Decrypts the paired computers and reads the pages' settings when the launcher starts, on a
 * background thread, so the first SUPER DESKTOP tile shown doesn't wait for Tink. A provider
 * because Android starts it with the app, from this module's own manifest.
 */
class SdWarmup : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return true
        thread(name = "sd-warmup", isDaemon = true) {
            runCatching {
                SdStore.load(app)
                SdPages.load(app)
            }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}
