// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ports

import kotlin.time.Duration

/**
 * The pixel engine, written in Rust (ARCHITECTURE.md).
 *
 * Pixels stay in native memory: this port only passes image handles and small
 * results. It grows with analysis, previews and saving.
 */
interface ImageEngine {
    /** Version and architecture of the loaded engine library. */
    fun buildInfo(): EngineBuildInfo

    /**
     * Downloads a camera's last image as ImageBytes straight into native memory.
     *
     * @param imageArrayUrl the camera's `imagearray` URL.
     * @param network the equipment's network handle, so the socket goes through its WiFi
     *   even without internet (ARCHITECTURE.md); null uses the default network.
     * @param bayer the camera's Bayer pattern from the top-left pixel, or null for mono.
     * @throws EngineException if the download or the image fails.
     */
    suspend fun download(imageArrayUrl: String, network: Long?, bayer: Bayer?, timeout: Duration): Frame

    /** An empty live stack: the first frame added becomes its reference. Null where the engine cannot stack. */
    fun newStack(): LiveStack? = null

    /** The stack [LiveStack.checkpoint] wrote at [path], to go on after the app was closed; null if there is none. */
    fun restoreStack(path: String): LiveStack? = null

    /** An empty master frame, for the live stack's darks, bias and flats; null where the engine cannot stack. */
    fun newMaster(): MasterFrame? = null

    /**
     * The flat in the FITS file open as [descriptor], which this call takes over and closes: each
     * colour over its own mean, whether it was saved so or in ADU; null if it is no flat.
     */
    fun readMasterFits(descriptor: Int): MasterFrame? = null

    /** [image] as a JPG, as the live mosaic is kept; null where the engine cannot encode one. */
    fun jpeg(image: Preview): ByteArray? = null
}

/**
 * A live stack in the engine's memory: each good light frame aligned on the first
 * by its stars and added to a running mean that leaves out what is far from the rest, as satellites.
 * Its colour shows balanced on its own stars, or as the camera gave it. Close it when it is done:
 * a Seestar's stack takes some 250 MB.
 */
interface LiveStack : AutoCloseable {
    /**
     * Adds [frame], whose [stars] the app found, [exposureSeconds] long, less the master [dark] of
     * its exposure and over the master [flat] of its filter, when given; it goes in only if its
     * stars match the first frame's. Takes up to a couple of seconds: call it off the main thread.
     */
    fun add(
        frame: Frame,
        stars: List<DetectedStar>,
        exposureSeconds: Double,
        dark: MasterFrame? = null,
        flat: MasterFrame? = null,
    ): StackAddition

    fun info(): StackInfo

    /**
     * [region] of the stack, in its pixels, drawn into [width] × [height] screen pixels, with one
     * stretch for its three channels, its background neutral and its white balanced if [balanced].
     */
    fun preview(balanced: Boolean, region: Region, width: Int, height: Int): Preview

    /**
     * The whole stack at [width] × [height], linear and with the camera's colour, for the live mosaic,
     * which matches, blends and stretches its panels itself; null where the engine cannot give it.
     */
    fun linear(width: Int, height: Int): LinearImage? = null

    /** Saves it at [path] as a linear 32-bit FITS through `path.part`. Blocks: call it off the main thread. */
    fun save(path: String, headers: List<HeaderCard>)

    /** Writes the FITS of [save] into the open file [descriptor], which this call takes over and closes. */
    fun writeTo(descriptor: Int, headers: List<HeaderCard>)

    /** Saves it at [path] as a JPG as the screen shows it, cropped to where half the frames reach. */
    fun saveJpeg(path: String, balanced: Boolean)

    /** Writes the JPG of [saveJpeg] into the open file [descriptor], which this call takes over and closes. */
    fun writeJpegTo(descriptor: Int, balanced: Boolean)

    /** Writes all it holds at [path], for [ImageEngine.restoreStack], with [label] to know it by. */
    fun checkpoint(path: String, label: String)

