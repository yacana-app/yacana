// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.profile

import app.yacana.alpaca.DeviceType
import app.yacana.domain.focus.FocusFitting
import app.yacana.domain.imaging.FileNames
import kotlinx.serialization.Serializable

/**
 * An equipment profile: the devices, the optics, the site and the options of one setup.
 * It is saved as versioned JSON ([ProfileFile], ARCHITECTURE.md).
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val devices: ProfileDevices = ProfileDevices(),
    val optics: Optics = Optics(),
    /** Null until the phone's location is read or the user types one. */
    val location: Location? = null,
    val options: ProfileOptions = ProfileOptions(),
)

/** The device chosen for each role. A role without a device is null. */
@Serializable
data class ProfileDevices(
    val mount: DeviceRef? = null,
    val camera: DeviceRef? = null,
    val focuser: DeviceRef? = null,
    val filterWheel: DeviceRef? = null,
    val switches: DeviceRef? = null,
    val flatPanel: DeviceRef? = null,
    val safetyMonitor: DeviceRef? = null,
    /** The filter wheel's filters, as it named them when it last connected, for planning without it. */
    val filterNames: List<String> = emptyList(),
) {
    /** The chosen devices by role, leaving out roles without one. */
    fun byRole(): Map<DeviceRole, DeviceRef> = buildMap {
        mount?.let { put(DeviceRole.Mount, it) }
        camera?.let { put(DeviceRole.Camera, it) }
        focuser?.let { put(DeviceRole.Focuser, it) }
        filterWheel?.let { put(DeviceRole.FilterWheel, it) }
        switches?.let { put(DeviceRole.Switches, it) }
        flatPanel?.let { put(DeviceRole.FlatPanel, it) }
        safetyMonitor?.let { put(DeviceRole.SafetyMonitor, it) }
    }

    /** The same devices with [ref] in [role], replacing the one there. */
    fun with(role: DeviceRole, ref: DeviceRef) = when (role) {
        DeviceRole.Mount -> copy(mount = ref)
        DeviceRole.Camera -> copy(camera = ref)
        DeviceRole.Focuser -> copy(focuser = ref)
        DeviceRole.FilterWheel -> copy(filterWheel = ref)
        DeviceRole.Switches -> copy(switches = ref)
        DeviceRole.FlatPanel -> copy(flatPanel = ref)
        DeviceRole.SafetyMonitor -> copy(safetyMonitor = ref)
    }

    /** The same roles with every device passed through [transform]. */
    fun map(transform: (DeviceRef) -> DeviceRef) = ProfileDevices(
        mount = mount?.let(transform),
        camera = camera?.let(transform),
        focuser = focuser?.let(transform),
        filterWheel = filterWheel?.let(transform),
        switches = switches?.let(transform),
        flatPanel = flatPanel?.let(transform),
        safetyMonitor = safetyMonitor?.let(transform),
    )
}

/** What a device is used for in a profile. */
enum class DeviceRole {
    Mount,
    Camera,
    Focuser,
    FilterWheel,
    Switches,
    FlatPanel,
    SafetyMonitor,
    ;

    companion object {
        /** The role an Alpaca device of [type] takes, or null for types a profile does not have yet. */
        fun of(type: DeviceType?): DeviceRole? = when (type) {
            DeviceType.Telescope -> Mount
            DeviceType.Camera -> Camera
            DeviceType.Focuser -> Focuser
            DeviceType.FilterWheel -> FilterWheel
            DeviceType.Switch -> Switches
            DeviceType.CoverCalibrator -> FlatPanel
            DeviceType.SafetyMonitor -> SafetyMonitor
            else -> null
        }
    }
}

/**
 * An Alpaca device: the server it was last found on, its number there, and its unique ID.
 * The unique ID stays the same when the server's address changes, as the Seestar's does
 * between its own WiFi and the home WiFi.
 */
@Serializable
data class DeviceRef(val serverUrl: String, val number: Int, val uniqueId: String = "", val name: String = "")

/**
 * The optics. [measuredPixelScaleArcsec] comes from a plate solve and wins over the scale
 * worked out from the focal length (docs/design/STYLE.md).
 */
