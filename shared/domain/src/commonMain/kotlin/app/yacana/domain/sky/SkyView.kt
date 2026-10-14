// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the screen, in pixels from its top left. */
data class ScreenPoint(val x: Double, val y: Double)

/**
 * The sky map's view: the J2000 point at the centre of a [width] × [height] screen and
 * the degrees of sky across it, in stereographic projection with east to the left of north, as the
 * sky looks, and north turned [rollDeg] clockwise from up: as the sky is seen from the site, the
 * zenith up (see [zenithUp]), or north up without one. Stereographic keeps shapes, so each photo
 * of the cache only turns and scales where it lands, from a wide view of the sky down to a small
 * field.
 */
data class SkyView(val center: SkyPosition, val fieldDeg: Double, val width: Double, val height: Double, val rollDeg: Double = 0.0) {
    private val cosRoll = cos(radians(rollDeg))
    private val sinRoll = sin(radians(rollDeg))

    private val ra0 = radians(center.raHours * DEGREES_PER_HOUR)
    private val sinDec0 = sin(radians(center.decDeg))
    private val cosDec0 = cos(radians(center.decDeg))

    /** Pixels per radian of the projection plane, so that [fieldDeg] spans the width at the centre. */
    private val scale = width / (2 * 2 * kotlin.math.tan(radians(fieldDeg) / 4))

    /** Pixels per degree at the centre. */
    val pixelsPerDegree get() = scale * PI / HALF_TURN

    /** Where [position] falls on the screen; null if it is behind the view, opposite its centre. */
    fun project(position: SkyPosition): ScreenPoint? {
        val ra = radians(position.raHours * DEGREES_PER_HOUR)
        val dec = radians(position.decDeg)
        val cosC = sinDec0 * sin(dec) + cosDec0 * cos(dec) * cos(ra - ra0)
        if (cosC <= BEHIND) return null
        val k = 2 / (1 + cosC)
        val x = k * cos(dec) * sin(ra - ra0)
        val y = k * (cosDec0 * sin(dec) - sinDec0 * cos(dec) * cos(ra - ra0))
        // East to the left and north up, then turned clockwise by the roll.
        val right = -x * scale
        val down = -y * scale
        return ScreenPoint(width / 2 + right * cosRoll - down * sinRoll, height / 2 + right * sinRoll + down * cosRoll)
    }

    /** The sky at [point] on the screen: the inverse of [project]. */
    fun unproject(point: ScreenPoint): SkyPosition {
        val turnedRight = point.x - width / 2
        val turnedDown = point.y - height / 2
        val x = -(turnedRight * cosRoll + turnedDown * sinRoll) / scale
        val y = -(-turnedRight * sinRoll + turnedDown * cosRoll) / scale
        val rho = hypot(x, y)
        if (rho == 0.0) return center
        val c = 2 * atan(rho / 2)
        val dec = asin((cos(c) * sinDec0 + y * sin(c) * cosDec0 / rho).coerceIn(-1.0, 1.0))
        val ra = ra0 + atan2(x * sin(c), rho * cosDec0 * cos(c) - y * sinDec0 * sin(c))
        return SkyPosition((degrees(ra) / DEGREES_PER_HOUR).mod(HOURS), degrees(dec))
    }

    /** The view with the sky moved by ([dx], [dy]) pixels, as when dragged. */
    fun dragged(dx: Double, dy: Double): SkyView = copy(center = unproject(ScreenPoint(width / 2 - dx, height / 2 - dy)))

    /** How far from the centre, in degrees, a corner of the screen is. */
    val reachDeg: Double get() = separation(center, unproject(ScreenPoint(0.0, 0.0)))

    companion object {
        /**
         * The roll that puts the zenith up at [center] for an observer at latitude [latitudeDeg]
         * whose meridian has right ascension [siderealDeg] (the local sidereal time): the parallactic
         * angle, how far north turns from the zenith there. West of the meridian north leans right.
         */
        fun zenithUp(center: SkyPosition, latitudeDeg: Double, siderealDeg: Double): Double {
            val hour = radians(siderealDeg - center.raHours * DEGREES_PER_HOUR)
            val dec = radians(center.decDeg)
            val latitude = radians(latitudeDeg)
            return degrees(atan2(sin(hour), kotlin.math.tan(latitude) * cos(dec) - sin(dec) * cos(hour)))
        }

        /** The angle between two places on the sky, in degrees. */
        fun separation(a: SkyPosition, b: SkyPosition): Double {
            val d1 = radians(a.decDeg)
            val d2 = radians(b.decDeg)
            val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(radians((a.raHours - b.raHours) * DEGREES_PER_HOUR))
            return degrees(acos(c.coerceIn(-1.0, 1.0)))
        }

        /** Where the screen's north is at [position]: its angle from up, clockwise, in degrees, and pixels per degree there. */
        fun SkyView.northAt(position: SkyPosition): Pair<Double, Double>? {
            val step = if (position.decDeg > MAX_NORTH) -STEP_DEG else STEP_DEG
            val here = project(position)
            val north = project(SkyPosition(position.raHours, position.decDeg + step))
            if (here == null || north == null) return null
            val dx = (north.x - here.x) * step / STEP_DEG
            val dy = (north.y - here.y) * step / STEP_DEG
            return degrees(atan2(dx, -dy)) to sqrt(dx * dx + dy * dy) / STEP_DEG
        }

        private const val DEGREES_PER_HOUR = 15.0
        private const val HALF_TURN = 180.0
        private const val HOURS = 24.0
        private const val STEP_DEG = 0.1
        private const val MAX_NORTH = 89.8

        /** Points closer than this to the opposite side of the sky are left out: they would be infinitely far. */
        private const val BEHIND = -0.9
    }
}

private fun radians(degrees: Double) = degrees * PI / HALF_TURN_DEG

private fun degrees(radians: Double) = radians * HALF_TURN_DEG / PI

private const val HALF_TURN_DEG = 180.0
