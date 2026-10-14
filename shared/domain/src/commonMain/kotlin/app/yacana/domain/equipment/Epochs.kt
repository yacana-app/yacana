// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// The formulas' coefficients are written as Meeus prints them, so they can be checked against the book.
@file:Suppress("MagicNumber")

package app.yacana.domain.equipment

import app.yacana.ports.SkyPosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.Instant

// From J2000, the epoch of catalogues and of plate solving, to the apparent place of the date
// ("JNow"), the system of most mounts and of the Seestar, as ASCOM's topocentric coordinates:
// precession, nutation and annual aberration, as in Jean Meeus, Astronomical Algorithms, 2nd
// edition, chapters 21 to 23. The nutation is Meeus's shorter series, good to half an arcsecond.

/** Where [j2000] is seen at [at], with precession, nutation and annual aberration. */
fun apparentPlace(j2000: SkyPosition, at: Instant): SkyPosition {
    val t = centuries(at)
    val mean = precess(j2000, t)
    val ra = radians(mean.raHours * DEGREES_PER_HOUR)
    val dec = radians(mean.decDeg)
    val (nutationRa, nutationDec) = nutation(ra, dec, t)
    val (aberrationRa, aberrationDec) = aberration(ra, dec, t)
    return SkyPosition(
        raHours = ((mean.raHours * DEGREES_PER_HOUR + (nutationRa + aberrationRa) / ARCSEC_PER_DEGREE) / DEGREES_PER_HOUR).mod(HOURS),
        decDeg = mean.decDeg + (nutationDec + aberrationDec) / ARCSEC_PER_DEGREE,
    )
}

/** The J2000 position of [apparent], seen at [at]: [apparentPlace] backwards, by successive corrections. */
fun j2000Place(apparent: SkyPosition, at: Instant): SkyPosition {
    var guess = apparent
    repeat(INVERSE_STEPS) {
        val seen = apparentPlace(guess, at)
        guess =
            SkyPosition(
                raHours = (guess.raHours + hoursBetween(seen.raHours, apparent.raHours)).mod(HOURS),
                decDeg = guess.decDeg + apparent.decDeg - seen.decDeg,
            )
    }
    return guess
}

/** Julian centuries since J2000.0, the time of the formulas. */
internal fun centuries(at: Instant): Double {
    val julianDay = at.toEpochMilliseconds() / MILLIS_PER_DAY + UNIX_EPOCH_JULIAN_DAY
    return (julianDay - J2000_JULIAN_DAY) / DAYS_PER_CENTURY
}

/** Meeus 21.2–21.4: the mean place of the date. */
private fun precess(j2000: SkyPosition, t: Double): SkyPosition {
    val zeta = radians((2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / ARCSEC_PER_DEGREE)
    val z = radians((2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / ARCSEC_PER_DEGREE)
    val theta = radians((2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / ARCSEC_PER_DEGREE)
    val ra0 = radians(j2000.raHours * DEGREES_PER_HOUR)
    val dec0 = radians(j2000.decDeg)
    val a = cos(dec0) * sin(ra0 + zeta)
    val b = cos(theta) * cos(dec0) * cos(ra0 + zeta) - sin(theta) * sin(dec0)
    val c = sin(theta) * cos(dec0) * cos(ra0 + zeta) + cos(theta) * sin(dec0)
    val ra = atan2(a, b) + z
    // Near the poles the declination comes from its cosine, which keeps its precision there.
    val dec = if (abs(c) > NEAR_POLE) acos(sqrt(a * a + b * b)) * sign(c) else asin(c)
    return SkyPosition((degrees(ra) / DEGREES_PER_HOUR).mod(HOURS), degrees(dec))
}

/** Meeus 22 (short series): the nutation in longitude and in obliquity, in arcseconds. */
internal fun nutationAngles(t: Double): Pair<Double, Double> {
    val sunLongitude = radians(280.4665 + 36000.7698 * t)
    val moonLongitude = radians(218.3165 + 481267.8813 * t)
    val node = radians(125.04452 - 1934.136261 * t)
    val longitude = -17.20 * sin(node) - 1.32 * sin(2 * sunLongitude) - 0.23 * sin(2 * moonLongitude) + 0.21 * sin(2 * node)
    val obliquity = 9.20 * cos(node) + 0.57 * cos(2 * sunLongitude) + 0.10 * cos(2 * moonLongitude) - 0.09 * cos(2 * node)
    return longitude to obliquity
}

/** Meeus 23.1: the nutation in right ascension and declination, in arcseconds. */
private fun nutation(ra: Double, dec: Double, t: Double): Pair<Double, Double> {
    val (longitude, obliquity) = nutationAngles(t)
    val epsilon = obliquity(t) + radians(obliquity / ARCSEC_PER_DEGREE)
    val deltaRa = (cos(epsilon) + sin(epsilon) * sin(ra) * tan(dec)) * longitude - cos(ra) * tan(dec) * obliquity
    val deltaDec = sin(epsilon) * cos(ra) * longitude + sin(ra) * obliquity
    return deltaRa to deltaDec
}

/** Meeus 23.3: the annual aberration in right ascension and declination, in arcseconds. */
private fun aberration(ra: Double, dec: Double, t: Double): Pair<Double, Double> {
    val meanLongitude = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
    val anomaly = radians(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
    val centre =
        (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(anomaly) + (0.019993 - 0.000101 * t) * sin(2 * anomaly) +
            0.000289 * sin(3 * anomaly)
    val sun = radians(meanLongitude + centre)
    val eccentricity = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
    val perihelion = radians(102.93735 + 1.71946 * t + 0.00046 * t * t)
    val epsilon = obliquity(t)
    val k = ABERRATION
    fun raTerm(longitude: Double) = (cos(ra) * cos(longitude) * cos(epsilon) + sin(ra) * sin(longitude)) / cos(dec)
    fun decTerm(longitude: Double) =
        cos(longitude) * cos(epsilon) * (tan(epsilon) * cos(dec) - sin(ra) * sin(dec)) + cos(ra) * sin(dec) * sin(longitude)
    val deltaRa = -k * raTerm(sun) + eccentricity * k * raTerm(perihelion)
    val deltaDec = -k * decTerm(sun) + eccentricity * k * decTerm(perihelion)
    return deltaRa to deltaDec
}

/** Meeus 22.2: the mean obliquity of the ecliptic, in radians. */
internal fun obliquity(t: Double): Double = radians(
    OBLIQUITY_J2000 + (-46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) / ARCSEC_PER_DEGREE,
)

private fun radians(degrees: Double) = degrees * PI / HALF_CIRCLE

private fun degrees(radians: Double) = radians * HALF_CIRCLE / PI

private const val HALF_CIRCLE = 180.0
private const val DEGREES_PER_HOUR = 15.0
private const val HOURS = 24.0
private const val ARCSEC_PER_DEGREE = 3600.0
private const val MILLIS_PER_DAY = 86_400_000.0
private const val UNIX_EPOCH_JULIAN_DAY = 2_440_587.5
private const val J2000_JULIAN_DAY = 2_451_545.0
private const val DAYS_PER_CENTURY = 36_525.0

/** 23° 26′ 21.448″. */
private const val OBLIQUITY_J2000 = 23.0 + 26.0 / 60 + 21.448 / 3600

/** The constant of aberration, in arcseconds. */
private const val ABERRATION = 20.49552
private const val NEAR_POLE = 0.99
private const val INVERSE_STEPS = 10
