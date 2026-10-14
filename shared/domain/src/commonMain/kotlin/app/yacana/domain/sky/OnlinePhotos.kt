// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition
import app.yacana.ports.SkyTile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A photo to ask for online: square, north up, centred on [center] and [fovArcmin]
 * across, rounded to hundredths as N.I.N.A. rounds it.
 */
data class OnlinePhoto(val center: SkyPosition, val fovArcmin: Double) {
    val fovDeg: Double get() = fovArcmin / ARCMIN_PER_DEGREE

    /** As N.I.N.A. names the photos it saves without a target: "00:42:44;41° 16' 09\"". */
    val name: String get() = "${hms(center.raHours)};${dms(center.decDeg)}"

    /** The photo in a cache, once it is in [fileName]. */
    fun tile(fileName: String) = SkyTile(center.raHours * DEGREES_PER_HOUR, center.decDeg, 0.0, fovDeg, fovDeg, fileName, Hips2Fits.SOURCE)

    private companion object {
        fun hms(hours: Double): String {
            val seconds = (hours.mod(HOURS) * SECONDS_PER_HOUR).roundToLong().mod(HOURS.toLong() * SECONDS_PER_HOUR)
            return "${two(seconds / SECONDS_PER_HOUR)}:${two(seconds / SIXTY % SIXTY)}:${two(seconds % SIXTY)}"
        }

        fun dms(degrees: Double): String {
            val seconds = (abs(degrees) * SECONDS_PER_HOUR).roundToLong()
            val sign = if (degrees < 0) "-" else ""
            return "$sign${two(seconds / SECONDS_PER_HOUR)}° ${two(seconds / SIXTY % SIXTY)}' ${two(seconds % SIXTY)}\""
        }

        fun two(value: Long) = value.toString().padStart(2, '0')
    }
}

/**
 * The CDS's hips2fits, as N.I.N.A. asks it for its framing photos: DSS2 in colour, in the
 * stereographic projection of the map, from its second server first; 3,000 pixels square, so
 * that a photo of a view barely zoomed in past the caches already has more detail than they have.
 */
object Hips2Fits {
    /** What N.I.N.A. writes as these photos' source in its cache. */
    const val SOURCE = "Hips2FitsSurvey"
    const val PIXELS = 3000
    val SERVERS = listOf("https://alaskybis.u-strasbg.fr", "https://alasky.u-strasbg.fr")
    private const val SURVEY = "CDS%2FP%2FDSS2%2Fcolor"

    fun url(server: String, photo: OnlinePhoto): String =
        "$server/hips-image-services/hips2fits?projection=STG&hips=$SURVEY&width=$PIXELS&height=$PIXELS" +
            "&fov=${photo.fovDeg}&ra=${photo.center.raHours * DEGREES_PER_HOUR}&dec=${photo.center.decDeg}&format=jpg"
}

/**
 * When the map asks for a photo online: as soon as the photos of N.I.N.A.'s full cache, 5° in
 * 2,000 pixels, start to look blurred on a phone, about 2.5° across it upright, with a photo of
 * the view that has more detail than they have; and not while one already there covers the view
 * with enough of it.
 */
object OnlinePhotos {
    /**
     * A photo is a half wider than the view, so that it still covers it with the map turned, zenith
     * up, and can be moved a little before another is needed.
     */
    private const val MARGIN = 1.5

    /** A third more detail than the full cache's photos. */
    private const val MAX_FOV_DEG = 5.5

    /** About the detail of DSS2 itself: below it, a smaller field brings no more. */
    private const val MIN_FOV_DEG = 0.3

    /** A photo already there will do while it is no more than a half wider than the one the view wants. */
    private const val COARSER = 1.5

    /**
     * Whether a view of [widthDeg] × [heightDeg] shows the photos online: only where they have the
     * detail the caches lack, as they are asked for. Wider, the caches do as well, and the photos
     * online, of another tone, would show as patches.
     */
    fun shown(widthDeg: Double, heightDeg: Double): Boolean = max(widthDeg, heightDeg) * MARGIN <= MAX_FOV_DEG

    /** The photo for a view of [widthDeg] × [heightDeg] around [center], given the photos online [have]; null when none is needed. */
    fun wanted(center: SkyPosition, widthDeg: Double, heightDeg: Double, have: List<SkyTile>): OnlinePhoto? {
        if (!shown(widthDeg, heightDeg)) return null
        val span = max(widthDeg, heightDeg)
        val fovDeg = max(span * MARGIN, MIN_FOV_DEG)
        val enough = have.any { tile -> tile.widthDeg <= fovDeg * COARSER && covers(tile, center, span) }
        return if (enough) null else OnlinePhoto(center, floor(fovDeg * ARCMIN_PER_DEGREE * HUNDREDTHS + HALF) / HUNDREDTHS)
    }

    private fun covers(tile: SkyTile, center: SkyPosition, span: Double): Boolean {
        val place = SkyPosition(tile.raDeg / DEGREES_PER_HOUR, tile.decDeg)
        return SkyView.separation(place, center) + span / 2 <= min(tile.widthDeg, tile.heightDeg) / 2
    }
}

private const val ARCMIN_PER_DEGREE = 60.0
private const val DEGREES_PER_HOUR = 15.0
private const val HOURS = 24.0
private const val SECONDS_PER_HOUR = 3600L
private const val SIXTY = 60L
private const val HUNDREDTHS = 100.0
private const val HALF = 0.5
