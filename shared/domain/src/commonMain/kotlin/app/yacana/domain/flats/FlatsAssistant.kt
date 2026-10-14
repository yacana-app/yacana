// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.flats

import app.yacana.alpaca.CalibratorStatus
import app.yacana.alpaca.CoverStatus
import app.yacana.domain.equipment.ConnectedEquipment
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.FlatPanelMediator
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.CaptureSettings
import app.yacana.domain.imaging.DARK_FILTER
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/** Where the flats' light comes from: the flat panel, the dawn sky, or a white screen. */
enum class FlatSource { Panel, Sky, Screen }

/**
 * What the flats assistant takes: [count] flats of each of [filters] (none: the one in place)
 * whose mean is within [TOLERANCE] of [targetAdu]; with the panel, at [panelBrightness]; and
 * after them, with [darks], darks with each filter's exposure and bias, behind the dark filter.
 */
data class FlatsOptions(
    val source: FlatSource = FlatSource.Sky,
    val targetAdu: Int = 30_000,
    val count: Int = 30,
    val filters: List<String> = emptyList(),
    val panelBrightness: Int = 0,
    val darks: Boolean = false,
    val gain: Int? = null,
    val offset: Int? = null,
)

/** One filter's flats: the exposure found and how many are kept. */
data class FilterFlats(val filter: String?, val count: Int, val exposureSeconds: Double? = null, val done: Int = 0)

/** What the assistant is doing: getting the panel ready, finding the exposure, taking flats, darks, or how it ended. */
enum class FlatsPhase { Preparing, Finding, Taking, Darks, Done, Stopped, Failed }

/** Why the flats stopped: too much light at the shortest exposure, too little at the longest, or an error. */
sealed interface FlatsFailure {
    data object TooBright : FlatsFailure

    data object TooDark : FlatsFailure

    data class Error(val message: String) : FlatsFailure
}

/** The flats so far: the phase, each filter's, the one in [current], and the last frame's mean. */
data class FlatsState(
    val options: FlatsOptions,
    val phase: FlatsPhase,
    val filters: List<FilterFlats>,
    val current: Int = 0,
    val lastMean: Double? = null,
    val failure: FlatsFailure? = null,
    /** Paused by hand: the next frame waits. */
    val paused: Boolean = false,
) {
    /**
     * Whether the phone's own screen is the light now, all white at full brightness: with the
     * screen as the source, while the flats run and are not paused.
     */
    val screenLit: Boolean
        get() = options.source == FlatSource.Screen && phase in RUNNING_PHASES && !paused
}

private val RUNNING_PHASES = setOf(FlatsPhase.Preparing, FlatsPhase.Finding, FlatsPhase.Taking, FlatsPhase.Darks)

/**
 * The flats assistant: for each filter it finds the exposure whose mean is within ±5 % of
 * the target, from test frames that are not saved, each exposure scaled by how far the last mean
 * was; then it takes the flats, keeping only those within the tolerance and scaling the next
 * exposure the same way, as the dawn sky brightens. With the panel it closes the cover and
 * turns the light on first, and off at the end.
 */
