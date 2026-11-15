package dev.gladimdim.tiling.adb

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.gladimdim.tiling.HelperLink
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Starts the window helper ([dev.gladimdim.tiling.helper.HelperMain]) from the phone itself, as `adb` from a
 * computer would: paired once with Wireless Debugging's six-digit code, the launcher connects to the phone's own
 * adbd and runs the helper there as the shell user.
 *
 * Android only lets the code be typed while its pairing dialog is on screen, so the launcher cannot ask for it in
 * its own window: whoever shows the setup watches [pairingPort] and takes the code somewhere that leaves Settings
 * in front (the launcher does, in a notification).
 */
object LocalAdbHelper {
    private const val TAG = "LocalAdbHelper"
    private const val PREFS = "tiling_adb"
    private const val KEY_PAIRED = "paired"
    private const val SERVICE_CONNECT = "_adb-tls-connect._tcp"
    private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    private const val HELPER_LOG = "/data/local/tmp/tiling-helper.log"
    private const val AUTO_RETRY_MS = 15_000L

    /** Between two tries at switching Wireless Debugging on by ourselves: off Wi-Fi, or on a network not yet allowed, Android only switches it off again. */
    private const val SWITCH_ON_RETRY_MS = 60_000L

    /**
     * A helper that outlived the launcher's process (an update of the app, or Android ending it in the background)
     * offers itself again every three seconds: for this long after the launcher started, none is started in its place.
     */
    private const val REOFFER_MS = 6_000L
    private val loadedAt = SystemClock.elapsedRealtime()

    enum class Step { Idle, Pairing, Starting }

    /** Why the last try ended without a helper. */
    enum class Problem {
        /** The phone did not take the code. Its pairing dialog has closed: a new one gives a new code. */
        WrongCode,

        /** No pairing dialog is open on this phone. */
        NoPairingDialog,

        /** Wireless Debugging is off, and the launcher may not switch it on (or Android switched it off again). */
        DebuggingOff,

        /** The phone no longer accepts our key: it has to be paired again. */
        KeyRejected,

        /** The helper was started and did not report back. */
        HelperSilent,
        Failed,
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The phone has our key, as far as we know: it is found out otherwise only by connecting. */
    var paired by mutableStateOf(false)
        private set
    var step by mutableStateOf(Step.Idle)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set

    /** The port of the pairing dialog that is open on this phone right now, while [watch]ed. */
    var pairingPort by mutableStateOf<Int?>(null)
        private set

    val busy get() = step != Step.Idle

    // Not tried for the pairing dialog: a connection that does not go on to pair counts as a failed pairing, and closes it.
    private val pairingFinder = Finder(SERVICE_PAIRING, tried = false) { publish { pairingPort = it } }
    private val connectFinder = Finder(SERVICE_CONNECT, tried = true) {}
    private var lastAutoTryAt = 0L
    private var lastSwitchOnAt = 0L

    fun init(context: Context) {
        paired = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)
    }

    /** Called on the main thread after any of the state above changed, for what is not Compose. Returns how to stop. */
    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Looks for a pairing dialog on this phone until [unwatch]; each call needs one of those. */
    fun watch(context: Context) = pairingFinder.acquire(context.applicationContext)

    fun unwatch() = pairingFinder.release()

    /** Whether Wireless Debugging is switched on, which Android lets any app read. */
    fun debuggingOn(context: Context): Boolean = Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    private fun usbDebuggingOn(context: Context): Boolean = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    fun developerOptionsOn(context: Context): Boolean = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Granted by the first start of the helper: from then on the launcher switches Wireless Debugging on itself when it needs the helper back. */
    fun maySwitchDebugging(context: Context): Boolean = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Pairs with the code of the pairing dialog that is open, then starts the helper. */
    fun pair(
        context: Context,
        code: String,
    ) {
        val app = context.applicationContext
        val digits = code.filter { it.isDigit() }
        if (busy) return
        if (digits.length != 6) {
            problem = Problem.WrongCode
            changed()
            return
        }
        step = Step.Pairing
        problem = null
        changed()
        worker.execute {
            val failed =
                try {
                    val port = pairingFinder.await(app, 4_000) ?: throw ConnectException("no pairing dialog")
                    AdbPairing.pair("127.0.0.1", port, digits, AdbCrypto.loadOrCreate(app))
                    null
                } catch (e: WrongPairingCode) {
                    Problem.WrongCode
                } catch (e: ConnectException) {
                    Problem.NoPairingDialog
                } catch (e: Exception) {
                    Log.w(TAG, "pairing failed", e)
                    Problem.Failed
                }
            // Android closes its dialog after any try, and says so on the network only a moment later.
            pairingFinder.forget()
            if (failed != null) {
                publish {
                    step = Step.Idle
                    problem = failed
                }
                return@execute
            }
            setPaired(app, true)
            publish { step = Step.Starting }
            finish(start(app, justPaired = true))
        }
    }

    /** Starts the helper through Wireless Debugging, for a phone that is paired. */
    fun startHelper(context: Context) {
        val app = context.applicationContext
        if (HelperLink.helper != null || busy) return
        step = Step.Starting
        problem = null
        changed()
        worker.execute { finish(start(app)) }
    }

