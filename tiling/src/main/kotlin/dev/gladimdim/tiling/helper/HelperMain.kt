package dev.gladimdim.tiling.helper

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.RequiresApi
import dev.gladimdim.tiling.ITilingHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The tiling desktop's helper process. Started as the shell user with app_process (scripts/tiling-helper.sh), the way
 * Shizuku and scrcpy start theirs: the shell user may create trusted virtual displays, start apps on them and inject
 * input, which the launcher itself may not. It hands [Helper] to the launcher through [dev.gladimdim.tiling.HelperProvider].
 *
 * Nothing here is an app: there is no Application, and the framework is reached through its hidden side by reflection.
 */
object HelperMain {
    const val VERSION = 2
    const val TAG = "TilingHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.firstOrNull() ?: run {
            System.err.println("usage: HelperMain <launcher package>")
            return
        }
        if (Build.VERSION.SDK_INT < 33) {
            System.err.println("the tiling desktop needs Android 13")
            return
        }
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        val helper = Helper(pkg)
        helper.offerUntilTaken()
        println("tiling helper $VERSION for $pkg, uid ${Process.myUid()}")
        Looper.loop()
    }
}

/** A context the system accepts from the shell user: the system context under the shell's package name. */
@RequiresApi(33)
private class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName() = SHELL

    override fun getOpPackageName() = SHELL

    override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()

    override fun getDeviceId() = 0

    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL = "com.android.shell"
    }
}

