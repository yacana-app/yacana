// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.format

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

// Coordinates in hours or degrees, minutes and seconds (docs/design/STYLE.md),
// rounded to the last unit shown, with two digits after the first unit.

/** Right ascension as `00h 42m 44s`. */
fun formatHms(hours: Double): String {
    val seconds = (hours.mod(HOURS_PER_DAY) * SECONDS_PER_HOUR).roundToLong() % (HOURS_PER_DAY.toLong() * SECONDS_PER_HOUR)
    return "${two(seconds / SECONDS_PER_HOUR)}h ${two(seconds / SIXTY % SIXTY)}m ${two(seconds % SIXTY)}s"
}

/** Declination or another signed angle as `+41° 16′ 09″`. */
fun formatDms(degrees: Double): String {
    val seconds = (abs(degrees) * SECONDS_PER_HOUR).roundToLong()
    val sign = if (degrees < 0 && seconds > 0) "−" else "+"
    return "$sign${two(seconds / SECONDS_PER_HOUR)}° ${two(seconds / SIXTY % SIXTY)}′ ${two(seconds % SIXTY)}″"
}

/** Altitude or azimuth to the arcminute, as `52° 10′` or `−3° 05′`. */
fun formatDm(degrees: Double): String {
    val minutes = (abs(degrees) * SIXTY).roundToLong()
    val sign = if (degrees < 0 && minutes > 0) "−" else ""
    return "$sign${minutes / SIXTY}° ${two(minutes % SIXTY)}′"
}

/** A number with [decimals] digits after the separator of the app's language: a comma in Spanish (docs/design/STYLE.md). */
fun formatDecimal(value: Double, decimals: Int, comma: Boolean): String {
    val scale = (1..decimals).fold(1L) { acc, _ -> acc * TEN }
    val scaled = (abs(value) * scale).roundToLong()
    val sign = if (value < 0 && scaled > 0) "−" else ""
    val whole = scaled / scale
    if (decimals == 0) return "$sign$whole"
    val fraction = (scaled % scale).toString().padStart(decimals, '0')
    return "$sign$whole${if (comma) ',' else '.'}$fraction"
}

/**
 * A polar alignment error as N.I.N.A.'s `AstroUtil.DegreesToDMS`, to the arcsecond: `00° 04′ 12″`,
 * whole degrees and minutes and the seconds rounded half to even, with a minus sign unless it
 * rounds to 0″.
 */
fun formatPolarError(degrees: Double): String {
    val seconds = round(abs(degrees) * SECONDS_PER_HOUR).toLong()
    val sign = if (degrees < 0 && seconds > 0) "−" else ""
    return "$sign${two(seconds / SECONDS_PER_HOUR)}° ${two(seconds / SIXTY % SIXTY)}′ ${two(seconds % SIXTY)}″"
}

/** A polar alignment error rounded to the arcsecond, as [formatPolarError] shows it. */
fun polarArcseconds(degrees: Double): Long = round(degrees * SECONDS_PER_HOUR).toLong()

/** A small angle, such as how far a frame is from its target: `12″`, `18′ 12″` or `2° 05′`. */
fun formatOffset(arcsec: Double): String {
    val seconds = arcsec.roundToLong()
    return when {
        seconds < SIXTY -> "$seconds″"
        seconds < SECONDS_PER_HOUR -> "${seconds / SIXTY}′ ${two(seconds % SIXTY)}″"
        else -> formatDm(arcsec / SECONDS_PER_HOUR)
    }
}

/** A size in bytes as the phone's settings show it: `0.3 MB`, `901 MB`, `1.2 GB`. */
fun formatSize(bytes: Long, comma: Boolean): String = when {
    bytes < MEGABYTE -> "${formatDecimal(bytes / MEGABYTE.toDouble(), 1, comma)} MB"
    bytes < GIGABYTE -> "${formatDecimal(bytes / MEGABYTE.toDouble(), 0, comma)} MB"
    else -> "${formatDecimal(bytes / GIGABYTE.toDouble(), 1, comma)} GB"
}

private fun two(value: Long) = value.toString().padStart(2, '0')

private const val MEGABYTE = 1_000_000L
private const val GIGABYTE = 1_000_000_000L

private const val HOURS_PER_DAY = 24.0
private const val SECONDS_PER_HOUR = 3600
private const val SIXTY = 60
private const val TEN = 10L

/**
 * Reads hours or degrees typed as decimals (`2.5`, `-41,27`) or as up to three fields
 * (`0 42 44`, `+41° 16′ 09″`, `0h42m44s`). Returns null if it is not a number.
 */
fun parseSexagesimal(text: String): Double? {
    val trimmed = text.trim().replace('−', '-').replace(',', '.')
    val negative = trimmed.startsWith('-')
    val parts = trimmed.split(Regex("[^0-9.]+")).filter { it.isNotEmpty() }
    val fields = parts.mapNotNull { it.toDoubleOrNull() }
    val valid = fields.size == parts.size && fields.size in 1..MAX_FIELDS && fields.drop(1).all { it < SIXTY }
    val value = fields.foldIndexed(0.0) { index, sum, field -> sum + field / (1..index).fold(1.0) { acc, _ -> acc * SIXTY } }
    return when {
        !valid -> null
        negative -> -value
        else -> value
    }
}

private const val MAX_FIELDS = 3
