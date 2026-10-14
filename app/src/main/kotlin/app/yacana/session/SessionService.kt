// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.yacana.MainActivity
import app.yacana.R
import app.yacana.YacanaApplication
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentState
import app.yacana.domain.imaging.CaptureActivity
import app.yacana.domain.imaging.CaptureSettings
import app.yacana.domain.imaging.CaptureState
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.overview
import app.yacana.domain.session.GuardState
import app.yacana.domain.session.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * The night session: a foreground service of type `connectedDevice`, which Android 15+
 * does not stop after 6 hours, running while the equipment is connected. It holds a
 * partial wake lock and WiFi locks, so capture goes on with the screen off, and shows a
 * notification with what is happening and a button that disconnects.
 *
 * If the system kills the process, it restarts the service, which connects the profile again
 * and resumes the sequence or the loop that was running (the session journal records the restart
 * and why the process ended); after the phone restarts, [BootReceiver] starts it to resume a
 * sequence.
 */
@Suppress("TooManyFunctions") // The service lifecycle plus one small function per job.
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app get() = application as YacanaApplication
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lowLatencyLock: WifiManager.WifiLock? = null
    private var running = false

    // While a restart connects again, a failed attempt does not end the session.
    @Volatile private var reconnecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted the service after the process died.
        val restarted = intent == null || intent.action == ACTION_RESTORE
        return when {
            intent?.action == ACTION_STOP -> {
                app.journal.log("stopped_from_notification")
                app.capture.abort()
                app.equipment.disconnect()
                START_NOT_STICKY
            }

            intent == null && !prefs.getBoolean(KEY_ACTIVE, false) -> {
                stopSelf()
                START_NOT_STICKY
            }

            else -> {
                if (!running) start(restarted)
                START_STICKY
            }
        }
    }

    private fun start(restarted: Boolean) {
        running = true
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.session_reconnecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        acquireLocks()
        prefs.edit { putBoolean(KEY_ACTIVE, true) }
        if (restarted) resume()
        watch()
    }

    /** After a restart: logs why the process ended, connects again, and resumes the sequence or the loop that was running. */
    private fun resume() {
        val probes = DeviceProbes(this)
        val exits = probes.processExitsSince(prefs.getLong(KEY_LAST_EXIT, 0))
        exits.forEach {
            app.journal.log("process_exit", "at" to it.timestampMillis, "reason" to it.reason, "description" to it.description)
        }
        exits.maxOfOrNull { it.timestampMillis }?.let { newest -> prefs.edit { putLong(KEY_LAST_EXIT, newest) } }
        val loop = prefs.getBoolean(KEY_LOOPING, false)
        // The sequence going on comes back as a safe pause, unless BootReceiver brought it back already.
        app.sequence.restore()
        val sequence = app.sequence.state.value?.status == RunStatus.Paused
        app.journal.log("session_restarted", "resumeLoop" to loop, "resumeSequence" to sequence)
        reconnecting = true
        scope.launch {
            val connected = connectAgain()
            reconnecting = false
            when {
                !connected -> Unit

                sequence -> app.sequence.resume()

                loop -> {
                    restoreSettings()?.let(app.capture::update)
                    app.capture.startLoop()
                }
            }
        }
    }

    /** Connects the profile, trying again for a while: after the phone restarts, its WiFi takes a moment. */
    private suspend fun connectAgain(): Boolean {
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(CONNECT_RETRY_MILLIS)
            app.equipment.connect()
            val state = app.equipment.state.first { it.connection is Connection.Connected || it.connection is Connection.Failed }
            if (state.connection is Connection.Connected) return true
        }
        return false
    }

    /** Keeps the notification up to date and stops the session when the equipment is disconnected. */
    @OptIn(FlowPreview::class)
    private fun watch() {
        scope.launch {
            combine(app.equipment.state, app.capture.state, app.sequence.state) { equipment, capture, run ->
                Triple(equipment, capture, run)
            }
                .sample(NOTIFICATION_EVERY_MILLIS)
                .collect { (equipment, capture, run) ->
                    if (capture.looping != prefs.getBoolean(KEY_LOOPING, false)) {
                        prefs.edit { putBoolean(KEY_LOOPING, capture.looping) }
                        if (capture.looping) saveSettings(capture.settings)
                    }
                    val ended = equipment.connection is Connection.Disconnected || equipment.connection is Connection.Failed
                    // While the guard reconnects, or waits for the link to stop safely, a failed attempt does not end
                    // the session: without it, Android cuts the app's network with the screen off.
                    val guard = app.guard.state.value
                    val guarding = guard is GuardState.Reconnecting || (guard is GuardState.SafeStop && !guard.done)
                    val keep = reconnecting || (guarding && equipment.connection is Connection.Failed)
                    if (ended && !keep) {
                        prefs.edit { putBoolean(KEY_ACTIVE, false) }
                        stopSession()
                    } else if (!ended) {
                        getSystemService(
                            NotificationManager::class.java,
                        ).notify(NOTIFICATION_ID, sessionNotification(equipment, capture, run))
                    }
                }
        }
    }

    /** The loop's settings, so that a loop resumed after a restart takes the same frames. */
    private fun saveSettings(settings: CaptureSettings) = prefs.edit {
        putFloat(KEY_EXPOSURE, settings.exposureSeconds.toFloat())
        putInt(KEY_GAIN, settings.gain ?: NONE)
        putInt(KEY_OFFSET, settings.offset ?: NONE)
        putInt(KEY_BINNING, settings.binning)
        putString(KEY_TYPE, settings.type.name)
        putString(KEY_TARGET, settings.target)
    }

    private fun restoreSettings(): CaptureSettings? {
        if (!prefs.contains(KEY_EXPOSURE)) return null
        return CaptureSettings(
            exposureSeconds = prefs.getFloat(KEY_EXPOSURE, 1f).toDouble(),
            gain = prefs.getInt(KEY_GAIN, NONE).takeIf { it != NONE },
            offset = prefs.getInt(KEY_OFFSET, NONE).takeIf { it != NONE },
            binning = prefs.getInt(KEY_BINNING, 1),
            type = FrameType.entries.firstOrNull { it.name == prefs.getString(KEY_TYPE, null) } ?: FrameType.Light,
            target = prefs.getString(KEY_TARGET, null).orEmpty(),
            save = true,
        )
    }

    /** While a sequence goes on, its target, frame and progress, as a bar on the lock screen too; else the capture's. */
    private fun sessionNotification(equipment: EquipmentState, capture: CaptureState, run: SequenceRun?): Notification {
        val going = run?.status == RunStatus.Running || run?.status == RunStatus.Paused
        if (run == null || !going) return notification(status(equipment, capture), equipment.active?.name)
        val overview = run.overview(Clock.System.now(), equipment.active?.options?.dither ?: DitherOptions())
        val exposures = overview.exposures
        val percent = (overview.progress * PERCENT).roundToInt()
        val text =
            if (exposures != null) {
                getString(R.string.session_sequence, overview.name ?: calibration(exposures.type), overview.frame, exposures.count, percent)
            } else {
                status(equipment, capture)
            }
        return notification(text, equipment.active?.name, percent)
    }

    /** The end's frames, outside any target: their names, as astrophotographers say them in any language. */
    private fun calibration(type: FrameType) = when (type) {
        FrameType.Dark -> "Darks"
        FrameType.Bias -> "Bias"
        FrameType.Flat -> "Flats"
        else -> type.name
    }

    private fun status(equipment: EquipmentState, capture: CaptureState): String {
        val activity = capture.activity
        val connected = equipment.connection as? Connection.Connected
        return when {
            activity is CaptureActivity.Exposing -> getString(R.string.session_exposing, activity.seconds.toString(), capture.saved)

            activity == CaptureActivity.Downloading -> getString(R.string.session_downloading, capture.saved)

            capture.saved > 0 -> getString(R.string.session_saved, capture.saved)

            connected != null -> {
                val total = connected.equipment.profile.devices.byRole().size
                getString(R.string.session_connected, total - connected.equipment.failures.size, total)
            }

            else -> getString(R.string.session_reconnecting)
        }
    }

    private fun stopSession() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.takeIf { it.isHeld }?.release()
        lowLatencyLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        running = false
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout") // Held for the whole session, which can last all night.
    private fun acquireLocks() {
        wakeLock =
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yacana:session").apply { acquire() }
        val wifi = getSystemService(WifiManager::class.java)

        // Up to Android 13, the only lock that keeps the WiFi out of power save with the screen off;
        // Android 14 turns it into the low-latency one.
        @Suppress("DEPRECATION")
        val highPerf = WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(highPerf, "yacana:session").apply { acquire() }
        // With the screen on and Yacana in front, as the app keeps them: no power save, and the least latency.
        lowLatencyLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "yacana:session-low-latency").apply { acquire() }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String, title: String? = null, progress: Int? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yacana)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.session_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // The session's state on the lock screen, as the design "Lock screen".
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { progress?.let { setProgress(PERCENT, it, false) } }
            .build()
    }

    companion object {
        private const val ACTION_STOP = "app.yacana.session.STOP_SESSION"
        private const val ACTION_RESTORE = "app.yacana.session.RESTORE_SESSION"
        private const val PREFS = "session"
        private const val KEY_ACTIVE = "active"
        private const val KEY_LOOPING = "looping"
        private const val KEY_LAST_EXIT = "lastExit"
        private const val KEY_EXPOSURE = "exposure"
        private const val KEY_GAIN = "gain"
        private const val KEY_OFFSET = "offset"
        private const val KEY_BINNING = "binning"
        private const val KEY_TYPE = "type"
        private const val KEY_TARGET = "target"
        private const val NONE = Int.MIN_VALUE
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFICATION_EVERY_MILLIS = 1_000L
        private const val PERCENT = 100
        private const val CONNECT_ATTEMPTS = 6
        private const val CONNECT_RETRY_MILLIS = 10_000L

        /**
         * Starts the session if it is not running, when the user connects the equipment. Android
         * refuses to start it from the background; then the session is already running, after a
         * restart, or the user is not there to connect anything.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
            true
        } catch (_: IllegalStateException) {
            false
        }

        /** Whether a session was going on when the app or the phone went down: the equipment was not disconnected. */
        fun wasActive(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

        /** Starts the session after the phone restarts, to resume the sequence that was going on; false if Android refuses. */
        fun restore(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java).setAction(ACTION_RESTORE))
            true
        } catch (_: IllegalStateException) {
            false
        }
    }
}