    /** The same, quietly and not too often: after a restart of the phone, or of the helper for a new build of the app. */
    fun autoStartIfPaired(context: Context) {
        val app = context.applicationContext
        if (HelperLink.helper != null || busy) return
        if (SystemClock.elapsedRealtime() - loadedAt < REOFFER_MS) return
        if (!paired) init(app)
        if (!paired) return
        val now = System.currentTimeMillis()
        if (now - lastAutoTryAt < AUTO_RETRY_MS) return
        lastAutoTryAt = now
        worker.execute {
            if (HelperLink.helper != null) return@execute
            val failed = start(app)
            if (failed != null) Log.d(TAG, "helper not started: $failed")
            // Only what the user can act on is shown for a try nobody asked for.
            if (failed == Problem.KeyRejected) publish { problem = failed }
        }
    }

    private fun finish(failed: Problem?) =
        publish {
            step = Step.Idle
            problem = failed
        }

    /**
     * On the worker. Null once the helper has reported to the launcher. [justPaired]: Android says the pairing is done
     * a moment before its adbd knows the new key, and until then adbd turns it down like any stranger's.
     */
    private fun start(
        app: Context,
        justPaired: Boolean = false,
    ): Problem? {
        var triesLeft = if (justPaired) 8 else 1
        while (true) {
            val failed = startOnce(app)
            if (failed != Problem.KeyRejected || --triesLeft <= 0) {
                if (failed == Problem.KeyRejected) setPaired(app, false)
                return failed
            }
            Thread.sleep(500)
        }
    }

    private fun startOnce(app: Context): Problem? {
        val crypto = AdbCrypto.loadOrCreate(app)
        var port = connectFinder.await(app, if (debuggingOn(app)) 5_000 else 1_500)
        var switchedOn = false
        if (port == null && !debuggingOn(app) && maySwitchDebugging(app) && onWifi(app)) {
            val now = System.currentTimeMillis()
            if (now - lastSwitchOnAt >= SWITCH_ON_RETRY_MS) {
                lastSwitchOnAt = now
                switchedOn = switchDebugging(app, true)
                port = connectFinder.await(app, 10_000)
            }
        }
        if (port == null) {
            if (switchedOn) switchDebugging(app, false)
            return Problem.DebuggingOff
        }
        try {
            AdbProtocol.runShellCommand("127.0.0.1", port, crypto, prepareCommand(app.packageName))
            AdbProtocol.runShellCommand("127.0.0.1", port, crypto, startCommand(app.packageName))
            if (awaitHelper(8_000)) return null
            val log = runCatching { AdbProtocol.runShellCommand("127.0.0.1", port, crypto, "cat $HELPER_LOG") }.getOrDefault("")
            Log.w(TAG, "the helper did not report back: ${log.trim()}")
            return Problem.HelperSilent
        } catch (e: AdbUnauthorized) {
            Log.w(TAG, "the phone turned our key down: ${e.cause?.message ?: e.message}")
            return Problem.KeyRejected
        } catch (e: ConnectException) {
            // Switched off between the lookup and now.
            connectFinder.forget()
            return Problem.DebuggingOff
        } catch (e: Exception) {
            Log.w(TAG, "starting the helper failed", e)
            return Problem.Failed
        } finally {
            // As it was found, when that is safe: with USB debugging on (the command above saw to it) adbd, and the
            // helper under it, stay. Where a phone refused that, switching this off would stop both.
            if (switchedOn && usbDebuggingOn(app)) switchDebugging(app, false)
        }
    }

    /**
     * Two things only the shell user may do, before the helper is started:
     * - USB debugging on. Android stops adbd, and every process started from it, when neither kind of debugging is
     *   on, and it switches Wireless Debugging off by itself whenever Wi-Fi drops: the helper would die with it.
     * - The launcher may write secure settings, so after a restart it can switch Wireless Debugging on for the
     *   moment it takes to start the helper again, without the user in Developer options each time.
     *
     * And the helper of an older build goes. A command of its own, as in `scripts/tiling-helper.sh`: in one line
     * with the start, pkill would find the helper's name in the very shell that runs it, and kill that.
     */
    private fun prepareCommand(pkg: String): String =
        "settings put global adb_enabled 1; " +
            "pm grant $pkg $WRITE_SECURE_SETTINGS; " +
            "pkill -f 'tiling.helper.[H]elperMain'"

    /**
     * What `scripts/tiling-helper.sh` runs from a computer, and then a wait for the helper's first line in its log.
     * The wait is what makes it start at all: we hang up as soon as the command returns, and a helper that has not
     * got as far as its own session by then goes down with the shell that started it (three starts in four did).
     */
    private fun startCommand(pkg: String): String =
        "apk=\$(pm path $pkg | head -1 | cut -d: -f2 | tr -d '\\r'); " +
            // Emptied here and not by the helper's own redirection, which the wait below would be ahead of.
            ": >$HELPER_LOG; " +
            "CLASSPATH=\$apk setsid app_process /system/bin dev.gladimdim.tiling.helper.HelperMain $pkg " +
            ">>$HELPER_LOG 2>&1 </dev/null & " +
            "n=0; while [ ! -s $HELPER_LOG ] && [ \$n -lt 24 ]; do sleep 0.25; n=\$((n+1)); done"

