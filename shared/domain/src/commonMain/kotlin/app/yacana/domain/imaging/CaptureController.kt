// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.alpaca.Binning
import app.yacana.alpaca.Camera
import app.yacana.alpaca.CameraInfo
import app.yacana.alpaca.GainControl
import app.yacana.alpaca.Subframe
import app.yacana.domain.equipment.CameraMediator
import app.yacana.domain.equipment.ConnectedEquipment
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.Dither
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentPlatform
import app.yacana.domain.equipment.FilterWheelMediator
import app.yacana.domain.equipment.LINK_HICCUP
import app.yacana.domain.equipment.linkTrouble
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import app.yacana.ports.Bayer
import app.yacana.ports.ColourStretch
import app.yacana.ports.Frame
import app.yacana.ports.FrameStatistics
import app.yacana.ports.HeaderCard
import app.yacana.ports.ImageEngine
import app.yacana.ports.LiveStack
import app.yacana.ports.MasterFrame
import app.yacana.ports.Region
import app.yacana.ports.StarDetection
import app.yacana.ports.Stretch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Where frames are saved: the folder the user picked, or the app's own. The platform implements it. */
interface FrameStore {
    /** The folder, as the user would recognise it. */
    val location: StateFlow<String>

    /**
     * Saves [frame] as a FITS file at [relativePath] plus `.fits` under the folder, without ever
     * leaving half a file under the final name, and returns the path to show.
     */
    suspend fun save(frame: Frame, relativePath: String, headers: List<HeaderCard>): String

    /**
     * Saves [stack] at [relativePath] plus `.fits`, with [headers], and as a JPG beside it, its
     * colour [balanced] or as the camera gave it, over the ones there; returns the FITS's
     * path to show. Neither is ever left half written under its final name.
     */
    suspend fun saveStack(stack: LiveStack, relativePath: String, headers: List<HeaderCard>, balanced: Boolean): String

    /**
     * Saves [master], a master flat, at [relativePath] plus `.fits` as a FITS file of 32-bit floats
     * with [headers], over the one there; returns the path to show. Where nothing is kept, nothing.
     */
    suspend fun saveMaster(master: MasterFrame, relativePath: String, headers: List<HeaderCard>): String = relativePath

    /** The flat at [relativePath] plus `.fits`, as [read] makes it from the file open for reading; null if there is none. */
    suspend fun readMaster(relativePath: String, read: (descriptor: Int) -> MasterFrame?): MasterFrame? = null

    /**
     * Saves [jpeg], the live mosaic, at [relativePath] plus `.jpg`, over the one there; returns the
     * path to show. Where nothing is kept, nothing.
     */
    suspend fun saveJpeg(relativePath: String, jpeg: ByteArray): String = relativePath
}

/** How the next frames are taken. A null gain or offset leaves the camera's own. */
data class CaptureSettings(
    val exposureSeconds: Double = 10.0,
    val gain: Int? = null,
    val offset: Int? = null,
    val binning: Int = 1,
    val type: FrameType = FrameType.Light,
    val target: String = "",
    /** Save single frames too; frames of a loop are always saved. */
    val save: Boolean = true,
    /**
     * A loop of lights dithers after every so many frames, as the sequence does, so that the
     * sensor's pattern does not build up into streaks in the stack while the mount drifts; 0 never.
     */
    val ditherEvery: Int = LOOP_DITHER_EVERY,
    /** The mosaic's panel these frames are of, for the live mosaic; none outside a mosaic. */
    val mosaic: MosaicPanel? = null,
)

/** A loop of lights dithers every 3 frames at first, as a sequence's target. */
const val LOOP_DITHER_EVERY = 3

/** Arcseconds per pixel for a camera whose scale the profile does not know, for a dither's pulses. */
private const val DEFAULT_SCALE = 2.0

/** What the camera is doing now. */
sealed interface CaptureActivity {
    data object Idle : CaptureActivity

