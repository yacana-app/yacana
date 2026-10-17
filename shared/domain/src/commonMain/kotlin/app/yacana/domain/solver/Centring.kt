// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.solver

import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.MountMediator
import app.yacana.domain.equipment.hoursBetween
import app.yacana.domain.equipment.separationArcsec
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.CapturedFrame
import app.yacana.domain.profile.SolverOptions
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What centring is doing. The target is in J2000; [offArcsec] is how far the last frame was from it. */
sealed interface CentringState {
    data object Idle : CentringState

    data class Working(val target: SkyPosition, val attempt: Int, val step: CentringStep, val offArcsec: Double? = null) : CentringState

    /** Centred: [solution] is the last frame's, where the frames that follow point. */
    data class Centred(val target: SkyPosition, val attempts: Int, val offArcsec: Double, val solution: Solution? = null) : CentringState

    data class Failed(val target: SkyPosition, val attempts: Int, val reason: CentringFailure, val offArcsec: Double? = null) :
        CentringState
}

enum class CentringStep { Slewing, Exposing, Solving }

/** Why centring stopped short of the target. */
sealed interface CentringFailure {
    /** The profile has no mount connected. */
    data object NoMount : CentringFailure

    /** A frame did not solve; [reason] says why. */
    data class NotSolved(val reason: SolveFailure) : CentringFailure

    /** After the last attempt the frame was still further than the tolerance. */
    data object TooFar : CentringFailure

    /** A device failed, or the target is below the horizon limit. */
    data class Error(val cause: Exception) : CentringFailure
}

/**
 * Sync and centring with plate solving. Centring goes to a target, takes an unsaved
 * frame, solves it and measures how far its centre is from the target; until that is within the
 * profile's tolerance, it syncs the mount with the solution (or, if the mount cannot sync, aims
 * as far off the other way) and goes to the target again.
 */
