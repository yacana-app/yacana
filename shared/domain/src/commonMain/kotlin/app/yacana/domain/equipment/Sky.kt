// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.EquatorialSystem
import app.yacana.domain.profile.HorizonPoint
import app.yacana.ports.SkyPosition
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

/** A position in the local sky: degrees above the horizon, and degrees from north through east. */
data class AltAz(val altitudeDeg: Double, val azimuthDeg: Double)

/**
 * Where [rightAscensionHours] and [declinationDeg] are in the sky of an observer at [latitudeDeg]
 * when the local sidereal time is [siderealTimeHours], without refraction.
 *
 * The mount's own sidereal time and site go in, so the result matches the mount's coordinate
 * system, whatever it is. That is accurate to well under a degree, enough for horizon limits.
 */
fun altAz(rightAscensionHours: Double, declinationDeg: Double, siderealTimeHours: Double, latitudeDeg: Double): AltAz {
    val hourAngle = radians((siderealTimeHours - rightAscensionHours) * DEGREES_PER_HOUR)
    val dec = radians(declinationDeg)
    val lat = radians(latitudeDeg)
    val sinAltitude = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle)
    val altitude = asin(sinAltitude.coerceIn(-1.0, 1.0))
    val azimuth = atan2(-cos(dec) * sin(hourAngle), sin(dec) * cos(lat) - cos(dec) * sin(lat) * cos(hourAngle))
    return AltAz(degrees(altitude), degrees(azimuth).mod(FULL_CIRCLE))
}

/** [j2000] in a mount's [system]: J2000 as it is, and the apparent place for the others (topocentric). */
fun toMountSystem(j2000: SkyPosition, system: EquatorialSystem, at: Instant): SkyPosition =
    if (system == EquatorialSystem.J2000) j2000 else apparentPlace(j2000, at)

/** A position in a mount's [system], in J2000. */
fun fromMountSystem(position: SkyPosition, system: EquatorialSystem, at: Instant): SkyPosition =
    if (system == EquatorialSystem.J2000) position else j2000Place(position, at)

/** The angle between two positions on the sky, in arcseconds. */
fun separationArcsec(a: SkyPosition, b: SkyPosition): Double {
    val ra1 = radians(a.raHours * DEGREES_PER_HOUR)
    val ra2 = radians(b.raHours * DEGREES_PER_HOUR)
    val dec1 = radians(a.decDeg)
    val dec2 = radians(b.decDeg)
    // The haversine formula, exact for small angles too.
    val h = sin((dec2 - dec1) / 2).let { it * it } + cos(dec1) * cos(dec2) * sin((ra2 - ra1) / 2).let { it * it }
    return degrees(2 * asin(sqrt(h.coerceIn(0.0, 1.0)))) * ARCSEC_PER_DEGREE
}

/** The shortest way from [from] to [to] in hours of right ascension, between −12 and +12. */
fun hoursBetween(from: Double, to: Double): Double = (to - from + HOURS / 2).mod(HOURS) - HOURS / 2

/**
 * The lowest altitude the mount may point at, by azimuth: the profile's altitude limit,
 * or its custom horizon where that is higher. The horizon is interpolated linearly between its
 * points, all the way round.
 */
class HorizonLimit(private val minAltitudeDeg: Double, horizon: List<HorizonPoint> = emptyList()) {
    private val points = horizon.map { HorizonPoint(it.azimuthDeg.mod(FULL_CIRCLE), it.altitudeDeg) }.sortedBy { it.azimuthDeg }

    fun altitudeAt(azimuthDeg: Double): Double = max(minAltitudeDeg, horizonAt(azimuthDeg.mod(FULL_CIRCLE)))

    fun allows(position: AltAz): Boolean = position.altitudeDeg >= altitudeAt(position.azimuthDeg)

    private fun horizonAt(azimuth: Double): Double = when (points.size) {
        0 -> Double.NEGATIVE_INFINITY

        1 -> points.single().altitudeDeg

        else -> {
            val next = points.indexOfFirst { it.azimuthDeg >= azimuth }
            val (from, to) = if (next <= 0) points.last() to points.first() else points[next - 1] to points[next]
            val span = (to.azimuthDeg - from.azimuthDeg).mod(FULL_CIRCLE)
            val offset = (azimuth - from.azimuthDeg).mod(FULL_CIRCLE)
            if (span == 0.0) from.altitudeDeg else from.altitudeDeg + (to.altitudeDeg - from.altitudeDeg) * offset / span
        }
    }
}

/** A GoTo target below the horizon limit: [target] is where it is, and [limitDeg] the lowest allowed altitude there. */
class BelowHorizonException(val target: AltAz, val limitDeg: Double) :
    Exception("The target is at ${target.altitudeDeg}° altitude, below the limit of $limitDeg° at azimuth ${target.azimuthDeg}°")

private const val DEGREES_PER_HOUR = 15.0
private const val FULL_CIRCLE = 360.0
private const val HOURS = 24.0
private const val ARCSEC_PER_DEGREE = 3600.0

private const val HALF_CIRCLE = 180.0

private fun radians(degrees: Double) = degrees * PI / HALF_CIRCLE

private fun degrees(radians: Double) = radians * HALF_CIRCLE / PI