    data class Exposing(val started: Instant, val seconds: Double) : CaptureActivity

    data object Downloading : CaptureActivity

    /** A loop's dither: the mount nudged, then its settle time. */
    data object Dithering : CaptureActivity
}

/** The last frame and what is known about it. [savedAs] is null if it was not saved. */
class CapturedFrame(
    val frame: Frame,
    val capture: Capture,
    val statistics: FrameStatistics,
    val autoStretch: ColourStretch,
    val histogram: LongArray,
    val savedAs: String?,
    /** When the download ended, to time how long the frame takes to reach the screen. */
    val downloaded: TimeMark = TimeSource.Monotonic.markNow(),
)

data class CaptureState(
    val settings: CaptureSettings = CaptureSettings(),
    val activity: CaptureActivity = CaptureActivity.Idle,
    val looping: Boolean = false,
    val last: CapturedFrame? = null,
    /** The stars of [last] and their HFR, as NINA finds them; null until they are found. */
    val stars: StarDetection? = null,
    /** Frames saved since the app started; also the next frame number minus one. */
    val saved: Int = 0,
    /** Why the last capture failed; a failure stops a loop, and nothing fails in silence. */
    val error: String? = null,
    /** The darks Imaging is taking for the live stack, and how many of them are done. */
    val darks: DarksProgress? = null,
)

/** Darks taken from Imaging: [done] of [count]. */
data class DarksProgress(val done: Int, val count: Int)

/**
 * Manual capture and loop capture: sets up the camera, exposes, waits,
 * downloads through the pixel engine, works out the stretch and histogram, and saves with
 * N.I.N.A.'s header and file names. In a loop, a frame is saved while the next one exposes.
 */
