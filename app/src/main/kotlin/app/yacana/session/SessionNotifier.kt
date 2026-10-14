// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.yacana.MainActivity
import app.yacana.R
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SequenceRunner
import app.yacana.domain.session.GuardState
import app.yacana.domain.session.SafeStopReason
import app.yacana.domain.session.SessionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The night's notifications, which arrive with the screen off and show on the lock
 * screen. Alerts, with the channel's sound: the link lost, a safe stop, a failed sequence. Events,
 * silent: an autofocus that ends during the sequence, the link back and the end of the sequence.
 */
class SessionNotifier(
    private val context: Context,
    private val equipment: EquipmentController,
    private val runner: SequenceRunner,
    private val guard: SessionGuard,
    private val autofocus: Autofocus,
) {
    private val manager = NotificationManagerCompat.from(context)
    private var nextId = FIRST_ID

    /** Watches the session for as long as [scope] lives. */
    fun watch(scope: CoroutineScope) {
        createChannels()
        scope.launch { watchRun() }
        scope.launch { watchGuard() }
        scope.launch {
            // Each autofocus that ends while the sequence goes on.
            autofocus.last.drop(1).collect { result ->
                if (result != null && runner.state.value?.status == RunStatus.Running) {
                    event(context.getString(R.string.notify_autofocus, hfr(result.hfr)))
                }
            }
        }
    }

    private suspend fun watchRun() {
        var before = runner.state.value?.status
        runner.state.collect { run ->
            val status = run?.status
            if (status != before) {
                when (status) {
                    RunStatus.Finished -> {
                        val frames = run.done.values.sum()
                        event(context.resources.getQuantityString(R.plurals.notify_finished, frames, frames))
                    }

                    RunStatus.Failed -> alert(context.getString(R.string.notify_failed), run.error)

                    else -> Unit
                }
            }
            before = status
        }
    }

    private suspend fun watchGuard() {
        var before: GuardState = guard.state.value
        guard.state.collect { state ->
            val profile = equipment.state.value.active?.name.orEmpty()
            when {
                // Only a running sequence is in a safe pause; without one, the app only reconnects.
                state is GuardState.Reconnecting && before !is GuardState.Reconnecting ->
                    alert(
                        context.getString(R.string.notify_lost, profile),
                        context.getString(if (state.attempts != null) R.string.notify_lost_text else R.string.notify_lost_idle),
                    )

                state == GuardState.Watching && before is GuardState.Reconnecting -> event(context.getString(R.string.notify_back, profile))

                state is GuardState.SafeStop && state.done && !(before is GuardState.SafeStop && (before as GuardState.SafeStop).done) ->
                    alert(context.getString(R.string.notify_safe_stop), safeStopText(state))
            }
            before = state
        }
    }

    private fun safeStopText(stop: GuardState.SafeStop): String {
        val why =
            when (stop.reason) {
                SafeStopReason.LostLink -> R.string.notify_why_lost
                SafeStopReason.SequenceFailed -> R.string.notify_why_failed
                SafeStopReason.LowBattery -> R.string.notify_why_battery
            }
        val done = if (stop.parked) R.string.notify_parked else R.string.notify_tracking_stopped
        return "${context.getString(why)} · ${context.getString(done)}"
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERTS, context.getString(R.string.notify_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
        // Shown on the lock screen like the alerts, without a sound at night.
        manager.createNotificationChannel(
            NotificationChannel(EVENTS, context.getString(R.string.notify_channel_events), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun alert(title: String, text: String?) = post(ALERTS, title, text)

    private fun event(title: String) = post(EVENTS, title, null)

    @SuppressLint("MissingPermission") // Without the permission, granted in the first steps, Android drops it.
    private fun post(channel: String, title: String, text: String?) {
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_yacana)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(if (channel == ALERTS) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
                .build()
        manager.notify(nextId++, notification)
    }

    private fun hfr(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    private companion object {
        const val ALERTS = "night_alerts"
        const val EVENTS = "night_events"

        /** After the session's own notification, 2. */
        const val FIRST_ID = 100
    }
}