class FlatsAssistant(
    private val equipment: EquipmentController,
    private val capture: CaptureController,
    private val scope: CoroutineScope,
    private val journal: Journal = Journal.None,
) {
    private val mutableState = MutableStateFlow<FlatsState?>(null)
    private var job: Job? = null

    /** The flats in progress, or the last; null before any. */
    val state: StateFlow<FlatsState?> = mutableState.asStateFlow()

    fun start(options: FlatsOptions) {
        if (job?.isActive == true) return
        val filters = options.filters.ifEmpty { listOf(null) }.map { FilterFlats(it, options.count) }
        mutableState.value = FlatsState(options, FlatsPhase.Preparing, filters)
        journal.log("flats_started", "source" to options.source.name, "target" to options.targetAdu, "filters" to options.filters)
        job = scope.launch { run(options) }
    }

    /** Pauses before the next frame; the one in progress ends. */
    fun pause() = mutableState.update { it?.takeIf { job?.isActive == true }?.copy(paused = true) ?: it }

    fun resume() = mutableState.update { it?.copy(paused = false) }

    /** Stops the flats now; the frame in progress is thrown away and the panel's light turned off. */
    fun stop() {
        if (job?.isActive != true) return
        job?.cancel()
        capture.abort()
        mutableState.update { it?.copy(phase = FlatsPhase.Stopped) }
        journal.log("flats_stopped")
        scope.launch { runCatching { equipment.connected()?.flatPanel?.lightOff() } }
    }

    /** Forgets the last flats, as when the assistant closes. */
    fun dismiss() {
        if (job?.isActive != true) mutableState.value = null
    }

    @Suppress("TooGenericExceptionCaught") // Whatever failed is shown.
    private suspend fun run(options: FlatsOptions) {
        var panel: FlatPanelMediator? = null
        val failure =
            try {
                val connected = equipment.connected() ?: error("The equipment is not connected")
                panel = connected.flatPanel.takeIf { options.source == FlatSource.Panel }
                panel?.let { prepare(it, options.panelBrightness) }
                flats(connected, options)
                darksAndBias(connected, options)
                // The flats and their bias become each filter's master flat, for the live stack.
                capture.calibrationDone()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Stop) {
                e.failure
            } catch (e: Exception) {
                FlatsFailure.Error(e.message ?: e::class.simpleName.orEmpty())
            }
        // The light goes off however the flats ended; a stop by hand turns it off itself.
        runCatching { panel?.lightOff() }
        mutableState.update { it?.copy(phase = if (failure == null) FlatsPhase.Done else FlatsPhase.Failed, failure = failure) }
        journal.log("flats_done", "failure" to failure?.toString())
    }

    /** Each filter's flats in turn. */
    private suspend fun flats(connected: ConnectedEquipment, options: FlatsOptions) {
        val camera = connected.camera?.info ?: error("The profile has no camera connected")
        // A flat longer than a minute means there is not enough light.
        val longest = camera.exposureMax.coerceAtMost(LONGEST_FLAT)
        val exposure = Exposure(options.targetAdu.toDouble(), camera.exposureMin, longest, camera.maxAdu.toDouble())
        var seconds = START_SECONDS.coerceIn(camera.exposureMin, longest)
        state.value?.filters.orEmpty().forEachIndexed { index, flats ->
            flats.filter?.let { select(connected, it) }
            mutableState.update { it?.copy(phase = FlatsPhase.Finding, current = index) }
            seconds = find(exposure, seconds, options)
            change(index) { it.copy(exposureSeconds = seconds) }
            mutableState.update { it?.copy(phase = FlatsPhase.Taking) }
            var done = 0
            while (done < flats.count) {
                val mean = measure(settings(seconds, FrameType.Flat, options)) { exposure.within(it) }
                if (exposure.within(mean)) done++
                change(index) { it.copy(done = done) }
                // The dawn sky brightens: the next frame gets the exposure that hits the target now.
                seconds = exposure.next(seconds, mean) ?: throw Stop(exposure.failure(mean))
            }
        }
    }

    /** The exposure within the tolerance, from frames that are not kept. */
    private suspend fun find(exposure: Exposure, start: Double, options: FlatsOptions): Double {
        var seconds = start
        repeat(MAX_TRIES) {
            val mean = measure(settings(seconds, FrameType.Flat, options)) { false }
            if (exposure.within(mean)) return seconds
            seconds = exposure.next(seconds, mean) ?: throw Stop(exposure.failure(mean))
        }
        throw Stop(exposure.failure(state.value?.lastMean ?: 0.0))
    }

    /**
     * Behind the dark filter, where no light reaches the sensor: darks with each filter's exposure
     * with [FlatsOptions.darks], and always [BIAS_FRAMES] bias, whose level each master flat keeps,
     * so that the live stack divides by it without darks too, as it could not on the second night.
     * Without a dark filter, neither.
     */
    private suspend fun darksAndBias(connected: ConnectedEquipment, options: FlatsOptions) {
        val wheel = connected.filterWheel
        val dark = wheel?.names?.indexOfFirst { it.equals(DARK_FILTER, ignoreCase = true) }?.takeIf { it >= 0 } ?: return
        mutableState.update { it?.copy(phase = FlatsPhase.Darks) }
        wheel.select(dark)
        if (options.darks) {
            val exposures = state.value?.filters.orEmpty().mapNotNull { it.exposureSeconds }.distinct()
            exposures.forEach { seconds -> repeat(options.count) { measure(settings(seconds, FrameType.Dark, options)) { true } } }
        }
        val bias = connected.camera?.info?.exposureMin ?: 0.0
        repeat(BIAS_FRAMES) { measure(settings(bias, FrameType.Bias, options)) { true } }
    }

    /** Takes a frame, after a pause, kept if [keep] says so of its mean; returns the mean. */
    private suspend fun measure(settings: CaptureSettings, keep: (Double) -> Boolean): Double {
        state.first { it?.paused != true }
        val mean = capture.takeMeasured(settings) { keep(it.mean) }.mean
        mutableState.update { it?.copy(lastMean = mean) }
        return mean
    }

    private fun change(index: Int, update: (FilterFlats) -> FilterFlats) = mutableState.update { state ->
        state?.copy(filters = state.filters.mapIndexed { at, flats -> if (at == index) update(flats) else flats })
    }

    private companion object {
        const val START_SECONDS = 1.0

        /** The bias the flats end with behind the dark filter, enough for their level. */
        const val BIAS_FRAMES = 10
        const val MAX_TRIES = 10
    }
}

