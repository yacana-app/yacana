// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Ported from N.I.N.A.'s Three Point Polar Alignment plugin (Vector3, PolarErrorDetermination and
// the legacy error estimate of TPAPAVM), Copyright © Stefan Berg and the N.I.N.A. contributors,
// MPL-2.0: https://github.com/isbeorn/nina.plugin.polaralignment

package app.yacana.domain.polar

import app.yacana.domain.equipment.AltAz
import app.yacana.domain.equipment.Atmosphere
import app.yacana.domain.equipment.j2000FromObserved
import app.yacana.domain.equipment.observedPlace
import app.yacana.domain.equipment.refractedAltitude
import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

/** A solved frame: where its centre is, in J2000, and when it was taken. */
data class SolvedPoint(val centre: SkyPosition, val at: Instant)

/**
 * How far the mount's axis is from the pole, in degrees. A positive [altitudeDeg] means the axis
 * is too high in the north (too low in the south); a positive [azimuthDeg], too far east in the
 * north (west in the south), as the plugin signs them.
 */
data class PolarError(val altitudeDeg: Double, val azimuthDeg: Double) {
    val totalDeg: Double get() = hypot(altitudeDeg, azimuthDeg)
}

/**
 * The mount's axis from three frames taken turning only its right ascension axis, as the
 * plugin's `PolarErrorDetermination`. Each frame's centre is seen from [site] through [air], at the
 * time it was taken; the circle they draw has the axis at its centre. The pole it is compared with
 * is the refracted pole, unless [correctForRefraction], the plugin's "Refraction adjustment".
 */
class PolarErrorMeasurement(points: List<SolvedPoint>, val site: Location, air: Atmosphere, correctForRefraction: Boolean) {
    init {
        require(points.size == THREE) { "Three points measure the axis" }
    }

    val northern: Boolean = site.latitude > 0

    /** The last frame, the reference of the live error. */
    val reference: SolvedPoint = points.last()

    /** Where the mount's axis points, in the observed sky. */
    val axis: AltAz

    val initial: PolarError

    init {
        val (a, b, c) = points.map { observedPlace(it.centre, it.at, site, air).toVector() }
        var normal = planeNormal(a, b, c)
        // The plane has two normals: the one towards the visible pole.
        val awayFromPole = if (northern) normal.x < 0 else normal.x > 0
        if (awayFromPole) normal = -normal
        axis = normal.toAltAz()
        val pole = if (correctForRefraction) abs(site.latitude) else refractedAltitude(abs(site.latitude), air)
        val altitude = if (northern) axis.altitudeDeg - pole else pole - axis.altitudeDeg
        var azimuth = if (northern) axis.azimuthDeg else axis.azimuthDeg + HALF_CIRCLE
        if (azimuth > HALF_CIRCLE) azimuth -= FULL_CIRCLE
        if (azimuth < -HALF_CIRCLE) azimuth += FULL_CIRCLE
        initial = PolarError(altitude, azimuth)
    }

    /**
     * The error now, from where [current] is centred, as the plugin's legacy estimate: the
     * reference frame's centre moves towards the corrected position as the screws turn, and how far
     * along each axis's line it has gone scales that axis's error.
     */
    fun current(current: SolvedPoint): PolarError {
        val centre = Point(0.0, 0.0)
        fun project(position: SkyPosition) = stereographic(position, current.centre)
        var origin = project(reference.centre)
        val shift = centre - origin
        origin += shift * 2.0
        fun target(azimuthDeg: Double, altitudeDeg: Double) = project(destination(azimuthDeg, altitudeDeg, current.at)) + shift * 2.0

        val destination = target(-initial.azimuthDeg, -initial.altitudeDeg)
        val originalAzimuth = target(-initial.azimuthDeg, 0.0)
        val correctedAzimuth = intersect(centre, originalAzimuth - origin, originalAzimuth, destination - originalAzimuth)
        val originalAltitude = target(0.0, -initial.altitudeDeg)
        val correctedAltitude = intersect(centre, originalAltitude - origin, originalAltitude, destination - originalAltitude)

        val azimuthSign = if ((destination - originalAltitude).dot(destination - correctedAltitude) > 0) 1.0 else -1.0
        val altitudeSign = if ((destination - originalAzimuth).dot(destination - correctedAzimuth) > 0) 1.0 else -1.0
        return PolarError(
            altitudeDeg =
            initial.altitudeDeg * altitudeSign * ratio(correctedAltitude.distanceTo(centre), originalAltitude.distanceTo(origin)),
            azimuthDeg = initial.azimuthDeg * azimuthSign * ratio(correctedAzimuth.distanceTo(centre), originalAzimuth.distanceTo(origin)),
        )
    }

