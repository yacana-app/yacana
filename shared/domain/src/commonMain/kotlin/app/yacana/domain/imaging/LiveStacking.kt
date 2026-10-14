// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import app.yacana.ports.ImageEngine
import app.yacana.ports.LiveStack
import app.yacana.ports.Preview
import app.yacana.ports.Region
import app.yacana.ports.StackInfo
import app.yacana.ports.StarDetection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What one live stack holds: the light frames of one night of one target, whose name tells its
 * panel apart, with one filter, exposure, gain and binning.
 */
data class StackKey(
    val night: LocalDate,
    val target: String?,
    val filter: String?,
    val exposureSeconds: Double,
    val gain: Int?,
    val binning: Int,
) {
    /** How its checkpoint knows it again after the app was closed. */
    val label: String get() = listOf(night, target.orEmpty(), filter.orEmpty(), exposureSeconds, gain ?: "", binning).joinToString("|")

    companion object {
        /** [capture]'s stack; its night is the date 12 hours before it began, as the file names'. */
        fun of(capture: Capture, zone: TimeZone) = StackKey(
            night = (capture.start - 12.hours).toLocalDateTime(zone).date,
            target = capture.target,
            filter = capture.filter,
            exposureSeconds = capture.exposureSeconds,
            gain = capture.gain,
            binning = capture.binX,
        )
    }
}

/**
 * What one live mosaic holds: the panels of one night of one mosaic, with one filter, exposure,
 * gain and binning.
 */
data class MosaicKey(
    val night: LocalDate,
    val mosaic: String,
    val filter: String?,
    val exposureSeconds: Double,
    val gain: Int?,
    val binning: Int,
) {
    companion object {
        /** [capture]'s mosaic, if it is of one. */
        fun of(capture: Capture, zone: TimeZone): MosaicKey? = capture.mosaic?.let { panel ->
            StackKey.of(capture, zone).let { MosaicKey(it.night, panel.mosaic, it.filter, it.exposureSeconds, it.gain, it.binning) }
        }
    }
}

/**
 * The live mosaic as the screens show it: [width] × [height] pixels, the corners of each of its
 * panels there, those [drawn] and the one [stacking]; [version] goes up as it changes.
 */
data class MosaicState(
    val name: String,
    val width: Int,
    val height: Int,
    val outlines: List<List<Pair<Double, Double>>>,
    val drawn: Set<Int>,
    val stacking: Int?,
    val version: Int,
)

/** The live stack as the screens show it. */
data class StackingState(
    /** What it stacks; null until the first light frame comes with stacking on. */
    val key: StackKey? = null,
    /** What it holds; null while it has no frame. */
    val info: StackInfo? = null,
    /** The light frames it left out because the history discards them, by why. */
    val discarded: Map<DiscardReason, Int> = emptyMap(),
    /** The light frames whose stars did not match the first frame's. */
    val notAligned: Int = 0,
    /** How the last frame added was turned from the first one, in degrees. */
    val rotationDeg: Double? = null,
    /** A frame is being added. */
    val adding: Boolean = false,
    /** Goes up each time the stack changes, for the screen to draw it again. */
    val version: Int = 0,
    /** Where it was last saved, and with how many frames. */
    val savedAs: String? = null,
    val savedFrames: Int = 0,
    val saving: Boolean = false,
    /** Why the last add, save or share failed. */
    val error: String? = null,
    /** What calibrated the last frame added. */
    val calibration: StackCalibration = StackCalibration(),
    /** The live mosaic, while the stack is of a mosaic's panel; Imaging shows it as the stack. */
    val mosaic: MosaicState? = null,
) {
    val frames: Int get() = info?.frames ?: 0

    /** The light frames that came: those in it and those left out. */
    val offered: Int get() = frames + discarded.values.sum() + notAligned
}

