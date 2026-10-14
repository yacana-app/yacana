// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// The coefficients are written as their sources print them, so they can be checked there.
@file:Suppress("MagicNumber")

package app.yacana.domain.equipment

import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.time.Instant

// Where a star is seen in the local sky, as N.I.N.A. finds it with SOFA's ICRS-to-observed
// transform: the apparent place (Epochs.kt), the hour angle from the apparent sidereal time, and
// refraction with the constants and the formula of SOFA and ERFA (refco and atioq). The polar
// alignment works in this observed sky.
//
// The refraction follows ERFA's eraRefco and eraAtioq (BSD-3-Clause, Copyright © 2013-2023,
// NumFOCUS Foundation), derived from the IAU SOFA library: https://github.com/liberfa/erfa

/**
 * The air the light crosses, for refraction: the pressure at the site in hPa (0 for none), the
 * temperature in °C, the relative humidity from 0 to 1 and the wavelength in µm.
 */
data class Atmosphere(val pressureHpa: Double, val temperatureC: Double, val relativeHumidity: Double, val wavelengthUm: Double) {
    companion object {
        /** N.I.N.A.'s air without a weather station: 1013.25 hPa, 15 °C, dry, 0.55 µm. */
        val STANDARD = Atmosphere(1013.25, 15.0, 0.0, 0.55)

        /** No refraction. */
        val NONE = Atmosphere(0.0, 0.0, 0.0, 0.0)
    }
}

/** Where [j2000] is seen at [at] from [site], refracted through [air]. */
fun observedPlace(j2000: SkyPosition, at: Instant, site: Location, air: Atmosphere): AltAz {
    val apparent = apparentPlace(j2000, at)
    val geometric = altAz(apparent.raHours, apparent.decDeg, localSiderealTimeHours(at, site.longitude), site.latitude)
    return refract(geometric, refractionConstants(air))
}

/** The J2000 position of what is seen at [observed] at [at] from [site], through [air]: [observedPlace] backwards. */
fun j2000FromObserved(observed: AltAz, at: Instant, site: Location, air: Atmosphere): SkyPosition {
    val geometric = unrefract(observed, refractionConstants(air))
    val alt = radians(geometric.altitudeDeg)
    val az = radians(geometric.azimuthDeg)
    val lat = radians(site.latitude)
    val dec = asin((sin(lat) * sin(alt) + cos(lat) * cos(alt) * cos(az)).coerceIn(-1.0, 1.0))
    val hourAngle = atan2(-sin(az) * cos(alt), sin(alt) * cos(lat) - cos(alt) * sin(lat) * cos(az))
    val ra = (localSiderealTimeHours(at, site.longitude) - degrees(hourAngle) / DEGREES_PER_HOUR).mod(HOURS)
    return j2000Place(SkyPosition(ra, degrees(dec)), at)
}

/** The local apparent sidereal time at [longitudeDeg] (east positive): Meeus 12.4 and the equation of the equinoxes. */
fun localSiderealTimeHours(at: Instant, longitudeDeg: Double): Double {
    val t = centuries(at)
    val days = t * DAYS_PER_CENTURY
    val mean = 280.46061837 + 360.98564736629 * days + 0.000387933 * t * t - t * t * t / 38_710_000.0
    val (nutationInLongitude, nutationInObliquity) = nutationAngles(t)
    val epsilon = obliquity(t) + radians(nutationInObliquity / ARCSEC_PER_DEGREE)
    val equinoxes = nutationInLongitude * cos(epsilon) / ARCSEC_PER_DEGREE
    return ((mean + equinoxes + longitudeDeg) / DEGREES_PER_HOUR).mod(HOURS)
}

/**
 * The altitude where a star at [trueAltitudeDeg] is seen through [air], as N.I.N.A.'s
 * `CalculateRefractedAltitude`: the refracted zenith distance, searched for in steps of 1″.
 */