    /** The label of its last checkpoint, or of the one it was restored from; empty before one. */
    fun label(): String
}

/**
 * What adding a frame did: whether it went in, how it was turned and shifted from the first, and
 * whether its [dark] and [flat] were used: neither is of another size, nor the flat without a dark
 * or the bias level it was made with.
 */
data class StackAddition(
    val added: Boolean,
    val frames: Int,
    val rotationDeg: Double,
    val shiftPx: Double,
    val matchedStars: Int,
    val dark: Boolean = false,
    val flat: Boolean = false,
)

/**
 * A master calibration frame in the engine's memory: the mean of a set of darks, bias or
 * flats on the sensor's own mosaic, some 33 MB for a Seestar's. Close it when it is done.
 */
interface MasterFrame : AutoCloseable {
    /** Adds [frame] to the mean; one of another size is left out. Returns how many frames it holds. */
    fun add(frame: Frame): Int

    fun frames(): Int

    /**
     * The master flat of this mean of flats: [bias] taken away, then each colour over its own mean,
     * so each pixel says how much of the light reaches it. Blocks: call it off the main thread.
     */
    fun flat(bias: MasterFrame?): MasterFrame

    /** Writes it at [path] as a FITS file of 32-bit floats with [headers], as Siril and PixInsight read it too. */
    fun saveFits(path: String, headers: List<HeaderCard>)

    /** Writes the FITS of [saveFits] into the open file [descriptor], which this call takes over and closes. */
    fun writeFitsTo(descriptor: Int, headers: List<HeaderCard>)

    /** A flat read from a FITS file: its DATE-OBS as written there; empty for one made here. */
    fun label(): String
}

/**
 * The stack's size, frames and their total exposure, and the gains that make its typical star white,
 * from the [balanceStars] it measured; 1 each until there are enough.
 */
data class StackInfo(
    val width: Int,
    val height: Int,
    val frames: Int,
    val exposureSeconds: Double,
    val redGain: Double = 1.0,
    val greenGain: Double = 1.0,
    val blueGain: Double = 1.0,
    val balanceStars: Int = 0,
) {
    /** Whether it measured enough stars for its gains; with fewer, its colour stays the camera's. */
    val starsBalanced: Boolean get() = balanceStars >= MIN_BALANCE_STARS

    companion object {
        /** As the engine needs them. */
        const val MIN_BALANCE_STARS = 10
    }
}

/** Identifies the engine build, for the About screen and the session log. */
data class EngineBuildInfo(val version: String, val arch: String)

/** The colour filter pattern from the top-left pixel, such as GRBG on the Seestar. */
enum class Bayer {
    RGGB,
    GRBG,
    GBRG,
    BGGR,
    ;