// The hidden framework classes are the point of this process: the shell user's rights are only reachable through
// them. Every call into them fails soft, as their shapes change between Android releases.
@SuppressLint("PrivateApi")
@RequiresApi(33)
private class Helper(private val pkg: String) : ITilingHelper.Stub() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val context: Context
    private val displays: DisplayManager
    private val shown = ConcurrentHashMap<Int, VirtualDisplay>()
    private val appUid: Int

    private val inputManager: Any
    private val inject: Method
    private val setDisplayId: Method = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    private val taskManager: Any = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!

    /** The launcher's process, while it holds us. */
    @Volatile
    private var app: IBinder? = null

    init {
        val thread = Class.forName("android.app.ActivityThread")
        val system = thread.getMethod("getSystemContext").invoke(thread.getMethod("systemMain").invoke(null)) as Context
        context = ShellContext(system)
        displays = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        appUid = runCatching { system.packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
        val global = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
        val managerClass = global ?: Class.forName("android.hardware.input.InputManager")
        inputManager = managerClass.getMethod("getInstance").invoke(null)!!
        inject = inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    // ---- handing ourselves to the launcher ----

    /** Every few seconds until the launcher has us; again after its process dies. */
    fun offerUntilTaken() {
        if (app != null) return
        runCatching { offer() }.onFailure { Log.w(HelperMain.TAG, "offer failed: ${cause(it)}") }
        main.postDelayed(::offerUntilTaken, 3000)
    }

    /** Calls the launcher's provider with our binder: a plain ContentResolver needs an app, which this process is not. */
    private fun offer() {
        val authority = "$pkg.tiling-helper"
        val am = ActivityManager::class.java.getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder =
            am.javaClass
                .getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(am, authority, 0, token, "tiling") ?: return
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return
            val call = provider.javaClass.methods.first { it.name == "call" && it.parameterTypes.size == 5 }
            call.isAccessible = true
            val extras = Bundle().apply { putBinder("helper", this@Helper) }
            call.invoke(provider, context.attributionSource, authority, "helper", null, extras)
        } finally {
            runCatching {
                am.javaClass
                    .getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(am, authority, token, 0)
            }
        }
    }

    /** Only the launcher this helper was started for. */
    private fun enforce() {
        val uid = Binder.getCallingUid()
        if (uid != appUid && uid != Process.myUid()) throw SecurityException("uid $uid is not $pkg")
    }

    override fun version(): Int = HelperMain.VERSION

    override fun attach(app: IBinder) {
        enforce()
        this.app = app
        app.linkToDeath({ main.post(::appGone) }, 0)
        Log.i(HelperMain.TAG, "attached to $pkg")
    }

    private fun appGone() {
        Log.i(HelperMain.TAG, "$pkg is gone: releasing ${shown.size} displays")
        app = null
        shown.values.forEach { runCatching { it.release() } }
        shown.clear()
        offerUntilTaken()
    }

    // ---- displays ----

    override fun createDisplay(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
        name: String,
    ): Int {
        enforce()
        val identity = Binder.clearCallingIdentity()
        try {
            val display = displays.createVirtualDisplay(name, width, height, dpi, surface, FLAGS) ?: return -1
            val id = display.display.displayId
            shown[id] = display
            noKeyboard(id)
            return id
        } catch (e: Throwable) {
            Log.e(HelperMain.TAG, "createDisplay", e)
            return -1
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * No on-screen keyboard for the display's apps. Android would show it on the main screen, and its navigation bar
     * with it, every time a text field in a tile is typed into; the desktop is only on with a hardware keyboard,
     * whose keys reach the app without it.
     */
    private fun noKeyboard(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, IME_POLICY_HIDE)
        }.onFailure { Log.w(HelperMain.TAG, "no ime policy: ${cause(it)}") }
    }

    override fun setSurface(
        displayId: Int,
        surface: Surface?,
    ) {
        enforce()
        asShell { shown[displayId]?.surface = surface }
    }

    override fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        dpi: Int,
    ) {
        enforce()
        asShell { shown[displayId]?.resize(width, height, dpi) }
    }

    override fun releaseDisplay(displayId: Int) {
        enforce()
        asShell {
            val display = shown.remove(displayId) ?: return@asShell
            runCatching {
                for (line in tasksByApi().lineSequence()) {
                    val parts = line.split(' ')
                    if (parts.size >= 2 && parts[1] == displayId.toString()) task("removeTask", parts[0].toInt())
                }
            }
            display.release()
        }
    }

    // ---- apps ----

    override fun startActivity(
        displayId: Int,
        args: Array<String>,
    ) {
        enforce()
        worker.execute { exec(listOf("cmd", "activity", "start-activity", "--display", displayId.toString()) + args) }
    }

    override fun tasks(): String {
        enforce()
        return asShell { runCatching { tasksByApi() }.getOrElse { Log.w(HelperMain.TAG, "tasks: ${cause(it)}"); "" } } ?: ""
    }

    /** IActivityTaskManager.getTasks, whose parameters grew over the releases; the display is a hidden field of TaskInfo. */
    private fun tasksByApi(): String {
        val get = taskManager.javaClass.methods.filter { it.name == "getTasks" }.maxBy { it.parameterTypes.size }
        val args: Array<Any> =
            when (get.parameterTypes.size) {
                1 -> arrayOf(MAX_TASKS)
                3 -> arrayOf(MAX_TASKS, false, false)
                else -> arrayOf(MAX_TASKS, false, false, -1)
            }
        val list = get.invoke(taskManager, *args) as List<*>
        val out = StringBuilder()
        for (task in list) {
            val info = task as? ActivityManager.RunningTaskInfo ?: continue
            val display = runCatching { info.javaClass.getField("displayId").getInt(info) }.getOrDefault(0)
            val top = info.topActivity?.flattenToShortString() ?: info.baseActivity?.flattenToShortString() ?: "?"
            out.append(info.taskId).append(' ').append(display).append(' ').append(top).append('\n')
        }
        return out.toString()
    }

    override fun focusDisplay(displayId: Int) {
        enforce()
        asShell {
            val top =
                tasksByApi().lineSequence().map { it.split(' ') }.firstOrNull { it.size >= 2 && it[1] == displayId.toString() }
                    ?: return@asShell
            task("setFocusedTask", top[0].toInt())
        }
    }

    override fun focusTask(taskId: Int) {
        enforce()
        asShell { task("setFocusedTask", taskId) }
    }

    override fun removeTask(taskId: Int) {
        enforce()
        asShell { task("removeTask", taskId) }
    }

    override fun moveTaskToDisplay(
        taskId: Int,
        displayId: Int,
    ) {
        enforce()
        asShell {
            val move = taskManager.javaClass.methods.first { it.name == "moveRootTaskToDisplay" }
            move.invoke(taskManager, taskId, displayId)
        }
    }

    private fun task(
        method: String,
        taskId: Int,
    ) {
        taskManager.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(taskManager, taskId)
    }

    override fun expandNotifications() {
        enforce()
        worker.execute { exec(listOf("cmd", "statusbar", "expand-notifications")) }
    }

    // ---- input ----

    override fun injectMotion(
        displayId: Int,
        event: MotionEvent,
    ) {
        enforce()
        send(displayId, event)
    }

    override fun injectKey(
        displayId: Int,
        event: KeyEvent,
    ) {
        enforce()
        send(displayId, event)
    }

    private fun send(
        displayId: Int,
        event: InputEvent,
    ) {
        if (!shown.containsKey(displayId)) return
        asShell {
            setDisplayId.invoke(event, displayId)
            inject.invoke(inputManager, event, INJECT_ASYNC)
        }
    }

    // ---- plumbing ----

    /** A binder call arrives as the launcher; the system must see the shell user asking. */
    private fun <T> asShell(block: () -> T): T? {
        val identity = Binder.clearCallingIdentity()
        try {
            return block()
        } catch (e: Throwable) {
            Log.w(HelperMain.TAG, "failed: ${cause(e)}")
            return null
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun exec(command: List<String>) {
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() != 0 || output.contains("Error")) Log.w(HelperMain.TAG, "${command.joinToString(" ")}: $output")
        }.onFailure { Log.w(HelperMain.TAG, "exec ${command.firstOrNull()}: $it") }
    }

    private fun cause(e: Throwable): Throwable = if (e is InvocationTargetException) e.cause ?: e else e

    companion object {
        const val MAX_TASKS = 100
        const val INJECT_ASYNC = 0

        /** WindowManager.DISPLAY_IME_POLICY_HIDE. */
        const val IME_POLICY_HIDE = 2

        // DisplayManager's VIRTUAL_DISPLAY_FLAG_*, the hidden ones by value. A public display with its own content that
        // takes touches and turns with its app, trusted (may hold any activity and take injected input), in its own
        // group and unlocked with the device, without touch feedback, with its own focus.
        private const val PUBLIC = 1
        private const val PRESENTATION = 1 shl 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val ROTATES_WITH_CONTENT = 1 shl 7
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15

        val FLAGS: Int =
            PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or TRUSTED or
                OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or
                (if (Build.VERSION.SDK_INT >= 34) OWN_FOCUS or DEVICE_DISPLAY_GROUP else 0)
    }
}
