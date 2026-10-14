// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// The run follows N.I.N.A.'s AutoFocusVM (StartAutoFocus, GetFocusPoints and
// ValidateCalculatedFocusPosition) and its OvershootBacklashCompensationDecorator, Copyright ©
// 2016-2026 Stefan Berg and the N.I.N.A. contributors, MPL-2.0: https://github.com/isbeorn/nina

package app.yacana.domain.focus

import app.yacana.domain.equipment.ConnectedEquipment
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.FocuserMediator
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.profile.AutofocusOptions
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What the autofocus is doing. */
sealed interface AutofocusState {
    data object Idle : AutofocusState

    /**
     * Measuring: the points so far, sorted by position, [expected] in all without extra ones, and
     * the [step] at [position]. [estimate] is the best focus the points give so far.
     */
    data class Running(
        val points: List<FocusPoint>,
        val expected: Int,
        val step: AutofocusStep,
        val position: Int?,
        val estimate: CurvePoint?,
        val temperature: Double?,
        val filter: String?,
        val options: AutofocusOptions,
        val initialPosition: Int,
    ) : AutofocusState

    data class Done(val result: AutofocusResult) : AutofocusState

    /** It stopped and the focuser went back to [initialPosition]. */
    data class Failed(val reason: AutofocusFailure, val points: List<FocusPoint>, val initialPosition: Int? = null) : AutofocusState
}

enum class AutofocusStep { Moving, Exposing }

/** Why an autofocus did not give a focus, in N.I.N.A.'s cases. */
sealed interface AutofocusFailure {
    data object NoFocuser : AutofocusFailure

    data object NoCamera : AutofocusFailure

    /** The focuser has no positions to go to (a relative one). */
    data object NotAbsolute : AutofocusFailure

    /** The points do not slope on either side of the lowest one. */
    data object NotEnoughSpread : AutofocusFailure

    /** The fit explains too little of the points: [rSquared] under [threshold]. */
    data class BadFit(val rSquared: Double, val threshold: Double) : AutofocusFailure

    /** The best focus falls outside the points measured. */
    data object OutsideCurve : AutofocusFailure

    /** At the best focus the HFR is more than 15 % worse than before (with no R² threshold). */
    data class Worse(val hfr: Double, val before: Double) : AutofocusFailure

    data class Error(val cause: Exception) : AutofocusFailure
}

/**
 * A finished autofocus: the focuser at [position], where the fit gives [hfr]; its points and
 * fitting, to draw the curve again; and the focuser's temperature and the filter then.
 */
data class AutofocusResult(
    val position: Int,
    val hfr: Double,
    val points: List<FocusPoint>,
    val fitting: FocusFitting,
    val initialPosition: Int,
    val temperature: Double?,
    val filter: String?,
    val at: Instant,
    val duration: Duration,
)

/**
 * Autofocus by HFR as N.I.N.A.'s: from [AutofocusOptions.offsetSteps] steps out, a point
 * at each step in, then more on the side that lacks them until there are as many on each side of
 * the lowest; then the fit's best focus, checked, and the focuser goes there. If it fails, the
 * focuser goes back to where it was. The frames are not saved; they show in Imaging. [previous] is
 * the last one that finished, if it is known from before.
 */
