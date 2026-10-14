// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.polar

import app.yacana.alpaca.TelescopeAxis
import app.yacana.domain.equipment.Atmosphere
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.MountMediator
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.CapturedFrame
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.PolarAlignmentOptions
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import app.yacana.domain.solver.PlateSolving
import app.yacana.domain.solver.SolveFailure
import app.yacana.domain.solver.SolveState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/** What the polar alignment is doing. */
sealed interface PolarAlignmentState {
    data object Idle : PolarAlignmentState

    /** Measuring point [point] of 3, at [step]; [solveTimes] are how long the points before took to solve. */
    data class Measuring(val point: Int, val step: MeasureStep, val solveTimes: List<Duration>) : PolarAlignmentState

    /**
     * Turning the screws: the error measured with the three points, the error [current] at
     * [measuredAt], and the ones before it, newest first. [missed] counts frames in a row that
     * did not solve; [interval] is the time between the last two errors.
     */
    data class Adjusting(
        val northern: Boolean,
        val initial: PolarError,
        val current: PolarError,
        val measuredAt: Instant,
        val previous: List<PolarError> = emptyList(),
        val interval: Duration? = null,
        val missed: Int = 0,
    ) : PolarAlignmentState

    data class Failed(val reason: PolarFailure) : PolarAlignmentState
}

enum class MeasureStep { Exposing, Solving, Turning }

/** Why the polar alignment stopped. */
sealed interface PolarFailure {
    data object NoMount : PolarFailure

    /** The profile has no site: the error is measured against the pole of a site. */
    data object NoSite : PolarFailure

    /** The mount cannot turn its right ascension axis by itself (MoveAxis). */
    data object CannotMoveAxis : PolarFailure

    /** Point [point] did not solve; [reason] says why. */
    data class NotSolved(val point: Int, val reason: SolveFailure) : PolarFailure

    data class Error(val cause: Exception) : PolarFailure
}

/**
 * Three-point polar alignment, as N.I.N.A.'s TPPA plugin:
 * three frames, turning only the right ascension axis between them, give the mount's axis and its
 * error; then frames solved near where the mount points follow the error while the screws turn.
 * The frames are not saved; they show in Imaging.
 */
