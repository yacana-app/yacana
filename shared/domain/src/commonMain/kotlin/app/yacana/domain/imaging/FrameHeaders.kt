// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.alpaca.CameraInfo
import app.yacana.domain.equipment.MountState
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Optics
import app.yacana.ports.HeaderCard
import app.yacana.ports.HeaderValue
import app.yacana.ports.StackInfo
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Instant

/** The kind of frame, as N.I.N.A. writes it in IMAGETYP. */
@Serializable
enum class FrameType(val fits: String) {
    Light("LIGHT"),
    Dark("DARK"),
    Flat("FLAT"),
    Bias("BIAS"),
    ;

    /** Lights and flats are taken with light; darks and bias with the light blocked. */
    val needsLight: Boolean get() = this == Light || this == Flat
}

/** Everything known about a frame when it was taken. Unknown values are null and left out of the header. */
data class Capture(
    val type: FrameType,
    val start: Instant,
    val exposureSeconds: Double,
    val camera: CameraInfo,
    val cameraName: String,
    val binX: Int = 1,
    val binY: Int = 1,
    val gain: Int? = null,
    val offset: Int? = null,
    val sensorTemperature: Double? = null,
    val target: String? = null,
    /** The mosaic's panel it is of, for the live mosaic; none outside a mosaic. */
    val mosaic: MosaicPanel? = null,
    val filter: String? = null,
    val focuserPosition: Int? = null,
    val focuserTemperature: Double? = null,
    val mount: MountState? = null,
    val mountName: String? = null,
    val site: Location? = null,
    val optics: Optics = Optics(),
    val software: String,
)

/**
 * The FITS header of a frame, with N.I.N.A.'s keywords so that Siril, PixInsight and ASTAP read
 * it as they read N.I.N.A.'s files.
 *
 * - The pixels are stored top-down (ROWORDER), and BAYERPAT is the pattern from the top-left pixel.
 * - RA and DEC are the mount's position in its own system (JNow on the Seestar), with no EQUINOX:
 *   the J2000 position and the WCS come from plate solving.
 */
fun frameHeaders(capture: Capture): List<HeaderCard> = buildList {
    fun text(key: String, value: String?, comment: String) = value?.let { add(HeaderCard(key, HeaderValue.Text(it), comment)) }
    fun real(key: String, value: Double?, comment: String) = value?.let { add(HeaderCard(key, HeaderValue.Real(it), comment)) }
    fun int(key: String, value: Int?, comment: String) = value?.let { add(HeaderCard(key, HeaderValue.Integer(it.toLong()), comment)) }
    val camera = capture.camera

    text("IMAGETYP", capture.type.fits, "Type of exposure")
    real("EXPOSURE", capture.exposureSeconds, "[s] Exposure duration")
    real("EXPTIME", capture.exposureSeconds, "[s] Exposure duration")
    text("DATE-OBS", fitsDate(capture.start), "Time of observation (UTC)")
    int("XBINNING", capture.binX, "X axis binning factor")
    int("YBINNING", capture.binY, "Y axis binning factor")
    int("GAIN", capture.gain, "Sensor gain")
    int("OFFSET", capture.offset, "Sensor gain offset")
    real("XPIXSZ", camera.pixelSizeX * capture.binX, "[um] Pixel X axis size")
    real("YPIXSZ", camera.pixelSizeY * capture.binY, "[um] Pixel Y axis size")
    text("INSTRUME", capture.cameraName, "Imaging instrument name")
    real("CCD-TEMP", capture.sensorTemperature, "[degC] CCD temperature")
    camera.bayerPattern?.let { pattern ->
        text("BAYERPAT", pattern, "Sensor Bayer pattern")
        int("XBAYROFF", 0, "Bayer pattern X axis offset")
        int("YBAYROFF", 0, "Bayer pattern Y axis offset")
    }
    text("ROWORDER", "TOP-DOWN", "FITS image row order")
    text("TELESCOP", capture.mountName, "Name of telescope")
    real("FOCALLEN", capture.optics.focalLengthMm, "[mm] Focal length")
    val ratio = capture.optics.focalLengthMm?.let { focal -> capture.optics.apertureMm?.let { focal / it } }
    real("FOCRATIO", ratio, "Focal ratio")
    capture.mount?.let { mount ->
        real("RA", mount.rightAscensionHours * DEGREES_PER_HOUR, "[deg] RA of telescope")
        real("DEC", mount.declinationDeg, "[deg] Declination of telescope")
        text("OBJCTRA", sexagesimal(mount.rightAscensionHours, signed = false), "[H M S] RA of imaged object")
        text("OBJCTDEC", sexagesimal(mount.declinationDeg, signed = true), "[D M S] Declination of imaged object")
        real("CENTALT", mount.altitudeDeg, "[deg] Altitude of telescope")
        real("CENTAZ", mount.azimuthDeg, "[deg] Azimuth of telescope")
    }
    text("OBJECT", capture.target, "Name of the object of interest")
    capture.site?.let { site ->
        real("SITEELEV", site.elevationM, "[m] Observation site elevation")
        real("SITELAT", site.latitude, "[deg] Observation site latitude")
        real("SITELONG", site.longitude, "[deg] Observation site longitude")
    }
    text("FILTER", capture.filter, "Active filter name")
    int("FOCPOS", capture.focuserPosition, "[step] Focuser position")
    real("FOCTEMP", capture.focuserTemperature, "[degC] Focuser temperature")
    text("SWCREATE", capture.software, "Software that created this file")
}

/**
 * The FITS header of a live stack whose first frame was [capture]: that frame's,
 * without its Bayer pattern, since the stack's channels are already apart, and with how many frames
 * it holds and their whole exposure, as Siril and PixInsight write them. EXPTIME stays one frame's,
 * since a mean keeps each frame's scale.
 */
fun stackHeaders(capture: Capture, info: StackInfo): List<HeaderCard> = frameHeaders(capture).filterNot { it.key in BAYER_KEYS } +
    listOf(
        HeaderCard("NCOMBINE", HeaderValue.Integer(info.frames.toLong()), "Number of frames stacked"),
        HeaderCard("STACKCNT", HeaderValue.Integer(info.frames.toLong()), "Number of frames stacked"),
        HeaderCard("LIVETIME", HeaderValue.Real(info.exposureSeconds), "[s] Exposure of all the frames"),
    )

private val BAYER_KEYS = setOf("BAYERPAT", "XBAYROFF", "YBAYROFF")

/** `2026-10-04T21:30:05.123`: FITS dates are UTC without a zone letter. */
internal fun fitsDate(time: Instant): String = Instant.fromEpochMilliseconds(time.toEpochMilliseconds()).toString().removeSuffix("Z").let {
    if ('.' in it) it else "$it.000"
}

/** `00 42 44` or `+41 16 09`, as N.I.N.A. writes OBJCTRA and OBJCTDEC. */
internal fun sexagesimal(value: Double, signed: Boolean): String {
    val seconds = (abs(value) * SECONDS).roundToLong()
    val sign = if (!signed) {
        ""
    } else if (value < 0 && seconds > 0) {
        "-"
    } else {
        "+"
    }
    fun two(n: Long) = n.toString().padStart(2, '0')
    return "$sign${two(seconds / SECONDS)} ${two(seconds / MINUTE % MINUTE)} ${two(seconds % MINUTE)}"
}

private const val DEGREES_PER_HOUR = 15.0
private const val SECONDS = 3600
private const val MINUTE = 60
