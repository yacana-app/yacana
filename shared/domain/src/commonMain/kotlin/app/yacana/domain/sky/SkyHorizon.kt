// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.equipment.HorizonLimit
import app.yacana.domain.equipment.j2000Place
import app.yacana.domain.profile.HorizonPoint
import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Instant

/**
 * The horizon of a site on the sky map, at a moment: the site's own horizon where the profile has
 * one, flat at 0° elsewhere, and the four cardinal points on it; in J2000, as the map's objects.
 */
class SkyHorizon(site: Location, at: Instant, horizon: List<HorizonPoint> = emptyList()) {
    private val sidereal = Ephemerides.localSiderealDeg(at, site)
    private val latitude = site.latitude
    private val limit = HorizonLimit(0.0, horizon)
    private val moment = at

    /** The horizon every degree of azimuth, from north through east all the way back to north. */
    val line: List<SkyPosition> = (0..FULL_TURN).map { azimuth -> at(azimuth.toDouble()) }

    /** North, east, south and west on the horizon. */
    val cardinals: List<SkyPosition> = listOf(0.0, QUARTER, 2 * QUARTER, 3 * QUARTER).map(::at)

    private fun at(azimuthDeg: Double): SkyPosition = j2000Place(equatorial(limit.altitudeAt(azimuthDeg), azimuthDeg), moment)

    /** Where the sky at [altitudeDeg] above the horizon and [azimuthDeg] from north through east is, of date. */
    private fun equatorial(altitudeDeg: Double, azimuthDeg: Double): SkyPosition {
        val alt = radians(altitudeDeg)
        val az = radians(azimuthDeg)
        val lat = radians(latitude)
        val dec = asin((sin(lat) * sin(alt) + cos(lat) * cos(alt) * cos(az)).coerceIn(-1.0, 1.0))
        val hourAngle = atan2(-sin(az) * cos(alt), cos(lat) * sin(alt) - sin(lat) * cos(alt) * cos(az))
        val ra = (sidereal - degrees(hourAngle)).mod(FULL_TURN.toDouble()) / DEGREES_PER_HOUR
        return SkyPosition(ra, degrees(dec))
    }

    private companion object {
        const val FULL_TURN = 360
        const val QUARTER = 90.0
        const val HALF_TURN = 180.0
        const val DEGREES_PER_HOUR = 15.0

        fun radians(degrees: Double) = degrees * PI / HALF_TURN

        fun degrees(radians: Double) = radians * HALF_TURN / PI
    }
}
