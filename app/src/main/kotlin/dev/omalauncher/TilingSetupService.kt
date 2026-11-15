package dev.omalauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.gladimdim.tiling.Tiling
import dev.gladimdim.tiling.adb.LocalAdbHelper
import dev.gladimdim.tiling.adb.LocalAdbHelper.Problem
import dev.gladimdim.tiling.adb.LocalAdbHelper.Step
import dev.omalauncher.ui.SettingsPage

// Fork: the tiling desktop's setup (ui/TilingSetupScreen.kt).

/**
 * Asks for Wireless Debugging's pairing code in a notification. Android shows the code in a dialog of its Settings
 * and takes it back the moment Settings is left, so it cannot be typed into the launcher: a notification's reply
 * field is typed into with Settings still in front. A service in the foreground, so the launcher keeps looking for
 * the dialog while it is not on screen itself.
 */
class TilingSetupService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var stopObserving: (() -> Unit)? = null
    private var shown: String? = null

    /** The pairing dialog's port the question was put up for. */
    private var asked: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, getString(R.string.tiling_setup_channel_progress), NotificationManager.IMPORTANCE_LOW))
        // High: the question comes up over Android's settings, where the code is being shown.
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.tiling_setup_channel), NotificationManager.IMPORTANCE_HIGH))
        // Before anything else: Android ends the app when a service started for the foreground does not go there.
        startForeground(ONGOING, progress(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        shown = "${LocalAdbHelper.step} ${LocalAdbHelper.problem}"
        if (!wanted || intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (stopObserving == null) {
            LocalAdbHelper.init(this)
            LocalAdbHelper.watch(this)
            stopObserving = LocalAdbHelper.observe(::refresh)
            main.postDelayed(::finish, GIVE_UP_MS)
        }
        if (intent?.action == ACTION_CODE) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
            // In place of the question and its spinning field while the code is tried.
            notifications.notify(ASK, answered())
            asked = ANSWERED
            LocalAdbHelper.pair(this, code)
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopObserving?.let {
            it()
            LocalAdbHelper.unwatch()
        }
        stopObserving = null
        super.onDestroy()
    }

    /** What the notifications say follows the pairing: called for every change of it. */
    private fun refresh() {
        val notifications = getSystemService(NotificationManager::class.java)
        when {
            Tiling.helperConnected -> {
                notifications.notify(RESULT, result(R.string.tiling_setup_note_ready, R.string.tiling_setup_note_ready_text))
                finish()
                return
            }
            // Paired, and the helper did not come up: nothing more to type, the rest is on the setup screen.
            LocalAdbHelper.paired && !LocalAdbHelper.busy -> {
                notifications.notify(RESULT, result(R.string.tiling_setup_note_unfinished, R.string.tiling_setup_note_unfinished_text))
                finish()
                return
            }
        }
        // The question is there for as long as a dialog of Android's is open with a code to give; a new dialog asks
        // anew. While a code is being tried, what stands in for the answered question stays.
        val port = if (LocalAdbHelper.busy) asked else LocalAdbHelper.pairingPort
        if (port != asked) {
            if (port != null) notifications.notify(ASK, asking()) else takeDownQuestion()
            asked = port
        }
        val state = "${LocalAdbHelper.step} ${LocalAdbHelper.problem}"
        if (state != shown) {
            shown = state
            notifications.notify(ONGOING, progress())
        }
    }

    /**
     * Android 15 keeps a notification that was answered, whatever its app cancels, until the app has put a newer one
     * in its place: so one more goes up before it is taken down. Not at once after the answer, when Android has not
     * yet marked it as answered and the newer one would come too early.
     */
    private fun takeDownQuestion() {
        val notifications = getSystemService(NotificationManager::class.java)
        if (asked == ANSWERED) notifications.notify(ASK, answered())
        notifications.cancel(ASK)
        asked = null
    }

    private fun finish() {
        wanted = false
        takeDownQuestion()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun builder(channel: String): Notification.Builder = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)

    /** A tap on it leads to the setup screen. Not for the question: leaving Android's settings takes the code back. */
    private fun Notification.Builder.opensSetup() = setContentIntent(activity(SettingsActivity.intent(this@TilingSetupService, SettingsPage.TilingSetup, null)))

    /** The service's own notification, quiet: where the setup stands, and the way to end it. */
    private fun progress(): Notification {
        val wrong = LocalAdbHelper.problem == Problem.WrongCode
        val title =
            when {
                LocalAdbHelper.step == Step.Pairing -> R.string.tiling_setup_note_pairing
                LocalAdbHelper.step == Step.Starting -> R.string.tiling_setup_note_starting
                wrong -> R.string.tiling_setup_note_wrong
                else -> R.string.tiling_setup_note_wait
            }
        return builder(CHANNEL_PROGRESS)
            .opensSetup()
            .setContentTitle(getString(title))
            .apply {
                if (LocalAdbHelper.busy) {
                    setProgress(0, 0, true)
                } else {
                    setContentText(getString(if (wrong) R.string.tiling_setup_note_wrong_text else R.string.tiling_setup_note_wait_text))
                    addAction(Notification.Action.Builder(null, getString(R.string.tiling_setup_note_stop), service(ACTION_STOP, mutable = false)).build())
                }
                // Android 12 otherwise holds a foreground service's notification back for ten seconds.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }.setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** A pairing dialog is open: the one notification that makes itself seen, with the field the code goes into. */
    private fun asking(): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.tiling_setup_note_code_label)).build()
        val reply =
            Notification.Action
                .Builder(null, getString(R.string.tiling_setup_note_code_action), service(ACTION_CODE, mutable = true))
                .addRemoteInput(input)
                .build()
        return builder(CHANNEL)
            .setContentTitle(getString(R.string.tiling_setup_note_code))
            .setContentText(getString(R.string.tiling_setup_note_code_text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(reply)
            .build()
    }

    /** In place of the question once it was answered, for the moment until it is taken down. */
    private fun answered(): Notification =
        builder(CHANNEL)
            .setContentTitle(getString(R.string.tiling_setup_note_pairing))
            .setOnlyAlertOnce(true)
            .build()

    /** How it ended, left behind when the service has gone. */
    private fun result(
        title: Int,
        text: Int,
    ): Notification =
        builder(CHANNEL)
            .opensSetup()
            .setContentTitle(getString(title))
            .setContentText(getString(text))
            .setAutoCancel(true)
            .build()

    private fun activity(intent: Intent): PendingIntent =
        PendingIntent.getActivity(this, 0, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The reply is written into the intent by Android, which needs it mutable. */
    private fun service(
        action: String,
        mutable: Boolean,
    ): PendingIntent {
        val mutability =
            when {
                !mutable -> PendingIntent.FLAG_IMMUTABLE
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
                else -> 0
            }
        return PendingIntent.getService(this, 0, Intent(this, TilingSetupService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    companion object {
        private const val CHANNEL = "tiling_setup"
        private const val CHANNEL_PROGRESS = "tiling_setup_progress"
        private const val ONGOING = 2
        private const val RESULT = 3
        private const val ASK = 4
        private const val ACTION_CODE = "dev.omalauncher.tiling.CODE"
        private const val ACTION_STOP = "dev.omalauncher.tiling.STOP"
        private const val KEY_CODE = "code"

        /** In place of a port in [asked]: the question was answered, and what stands in for it is still up. */
        private const val ANSWERED = -1

        /** Long enough to find Developer options for the first time; after it the setup screen starts it again. */
        private const val GIVE_UP_MS = 15 * 60_000L

        // Main thread only. A stop that comes before the service has started is kept for when it has.
        private var wanted = false

        /** Whether it is waiting for the code, for the screen that shows the setup. */
        val running get() = wanted

        /** From a screen the user is looking at: Android refuses a foreground service started from the background. */
        fun start(context: Context) {
            if (wanted) return
            wanted = true
            runCatching { context.startForegroundService(Intent(context, TilingSetupService::class.java)) }.onFailure { wanted = false }
        }

        /** Leaves a pairing that is under way to finish. */
        fun stop(context: Context) {
            if (!wanted || LocalAdbHelper.busy) return
            // Through onStartCommand, which puts it in the foreground first, as Android demands of every start.
            runCatching { context.startService(Intent(context, TilingSetupService::class.java).setAction(ACTION_STOP)) }.onFailure { wanted = false }
        }
    }
}