@Serializable
data class Optics(
    val focalLengthMm: Double? = null,
    val apertureMm: Double? = null,
    val measuredPixelScaleArcsec: Double? = null,
    /** The camera's sensor, kept from its last connection, so the field is known without it. */
    val sensorWidthPx: Int? = null,
    val sensorHeightPx: Int? = null,
    val pixelSizeMicrons: Double? = null,
    /**
     * The camera's rotation on the sky as the framing counts it, as the last plate solve measured
     * it: without a rotator the camera does not turn, and the framing keeps it.
     */
    val measuredRotationDeg: Double? = null,
) {
    /** Arcseconds per (unbinned) pixel for a camera with [pixelSizeMicrons], or null without a focal length. */
    fun pixelScaleArcsec(pixelSizeMicrons: Double): Double? =
        measuredPixelScaleArcsec ?: focalLengthMm?.let { SCALE_FACTOR * pixelSizeMicrons / it }

    /** The field across the sensor's width and height, in degrees, once the sensor and the scale are known. */
    fun fieldDeg(): Pair<Double, Double>? {
        val scale = pixelSizeMicrons?.let(::pixelScaleArcsec)
        val width = sensorWidthPx
        val height = sensorHeightPx
        if (scale == null || width == null || height == null) return null
        return width * scale / ARCSEC_PER_DEGREE to height * scale / ARCSEC_PER_DEGREE
    }

    private companion object {
        const val ARCSEC_PER_DEGREE = 3600.0

        /** Arcseconds per radian divided by 1,000, because pixels are in µm and focal lengths in mm. */
        const val SCALE_FACTOR = 206.264806
    }
}

/** The observing site: degrees north and east, and metres above sea level. */
@Serializable
data class Location(val latitude: Double, val longitude: Double, val elevationM: Double = 0.0)

@Serializable
data class ProfileOptions(
    /** Send the UTC time and the site to the mount when connecting. */
    val sendTimeAndLocation: Boolean = true,
    /** Take the site from the phone's location; otherwise the profile's [Profile.location] is used as typed. */
    val locationFromPhone: Boolean = true,
    /** The ASTAP star database for plate solving, such as `D50`. */
    val starDatabase: String = "D50",
    /** ASTAP's options for plate solving. */
    val solver: SolverOptions = SolverOptions(),
    /** The three-point polar alignment's options. */
    val polarAlignment: PolarAlignmentOptions = PolarAlignmentOptions(),
    /** The autofocus's options. */
    val autofocus: AutofocusOptions = AutofocusOptions(),
    /** No GoTo goes below this altitude, in degrees, nor below [horizon]. */
    val minAltitudeDeg: Double = 0.0,
    /** A custom horizon: the altitude of obstacles by azimuth. Empty means flat. */
    val horizon: List<HorizonPoint> = emptyList(),
    /** Where each frame goes and its name, with N.I.N.A.'s tokens; see `FileNames`. */
    val filePattern: String = FileNames.DEFAULT_PATTERN,
    /** How the sequence dithers without a guider. */
    val dither: DitherOptions = DitherOptions(),
    /** How long the night's steps take with this equipment, as its last night measured. */
    val pace: Pace = Pace(),
    /** What the session does on a lost link, a failed sequence or a low battery. */
    val safety: SafetyOptions = SafetyOptions(),
    /** Live stacking of the light frames. */
    val stacking: StackingOptions = StackingOptions(),
    /**
     * The camera's gain for every frame: Imaging's, the sequence's, the flats' and the darks' and
     * bias'; for a camera of named gains, the index of one. One the camera does not take leaves its own.
     */
    val gain: Int = DEFAULT_GAIN,
)

/** The gain a profile starts with: the Seestar S50 Pro app's, 200, where its camera's read noise is under one electron. */
const val DEFAULT_GAIN = 200

/**
 * Live stacking: off at first, so that only who wants it pays its memory and
 * battery; [balanced], the stack's colour is balanced on its own stars, else as the camera gives it.
 */
@Serializable
data class StackingOptions(val live: Boolean = false, val balanced: Boolean = true)

/**
 * The session's safety. A safe stop [park]s the mount, or else stops the
 * exposure and the tracking. With the link lost, the sequence waits [waitMinutes] in a safe pause
 * while the app reconnects, then stops safely, as soon as the link is back. Under
 * [lowBatteryPercent] of the phone's battery, and always under 10 %, it stops safely.
 */
@Serializable
data class SafetyOptions(val park: Boolean = true, val waitMinutes: Double = 2.0, val lowBatteryPercent: Int = 20)

/**
 * How long a night's steps take with this equipment beyond the exposures, for the plan and the
 * running night's end: each light's download, saving and stars ([frameSeconds]), each dither with
 * its settling ([ditherSeconds]; null until measured, then the move and the settle time), each
 * autofocus and each centring. A night measures them and keeps them here; until then, a Seestar's
 * of its first nights: a 30 s light took 34.7 s from start to start, an autofocus 3.5 minutes.
 */
