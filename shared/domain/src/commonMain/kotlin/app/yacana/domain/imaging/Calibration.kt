// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import app.yacana.ports.HeaderCard
import app.yacana.ports.HeaderValue
import app.yacana.ports.ImageEngine
import app.yacana.ports.MasterFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours

/** What a master dark is for: the light frames of one exposure, gain, offset and binning. */
data class DarkSettings(val exposureSeconds: Double, val gain: Int?, val offset: Int?, val binning: Int) {
    companion object {
        fun of(capture: Capture) = DarkSettings(capture.exposureSeconds, capture.gain, capture.offset, capture.binX)
    }
}

/** A set of darks of one night, its master, and the sensor's mean temperature while it was taken, if the camera says it. */
class DarkSet internal constructor(val settings: DarkSettings, val night: LocalDate, internal val master: MasterFrame) {
    private var temperatures = 0
    private var sum = 0.0

    val temperatureC: Double? get() = if (temperatures == 0) null else sum / temperatures

    internal fun measured(temperature: Double?) {
        if (temperature == null) return
        temperatures++
        sum += temperature
    }
}

/** The master flat of a filter, taken on [night] if its file says when, kept in [file] under the frames' folder. */
class FlatSet internal constructor(
    val filter: String?,
    val binning: Int,
    val night: LocalDate?,
    internal val master: MasterFrame,
    val file: String,
)

/** What calibrated the stack's last frame, as its sheet says it: the dark and its temperature, the flat, its file and its night. */
data class StackCalibration(
    val dark: Boolean = false,
    val darkTemperatureC: Double? = null,
    val flat: Boolean = false,
    val flatNight: LocalDate? = null,
    val flatFile: String? = null,
)

/**
 * The live stack's calibration: master darks, bias and flats made from the frames as they are
 * saved. The darks of a light frame are those of its exposure, gain, offset and binning, of its night,
 * of the temperature closest to its sensor's; its flat, its filter's master flat. A set ends when a
 * frame of another kind or setting comes, and the flats, once their bias is in or the set is done,
 * become a master flat kept in [store] for the next nights, one per filter, as
 * `FLATS/MasterFlat_IR.fits`: a flat put there with that name, as from Siril, counts the same. The
 * darks are only kept while the app is open.
 */