    /**
     * The reference frame's centre at [at], turned [azimuthDeg] around the vertical and then
     * [altitudeDeg] around the horizontal, as the plugin's `GetDestinationCoordinates`, without
     * refraction. Turned by the error itself, it is where the field goes once the axis is
     * corrected; turned by minus the error, where the reference star goes in the image.
     */
    internal fun destination(azimuthDeg: Double, altitudeDeg: Double, at: Instant): SkyPosition {
        val reference = observedPlace(this.reference.centre, at, site, Atmosphere.NONE).toVector()
        val up = Vector3(0.0, 0.0, 1.0)
        val turned = reference.rotate(up, azimuthDeg)
        val altitudeAxis = Vector3(0.0, 1.0, 0.0).rotate(up, azimuthDeg)
        return j2000FromObserved(turned.rotate(altitudeAxis, altitudeDeg).toAltAz(), at, site, Atmosphere.NONE)
    }

    private fun ratio(corrected: Double, original: Double) = if (original < TINY) 0.0 else corrected / original
}

/** A direction in the local sky: x towards north, y towards west, z up, as the plugin's `Vector3`. */
internal data class Vector3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)

    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)

    operator fun times(factor: Double) = Vector3(x * factor, y * factor, z * factor)

    operator fun unaryMinus() = Vector3(-x, -y, -z)

    fun cross(other: Vector3) = Vector3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)

    fun dot(other: Vector3) = x * other.x + y * other.y + z * other.z

    fun unit(): Vector3 {
        val length = sqrt(dot(this))
        return if (length == 0.0) Vector3(0.0, 0.0, 0.0) else Vector3(x / length, y / length, z / length)
    }

    /** Turned by [degrees] around the unit vector [k]: Rodrigues' rotation formula. */
    fun rotate(k: Vector3, degrees: Double): Vector3 {
        val angle = radians(degrees)
        return this * cos(angle) + k.cross(this) * sin(angle) + k * (k.dot(this) * (1 - cos(angle)))
    }

    fun toAltAz(): AltAz {
        val horizontal = sqrt(x * x + y * y)
        if (horizontal == 0.0 && z == 0.0) return AltAz(0.0, 0.0)
        var azimuth = atan2(-y, x)
        if (azimuth < 0) azimuth += 2 * PI
        return AltAz(degrees(atan2(z, horizontal)), degrees(azimuth))
    }
}

internal fun AltAz.toVector(): Vector3 {
    val theta = -radians(azimuthDeg)
    val phi = PI / 2 - radians(altitudeDeg)
    return Vector3(cos(theta) * sin(phi), sin(theta) * sin(phi), cos(phi))
}

/** The unit normal of the plane through [a], [b] and [c]. */
internal fun planeNormal(a: Vector3, b: Vector3, c: Vector3): Vector3 = (b - a).cross(c - b).unit()

/** A point of the plugin's image plane, in radians of the stereographic projection. */
private data class Point(val x: Double, val y: Double) {
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)

    operator fun plus(other: Point) = Point(x + other.x, y + other.y)

    operator fun times(factor: Double) = Point(x * factor, y * factor)

    fun dot(other: Point) = x * other.x + y * other.y

    fun distanceTo(other: Point) = hypot(x - other.x, y - other.y)
}

/**
 * Where [position] falls in a stereographic projection centred on [centre], as N.I.N.A.'s
 * `XYProjection` with no rotation and a scale of one. The estimate only uses ratios of distances
 * and directions, which neither the image's scale nor its rotation change.
 */
private fun stereographic(position: SkyPosition, centre: SkyPosition): Point {
    var ra = position.raHours * DEGREES_PER_HOUR
    val delta = ra - centre.raHours * DEGREES_PER_HOUR
    if (delta > HALF_CIRCLE) ra -= FULL_CIRCLE
    if (delta < -HALF_CIRCLE) ra += FULL_CIRCLE
    val raDiff = radians(ra - centre.raHours * DEGREES_PER_HOUR)
    val dec = radians(position.decDeg)
    val centreDec = radians(centre.decDeg)
    val dd = 2.0 / (1.0 + sin(dec) * sin(centreDec) + cos(dec) * cos(centreDec) * cos(raDiff))
    val x = dd * sin(raDiff) * cos(dec)
    val y = dd * (sin(dec) * cos(centreDec) - cos(dec) * sin(centreDec) * cos(raDiff))
    return Point(-x, -y)
}

/** Where the line through [a] along [da] meets the line through [b] along [db]. */
private fun intersect(a: Point, da: Point, b: Point, db: Point): Point {
    val cross = da.x * db.y - da.y * db.x
    if (abs(cross) < TINY) return a
    val distance = b - a
    val t = (distance.x * db.y - distance.y * db.x) / cross
    return a + da * t
}

private fun radians(degrees: Double) = degrees * PI / HALF_CIRCLE

private fun degrees(radians: Double) = radians * HALF_CIRCLE / PI

private const val THREE = 3
private const val HALF_CIRCLE = 180.0
private const val FULL_CIRCLE = 360.0
private const val DEGREES_PER_HOUR = 15.0
private const val TINY = 1e-15
