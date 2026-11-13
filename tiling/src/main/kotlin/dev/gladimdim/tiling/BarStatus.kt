package dev.gladimdim.tiling

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What the bar shows of the device: the time, Wi-Fi, Bluetooth and the battery. */
internal object BarStatus {
    var now by mutableStateOf(LocalDateTime.now())
        private set
    var wifi by mutableStateOf(false)
        private set
    var bluetooth by mutableStateOf(false)
        private set

    /** Percent, or -1 before the first reading (and on devices without a battery). */
    var battery by mutableIntStateOf(-1)
        private set
    var charging by mutableStateOf(false)
        private set

    val time: String get() = now.format(DateTimeFormatter.ofPattern("EEEE HH:mm"))
    val hours: String get() = now.format(DateTimeFormatter.ofPattern("HH"))
    val minutes: String get() = now.format(DateTimeFormatter.ofPattern("mm"))

    private val main = Handler(Looper.getMainLooper())
    private var started = false

    @RequiresApi(33)
    fun start(context: Context) {
        if (started) return
        started = true
        tick()
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        val on = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        main.post { wifi = on }
                    }

                    override fun onLost(network: Network) {
                        main.post { wifi = false }
                    }
                },
            )
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        bluetooth = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothAdapter.ACTION_STATE_CHANGED ->
                            bluetooth = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) == BluetoothAdapter.STATE_ON
                        Intent.ACTION_BATTERY_CHANGED -> {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                            battery = if (level < 0 || scale <= 0) -1 else level * 100 / scale
                            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                        }
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
        // Both are sent by the system only.
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    /** On the minute. */
    private fun tick() {
        now = LocalDateTime.now()
        main.postDelayed(::tick, 60_000L - System.currentTimeMillis() % 60_000L + 50)
    }
}