    companion object {
        /** The pattern named like `CameraInfo.bayerPattern`, or null for none. */
        fun fromName(name: String?): Bayer? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A frame in the engine's memory. Close it when it is no longer shown or saved: its pixels,
 * 16 MB for a Seestar frame, are freed at once instead of whenever the garbage collector runs.
 */
interface Frame : AutoCloseable {
    val width: Int
    val height: Int
    val bayer: Bayer?

    /** Exact statistics of every pixel, in ADU. */
    fun statistics(): FrameStatistics

    /**
     * N.I.N.A.'s automatic stretch as its Imaging tab applies it by default: the background at 20 %
     * grey and black 2.8 MADs below the median, each channel on its own for a colour sensor.
     */
    fun autoStretch(): ColourStretch

    /** Counts of the pixel values in [bins] equal bins over the full 16-bit range. */
    fun histogram(bins: Int): LongArray

    /**
     * The stars and their half-flux radius (HFR) as N.I.N.A. finds them with its default image
     * settings, so the numbers match NINA's. [pixelSizeUm] and [focalLengthMm] give the
     * image scale NINA uses to shrink a wide frame first. Takes a fraction of a second: call it off
     * the main thread.
     */
    fun detectStars(pixelSizeUm: Double?, focalLengthMm: Double?): StarDetection

    /**
     * [region] of the frame, in frame pixels, drawn into [width] × [height] screen pixels with
     * [stretch], debayered for a colour sensor (ARCHITECTURE.md). Takes milliseconds: call it off the main thread.
     */
    fun preview(stretch: ColourStretch, region: Region, width: Int, height: Int): Preview

    /**
     * Saves a FITS file at [path] through `path.part`, flushed and then renamed, so a crash never
     * leaves half a frame under the final name. Blocks: call it off the main thread.
     * @throws EngineException if the file cannot be written.
     */
    fun save(path: String, headers: List<HeaderCard>)

    /**
     * Writes a FITS file into the open file [descriptor], which this call takes over and closes,
     * such as one from Android's file picker. The caller writes to a temporary name and renames it.
     * @throws EngineException if the file cannot be written.
     */
    fun writeTo(descriptor: Int, headers: List<HeaderCard>)
}

/** A FITS keyword, as N.I.N.A. writes them. */
data class HeaderCard(val key: String, val value: HeaderValue, val comment: String = "")

sealed interface HeaderValue {
    data class Text(val value: String) : HeaderValue

    data class Integer(val value: Long) : HeaderValue

    data class Real(val value: Double) : HeaderValue

    data class Logical(val value: Boolean) : HeaderValue
}

data class FrameStatistics(val min: Int, val max: Int, val mean: Double, val median: Double, val mad: Double, val stdDev: Double)

/**
 * The stars of a frame, with their mean HFR in pixels and its standard deviation as N.I.N.A. gives
 * them, and the median [fwhm] in pixels of the stars with a profile fit, as the Hocus Focus plugin
 * gives it; 0 without stars.
 */
data class StarDetection(val stars: List<DetectedStar>, val averageHfr: Double, val hfrStdDev: Double, val fwhm: Double = 0.0) {
    companion object {
        val None = StarDetection(emptyList(), 0.0, 0.0)
    }
}

/**
 * One star: its centroid in frame pixels from the top-left corner, its HFR in pixels, the local
 * background and its brightest pixel in ADU, the [box] around it that was measured, the mean light
 * above the background in that box that NINA ranks stars by, and its [fwhm] in pixels, null when
 * its profile could not be fitted.
 */
data class DetectedStar(
    val x: Float,
    val y: Float,
    val hfr: Double,
    val background: Double,
    val maxBrightness: Double,
    val box: Region,
    val averageBrightness: Double = 0.0,
    val fwhm: Double? = null,
)

/**
 * How pixel values map to the screen, as N.I.N.A. does, with values as fractions of the 16-bit
 * range: a value becomes the midtones transfer function of `1 - highlights + value - shadows`, so
 * values below [shadows] are black and [midtones] is the balance that goes to 50 % (0.5 is linear).
 * [highlights] is 1 except for a frame brighter than half the range.
 */
data class Stretch(val shadows: Float, val midtones: Float, val highlights: Float = 1f) {
    companion object {
        val Linear = Stretch(shadows = 0f, midtones = 0.5f, highlights = 1f)
    }
}

/** A stretch per channel: one for all three when linked, and one each for NINA's unlinked stretch. */
data class ColourStretch(val red: Stretch, val green: Stretch, val blue: Stretch) {
    companion object {
        fun linked(stretch: Stretch) = ColourStretch(stretch, stretch, stretch)

        val Linear = linked(Stretch.Linear)
    }
}

/** A rectangle of a frame, in frame pixels from the top-left corner. */
data class Region(val x: Double, val y: Double, val width: Double, val height: Double)

/** Screen pixels, row by row, four bytes each: red, green, blue and alpha. */
class Preview(val width: Int, val height: Int, val rgba: ByteArray)

/**
 * A stack's pixels, linear: red, green and blue of each pixel in turn, row by row, in ADU; NaN
 * where no frame reaches.
 */
class LinearImage(val width: Int, val height: Int, val values: FloatArray)

/** An engine call failed; [message] says why, such as a network error or an Alpaca error from the camera. */
class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