/**
 * Live stacking: each saved light frame that the history keeps goes into the stack
 * of its night, target, filter, exposure, gain and binning, aligned on that stack's first frame by
 * its stars, in the engine. A frame of another stack saves the one before and starts its own.
 *
 * The stack is saved in the frames' folder, as a linear 32-bit FITS and a JPG, with the name the
 * file pattern gives with `STACK` as its type and number (one file per stack, saved over): when
 * asked, every [SAVE_EVERY] while it grows, and when a loop or a sequence ends. Kept as well in the
 * app's own [checkpointPath], it goes on after the app was closed when the next frame is of the same stack.
 */
@Suppress("TooManyFunctions") // What the screens do with the stack, and each step of a frame going in.
class LiveStacking(
    private val engine: ImageEngine,
    private val store: FrameStore,
    /** The app's own file where the stack is kept, to go on after the app was closed; null to keep none. */
    private val checkpointPath: String? = null,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val journal: Journal = Journal.None,
    private val time: TimeSource = TimeSource.Monotonic,
    /** The master darks and flats the frames are calibrated with. */
    val calibration: Calibration = Calibration(engine, store, zone = zone, journal = journal),
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(StackingState())

    @Volatile
    private var stack: LiveStack? = null

    /** The stack's first frame, or the first after it was restored: its header and its name. */
    private var first: Capture? = null

    /** The live mosaic of the panels stacked one after another, its key, and the first frame of it, for its file's name. */
    private var mosaic: LiveMosaic? = null
    private var mosaicKey: MosaicKey? = null
    private var mosaicFirst: Capture? = null
    private var options = ProfileOptions()
    private var restoreTried = false
    private var kept: TimeMark? = null

    val state: StateFlow<StackingState> = mutableState.asStateFlow()

    /**
     * Offers the saved light frame [captured], with its [stars], for its stack; [discarded] says why
     * the history discards it, if it does. Takes as long as aligning and adding it, and a save
     * now and then: call it where the next frame does not wait for it.
     */
    suspend fun offer(captured: CapturedFrame, stars: StarDetection, discarded: DiscardReason?, options: ProfileOptions) = mutex.withLock {
        this.options = options
        val capture = captured.capture
        val key = StackKey.of(capture, zone())
        calibration.lightCame()
        if (!restoreTried) {
            restoreTried = true
            restore(key, capture)
        }
        if (key != state.value.key) {
            finish()
            // Another panel of the same mosaic goes on with it; anything else ends it.
            val mosaicNow = MosaicKey.of(capture, zone())
            if (mosaicNow != mosaicKey) finishMosaic()
            mutableState.value =
                StackingState(key = key, mosaic = state.value.mosaic.takeIf { mosaicNow != null && mosaicNow == mosaicKey })
        }
        if (discarded != null) {
            mutableState.update { it.copy(discarded = it.discarded + (discarded to (it.discarded[discarded] ?: 0) + 1)) }
            return@withLock
        }
        val current = stack ?: engine.newStack()?.also {
            stack = it
            first = capture
        } ?: return@withLock
        add(current, captured, stars, key)
    }

    /** Adds [captured] to [current], the stack of [key], and keeps and saves it when it is due. */
    private suspend fun add(current: LiveStack, captured: CapturedFrame, stars: StarDetection, key: StackKey) {
        val capture = captured.capture
        mutableState.update { it.copy(adding = true) }
        val (dark, flat) = calibration.forLight(capture)
        val addition =
            try {
                reported {
                    withContext(Dispatchers.Default) {
                        current.add(captured.frame, stars.stars, capture.exposureSeconds, dark?.master, flat?.master)
                    }
                }
            } finally {
                mutableState.update { it.copy(adding = false) }
            } ?: return
        val info = withContext(Dispatchers.Default) { current.info() }
        val used =
            StackCalibration(
                addition.dark,
                dark?.temperatureC.takeIf {
                    addition.dark
                },
                addition.flat,
                flat?.night.takeIf { addition.flat },
                flat?.file.takeIf { addition.flat },
            )
        mutableState.update {
            it.copy(
                info = info,
                notAligned = it.notAligned + if (addition.added) 0 else 1,
                rotationDeg = if (addition.added) addition.rotationDeg else it.rotationDeg,
                version = it.version + 1,
                calibration = if (addition.added) used else it.calibration,
            )
        }
        journal.log(
            "stack_frame",
            "start" to capture.start.toString(),
            "added" to addition.added,
            "frames" to addition.frames,
            "rotationDeg" to addition.rotationDeg,
            "shiftPx" to addition.shiftPx,
            "matched" to addition.matchedStars,
            "dark" to addition.dark,
            "flat" to addition.flat,
        )
        if (addition.added) drawMosaic(current, capture, info)
        val due = kept?.let { it.elapsedNow() >= SAVE_EVERY } ?: true
        if (addition.added && due) {
            kept = time.markNow()
            keep(current, key)
            if (info.frames >= MIN_FRAMES) saveNow(current)
        }
    }

    /** Saves the stack now, in the frames' folder; returns where, or null if it could not. */
    suspend fun save(): String? = mutex.withLock { stack?.let { saveNow(it) } }

    /** Saves the stack if it grew since it was last saved, as when a loop or a sequence ends, and the live mosaic. */
    suspend fun saveIfGrown() {
        mutex.withLock {
            saveMosaic()
            val current = stack ?: return@withLock
            if (state.value.frames >= MIN_FRAMES && state.value.frames > state.value.savedFrames) saveNow(current)
        }
    }

    /** The live mosaic as the screen draws it, in the stack's colour; null without one. Takes a moment: call it off the main thread. */
    suspend fun mosaicImage(): Preview? = mutex.withLock {
        val built = mosaic ?: return@withLock null
        withContext(Dispatchers.Default) { built.image(mosaicColour()) }
    }

    /** Starts the stack again: the next frame is the first of a new one. What it held is thrown away. */
    suspend fun reset() {
        mutex.withLock {
            close()
            dropMosaic()
            journal.log("stack_reset")
            mutableState.update { StackingState(key = it.key) }
        }
    }

    /** Stacking was turned off: saves the stack if it grew, and frees its memory. */
    suspend fun end() {
        mutex.withLock {
            finish()
            finishMosaic()
            mutableState.value = StackingState()
        }
    }

    /** [region] of the stack in [width] × [height] screen pixels, its colour [balanced] or as the camera gave it; null without one. */
    fun preview(balanced: Boolean, region: Region, width: Int, height: Int): Preview? = stack?.preview(balanced, region, width, height)

    /** The checkpoint at [checkpointPath], if it is of [key]'s stack, as the stack to go on with. */
    private fun restore(key: StackKey, capture: Capture) {
        val restored = checkpointPath?.let(engine::restoreStack) ?: return
        if (restored.label() != key.label) return restored.close()
        stack = restored
        first = capture
        val info = restored.info()
        mutableState.value = StackingState(key = key, info = info, version = 1)
        journal.log("stack_restored", "frames" to info.frames)
    }

    /** Keeps the stack at [checkpointPath], to go on with it after the app was closed. */
    private suspend fun keep(current: LiveStack, key: StackKey) {
        val path = checkpointPath ?: return
        reported { withContext(Dispatchers.Default) { current.checkpoint(path, key.label) } }
    }

    /**
     * Draws the panel [capture] is of into the live mosaic, from [current], its stack, at the
     * mosaic's size; the mosaic starts with the first panel that has a solution to place it by.
     */
    private suspend fun drawMosaic(current: LiveStack, capture: Capture, info: StackInfo) {
        val panel = capture.mosaic
        val solution = panel?.solution
        val key = MosaicKey.of(capture, zone())
        if (panel == null || solution == null || key == null) return
        if (key != mosaicKey) {
            finishMosaic()
            mosaicKey = key
        }
        val built = mosaic ?: LiveMosaic.of(panel, info.width, info.height)?.also {
            mosaic = it
            mosaicFirst = capture.copy(target = panel.mosaic)
        }
        val drawn =
            built != null &&
                reported {
                    withContext(Dispatchers.Default) {
                        val (width, height) = built.imageSize(info.width, info.height)
                        current.linear(width, height)?.let { built.draw(panel.index, solution, info.width, info.height, it) } != null
                    }
                } == true
        if (built != null && drawn) {
            mutableState.update {
                it.copy(
                    mosaic =
                    MosaicState(
                        name = panel.mosaic,
                        width = built.width,
                        height = built.height,
                        outlines = built.outlines,
                        drawn = built.drawn,
                        stacking = built.stacking,
                        version = (it.mosaic?.version ?: 0) + 1,
                    ),
                )
            }
        }
    }

    /** The colour of the stack being added to, as the mosaic's: balanced on its stars if they are enough. */
    private fun mosaicColour(): MosaicColour {
        val info = state.value.info
        return if (options.stacking.balanced && info != null && info.starsBalanced) {
            MosaicColour(info.redGain, info.greenGain, info.blueGain, balanced = true)
        } else {
            MosaicColour(balanced = options.stacking.balanced)
        }
    }

    /** Keeps the live mosaic as a JPG next to its panels' folders: after each panel, and as a loop or a sequence ends. */
    private suspend fun saveMosaic() {
        val built = mosaic ?: return
        val capture = mosaicFirst ?: return
        reported {
            val jpeg = withContext(Dispatchers.Default) { engine.jpeg(built.image(mosaicColour())) } ?: return@reported
            val path = store.saveJpeg(FileNames.renderStack(options.filePattern, capture, zone()) + MOSAIC_SUFFIX, jpeg)
            journal.log("mosaic_saved", "file" to path, "panels" to built.drawn.size)
        }
    }

    /** Saves the live mosaic and lets it go, as another mosaic or no mosaic comes. */
    private suspend fun finishMosaic() {
        saveMosaic()
        dropMosaic()
    }

    private fun dropMosaic() {
        mosaic = null
        mosaicKey = null
        mosaicFirst = null
        mutableState.update { it.copy(mosaic = null) }
    }

    /** Saves the stack if it grew since it was last saved, and frees it; a mosaic's panel, done, keeps the mosaic as it is now. */
    private suspend fun finish() {
        if (stack != null && mosaic != null) saveMosaic()
        val current = stack ?: return
        if (state.value.frames >= MIN_FRAMES && state.value.frames > state.value.savedFrames) saveNow(current)
        close()
    }

    private fun close() {
        stack?.close()
        stack = null
        first = null
        kept = null
    }

    private suspend fun saveNow(current: LiveStack): String? {
        val capture = first ?: return null
        mutableState.update { it.copy(saving = true) }
        return try {
            reported {
                val info = withContext(Dispatchers.Default) { current.info() }
                val path = FileNames.renderStack(options.filePattern, capture, zone())
                val savedAs = store.saveStack(current, path, stackHeaders(capture, info), options.stacking.balanced)
                journal.log("stack_saved", "file" to savedAs, "frames" to info.frames)
                mutableState.update { it.copy(savedAs = savedAs, savedFrames = info.frames, error = null) }
                savedAs
            }
        } finally {
            mutableState.update { it.copy(saving = false) }
        }
    }

    /** Runs [block]; if it fails, the screen says why and the stack goes on as it was. */
    @Suppress("TooGenericExceptionCaught") // Whatever failed is shown.
    private suspend fun <T> reported(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = e.message ?: e::class.simpleName
        journal.log("stack_error", "reason" to reason, "type" to e::class.simpleName)
        mutableState.update { it.copy(error = reason) }
        null
    }

    companion object {
        /** While it grows, the stack is kept and saved this often, so that a crash loses little of it. */
        val SAVE_EVERY = 10.minutes

        /** A stack is saved from two frames on: one is no stack. */
        const val MIN_FRAMES = 2

        /** The live mosaic's JPG is named as the mosaic's stack would be, with this after it. */
        const val MOSAIC_SUFFIX = "_MOSAIC"
    }
}
