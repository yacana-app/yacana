// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.solver

import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.CapturedFrame
import app.yacana.domain.imaging.frameHeaders
import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.profile.SolverOptions
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import app.yacana.domain.sky.cameraRotation
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.PlateSolver
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import app.yacana.ports.SolveRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What plate solving is doing. */
sealed interface SolveState {
    data object Idle : SolveState

    /** Solving, near the mount's position or [blind]. */
    data class Solving(val blind: Boolean) : SolveState

    /**
     * Solved with [database] in [elapsed]; [blind] when the mount's position did not help or there
     * was none. The frame was [frameWidth] × [frameHeight] pixels.
     */
    data class Solved(
        val solution: Solution,
        val blind: Boolean,
        val database: String,
        val elapsed: Duration,
        val frameWidth: Int,
        val frameHeight: Int,
    ) : SolveState

    data class Failed(val reason: SolveFailure) : SolveState
}

/** Why a solve did not give a position; the screen says it in the user's words. */
sealed interface SolveFailure {
    /** There is no frame to solve yet. */
    data object NoFrame : SolveFailure

    /** The profile's star database is not on the phone. */
    data class NoDatabase(val name: String) : SolveFailure

    /** The solver found no solution, near the mount nor blind, within the time allowed. */
    data class NotSolved(val database: String, val elapsed: Duration) : SolveFailure

    /** The solver could not run, or the frame was gone; [message] says why. */
    data class Error(val message: String) : SolveFailure
}

/**
 * Plate solving of the frame on screen with ASTAP: first within the profile's
 * search radius around where the mount pointed during the exposure, then the whole sky, with the
 * profile's database, downsampling and time limit.
 */
class PlateSolving(
    private val equipment: EquipmentController,
    private val capture: CaptureController,
    private val solver: PlateSolver,
    private val scope: CoroutineScope,
    private val journal: Journal = Journal.None,
) {
    private val mutableState = MutableStateFlow<SolveState>(SolveState.Idle)
    private var job: Job? = null

    val state: StateFlow<SolveState> = mutableState.asStateFlow()

    /** The databases on the phone, such as `D50`. */
    fun installedDatabases(): Set<String> = solver.installedDatabases()

    /** Solves the last frame; its result replaces the one before. */
    fun solveLastFrame() {
        if (job?.isActive == true) return
        val plan = plan(capture.state.value.last) ?: return
        job = scope.launch { run(plan, blindAllowed = true) }
    }

    /**
     * Solves [frame] now, as [solveLastFrame] does, and returns how it went, which [state] shows
     * too. Without [blind], it only searches near the mount.
     */
    suspend fun solve(frame: CapturedFrame, blind: Boolean = true): SolveState {
        plan(frame)?.let { run(it, blind) }
        return state.value
    }

    /** What to solve and how, or null with the reason in [state]. */
    private fun plan(last: CapturedFrame?): Plan? {
        val options = equipment.state.value.active?.options ?: ProfileOptions()
        val database = options.starDatabase
        if (last == null || database !in solver.installedDatabases()) {
            mutableState.value = SolveState.Failed(if (last == null) SolveFailure.NoFrame else SolveFailure.NoDatabase(database))
            return null
        }
        val taken = last.capture
        val scale = taken.optics.pixelScaleArcsec(taken.camera.pixelSizeX)?.times(taken.binY)
        return Plan(
            frame = last.frame,
            headers = frameHeaders(taken),
            database = database,
            // ASTAP wants the frame's height in degrees; 0 lets it find the scale itself.
            fieldHeightDeg = scale?.let { last.frame.height * it / ARCSEC_PER_DEGREE } ?: 0.0,
            // The mount's position during the exposure, in J2000 as ASTAP wants it.
            hint = taken.mount?.let { mountState ->
                val position = SkyPosition(mountState.rightAscensionHours, mountState.declinationDeg)
                mount()?.fromMount(position, taken.start) ?: position
            },
            options = options.solver,
        )
    }

    private fun mount() = (equipment.state.value.connection as? Connection.Connected)?.equipment?.mount

    /** Keeps the camera's rotation in the profile, for the framing, when it changed by more than a hair. */
    private fun keepRotation(rotationDeg: Double) {
        val profile = equipment.state.value.active ?: return
        val kept = profile.optics.measuredRotationDeg
        if (kept != null && abs(kept - rotationDeg) < ROTATION_HAIR_DEG) return
        equipment.save(profile.copy(optics = profile.optics.copy(measuredRotationDeg = rotationDeg)))
    }

    private suspend fun run(plan: Plan, blindAllowed: Boolean) {
        val started = TimeSource.Monotonic.markNow()
        val timeout = plan.options.timeoutSeconds.seconds
        var blind = plan.hint == null
        mutableState.value = SolveState.Solving(blind)
        try {
            var solution = solver.solve(plan.frame, plan.headers, plan.request(plan.hint, timeout))
            val left = timeout - started.elapsedNow()
            val blindNext = !blind && blindAllowed && left.isPositive()
            if (solution == null && blindNext) {
                blind = true
                mutableState.value = SolveState.Solving(blind = true)
                solution = solver.solve(plan.frame, plan.headers, plan.request(null, left))
            }
            val elapsed = started.elapsedNow()
            mutableState.value =
                solution?.let { SolveState.Solved(it, blind, plan.database, elapsed, plan.frame.width, plan.frame.height) }
                    ?: SolveState.Failed(SolveFailure.NotSolved(plan.database, elapsed))
            solution?.yAxisDeg?.let { keepRotation(cameraRotation(it, plan.frame.width, plan.frame.height)) }
            journal.log(
                "plate_solve",
                "database" to plan.database,
                "blind" to blind,
                "solved" to (solution != null),
                "ms" to elapsed.inWholeMilliseconds,
                "ra" to solution?.centre?.raHours,
                "dec" to solution?.centre?.decDeg,
                "rotationDeg" to solution?.rotationDeg,
                "scaleArcsec" to solution?.scaleArcsec,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A solver that cannot run, or a frame freed by a newer one: the reason is shown.
            mutableState.value = SolveState.Failed(SolveFailure.Error(e.message ?: e::class.simpleName.orEmpty()))
            journal.log("plate_solve_error", "reason" to e.message)
        }
    }

    /** One frame to solve with the profile's options; near [hint] first if there is one. */
    private class Plan(
        val frame: Frame,
        val headers: List<HeaderCard>,
        val database: String,
        val fieldHeightDeg: Double,
        val hint: SkyPosition?,
        val options: SolverOptions,
    ) {
        fun request(near: SkyPosition?, time: Duration) =
            SolveRequest(database, fieldHeightDeg, near, if (near == null) WHOLE_SKY else options.searchRadiusDeg, options.downsample, time)
    }

    /** Forgets the last result, once its sheet is closed. */
    fun dismiss() {
        if (job?.isActive != true) mutableState.value = SolveState.Idle
    }

    private companion object {
        const val ARCSEC_PER_DEGREE = 3600.0
        const val WHOLE_SKY = 180.0
    }
}

/** A change of the camera's rotation under this is the solve's own scatter. */
private const val ROTATION_HAIR_DEG = 0.05