@Suppress("TooManyFunctions") // Centring, its sync and stop, and the drift a sequence asks of a light.
class Centring(
    private val equipment: EquipmentController,
    private val capture: CaptureController,
    private val solving: PlateSolving,
    private val scope: CoroutineScope,
    private val journal: Journal = Journal.None,
    private val clock: Clock = Clock.System,
    private val settle: Duration = SETTLE,
) {
    private val mutableState = MutableStateFlow<CentringState>(CentringState.Idle)
    private var job: Job? = null

    val state: StateFlow<CentringState> = mutableState.asStateFlow()

    /** Whether the connected mount can be synced, so a solution can be given to it. */
    val canSync: Boolean get() = mount()?.capabilities?.canSync == true

    /** The profile's tolerance and exposure for centring. */
    val options: SolverOptions get() = equipment.state.value.active?.options?.solver ?: SolverOptions()

    /** Goes to [target], in J2000, and centres it; [state] follows each step. */
    fun centre(target: SkyPosition) {
        if (job?.isActive == true) return
        job = scope.launch { run(target) }
    }

    /** Goes to [target] and centres it now, as [centre] does, for the sequence; returns how it ended. */
    suspend fun centreNow(target: SkyPosition): CentringState {
        run(target)
        return state.value
    }

    /**
     * How far the centre of [frame] is from [target], in arcminutes, by its plate solve near the
     * mount, as N.I.N.A.'s Center After Drift measures it; null if it does not solve.
     */
    suspend fun driftArcmin(frame: CapturedFrame, target: SkyPosition): Double? =
        solution(frame)?.let { separationArcsec(it.centre, target) / ARCSEC_PER_ARCMIN }

    /** The plate solve of [frame] near the mount; null if it does not solve. */
    suspend fun solution(frame: CapturedFrame): Solution? = (solving.solve(frame, blind = false) as? SolveState.Solved)?.solution

    /** Centres coordinates typed in the mount's own system, as the mount screen shows them. */
    fun centreMountCoordinates(rightAscensionHours: Double, declinationDeg: Double) {
        val mount = mount() ?: return centre(SkyPosition(rightAscensionHours, declinationDeg))
        centre(mount.fromMount(SkyPosition(rightAscensionHours, declinationDeg), clock.now()))
    }

    /** Forgets how the last centring ended, once the user does something else. */
    fun dismiss() {
        if (job?.isActive != true) mutableState.value = CentringState.Idle
    }

    /** Stops centring: the mount stops where it is and the exposure in progress is thrown away. */
    fun stop() {
        if (job?.isActive != true) return
        job?.cancel()
        capture.abort()
        mutableState.value = CentringState.Idle
        scope.launch { runCatching { mount()?.stop() } }
    }

    /**
     * Tells the mount it points at [solution], and returns how far from it the mount then
     * says it is, in arcseconds.
     */
    suspend fun sync(solution: Solution): Double {
        val mount = mount() ?: error("Connect a mount in Equipment first")
        val there = mount.toMount(solution.centre, clock.now())
        mount.sync(there.raHours, there.decDeg)
        return separationArcsec(there, mount.position()).also { journal.log("mount_sync", "offArcsec" to it) }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever failed is shown.
    private suspend fun run(target: SkyPosition) {
        journal.log("centring_started", "ra" to target.raHours, "dec" to target.decDeg)
        val mount = mount()
        val end =
            try {
                if (mount == null) CentringState.Failed(target, 0, CentringFailure.NoMount) else attempts(target, mount, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val working = state.value as? CentringState.Working
                CentringState.Failed(target, working?.attempt ?: 0, CentringFailure.Error(e), working?.offArcsec)
            }
        finish(end)
    }

    /** Goes, takes a frame and solves it, until the frame is within the tolerance or the attempts run out. */
    private suspend fun attempts(target: SkyPosition, mount: MountMediator, settings: SolverOptions): CentringState {
        var aim = target
        var off: Double? = null
        var attempt = 0
        var end: CentringState? = null
        while (end == null) {
            attempt++
            step(target, attempt, CentringStep.Slewing, off)
            mount.goToAndWait(mount.toMount(aim, clock.now()))
            delay(settle)
            step(target, attempt, CentringStep.Exposing, off)
            val frame = capture.takeForSolving(settings.centringExposureSeconds)
            step(target, attempt, CentringStep.Solving, off)
            val solved = solving.solve(frame)
            if (solved !is SolveState.Solved) {
                val reason = (solved as? SolveState.Failed)?.reason ?: SolveFailure.NoFrame
                end = CentringState.Failed(target, attempt, CentringFailure.NotSolved(reason), off)
                continue
            }
            val centre = solved.solution.centre
            val missed = separationArcsec(centre, target)
            off = missed
            journal.log("centring_attempt", "attempt" to attempt, "offArcsec" to missed)
            end =
                when {
                    missed <= settings.centringToleranceArcsec -> CentringState.Centred(target, attempt, missed, solved.solution)
                    attempt >= MAX_ATTEMPTS -> CentringState.Failed(target, attempt, CentringFailure.TooFar, missed)
                    else -> null
                }
            if (end == null) {
                if (mount.capabilities.canSync) {
                    sync(solved.solution)
                } else {
                    // Aim as far off the other way.
                    aim =
                        SkyPosition(
                            (aim.raHours + hoursBetween(centre.raHours, target.raHours)).mod(HOURS),
                            aim.decDeg + target.decDeg - centre.decDeg,
                        )
                }
            }
        }
        return end
    }

    private fun step(target: SkyPosition, attempt: Int, step: CentringStep, off: Double?) {
        mutableState.value = CentringState.Working(target, attempt, step, off)
    }

    private suspend fun finish(end: CentringState) = withContext(NonCancellable) {
        mutableState.value = end
        val (attempts, off) =
            when (end) {
                is CentringState.Centred -> end.attempts to end.offArcsec
                is CentringState.Failed -> end.attempts to end.offArcsec
                else -> 0 to null
            }
        journal.log("centring_done", "centred" to (end is CentringState.Centred), "attempts" to attempts, "offArcsec" to off)
    }

    private fun mount(): MountMediator? = (equipment.state.value.connection as? Connection.Connected)?.equipment?.mount

    private companion object {
        /** Attempts before giving up; the tolerance is usually reached in 3. */
        const val MAX_ATTEMPTS = 5
        const val ARCSEC_PER_ARCMIN = 60.0

        /** After a GoTo, so the frame is not taken while the mount still shakes. */
        val SETTLE = 2.seconds
        const val HOURS = 24.0
    }
}
