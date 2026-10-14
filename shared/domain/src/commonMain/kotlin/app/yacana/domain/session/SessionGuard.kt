// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.session

import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.linkLost
import app.yacana.domain.equipment.linkTrouble
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.profile.SafetyOptions
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.SequenceRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The phone's battery, in percent; null while it is not known, or while it charges and only fills. */
fun interface PhoneBattery {
    fun percent(): Int?

    companion object {
        val Unknown = PhoneBattery { null }
    }
}

/** Why the session stopped safely. */
enum class SafeStopReason { LostLink, SequenceFailed, LowBattery }

/** What the session's guard is doing. */
sealed interface GuardState {
    /** All is well, or there is nothing to guard. */
    data object Watching : GuardState

    /**
     * The mount or the camera stopped answering at [since], and it reconnects: [attempt] attempts
     * so far, of [attempts] before the safe stop at [stopAt] when a sequence is running; without
     * one, it keeps trying and there is no safe stop.
     */
    data class Reconnecting(val since: Instant, val attempt: Int, val attempts: Int?, val stopAt: Instant?) : GuardState

    /** A safe stop for [reason]: [done] once the mount parked or stopped tracking, [parked] which; it waits for the link to do it. */
    data class SafeStop(val reason: SafeStopReason, val done: Boolean, val parked: Boolean) : GuardState
}

/**
 * The session's guard, every second while the app runs:
 * - When the mount or the camera stops answering, it puts the sequence in a safe pause and
 *   reconnects every [retry]. Back in time, the sequence goes on; after the profile's wait, or with
 *   a sequence that failed on its own, or with the phone's battery low, it stops safely: the
 *   exposure stops and the mount parks, or stops tracking, as the profile says, as soon as the
 *   link is there to do it.
 * - Without a sequence it only reconnects.
 */
class SessionGuard(
    private val equipment: EquipmentController,
    private val runner: SequenceRunner,
    private val capture: CaptureController,
    private val battery: PhoneBattery,
    scope: CoroutineScope,
    private val journal: Journal = Journal.None,
    private val clock: Clock = Clock.System,
    private val retry: Duration = RETRY,
) {
    private val mutableState = MutableStateFlow<GuardState>(GuardState.Watching)
    private val now = Channel<Unit>(Channel.CONFLATED)
    private var nextAttempt: Instant? = null
    private var handled: SequenceRun? = null

    val state: StateFlow<GuardState> = mutableState.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                check()
                withTimeoutOrNull(TICK) { now.receive() }
            }
        }
    }

    /** Tries to reconnect now, as "Retry now" asks, instead of at the next attempt. */
    fun retryNow() {
        nextAttempt = null
        now.trySend(Unit)
    }

    /** Forgets a safe stop once it is done and seen. */
    fun dismiss() {
        if ((state.value as? GuardState.SafeStop)?.done == true) mutableState.value = GuardState.Watching
    }

    private suspend fun check() {
        when (val current = state.value) {
            GuardState.Watching -> watch()
            is GuardState.Reconnecting -> reconnecting(current)
            is GuardState.SafeStop -> if (!current.done) safeStop(current.reason)
        }
    }

    private suspend fun watch() {
        val connected = (equipment.state.value.connection as? Connection.Connected)?.equipment ?: return
        val run = runner.state.value
        val running = run?.status == RunStatus.Running || run?.status == RunStatus.Paused
        val percent = battery.percent()
        when {
            connected.linkLost(clock.now()) -> lost(running)

            // Paused by a step that failed as the link stumbled, not by hand: once it answers well, it goes on.
            run?.status == RunStatus.Paused && run.error != null && !connected.linkTrouble(clock.now()) -> {
                journal.log("link_steady")
                runner.resume()
            }

            run != null && run.status == RunStatus.Failed && run !== handled -> {
                handled = run
                startSafeStop(SafeStopReason.SequenceFailed)
            }

            running && percent != null && percent <= max(options().lowBatteryPercent, CRITICAL_BATTERY) -> {
                runner.stop("battery at $percent %")
                startSafeStop(SafeStopReason.LowBattery)
            }
        }
    }

    private fun lost(running: Boolean) {
        val since = clock.now()
        val wait = options().waitMinutes.minutes
        journal.log("link_lost", "running" to running)
        runner.pause("link lost")
        nextAttempt = since + retry
        mutableState.value =
            GuardState.Reconnecting(
                since,
                attempt = 0,
                attempts = if (running) (wait / retry).roundToInt().coerceAtLeast(1) else null,
                stopAt = if (running) since + wait else null,
            )
    }

    private suspend fun reconnecting(current: GuardState.Reconnecting) {
        val run = runner.state.value
        val stopAt = current.stopAt
        when {
            // Disconnected by hand: nothing to wait for.
            equipment.state.value.connection == Connection.Disconnected -> mutableState.value = GuardState.Watching

            stopAt != null && clock.now() >= stopAt && run?.status == RunStatus.Paused -> {
                runner.stop("link lost for ${options().waitMinutes} min")
                startSafeStop(SafeStopReason.LostLink)
            }

            nextAttempt.let { it == null || clock.now() >= it } -> {
                val attempt = current.attempt + 1
                mutableState.value = current.copy(attempt = attempt)
                if (equipment.reconnect()) {
                    journal.log("link_back", "attempts" to attempt)
                    mutableState.value = GuardState.Watching
                    // A run paused by hand, with no reason, stays paused.
                    if (runner.state.value?.error != null) runner.resume()
                } else {
                    nextAttempt = clock.now() + retry
                }
            }
        }
    }

    private suspend fun startSafeStop(reason: SafeStopReason) {
        journal.log("safe_stop_needed", "reason" to reason.name)
        mutableState.value = GuardState.SafeStop(reason, done = false, parked = false)
        safeStop(reason)
    }

    /** The exposure stops, and the mount parks or stops tracking; without the link, it reconnects first. */
    @Suppress("TooGenericExceptionCaught") // Whatever fails, the next tick tries again.
    private suspend fun safeStop(reason: SafeStopReason) {
        val connected = (equipment.state.value.connection as? Connection.Connected)?.equipment
        if (connected == null || connected.linkLost(clock.now())) {
            if (nextAttempt.let { it == null || clock.now() >= it }) {
                nextAttempt = clock.now() + retry
                equipment.reconnect()
            }
            return
        }
        val park = options().park
        try {
            capture.abort()
            val mount = connected.mount
            if (park) mount?.parkAndWait() else mount?.setTracking(false)
            journal.log("safe_stop", "reason" to reason.name, "parked" to park)
            mutableState.value = GuardState.SafeStop(reason, done = true, parked = park)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            journal.log("safe_stop_error", "reason" to e.message)
        }
    }

    private fun options(): SafetyOptions = equipment.state.value.active?.options?.safety ?: SafetyOptions()

    private companion object {
        val TICK = 1.seconds

        /** How often it tries to reconnect: within 30 s of the WiFi coming back. */
        val RETRY = 10.seconds

        /** Under this, the session always stops safely. */
        const val CRITICAL_BATTERY = 10
    }
}
