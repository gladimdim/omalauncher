package dev.gladimdim.tiling

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Super shortcuts while an app in a tile has the keyboard: Android gives hardware keys to the focused app, and only
 * an accessibility service sees them first. It asks for key events alone and reads no window.
 */
class TilingKeyService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        connected = true
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            connected = false
        }
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = Tiling.key(event, fromLauncher = false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private var instance: TilingKeyService? = null

        /** Whether the user has turned the service on. */
        var connected by mutableStateOf(false)
            private set

        /** Opens the notification shade; false when the service is off. */
        fun showNotifications(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
    }
}