@Serializable
data class Pace(
    val frameSeconds: Double = 4.7,
    val ditherSeconds: Double? = null,
    val autofocusSeconds: Double = 210.0,
    val centringSeconds: Double = 60.0,
)

/**
 * Dithering without a guider, as N.I.N.A.'s direct guider and with its settings: each
 * dither moves the field to an offset around where it began, at a random angle and a distance of a
 * normal distribution whose standard deviation is [pixels] ("Dither pixels"), within three times
 * that, and at least [minimumPixels] from the last one; only in right ascension with [raOnly]. The
 * mount gets the guide pulses that move it there, or a slew if it cannot pulse guide; then
 * [settleSeconds]. A profile of before, with a pulse length, gets these.
 */
@Serializable
data class DitherOptions(
    val pixels: Double = 5.0,
    val minimumPixels: Double = 0.0,
    val raOnly: Boolean = false,
    val settleSeconds: Double = 10.0,
)

/**
 * ASTAP's options for a profile: its [downsample] factor, 0 for automatic; how far from the
 * mount's position to search, in degrees; and how long a solve may take, near the mount and then
 * blind, in seconds. Then centring's.
 */
@Serializable
data class SolverOptions(
    val downsample: Int = 0,
    val searchRadiusDeg: Double = 5.0,
    val timeoutSeconds: Int = 30,
    /** Centring stops once the frame's centre is this close to the target. */
    val centringToleranceArcsec: Double = 30.0,
    /** The exposure of the frames that centring solves. */
    val centringExposureSeconds: Double = 5.0,
)

/**
 * The three-point polar alignment's options: the right ascension turned
 * between points, which way and how fast in degrees per second; the frames' exposure and binning,
 * with Imaging's gain; the optional tolerance in arcminutes, off at 0 as in the plugin; whether
 * the pole is the true one rather than the refracted one, the plugin's "Refraction adjustment";
 * and whether the three points are solved only near the mount, the refining mode.
 */
@Serializable
data class PolarAlignmentOptions(
    val stepDeg: Double = 15.0,
    val east: Boolean = false,
    val rateDegPerSecond: Double = 3.0,
    val exposureSeconds: Double = 1.0,
    val binning: Int = 2,
    val toleranceArcmin: Double = 0.0,
    val refractionAdjustment: Boolean = false,
    val hintOnly: Boolean = false,
)

/**
 * The autofocus's options, as N.I.N.A.'s focuser settings and with its defaults: the
 * focuser's [stepSize] between points and how many steps out it starts ([offsetSteps], so 9
 * points at 4); the frames' exposure, binning and how many per point; the [fitting]; the
 * backlash in and out, compensated by overshooting; the coefficient of determination a fit needs,
 * none at 0, and then the result is kept only if it is not 15 % worse than before; how many
 * attempts; the seconds to settle after each move; and the time limit. And each filter's focus
 * offset in steps, by its name: on a filter change the focuser moves by the new filter's
 * offset minus the old one's; filters without one are at 0.
 */
@Serializable
data class AutofocusOptions(
    val stepSize: Int = 50,
    val offsetSteps: Int = 4,
    val exposureSeconds: Double = 4.0,
    val binning: Int = 1,
    val framesPerPoint: Int = 1,
    val fitting: FocusFitting = FocusFitting.Hyperbolic,
    val backlashIn: Int = 0,
    val backlashOut: Int = 0,
    val rSquaredThreshold: Double = 0.7,
    val attempts: Int = 1,
    val settleSeconds: Double = 0.0,
    val timeoutSeconds: Int = 600,
    val filterOffsets: Map<String, Int> = emptyMap(),
)

/** The horizon's altitude at one azimuth, in degrees (azimuth from north through east). */
@Serializable
data class HorizonPoint(val azimuthDeg: Double, val altitudeDeg: Double)

/**
 * Reads a horizon file as N.I.N.A. writes it: one point per line, azimuth then altitude in degrees,
 * separated by spaces or tabs. Blank lines and lines starting with `#` are skipped.
 *
 * @throws IllegalArgumentException if a line is not two numbers, or the file has no points.
 */
fun parseHorizon(text: String): List<HorizonPoint> {
    val points =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapIndexed { index, line ->
            val numbers = line.split(' ', '\t').filter { it.isNotEmpty() }.map { it.replace(',', '.').toDoubleOrNull() }
            require(numbers.size == 2 && numbers.all { it != null }) { "Line ${index + 1} is not an azimuth and an altitude: $line" }
            HorizonPoint(numbers[0]!!, numbers[1]!!)
        }.toList()
    require(points.isNotEmpty()) { "The file has no horizon points" }
    return points
}
