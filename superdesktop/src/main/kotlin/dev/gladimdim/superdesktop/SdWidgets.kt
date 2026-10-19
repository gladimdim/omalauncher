package dev.gladimdim.superdesktop

import android.content.Context
import java.io.File

/**
 * Adding SUPER DESKTOP widgets to the home screen, for the launcher's own menus (and the tiles' ⚙).
 * Each writes a widget file the launcher loads within a second, on [page] or the next page with room,
 * and leaves the new widget a note saying what to do next.
 */
object SdWidgets {
    /** A terminal widget, 6x4 and resizable; it asks which harness to lock to, unless [lockTo] says. */
    fun addTerminal(
        context: Context,
        widgetsDir: File,
        page: Int,
        lockTo: PageConfig.Pinned? = null,
        lockedName: String? = null,
    ): String {
        val id = SdPages.newId()
        Notes.put(
            id,
            if (lockTo == null) "Pick the terminal this widget locks to; long-press to move or resize it" else "Locked to ${lockedName ?: "its harness"}; long-press to move or resize it",
            error = false,
        )
        return SdPages.newTerminalWidget(context, widgetsDir, lockTo, id, page)
    }

    /** A whole SUPER DESKTOP page: every harness of every paired computer until its ⚙ says otherwise. */
    fun addPage(
        widgetsDir: File,
        page: Int,
    ): String {
        val id = SdPages.newId()
        Notes.put(id, "A new SUPER DESKTOP page: ⚙ picks its computers and harness", error = false)
        return SdPages.newPage(widgetsDir, id, page)
    }

    /** A SUPER DESKTOP face: the dashboard as a whole home page, left of page 1 (or the next empty page there). */
    fun addFace(
        widgetsDir: File,
        page: Int = 0,
    ): String {
        val id = SdPages.newId()
        Notes.put(id, "SUPER DESKTOP as a face: ⚙ picks its computers and harness", error = false)
        return SdPages.newFace(widgetsDir, id, page)
    }
}