class Calibration(
    private val engine: ImageEngine,
    /** Where the master flats are kept, in FLATS under the frames' folder; null to keep none. */
    private val store: FrameStore? = null,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val journal: Journal = Journal.None,
) {
    private data class BiasSettings(val gain: Int?, val offset: Int?, val binning: Int)

    private data class FlatKey(val filter: String?, val binning: Int)

    /** Flats being taken: their mean so far, and the first one's capture, for the master's header. */
    private class Taking(val master: MasterFrame, val capture: Capture)

    private val mutex = Mutex()
    private val darks = mutableListOf<DarkSet>()
    private val bias = mutableMapOf<BiasSettings, MasterFrame>()
    private val taking = mutableMapOf<FlatKey, Taking>()
    private val flats = mutableMapOf<FlatKey, FlatSet>()

    /** The kind and settings of the set the last frame went into: a frame of another starts another. */
    private var open: Pair<FrameType, Any>? = null

    /** Adds [captured], a saved dark, bias or flat, to its set. */
    suspend fun offer(captured: CapturedFrame) {
        mutex.withLock {
            val capture = captured.capture
            val (type, settings) =
                when (capture.type) {
                    FrameType.Light -> return@withLock
                    FrameType.Dark -> FrameType.Dark to DarkSettings.of(capture)
                    FrameType.Bias -> FrameType.Bias to BiasSettings(capture.gain, capture.offset, capture.binX)
                    FrameType.Flat -> FrameType.Flat to FlatKey(capture.filter, capture.binX)
                }
            val master = masterFor(type, settings, capture) ?: return@withLock
            reported { withContext(Dispatchers.Default) { master.add(captured.frame) } }
            if (type == FrameType.Dark) darks.last().measured(capture.sensorTemperature)
        }
    }

    /** The sets are done, as when a sequence or the flats end: flats waiting for their bias become master flats. */
    suspend fun finish() = mutex.withLock { end(all = true) }

    /** A light frame came: the sets before it are done, and their flats too, with or without bias. */
    suspend fun lightCame() = mutex.withLock { if (open != null || taking.isNotEmpty()) end(all = true) }

    /** The masters for the light frame [capture]: the dark of the temperature closest to its sensor's, and its filter's flat. */
    suspend fun forLight(capture: Capture): Pair<DarkSet?, FlatSet?> = mutex.withLock {
        val settings = DarkSettings.of(capture)
        val night = night(capture)
        val temperature = capture.sensorTemperature
        val candidates = darks.filter { it.settings == settings && it.night == night }
        val dark =
            if (temperature == null) {
                candidates.lastOrNull()
            } else {
                candidates.minByOrNull { set -> set.temperatureC?.let { abs(it - temperature) } ?: Double.MAX_VALUE }
            }
        dark to flatFor(FlatKey(capture.filter, capture.binX))
    }

    /** The open set's master, or a new one if [capture] starts another set; null where the engine makes none. */
    private suspend fun masterFor(type: FrameType, settings: Any, capture: Capture): MasterFrame? {
        if (open != type to settings) begin(type, settings, capture)
        if (open != type to settings) return null
        return when (type) {
            FrameType.Dark -> darks.last().master
            FrameType.Bias -> bias[settings]
            else -> taking[settings]?.master
        }
    }

    /** Ends the open set and starts one of [type] and [settings], when the engine can make its master. */
    private suspend fun begin(type: FrameType, settings: Any, capture: Capture) {
        end()
        val master = engine.newMaster() ?: return
        open = type to settings
        when (type) {
            FrameType.Dark -> {
                darks += DarkSet(settings as DarkSettings, night(capture), master)
                // A night's sets of darks, at most: the oldest goes.
                if (darks.size > MAX_DARK_SETS) darks.removeAt(0).master.close()
            }

            FrameType.Bias -> bias.put(settings as BiasSettings, master)?.close()

            else -> taking.put(settings as FlatKey, Taking(master, capture))?.master?.close()
        }
        journal.log("calibration_set", "type" to type.fits, "settings" to settings.toString())
    }

    /** The open set is done; after its bias, or when [all] are, the flats taken become master flats. */
    private suspend fun end(all: Boolean = false) {
        val ended = open
        open = null
        if (taking.isEmpty() || (!all && ended?.first != FrameType.Bias)) return
        for ((key, taken) in taking) {
            val bias = bias.entries.lastOrNull { it.key.binning == key.binning }?.value
            val master = reported { withContext(Dispatchers.Default) { taken.master.flat(bias) } }
            taken.master.close()
            if (master == null) continue
            val path = flatPath(key.filter, key.binning)
            val day = taken.capture.start.toLocalDateTime(zone()).date
            this.flats.put(key, FlatSet(key.filter, key.binning, day, master, "$path.$FITS"))?.master?.close()
            store?.let { reported { it.saveMaster(master, path, flatHeaders(taken.capture, master.frames())) } }
            journal.log(
                "calibration_flat",
                "filter" to key.filter,
                "frames" to master.frames(),
                "bias" to (bias != null),
                "file" to "$path.$FITS",
            )
        }
        taking.clear()
    }

    /** [key]'s master flat: the one made here, or else the one in FLATS, kept once read. */
    private suspend fun flatFor(key: FlatKey): FlatSet? = flats[key] ?: store?.let { store ->
        val path = flatPath(key.filter, key.binning)
        reported { store.readMaster(path) { engine.readMasterFits(it) } }?.let { master ->
            FlatSet(key.filter, key.binning, dayOf(master.label(), zone()), master, "$path.$FITS").also { flats[key] = it }
        }
    }

    private fun night(capture: Capture): LocalDate = (capture.start - 12.hours).toLocalDateTime(zone()).date

    @Suppress("TooGenericExceptionCaught") // A master that fails leaves the stack uncalibrated, and says why in the log.
    private suspend fun <T> reported(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        journal.log("calibration_error", "reason" to (e.message ?: e::class.simpleName))
        null
    }

    private companion object {
        /** Enough for a night's temperatures, at 33 MB each with a Seestar. */
        const val MAX_DARK_SETS = 8
    }
}

/** `FLATS/MasterFlat_IR`, or `FLATS/MasterFlat_IR_bin2` for another binning, without its `.fits`. */
private fun flatPath(filter: String?, binning: Int): String {
    val name = filter?.map { if (it.isLetterOrDigit() || it == '-') it else '_' }?.joinToString("")
    return "$FLATS/" + listOfNotNull("MasterFlat", name, "bin$binning".takeIf { binning > 1 }).joinToString("_")
}

/** The master flat's header: its first flat's, as a master flat of so many frames. */
private fun flatHeaders(capture: Capture, frames: Int): List<HeaderCard> = frameHeaders(capture).filterNot { it.key == "IMAGETYP" } +
    listOf(
        HeaderCard("IMAGETYP", HeaderValue.Text("Master Flat"), "Type of exposure"),
        HeaderCard("NCOMBINE", HeaderValue.Integer(frames.toLong()), "Number of frames combined"),
    )

/** The day in [zone] of a FITS DATE-OBS, as `2026-10-11T15:02:03.000` in UTC; null without one. */
private fun dayOf(date: String, zone: TimeZone): LocalDate? = runCatching {
    LocalDateTime.parse(date.take(DATE_TIME)).toInstant(TimeZone.UTC).toLocalDateTime(zone).date
}.getOrNull()

/** The folder of the master flats, under the frames' folder. */
private const val FLATS = "FLATS"
private const val FITS = "fits"

/** `2026-10-11T15:02:03` of a DATE-OBS. */
private const val DATE_TIME = 19