@Suppress("TooManyFunctions") // Each step of a capture is its own small function.
class CaptureController(
    private val equipment: EquipmentController,
    private val engine: ImageEngine,
    private val store: FrameStore,
    private val platform: EquipmentPlatform,
    private val scope: CoroutineScope,
    private val software: String,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val journal: Journal = Journal.None,
    /** Live stacking, which the profile turns on; none where the app does not stack. */
    val stacking: LiveStacking? = null,
) {
    /** A loop's dithers, around where it began, as N.I.N.A.'s direct guider. */
    private val dithers = Dither()

    private val mutableState = MutableStateFlow(CaptureState())
    private var job: Job? = null
    private var lastShown: CapturedFrame? = null

    /** The sequence's last frame being saved, so they are saved one at a time. */
    private var saving: Job? = null

    /** The filter in use before a dark or bias moved the wheel to its Dark filter. */
    private var filterBeforeDark: Int? = null

    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    /** The frames saved this session, with their quality. */
    val history = SessionHistory()

    init {
        // The screen starts from the camera's own gain and offset, so it shows what a capture will use.
        scope.launch {
            equipment.state
                .map { (it.connection as? Connection.Connected)?.equipment?.camera }
                .distinctUntilChanged()
                .collect { camera -> if (camera != null) adoptCameraSettings(camera) }
        }
    }

    fun update(settings: CaptureSettings) = mutableState.update { it.copy(settings = settings) }

    /**
     * Changes the frame type. If the filter wheel has a filter named Dark, as the Seestar's has to
     * block the light, darks and bias move the wheel to it, and lights and flats move it back to
     * the filter before (or the first other one).
     */
    fun setType(type: FrameType) {
        val settings = state.value.settings
        if (type == settings.type) return
        update(settings.copy(type = type))
        connected()?.filterWheel?.let { useDarkFilter(it, type) }
    }

    /**
     * Moves the wheel off its Dark filter, as a frame to solve or to focus needs stars: to IR, the
     * Seestar's light filter, or else to the light filter used before the Dark one, or the first.
     */
    private suspend fun leaveDarkFilter() {
        val wheel = connected()?.filterWheel ?: return
        val dark = wheel.names.indexOfFirst { it.equals(DARK_FILTER, ignoreCase = true) }
        val light =
            wheel.names.indexOfFirst { it.equals(LIGHT_FILTER, ignoreCase = true) }.takeIf { it >= 0 }
                ?: filterBeforeDark?.takeIf { it != dark }
                ?: wheel.names.indices.firstOrNull { it != dark }
        if (dark >= 0 && light != null && wheel.wheel.position() == dark) {
            journal.log("dark_filter_left", "filter" to wheel.names[light])
            wheel.select(light)
        }
    }

    /** Moves [wheel] to its Dark filter for darks and bias, or away from it for lights and flats. */
    private fun useDarkFilter(wheel: FilterWheelMediator, type: FrameType) {
        val dark = wheel.names.indexOfFirst { it.equals(DARK_FILTER, ignoreCase = true) }
        if (dark < 0) return
        scope.launch {
            reported {
                val current = wheel.wheel.position()
                val next =
                    when {
                        !type.needsLight -> dark.also { if (current != dark) filterBeforeDark = current }
                        current == dark -> filterBeforeDark?.takeIf { it != dark } ?: wheel.names.indices.first { it != dark }
                        else -> current
                    }
                if (next != null && next != current) wheel.select(next)
            }
        }
    }

    /** Takes one frame, saved if the settings say so. */
    fun capture() = start(loop = false)

    /** Takes frames one after another, each saved, until [stop] or a failure. */
    fun startLoop() = start(loop = true)

    /**
     * Takes one light frame of [exposureSeconds] with the current gain, and [binning] or the
     * current one, unsaved, and shows it, for plate solving, centring, the polar alignment
     * and the autofocus; returns it once it is on screen. Through the Dark filter it would have no
     * stars: the wheel leaves it first. It fails while Imaging is taking frames.
     */
    suspend fun takeForSolving(exposureSeconds: Double, binning: Int? = null): CapturedFrame {
        check(job?.isActive != true) { "Imaging is taking frames" }
        val current = state.value.settings
        val settings = current.copy(
            exposureSeconds = exposureSeconds,
            binning = binning ?: current.binning,
            type = FrameType.Light,
            save = false,
        )
        mutableState.update { it.copy(error = null) }
        val taking =
            scope.async {
                try {
                    leaveDarkFilter()
                    // After the frame before is shown, which it would otherwise free while that is stacked.
                    takeOne(settings).also {
                        saving?.join()
                        show(it, savedAs = null, number = 0)
                    }
                } finally {
                    mutableState.update { it.copy(activity = CaptureActivity.Idle) }
                }
            }
        job = taking
        val pending = taking.await()
        return state.value.last?.takeIf { it.capture == pending.capture } ?: error("The frame was replaced before it was solved")
    }

    /**
     * Takes a frame of [settings] for the sequence and saves it, as a loop does: it
     * returns once the frame is downloaded, and the frame is saved, shown and its stars found while
     * the sequence goes on, one save at a time. It fails while Imaging is taking frames.
     */
    suspend fun takeInSequence(settings: CaptureSettings) {
        check(job?.isActive != true) { "Imaging is taking frames" }
        mutableState.update { it.copy(error = null) }
        // A failure, as when the link is lost, goes to the sequence that waits for the frame, and no further.
        val taking =
            scope.async {
                try {
                    runCatching { takeOne(settings) }
                } finally {
                    mutableState.update { it.copy(activity = CaptureActivity.Idle) }
                }
            }
        job = taking
        val pending = taking.await().getOrThrow()
        val number = mutableState.updateAndGet { it.copy(saved = it.saved + 1) }.saved
        val before = saving
        saving =
            scope.launch {
                before?.join()
                val savedAs = reported { save(pending, number) }
                show(pending, savedAs, number)
            }
    }

    /**
     * Takes a frame of [settings] for the flats and returns its statistics, to set the next
     * exposure: it is saved, as [takeInSequence]'s are, only if [keep] says so of them, and shown
     * either way. It fails while Imaging is taking frames.
     */
    suspend fun takeMeasured(settings: CaptureSettings, keep: (FrameStatistics) -> Boolean): FrameStatistics {
        check(job?.isActive != true) { "Imaging is taking frames" }
        mutableState.update { it.copy(error = null) }
        val taking =
            scope.async {
                try {
                    runCatching { takeOne(settings) }
                } finally {
                    mutableState.update { it.copy(activity = CaptureActivity.Idle) }
                }
            }
        job = taking
        val pending = taking.await().getOrThrow()
        val statistics = withContext(Dispatchers.Default) { pending.frame.statistics() }
        val number = if (keep(statistics)) mutableState.updateAndGet { it.copy(saved = it.saved + 1) }.saved else 0
        val before = saving
        saving =
            scope.launch {
                before?.join()
                val savedAs = if (number > 0) reported { save(pending, number) } else null
                show(pending, savedAs, number)
            }
        return statistics
    }

    /** Waits until the sequence's frames are saved. */
    suspend fun sequenceSaved() {
        saving?.join()
    }

    /** The sequence ended: waits for its frames, then saves the live stack if it grew. */
    suspend fun sequenceEnded() {
        sequenceSaved()
        stacking?.calibration?.finish()
        stacking?.saveIfGrown()
    }

    /** Calibration frames are done, as when the flats end: the flats become master flats once saved. */
    suspend fun calibrationDone() {
        sequenceSaved()
        stacking?.calibration?.finish()
    }

    /** The wheel's filter that blocks the light, as the Seestar's "Dark"; null without one. */
    fun darkFilter(): Int? = connected()?.filterWheel?.names?.indexOfFirst { it.equals(DARK_FILTER, ignoreCase = true) }?.takeIf { it >= 0 }

    /**
     * Takes [count] darks with the current exposure, gain and binning, saved, for the live stack's
     * calibration: behind the [darkFilter] when the wheel has one, back to the filter before
     * after; without one, the telescope must be covered first. It fails while Imaging is taking frames.
     */
    fun takeDarks(count: Int) {
        if (job?.isActive == true) return
        val settings = state.value.settings.copy(type = FrameType.Dark, save = true)
        mutableState.update { it.copy(error = null, darks = DarksProgress(0, count)) }
        journal.log("darks_started", "count" to count, "seconds" to settings.exposureSeconds)
        job =
            scope.launch {
                val wheel = connected()?.filterWheel
                val dark = darkFilter()
                var before: Int? = null
                try {
                    reported {
                        if (wheel != null && dark != null) {
                            before = wheel.wheel.position()
                            if (before != dark) wheel.select(dark)
                            wheel.awaitStill()
                        }
                        var saving: Job? = null
                        repeat(count) { done ->
                            val pending = takeOne(settings)
                            val number = mutableState.updateAndGet { it.copy(saved = it.saved + 1) }.saved
                            saving?.join()
                            saving =
                                launch {
                                    val savedAs = reported { save(pending, number) }
                                    show(pending, savedAs, number)
                                }
                            mutableState.update { it.copy(darks = DarksProgress(done + 1, count)) }
                        }
                        saving?.join()
                        stacking?.calibration?.finish()
                    }
                } finally {
                    mutableState.update { it.copy(activity = CaptureActivity.Idle, darks = null) }
                    // Back to the filter before, even after an abort.
                    val back = before
                    if (wheel != null && back != null && back != dark) scope.launch { runCatchingDevice { wheel.select(back) } }
                    journal.log("darks_done", "error" to state.value.error)
                }
            }
    }

    /** Ends a loop after the frame in progress. */
    fun stop() = mutableState.update { it.copy(looping = false) }

    /** The viewer drew [frame] for the first time: the journal keeps how long after its download. */
    fun shown(frame: CapturedFrame) {
        if (frame === lastShown) return
        lastShown = frame
        journal.log(
            "frame_shown",
            "start" to frame.capture.start.toString(),
            "shownMs" to frame.downloaded.elapsedNow().inWholeMilliseconds,
        )
    }

    /** Stops the exposure in progress and throws it away. */
    fun abort() {
        job?.cancel()
        mutableState.update { it.copy(looping = false, activity = CaptureActivity.Idle) }
        val camera = connected()?.camera ?: return
        if (camera.info.canAbortExposure) scope.launch { runCatchingDevice { camera.camera.abortExposure() } }
    }

    private fun start(loop: Boolean) {
        if (job?.isActive == true) return
        // A loop's dithers go around where it began.
        dithers.reset()
        mutableState.update { it.copy(looping = loop, error = null) }
        val settings = state.value.settings
        journal.log(
            if (loop) "loop_started" else "capture_started",
            "seconds" to settings.exposureSeconds,
            "gain" to settings.gain,
            "type" to settings.type.fits,
            "target" to settings.target,
        )
        job =
            scope.launch {
                var saving: Job? = null
                try {
                    reported {
                        do {
                            val settings = state.value.settings
                            val pending = if (loop) takeLooping(settings) ?: break else takeOne(settings)
                            val keep = loop || settings.save
                            val number = if (keep) mutableState.updateAndGet { it.copy(saved = it.saved + 1) }.saved else 0
                            // One save at a time on the storage, while the next frame exposes.
                            saving?.join()
                            saving =
                                launch {
                                    val savedAs = if (keep) reported { save(pending, number) } else null
                                    show(pending, savedAs, number)
                                }
                            if (loop && ditherDue(settings, number)) ditherLoop()
                        } while (loop && state.value.looping)
                    }
                    saving?.join()
                    if (loop) stacking?.saveIfGrown()
                } finally {
                    mutableState.update { it.copy(activity = CaptureActivity.Idle, looping = false) }
                    journal.log("capture_stopped", "saved" to state.value.saved, "error" to state.value.error)
                }
            }
    }

    /** Whether a loop dithers after its frame [number]: of lights, every [CaptureSettings.ditherEvery], while it goes on. */
    private fun ditherDue(settings: CaptureSettings, number: Int): Boolean =
        settings.type == FrameType.Light && settings.ditherEvery > 0 && number > 0 && number % settings.ditherEvery == 0 &&
            state.value.looping

    /**
     * Nudges the mount between frames of a loop, as the sequence dithers, with the profile's
     * pulse and settle time, until the next frame exposes or the loop ends. Without a mount the
     * loop goes on as it was, and a dither that fails leaves it going too, as the log says.
     */
    @Suppress("TooGenericExceptionCaught") // A dither that fails is not worth stopping the loop for.
    private suspend fun ditherLoop() {
        val connected = connected() ?: return
        val mount = connected.mount ?: return
        val options = connected.profile.options.dither
        val scale = connected.camera?.info?.let { connected.profile.optics.pixelScaleArcsec(it.pixelSizeX) } ?: DEFAULT_SCALE
        mutableState.update { it.copy(activity = CaptureActivity.Dithering) }
        try {
            val at = dithers.move(mount, options, scale)
            journal.log(
                "loop_dither",
                "east" to at.eastPixels,
                "north" to at.northPixels,
                "goTos" to at.goTos,
                "pixels" to options.pixels,
                "settle" to options.settleSeconds,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            journal.log("loop_dither_failed", "reason" to (e.message ?: e::class.simpleName))
        }
    }

    /**
     * A frame of a loop. One that fails while the link with the equipment stumbles or is lost is taken
     * again once the equipment answers well, after a reconnection too, as on the first night a loop
     * stopped after a slow answer of the Seestar; any other failure stops the loop, as before. Null
     * once the loop is stopped meanwhile, or the equipment disconnected by hand.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever failed while the link stumbled is the link's.
    private suspend fun takeLooping(settings: CaptureSettings): Pending? {
        while (true) {
            try {
                return takeOne(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!linkStumblingSoon()) throw e
                journal.log("loop_waiting", "reason" to e.message)
                mutableState.update { it.copy(activity = CaptureActivity.Idle) }
                while (state.value.looping && linkNotSteady()) delay(LINK_POLL)
                if (!state.value.looping || equipment.state.value.connection == Connection.Disconnected) return null
                journal.log("loop_resumed")
            }
        }
    }

    /** Whether the link stumbles, now or by the time the polls notice, as the sequence asks. */
    private suspend fun linkStumblingSoon(): Boolean = withTimeoutOrNull(LINK_HICCUP + LINK_POLL) {
        while (!linkNotSteady()) delay(LINK_POLL)
        true
    } == true

    /** Whether the equipment does not answer well: reconnecting, or a read failing or late; disconnected by hand ends the wait. */
    private fun linkNotSteady(): Boolean = when (val connection = equipment.state.value.connection) {
        is Connection.Connected -> connection.equipment.linkTrouble(clock.now())
        Connection.Disconnected -> false
        else -> true
    }

    /** Runs [block]; if it fails, the user sees why and a loop stops (nothing fails in silence). */
    @Suppress("TooGenericExceptionCaught") // Whatever failed is shown.
    private suspend fun <T> reported(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = e.message ?: e::class.simpleName
        journal.log("capture_error", "reason" to reason, "type" to e::class.simpleName)
        mutableState.update { it.copy(error = reason, looping = false) }
        null
    }

    private suspend fun takeOne(settings: CaptureSettings): Pending {
        val equipment = connected() ?: error("Connect a camera in Equipment first")
        val camera = equipment.camera ?: error("The profile has no camera connected")
        val info = camera.info
        configure(camera.camera, info, settings)

        val started = clock.now()
        camera.camera.startExposure(settings.exposureSeconds, light = settings.type.needsLight)
        mutableState.update { it.copy(activity = CaptureActivity.Exposing(started, settings.exposureSeconds)) }
        // A read that fails now and then, as a Seestar busy with the frame may, is asked again.
        withTimeoutOrNull(settings.exposureSeconds.seconds + READY_MARGIN) {
            while (runCatchingDevice { camera.camera.imageReady() } != true) delay(READY_POLL)
        } ?: error("The camera did not hand over its frame in time")
        mutableState.update { it.copy(activity = CaptureActivity.Downloading) }
        val download = TimeSource.Monotonic.markNow()
        val frame = engine.download(
            camera.camera.imageArrayUrl,
            platform.networkHandle(),
            Bayer.fromName(info.bayerPattern),
            DOWNLOAD_TIMEOUT,
        )
        val downloaded = TimeSource.Monotonic.markNow()
        val downloadMillis = (downloaded - download).inWholeMilliseconds
        val start = runCatchingDevice { camera.camera.lastExposureStartTime() } ?: started
        return Pending(frame, describe(equipment, settings, start), downloadMillis, downloaded)
    }

    /**
     * Fills in the gain and offset the user has not chosen: the gain 200 where the camera takes it, as
     * the Seestar's own app takes its frames, not whatever another app left it at, else the camera's
     * current ones.
     */
    private suspend fun adoptCameraSettings(camera: CameraMediator) {
        val control = camera.info.gain
        val gain =
            when {
                control is GainControl.Range && DEFAULT_GAIN in control.min..control.max -> DEFAULT_GAIN
                control is GainControl.None -> null
                else -> runCatchingDevice { camera.camera.gain() }
            }
        val offset = if (camera.info.offset is GainControl.None) null else runCatchingDevice { camera.camera.offset() }
        mutableState.update { it.copy(settings = it.settings.copy(gain = it.settings.gain ?: gain, offset = it.settings.offset ?: offset)) }
    }

    /** Gain, offset and binning, each only if the camera can take it; the subframe is the whole sensor. */
    private suspend fun configure(camera: Camera, info: CameraInfo, settings: CaptureSettings) {
        val gain = info.gain
        settings.gain?.let { value ->
            when (gain) {
                is GainControl.Range -> camera.setGain(value.coerceIn(gain.min, gain.max))
                is GainControl.Named -> camera.setGain(value.coerceIn(0, gain.names.lastIndex))
                GainControl.None -> Unit
            }
        }
        val offset = info.offset
        if (settings.offset != null && offset is GainControl.Range) camera.setOffset(settings.offset.coerceIn(offset.min, offset.max))
        val bin = settings.binning.coerceIn(1, minOf(info.maxBinX, info.maxBinY))
        if (info.maxBinX > 1 || info.maxBinY > 1) camera.setBinning(Binning(bin, bin))
        runCatchingDevice { camera.setSubframe(Subframe(0, 0, info.width / bin, info.height / bin)) }
    }

    /** What the header needs, from each device's latest live values and the profile. */
    private fun describe(equipment: ConnectedEquipment, settings: CaptureSettings, start: Instant): Capture {
        val profile: Profile = equipment.profile
        val camera = equipment.camera!!
        val wheel = equipment.filterWheel
        val focuser = equipment.focuser?.state?.value?.value
        val bin = settings.binning.coerceIn(1, minOf(camera.info.maxBinX, camera.info.maxBinY))
        return Capture(
            type = settings.type,
            start = start,
            exposureSeconds = settings.exposureSeconds,
            camera = camera.info,
            cameraName = profile.devices.camera?.name?.ifBlank { null } ?: "Camera",
            binX = bin,
            binY = bin,
            gain = settings.gain.takeIf { camera.info.gain !is GainControl.None },
            offset = settings.offset.takeIf { camera.info.offset !is GainControl.None },
            sensorTemperature = camera.state.value.value?.ccdTemperature,
            target = settings.target.ifBlank { null },
            mosaic = settings.mosaic,
            filter = wheel?.position?.value?.value?.let { wheel.names.getOrNull(it) },
            focuserPosition = focuser?.position,
            focuserTemperature = focuser?.temperature,
            mount = equipment.mount?.state?.value?.value,
            mountName = profile.devices.mount?.name?.ifBlank { null } ?: profile.name,
            site = profile.location,
            optics = profile.optics,
            software = software,
        )
    }

    private suspend fun save(pending: Pending, frameNumber: Int): String {
        val options = connected()?.profile?.options ?: ProfileOptions()
        val path = FileNames.render(options.filePattern, pending.capture, frameNumber, zone())
        return store.save(pending.frame, path, frameHeaders(pending.capture)).also { savedAs ->
            journal.log(
                "frame_saved",
                "number" to frameNumber,
                "file" to savedAs,
                "start" to pending.capture.start.toString(),
                // A full Seestar frame in 3 s at most.
                "downloadMs" to pending.downloadMillis,
            )
        }
    }

    /**
     * Makes [pending] the frame on screen, with its stretch and histogram, and frees the one before;
     * then finds its stars and, if it was saved as frame [number], adds it to the history and, with
     * live stacking on, a light frame to the stack; darks, bias and flats go to its calibration.
     */
    private suspend fun show(pending: Pending, savedAs: String?, number: Int) {
        val captured =
            withContext(Dispatchers.Default) {
                CapturedFrame(
                    frame = pending.frame,
                    capture = pending.capture,
                    statistics = pending.frame.statistics(),
                    autoStretch = pending.frame.autoStretch(),
                    histogram = pending.frame.histogram(HISTOGRAM_BINS),
                    savedAs = savedAs,
                    downloaded = pending.downloaded,
                )
            }
        val previous = state.value.last
        mutableState.update { it.copy(last = captured, stars = null) }
        previous?.frame?.close()
        // The stars come once the frame is on screen, so finding them never delays it.
        val started = TimeSource.Monotonic.markNow()
        val capture = pending.capture
        // A dark, a bias or a frame through the Dark filter has no stars: what would be found is noise.
        val stars =
            if (capture.unlit) {
                StarDetection.None
            } else {
                withContext(Dispatchers.Default) {
                    pending.frame.detectStars(capture.camera.pixelSizeX * capture.binX, capture.optics.focalLengthMm)
                }
            }
        mutableState.update { if (it.last === captured) it.copy(stars = stars.takeUnless { capture.unlit }) else it }
        journal.log(
            "frame_stars",
            "start" to capture.start.toString(),
            "stars" to stars.stars.size,
            "hfr" to stars.averageHfr,
            "fwhm" to stars.fwhm,
            "ms" to started.elapsedNow().inWholeMilliseconds,
        )
        if (savedAs == null) return
        history.add(historyFrame(captured, stars, number, savedAs))
        stack(captured, stars, number)
    }

    /** With live stacking on, [captured], saved as frame [number], goes to the stack, or to its calibration. */
    private suspend fun stack(captured: CapturedFrame, stars: StarDetection, number: Int) {
        val stacking = stacking ?: return
        // The profile as it is now: stacking is turned on and off while connected.
        val options = equipment.state.value.active?.options ?: return
        if (captured.capture.type != FrameType.Light) {
            // Kept even before live stacking is turned on, as it was on the second night, for the stack
            // that comes; bias and flats make the flats of the next nights.
            stacking.calibration.offer(captured)
        } else if (options.stacking.live) {
            val discarded = history.frames.value.lastOrNull { it.number == number }?.discarded
            stacking.offer(captured, stars, discarded, options)
        }
    }

    /** The history's record of [captured], with a thumbnail of the middle of the frame. */
    private suspend fun historyFrame(captured: CapturedFrame, stars: StarDetection, number: Int, savedAs: String): HistoryFrame {
        val frame = captured.frame
        val side = min(frame.width, frame.height).toDouble()
        val middle = Region((frame.width - side) / 2, (frame.height - side) / 2, side, side)
        val thumbnail = withContext(Dispatchers.Default) { frame.preview(captured.autoStretch, middle, THUMBNAIL_PIXELS, THUMBNAIL_PIXELS) }
        val capture = captured.capture
        return HistoryFrame(
            number = number,
            at = capture.start,
            type = capture.type,
            filter = capture.filter,
            exposureSeconds = capture.exposureSeconds,
            gain = capture.gain,
            binning = capture.binX,
            hfr = stars.averageHfr,
            stars = stars.stars.size,
            background = captured.statistics.median,
            mad = captured.statistics.mad,
            savedAs = savedAs,
            thumbnail = thumbnail,
            target = capture.target,
        )
    }

    private fun connected(): ConnectedEquipment? = (equipment.state.value.connection as? Connection.Connected)?.equipment

    private class Pending(val frame: Frame, val capture: Capture, val downloadMillis: Long, val downloaded: TimeMark)

    private companion object {
        val READY_POLL = 250.milliseconds

        /** How often a loop waiting for the equipment looks at its link. */
        val LINK_POLL = 1.seconds
        val READY_MARGIN = 60.seconds
        val DOWNLOAD_TIMEOUT = 60.seconds
        const val HISTOGRAM_BINS = 256

        /** The history's thumbnails, square: enough for a list row, little memory for a whole night. */
        const val THUMBNAIL_PIXELS = 64
    }
}

/** The name of the filter that blocks the light, as the Seestar's wheel calls it. */
const val DARK_FILTER = "Dark"

/** Whether the frame took no light: a dark, a bias, or any frame through the Dark filter. */
val Capture.unlit: Boolean get() = !type.needsLight || filter.equals(DARK_FILTER, ignoreCase = true)

/** The Seestar's filter for lights, which a frame to solve or focus takes in place of the Dark one. */
private const val LIGHT_FILTER = "IR"

/** The gain frames start with when none was chosen and the camera takes it: the Seestar app's, 200. */
const val DEFAULT_GAIN = 200

@Suppress("TooGenericExceptionCaught") // An optional read: a failure only leaves the value out.
private suspend fun <T> runCatchingDevice(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