class PolarAlignment(
    private val equipment: EquipmentController,
    private val capture: CaptureController,
    private val solving: PlateSolving,
    private val scope: CoroutineScope,
    private val journal: Journal = Journal.None,
    private val clock: Clock = Clock.System,
    private val settle: Duration = SETTLE,
) {
    private val mutableState = MutableStateFlow<PolarAlignmentState>(PolarAlignmentState.Idle)
    private val mutableLast = MutableStateFlow<PolarAlignmentState.Adjusting?>(null)
    private var job: Job? = null

    val state: StateFlow<PolarAlignmentState> = mutableState.asStateFlow()

    /** How the last alignment was left, with its error and when it was measured; null before any. */
    val last: StateFlow<PolarAlignmentState.Adjusting?> = mutableLast.asStateFlow()

    /** The profile's options. */
    val options: PolarAlignmentOptions get() = equipment.state.value.active?.options?.polarAlignment ?: PolarAlignmentOptions()

    /** Measures from where the mount points now, then follows the error until [stop]. */
    fun start() {
        job?.cancel()
        job = scope.launch { run() }
    }

    /** Ends the alignment: the mount stops turning and the exposure in progress is thrown away. */
    fun stop() {
        job?.cancel()
        capture.abort()
        (state.value as? PolarAlignmentState.Adjusting)?.let { left ->
            mutableLast.value = left
            // How the screws left the axis, for the night's report.
            journal.log(
                "polar_alignment_finished",
                "altitudeArcsec" to left.current.altitudeDeg * ARCSEC_PER_DEGREE,
                "azimuthArcsec" to left.current.azimuthDeg * ARCSEC_PER_DEGREE,
                "totalArcsec" to left.current.totalDeg * ARCSEC_PER_DEGREE,
                "initialTotalArcsec" to left.initial.totalDeg * ARCSEC_PER_DEGREE,
            )
        }
        mutableState.value = PolarAlignmentState.Idle
        scope.launch { runCatching { mount()?.move(TelescopeAxis.Primary, 0.0) } }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever failed is shown.
    private suspend fun run() {
        val options = options
        val site = equipment.state.value.active?.location
        val mount = mount()
        val failure =
            when {
                mount == null -> PolarFailure.NoMount
                site == null -> PolarFailure.NoSite
                TelescopeAxis.Primary !in mount.capabilities.movableAxes -> PolarFailure.CannotMoveAxis
                else -> null
            }
        if (failure != null || mount == null || site == null) {
            mutableState.value = PolarAlignmentState.Failed(failure ?: PolarFailure.NoMount)
            return
        }
        journal.log("polar_alignment_started", "stepDeg" to options.stepDeg, "east" to options.east, "exposure" to options.exposureSeconds)
        try {
            if (mount.capabilities.canSetTracking) mount.setTracking(true)
            val measurement = measure(mount, site, options) ?: return
            journal.log(
                "polar_alignment_measured",
                "altitudeArcsec" to measurement.initial.altitudeDeg * ARCSEC_PER_DEGREE,
                "azimuthArcsec" to measurement.initial.azimuthDeg * ARCSEC_PER_DEGREE,
            )
            follow(measurement, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = PolarAlignmentState.Failed(PolarFailure.Error(e))
            journal.log("polar_alignment_error", "reason" to e.message)
        }
    }

    /** The three points, turning the axis between them; null with the reason in [state] if one does not solve. */
    private suspend fun measure(mount: MountMediator, site: Location, options: PolarAlignmentOptions): PolarErrorMeasurement? {
        val points = mutableListOf<SolvedPoint>()
        val solveTimes = mutableListOf<Duration>()
        for (point in 1..POINTS) {
            if (point > 1) {
                mutableState.value = PolarAlignmentState.Measuring(point, MeasureStep.Turning, solveTimes.toList())
                turn(mount, options)
            }
            mutableState.value = PolarAlignmentState.Measuring(point, MeasureStep.Exposing, solveTimes.toList())
            val frame = capture.takeForSolving(options.exposureSeconds, options.binning)
            mutableState.value = PolarAlignmentState.Measuring(point, MeasureStep.Solving, solveTimes.toList())
            val started = TimeSource.Monotonic.markNow()
            val solved = solving.solve(frame, blind = !options.hintOnly)
            if (solved !is SolveState.Solved) {
                val reason = (solved as? SolveState.Failed)?.reason ?: SolveFailure.NoFrame
                mutableState.value = PolarAlignmentState.Failed(PolarFailure.NotSolved(point, reason))
                return null
            }
            solveTimes += started.elapsedNow()
            points += SolvedPoint(solved.solution.centre, middle(frame))
        }
        return PolarErrorMeasurement(points, site, Atmosphere.STANDARD, options.refractionAdjustment)
    }

    /**
     * Turns the right ascension axis by the step, as the plugin's `MoveToNextPoint`: at the
     * fastest rate the mount allows up to the one asked for, until the mount says it has turned
     * that far, or twice the time it should take.
     */
    private suspend fun turn(mount: MountMediator, options: PolarAlignmentOptions) {
        val asked = options.rateDegPerSecond
        val rates = runCatching { mount.telescope.axisRates(TelescopeAxis.Primary) }.getOrDefault(emptyList())
        val rate =
            if (rates.isEmpty() ||
                rates.any { asked in it }
            ) {
                asked
            } else {
                rates.map { it.endInclusive }.filter { it < asked }.maxOrNull() ?: asked
            }
        val start = mount.position().raHours * DEGREES_PER_HOUR
        mount.move(TelescopeAxis.Primary, if (options.east) rate else -rate)
        try {
            withTimeoutOrNull((options.stepDeg / rate * TIMEOUT_FACTOR).seconds) {
                while (raDistance(mount.position().raHours * DEGREES_PER_HOUR, start) < options.stepDeg) delay(TURN_POLL)
            }
        } finally {
            mount.move(TelescopeAxis.Primary, 0.0)
        }
        withTimeoutOrNull(SLEW_END) {
            while (mount.telescope.slewing()) delay(TURN_POLL)
        }
        delay(settle)
    }

    /** Frames solved near the mount follow the error, until [stop]. */
    private suspend fun follow(measurement: PolarErrorMeasurement, options: PolarAlignmentOptions) {
        var shown =
            PolarAlignmentState.Adjusting(measurement.northern, measurement.initial, measurement.initial, measurement.reference.at)
        mutableState.value = shown
        while (true) {
            val frame = capture.takeForSolving(options.exposureSeconds, options.binning)
            val solved = solving.solve(frame, blind = false)
            shown =
                if (solved is SolveState.Solved) {
                    val at = middle(frame)
                    val current = measurement.current(SolvedPoint(solved.solution.centre, at))
                    shown.copy(
                        current = current,
                        measuredAt = at,
                        previous = (listOf(shown.current) + shown.previous).take(TRAIL),
                        interval = at - shown.measuredAt,
                        missed = 0,
                    )
                } else {
                    shown.copy(missed = shown.missed + 1)
                }
            mutableState.value = shown
        }
    }

    /** The middle of the frame's exposure, when its centre was where it was solved. */
    private fun middle(frame: CapturedFrame): Instant = frame.capture.start + (frame.capture.exposureSeconds / 2).seconds

    private fun mount(): MountMediator? = (equipment.state.value.connection as? Connection.Connected)?.equipment?.mount

    /** Whether [state]'s error is within the profile's tolerance, if it has one; the arrows show anyway. */
    fun withinTolerance(state: PolarAlignmentState.Adjusting): Boolean {
        val tolerance = options.toleranceArcmin
        return tolerance > 0 && state.current.totalDeg * ARCMIN_PER_DEGREE <= tolerance
    }

    private companion object {
        const val POINTS = 3
        const val TRAIL = 2
        const val DEGREES_PER_HOUR = 15.0
        const val ARCSEC_PER_DEGREE = 3600.0
        const val ARCMIN_PER_DEGREE = 60.0
        const val HALF_CIRCLE = 180.0
        const val TIMEOUT_FACTOR = 2.0
        val TURN_POLL = 100.milliseconds
        val SLEW_END = 30.seconds

        /** After turning, so the frame is not taken while the mount still shakes. */
        val SETTLE = 2.seconds

        /** The angle between two right ascensions in degrees, as the plugin's `Distance`. */
        fun raDistance(a: Double, b: Double) = HALF_CIRCLE - abs(abs(a - b) - HALF_CIRCLE)
    }
}