class Autofocus(
    private val equipment: EquipmentController,
    private val capture: CaptureController,
    private val scope: CoroutineScope,
    private val journal: Journal = Journal.None,
    private val clock: Clock = Clock.System,
    previous: AutofocusResult? = null,
) {
    private val mutableState = MutableStateFlow<AutofocusState>(AutofocusState.Idle)
    private val mutableLast = MutableStateFlow(previous)
    private val mutableRuns = MutableStateFlow(listOfNotNull(previous))
    private var job: Job? = null

    val state: StateFlow<AutofocusState> = mutableState.asStateFlow()

    /** The last autofocus that finished, for the focuser's screen and the triggers. */
    val last: StateFlow<AutofocusResult?> = mutableLast.asStateFlow()

    /** The autofocus runs that finished this session, [previous] first, for the history's chart. */
    val runs: StateFlow<List<AutofocusResult>> = mutableRuns.asStateFlow()

    val options: AutofocusOptions get() = equipment.state.value.active?.options?.autofocus ?: AutofocusOptions()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Runs an autofocus now, as [start] does, for the sequence; returns how it ended. */
    suspend fun runNow(): AutofocusState {
        run()
        return state.value
    }

    /** Stops: the exposure in progress is thrown away and the focuser goes back to where it was. */
    fun cancel() {
        job?.cancel()
        capture.abort()
    }

    /** Forgets how the last run ended, once its screen is closed. */
    fun dismiss() {
        if (job?.isActive != true) mutableState.value = AutofocusState.Idle
    }

    @Suppress("TooGenericExceptionCaught") // Whatever failed is shown.
    private suspend fun run() {
        val connected = (equipment.state.value.connection as? Connection.Connected)?.equipment
        val focuser = connected?.focuser
        val failure =
            when {
                focuser == null -> AutofocusFailure.NoFocuser
                !focuser.info.absolute -> AutofocusFailure.NotAbsolute
                connected.camera == null -> AutofocusFailure.NoCamera
                else -> null
            }
        if (failure != null || focuser == null) {
            mutableState.value = AutofocusState.Failed(failure ?: AutofocusFailure.NoFocuser, emptyList())
            return
        }
        val run = Run(focuser, connected, options, focuser.focuser.position())
        journal.log("autofocus_started", "position" to run.initial, "step" to run.options.stepSize, "fitting" to run.options.fitting.name)
        try {
            withTimeout(run.options.timeoutSeconds.seconds) { run.measure() }
        } catch (e: CancellationException) {
            run.restore()
            mutableState.value = AutofocusState.Idle
            throw e
        } catch (e: Exception) {
            run.fail(AutofocusFailure.Error(e))
        }
    }

    /** One autofocus, with the focuser where it started at [initial]. */
    private inner class Run(
        val focuser: FocuserMediator,
        val equipment: ConnectedEquipment,
        val options: AutofocusOptions,
        val initial: Int,
    ) {
        val started = clock.now()
        val mover = FocuserMover(focuser, options)
        val points = mutableListOf<FocusPoint>()
        var position = initial
        val filter = equipment.filterWheel?.let { wheel -> wheel.position.value.value?.let { wheel.names.getOrNull(it) } }

        suspend fun measure() {
            val initialHfr = if (options.rSquaredThreshold <= 0) measurePoint().first else Double.NaN
            val reverse = options.backlashIn > 0 && options.backlashOut == 0
            var attempt = 0
            var final: CurvePoint? = null
            var problem: AutofocusFailure?
            do {
                attempt++
                if (attempt > 1) mover.move(initial)
                points.clear()
                getFocusPoints(options.offsetSteps + 1, options.offsetSteps, reverse)
                problem = extend(options.offsetSteps)
                // Without slopes on either side, NINA does not try again.
                if (problem != null) break
                final = finalFocus(points, options.fitting)
                problem = if (final == null) AutofocusFailure.NotEnoughSpread else validate(final, initialHfr)
            } while (problem != null && attempt < options.attempts)
            if (problem == null && final != null) finish(final) else fail(problem ?: AutofocusFailure.NotEnoughSpread)
        }

        /**
         * More points to the left, then to the right, until each side of the lowest has
         * [offsetSteps], as NINA's loop: it stops early, and the fit is tried anyway, past its
         * limit of points or at position 0. Not enough spread when neither side slopes.
         */
        suspend fun extend(offsetSteps: Int): AutofocusFailure? {
            val maximum = options.framesPerPoint * options.offsetSteps * TEN
            var trend = TrendlineFitting(points)
            while (points.lacking(trend, offsetSteps)) {
                if (trend.left.points.isEmpty() && trend.right.points.isEmpty()) return AutofocusFailure.NotEnoughSpread
                addPoint(trend, offsetSteps)
                trend = TrendlineFitting(points)
                if (maximum < points.size || position == 0) {
                    journal.log("autofocus_stopped_extending", "points" to points.size, "position" to position)
                    break
                }
            }
            return null
        }

        /** One more point beyond the first, or else beyond the last, on the side that lacks them. */
        private suspend fun addPoint(trend: TrendlineFitting, offsetSteps: Int) {
            if (trend.left.points.size < offsetSteps && points.zeros(trend, left = true) < offsetSteps) {
                val first = points.first().position.toInt()
                if (position != first) position = mover.move(first)
                getFocusPoints(1, -1, reverse = false)
            } else if (trend.right.points.size < offsetSteps && points.zeros(trend, left = false) < offsetSteps) {
                val last = points.last().position.toInt()
                if (position != last) position = mover.move(last)
                getFocusPoints(1, 1, reverse = false)
            }
        }

        /** NINA's GetFocusPoints: from [offset] steps away, [count] points, a step in after each but the last. */
        suspend fun getFocusPoints(count: Int, offset: Int, reverse: Boolean) {
            val sign = if (reverse) -1 else 1
            if (offset != 0) position = mover.move(position + sign * offset * options.stepSize)
            repeat(count) { i ->
                val here = position
                val (hfr, error) = measurePoint()
                points += FocusPoint(here.toDouble(), hfr, max(MIN_ERROR, if (hfr == 0.0) NO_STARS_ERROR else error))
                points.sortBy { it.position }
                show(AutofocusStep.Moving)
                if (i < count - 1) position = mover.move(position - sign * options.stepSize)
            }
        }

        /** The average HFR of the point's frames, and its error, as NINA's EvaluateAllExposures. */
        suspend fun measurePoint(): Pair<Double, Double> {
            var sum = 0.0
            var variances = 0.0
            repeat(options.framesPerPoint) {
                show(AutofocusStep.Exposing)
                capture.takeForSolving(options.exposureSeconds, options.binning)
                val stars = capture.state.value.stars
                val hfr = stars?.averageHfr?.takeIf { !it.isNaN() } ?: 0.0
                val error = stars?.hfrStdDev?.takeIf { !it.isNaN() } ?: 0.0
                sum += hfr
                variances += error * error
            }
            return sum / options.framesPerPoint to sqrt(variances / options.framesPerPoint)
        }

        /** NINA's ValidateCalculatedFocusPosition: null when the focus is good, and the focuser is then there. */
        suspend fun validate(final: CurvePoint, initialHfr: Double): AutofocusFailure? {
            val outside = final.position < points.minOf { it.position } || final.position > points.maxOf { it.position }
            val problem = points.badFit(options) ?: if (outside) AutofocusFailure.OutsideCurve else null
            if (problem != null) return problem
            position = mover.move(final.position.toInt())
            val hfr = measurePoint().first
            val worse = options.rSquaredThreshold <= 0 && initialHfr != 0.0 && hfr > initialHfr * WORSE
            return if (worse) AutofocusFailure.Worse(hfr, initialHfr) else null
        }

        fun finish(final: CurvePoint) {
            val result =
                AutofocusResult(
                    position = final.position.toInt(),
                    hfr = final.hfr,
                    points = points.toList(),
                    fitting = options.fitting,
                    initialPosition = initial,
                    temperature = focuser.state.value.value?.temperature,
                    filter = filter,
                    at = clock.now(),
                    duration = clock.now() - started,
                )
            mutableLast.value = result
            mutableRuns.update { it + result }
            mutableState.value = AutofocusState.Done(result)
            journal.log(
                "autofocus_done",
                "position" to result.position,
                "hfr" to result.hfr,
                "points" to points.size,
                "temperature" to result.temperature,
                "fitting" to result.fitting.name,
                "rSquared" to points.rSquared(result.fitting),
            )
        }

        suspend fun fail(reason: AutofocusFailure) {
            restore()
            mutableState.value = AutofocusState.Failed(reason, points.toList(), initial)
            journal.log("autofocus_failed", "reason" to reason.toString(), "points" to points.size)
        }

        /** Back to where it started, even when cancelled. */
        suspend fun restore() = withContext(NonCancellable) { runCatching { mover.move(initial) } }

        fun show(step: AutofocusStep) {
            val estimate = if (points.size >= MIN_POINTS) finalFocus(points, options.fitting) else null
            mutableState.value =
                AutofocusState.Running(
                    points = points.toList(),
                    expected = 2 * options.offsetSteps + 1,
                    step = step,
                    position = position,
                    estimate = estimate,
                    temperature = focuser.state.value.value?.temperature,
                    filter = filter,
                    options = options,
                    initialPosition = initial,
                )
        }
    }

    private companion object {
        const val TEN = 10
        const val MIN_POINTS = 3
        const val MIN_ERROR = 0.001

        /** A point without stars counts for almost nothing in the fits, as in NINA. */
        const val NO_STARS_ERROR = 1000.0
        const val WORSE = 1.15
    }
}