fun refractedAltitude(trueAltitudeDeg: Double, air: Atmosphere): Double {
    val (a, b) = refractionConstants(air)
    val z = radians(90.0 - trueAltitudeDeg)
    val step = radians(1.0 / ARCSEC_PER_DEGREE)
    var roller = step
    repeat(MAX_REFRACTION_STEPS) {
        val refracted = z - roller
        val original = refracted + a * tan(refracted) + b * tan(refracted).pow(3)
        if (abs(original - z) < step) return 90.0 - degrees(refracted)
        roller += step
    }
    return trueAltitudeDeg
}

/** ERFA's refco: the constants A and B of refraction, in radians, as in dZ = A tan Z + B tan³ Z. */
internal fun refractionConstants(air: Atmosphere): Pair<Double, Double> {
    val optic = air.wavelengthUm <= 100.0
    val t = air.temperatureC.coerceIn(-150.0, 200.0)
    val p = air.pressureHpa.coerceIn(0.0, 10_000.0)
    val r = air.relativeHumidity.coerceIn(0.0, 1.0)
    val w = air.wavelengthUm.coerceIn(0.1, 1e6)
    val pw =
        if (p > 0.0) {
            val ps = 10.0.pow((0.7859 + 0.03477 * t) / (1.0 + 0.00412 * t)) * (1.0 + p * (4.5e-6 + 6e-10 * t * t))
            r * ps / (1.0 - (1.0 - r) * ps / p)
        } else {
            0.0
        }
    val tk = t + 273.15
    val gamma =
        if (optic) {
            val wlsq = w * w
            ((77.53484e-6 + (4.39108e-7 + 3.666e-9 / wlsq) / wlsq) * p - 11.2684e-6 * pw) / tk
        } else {
            (77.6890e-6 * p - (6.3938e-6 - 0.375463 / tk) * pw) / tk
        }
    var beta = 4.4474e-6 * tk
    if (!optic) beta -= 0.0074 * pw * beta
    return gamma * (1.0 - beta) to -gamma * (beta - gamma / 2.0)
}

/** ERFA's atioq: lifts a geometric position to where refraction shows it. */
private fun refract(geometric: AltAz, constants: Pair<Double, Double>): AltAz {
    val (a, b) = constants
    val alt = radians(geometric.altitudeDeg)
    val horizontal = cos(alt)
    val vertical = sin(alt)
    val r = max(horizontal, CEL_MIN)
    val z = max(vertical, SEL_MIN)
    val tz = r / z
    val w = b * tz * tz
    val del = (a + w) * tz / (1.0 + (a + 3.0 * w) / (z * z))
    val cosdel = 1.0 - del * del / 2.0
    val f = cosdel - del * z / r
    val observed = atan2(cosdel * vertical + del * r, horizontal * f)
    return AltAz(degrees(observed), geometric.azimuthDeg)
}

/** [refract] backwards, by successive corrections. */
private fun unrefract(observed: AltAz, constants: Pair<Double, Double>): AltAz {
    var guess = observed
    repeat(INVERSE_STEPS) {
        val seen = refract(guess, constants)
        guess = AltAz(min(90.0, guess.altitudeDeg + observed.altitudeDeg - seen.altitudeDeg), observed.azimuthDeg)
    }
    return guess
}

private fun radians(degrees: Double) = degrees * PI / 180.0

private fun degrees(radians: Double) = radians * 180.0 / PI

private const val DEGREES_PER_HOUR = 15.0
private const val HOURS = 24.0
private const val ARCSEC_PER_DEGREE = 3600.0
private const val DAYS_PER_CENTURY = 36_525.0

/** ERFA's limits: the smallest horizontal and vertical parts of the direction that refraction takes. */
private const val CEL_MIN = 1e-6
private const val SEL_MIN = 0.05
private const val INVERSE_STEPS = 6
private const val MAX_REFRACTION_STEPS = 1000
