// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A spot of the sky to frame that is no object of the catalogue, as the map's centre: named by its
 * coordinates, "03h 47m +24° 07′", the same in every language, for the sequence and its files.
 */
fun skySpot(position: SkyPosition): SkyObject =
    SkyObject(listOf(spotName(position)), ObjectType.Other, position, "", null, null, null, null, null, emptyList(), null)

/** [position] to the minute of right ascension and of declination. */
fun spotName(position: SkyPosition): String {
    val raMinutes = (position.raHours * MINUTES).roundToInt().mod(HOURS * MINUTES)
    val decMinutes = (abs(position.decDeg) * MINUTES).roundToInt()
    val sign = if (position.decDeg < 0) "−" else "+"
    return "${two(raMinutes / MINUTES)}h ${two(raMinutes % MINUTES)}m $sign${two(decMinutes / MINUTES)}° ${two(decMinutes % MINUTES)}′"
}

private fun two(value: Int) = value.toString().padStart(2, '0')

private const val MINUTES = 60
private const val HOURS = 24
