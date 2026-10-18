package dev.gladimdim.superdesktop.pair

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.CaptureActivity

// Ported from the SUPER DESKTOP app's QrScanActivity.kt (see bridge/SOURCE.md).

/**
 * The pairing QR scanner: zxing's scanner, edge to edge and free to rotate. The library's own activity is
 * locked to landscape and, from Android 15, lays out under the system bars.
 */
class QrScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        // The camera view and its prompt stay clear of the bars and the cutout.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