/** Why the flats stop, as [FlatsFailure]. */
private class Stop(val failure: FlatsFailure) : Exception()

private fun EquipmentController.connected(): ConnectedEquipment? = (state.value.connection as? Connection.Connected)?.equipment

/** Closes the cover and turns the light on at [brightness], waiting for each. */
private suspend fun prepare(panel: FlatPanelMediator, brightness: Int) {
    withTimeout(PANEL_TIMEOUT) {
        if (panel.state.value.value?.cover != CoverStatus.NotPresent) {
            panel.close()
            panel.state.first { it.value?.cover == CoverStatus.Closed || it.value?.cover == CoverStatus.NotPresent }
        }
        panel.lightOn(brightness)
        panel.state.first { it.value?.light == CalibratorStatus.Ready }
    }
}

private suspend fun select(connected: ConnectedEquipment, filter: String) {
    val wheel = connected.filterWheel ?: return
    val position = wheel.names.indexOfFirst { it.equals(filter, ignoreCase = true) }.takeIf { it >= 0 }
    wheel.select(position ?: error("The filter wheel has no $filter"))
}

private fun settings(seconds: Double, type: FrameType, options: FlatsOptions) =
    CaptureSettings(exposureSeconds = seconds, gain = options.gain, offset = options.offset, type = type, save = true)

private val PANEL_TIMEOUT = 2.minutes

/** The longest flat, in seconds. */
private const val LONGEST_FLAT = 60.0

/** The tolerance of the flats' mean around the target: ±5 %. */
const val TOLERANCE = 0.05

/** How the exposure goes to [target] between the camera's [min] and [max], as a frame's mean says. */
internal class Exposure(private val target: Double, private val min: Double, private val max: Double, private val maxAdu: Double) {
    fun within(mean: Double) = abs(mean - target) <= target * TOLERANCE

    /**
     * The exposure that brings [mean] to the target, as the light is linear, at most 8 times longer
     * or shorter than [seconds]; a quarter of it when the frame is saturated. Null when it would go
     * beyond the shortest or the longest.
     */
    fun next(seconds: Double, mean: Double): Double? {
        val wanted =
            when {
                mean >= maxAdu * SATURATED -> seconds / QUARTER
                mean <= 0.0 -> seconds * STEP
                else -> (seconds * target / mean).coerceIn(seconds / STEP, seconds * STEP)
            }
        val next = wanted.coerceIn(min, max)
        // Stuck at a limit and still not there: there is too much light, or too little.
        return next.takeUnless { it == seconds && !within(mean) && (it == min || it == max) }
    }

    /** Why the exposure can go no further, as the last [mean] says: too much light, or too little. */
    fun failure(mean: Double): FlatsFailure = if (mean > target) FlatsFailure.TooBright else FlatsFailure.TooDark

    private companion object {
        const val SATURATED = 0.98
        const val QUARTER = 4.0
        const val STEP = 8.0
    }
}