/**
 * Moves the focuser as NINA's overshoot backlash compensation: past the target by the backlash of
 * the direction it moves in, when that stays within the focuser, and then back to it; and rests
 * after each move.
 */
private class FocuserMover(private val focuser: FocuserMediator, private val options: AutofocusOptions) {
    suspend fun move(target: Int): Int {
        val from = focuser.focuser.position()
        val overshoot =
            when {
                target < from && options.backlashIn != 0 -> target - options.backlashIn
                target > from && options.backlashOut != 0 -> target + options.backlashOut
                else -> null
            }
        if (overshoot != null && overshoot in 0..focuser.info.maxStep) {
            focuser.moveAndWait(overshoot)
            settle()
        }
        return focuser.moveAndWait(target).also { settle() }
    }

    private suspend fun settle() {
        if (options.settleSeconds > 0) delay(options.settleSeconds.seconds)
    }
}

/** The points without stars on one side of the lowest. */
private fun List<FocusPoint>.zeros(trend: TrendlineFitting, left: Boolean) = count {
    val side = if (left) it.position < trend.minimum.position else it.position > trend.minimum.position
    side && it.hfr == 0.0
}

/** Whether a side of the lowest has fewer than [offsetSteps] points, counting those without stars. */
private fun List<FocusPoint>.lacking(trend: TrendlineFitting, offsetSteps: Int) =
    trend.right.points.size + zeros(trend, left = false) < offsetSteps ||
        trend.left.points.size + zeros(trend, left = true) < offsetSteps

/** The fit's R², or its trend lines', under the threshold, if there is one. */
private fun List<FocusPoint>.badFit(options: AutofocusOptions): AutofocusFailure? {
    val threshold = options.rSquaredThreshold
    if (threshold <= 0) return null
    val worst = rSquared(options.fitting)
    return if (worst != null && worst < threshold) AutofocusFailure.BadFit(worst, threshold) else null
}

/** The lower R² of the curve and of its trend lines, as [fitting] uses them. */
private fun List<FocusPoint>.rSquared(fitting: FocusFitting): Double? {
    val curve =
        when (fitting) {
            FocusFitting.Hyperbolic, FocusFitting.TrendHyperbolic -> HyperbolicFitting(this).rSquared
            FocusFitting.Parabolic, FocusFitting.TrendParabolic -> QuadraticFitting(this).rSquared
            FocusFitting.Trendlines -> null
        }
    val trend = if (fitting in TREND_FITTINGS) TrendlineFitting(this).let { minOf(it.left.rSquared, it.right.rSquared) } else null
    return listOfNotNull(curve, trend).minOrNull()
}

private val TREND_FITTINGS = setOf(FocusFitting.Trendlines, FocusFitting.TrendHyperbolic, FocusFitting.TrendParabolic)
