package dev.gladimdim.tiling

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.gladimdim.tiling.helper.HelperMain

/** The launcher's hold on the helper process (helper/HelperMain.kt): null until it has offered itself, and after it dies. */
object HelperLink {
    var helper by mutableStateOf<ITilingHelper?>(null)
        private set

    /** A helper from an older build of the app is running: it has to be started again. */
    var outdated by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())

    /** What the helper watches to know this process is alive. */
    private val alive = Binder()

    internal fun offered(binder: IBinder) {
        val offered = ITilingHelper.Stub.asInterface(binder) ?: return
        val version = runCatching { offered.version() }.getOrNull() ?: return
        if (version != HelperMain.VERSION) {
            Log.w("Tiling", "helper is version $version, this app needs ${HelperMain.VERSION}")
            main.post { outdated = true }
            return
        }
        runCatching {
            binder.linkToDeath({ main.post { if (helper === offered) lost() } }, 0)
            offered.attach(alive)
        }.onFailure { return }
        main.post {
            outdated = false
            helper = offered
            Tiling.helperReady()
        }
    }

    private fun lost() {
        helper = null
        Tiling.helperLost()
    }
}

/**
 * Where the helper process hands its binder to the launcher: `call("helper")` with the binder in the extras. Exported
 * so a process outside the app can reach it, and open to the shell user (and root) alone.
 */
class HelperProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) throw SecurityException("uid $uid may not offer a helper")
        if (method == "helper") extras?.getBinder("helper")?.let(HelperLink::offered)
        return null
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