    private fun awaitHelper(timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (HelperLink.helper != null) return true
            Thread.sleep(200)
        }
        return HelperLink.helper != null
    }

    /** True when this changed the setting. */
    private fun switchDebugging(
        app: Context,
        on: Boolean,
    ): Boolean {
        if (debuggingOn(app) == on) return false
        return runCatching { Settings.Global.putInt(app.contentResolver, ADB_WIFI_ENABLED, if (on) 1 else 0) }.getOrDefault(false)
    }

    private fun onWifi(app: Context): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun setPaired(
        app: Context,
        now: Boolean,
    ) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAIRED, now).apply()
        publish { paired = now }
    }

    private fun publish(change: () -> Unit) {
        main.post {
            change()
            changed()
        }
    }

    private fun changed() = listeners.forEach { it() }

    /**
     * Android's own lookup of one kind of adb service on the network, kept running while anyone needs it. Every
     * phone and tablet on the Wi-Fi with Wireless Debugging on answers it: only this phone's own count.
     */
    private class Finder(
        private val type: String,
        /**
         * Whether a port has to take a connection to count. adbd does not take its service back when Wireless
         * Debugging goes off: the old port goes on being found for minutes, with nothing listening on it.
         */
        private val tried: Boolean,
        private val found: (Int?) -> Unit,
    ) {
        private var users = 0
        private var nsd: NsdManager? = null
        private var listener: NsdManager.DiscoveryListener? = null

        /** This phone's services by name. More than one only for the moment an old one has not been taken back yet. */
        private val ports = LinkedHashMap<String, Int>()
        private val unresolved = ArrayDeque<NsdServiceInfo>()
        private var resolving = false

        @Volatile
        private var port: Int? = null

        @Synchronized
        fun acquire(app: Context) {
            if (users++ > 0) return
            val manager = app.getSystemService(NsdManager::class.java) ?: return
            val discovery =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        Log.w(TAG, "looking for $type failed: $errorCode")
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) = Unit

                    override fun onServiceFound(info: NsdServiceInfo) = resolve(this, info)

                    override fun onServiceLost(info: NsdServiceInfo) = lost(this, info.serviceName)
                }
            // Without the local network permission Android 17 refuses, or finds nothing.
            runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery) }
                .onSuccess {
                    nsd = manager
                    listener = discovery
                }.onFailure { Log.w(TAG, "looking for $type failed", it) }
        }

        @Synchronized
        fun release() {
            if (users == 0 || --users > 0) return
            listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
            listener = null
            nsd = null
            unresolved.clear()
            resolving = false
            ports.clear()
            update()
        }

        /** The port once it is known, or null after [timeoutMs] without one. */
        fun await(
            app: Context,
            timeoutMs: Long,
        ): Int? {
            acquire(app)
            try {
                val until = System.currentTimeMillis() + timeoutMs
                while (port == null && System.currentTimeMillis() < until) Thread.sleep(100)
                return port
            } finally {
                release()
            }
        }

        /** The service is known to be gone before the network has said so. */
        @Synchronized
        fun forget() {
            ports.clear()
            update()
        }

        @Synchronized
        private fun resolve(
            from: NsdManager.DiscoveryListener,
            info: NsdServiceInfo,
        ) {
            if (from !== listener) return
            unresolved += info
            next()
        }

        /** One at a time: before Android 14 a second resolve fails while the first is under way. */
        @Suppress("DEPRECATION")
        private fun next() {
            if (resolving) return
            val manager = nsd ?: return
            val info = unresolved.removeFirstOrNull() ?: return
            val asked = listener
            resolving = true
            val resolved =
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        info: NsdServiceInfo,
                        errorCode: Int,
                    ) = done(null)

                    override fun onServiceResolved(info: NsdServiceInfo) = done(info)

                    private fun done(info: NsdServiceInfo?) {
                        synchronized(this@Finder) {
                            if (asked !== listener) return
                            resolving = false
                            if (info != null && info.port > 0 && isThisPhone(info.host) && (!tried || listening(info.port))) {
                                ports[info.serviceName] = info.port
                                update()
                            }
                            next()
                        }
                    }
                }
            runCatching { manager.resolveService(info, resolved) }.onFailure {
                resolving = false
                next()
            }
        }

        @Synchronized
        private fun lost(
            from: NsdManager.DiscoveryListener,
            name: String,
        ) {
            if (from !== listener) return
            if (ports.remove(name) != null) update()
        }

        private fun update() {
            val now = ports.values.lastOrNull()
            if (now == port) return
            port = now
            found(now)
        }

        private fun listening(port: Int): Boolean =
            runCatching { Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500) } }.isSuccess

        private fun isThisPhone(host: InetAddress?): Boolean {
            host ?: return false
            if (host.isLoopbackAddress) return true
            val mine = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() } }.getOrDefault(emptyList())
            return mine.any { it.address.contentEquals(host.address) }
        }
    }
}
