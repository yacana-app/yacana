// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.equipment.separationArcsec
import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** A night as JPL Horizons computes it (`tools/horizons/reference.py`); a place is RA and Dec in degrees. */
internal data class HorizonsNight(
    val site: String,
    val location: Location,
    val evening: LocalDate,
    val sunset: Instant?,
    val civilDusk: Instant?,
    val nauticalDusk: Instant?,
    val astronomicalDusk: Instant?,
    val astronomicalDawn: Instant?,
    val nauticalDawn: Instant?,
    val civilDawn: Instant?,
    val sunrise: Instant?,
    val moonrise: Instant?,
    val moonset: Instant?,
    val midnight: Instant,
    val sun: Place,
    val moon: Place,
    val moonIlluminated: Double,
)

internal data class Place(val raDeg: Double, val decDeg: Double) {
    val position get() = SkyPosition(raDeg / DEGREES_PER_HOUR, decDeg)
}

private const val DEGREES_PER_HOUR = 15.0

/** Against JPL Horizons, rise, set and twilight within 1 min and places within ±1′. */
class EphemeridesTest {
    @Test
    fun theNightsEventsAreWithinAMinuteOfHorizons() {
        for (reference in HORIZONS) {
            val night = Ephemerides.night(reference.evening, reference.location)
            val name = "${reference.site}, ${reference.evening}"
            listOf(
                "sunset" to (night.sunset to reference.sunset),
                "civil dusk" to (night.civilDusk to reference.civilDusk),
                "nautical dusk" to (night.nauticalDusk to reference.nauticalDusk),
                "astronomical dusk" to (night.astronomicalDusk to reference.astronomicalDusk),
                "astronomical dawn" to (night.astronomicalDawn to reference.astronomicalDawn),
                "nautical dawn" to (night.nauticalDawn to reference.nauticalDawn),
                "civil dawn" to (night.civilDawn to reference.civilDawn),
                "sunrise" to (night.sunrise to reference.sunrise),
                "moonrise" to (night.moonrise to reference.moonrise),
                "moonset" to (night.moonset to reference.moonset),
            ).forEach { (event, times) ->
                val (ours, horizons) = times
                if (horizons == null) {
                    assertNull(ours, "$name: $event should not happen")
                } else {
                    assertTrue(ours != null && abs((ours - horizons).inWholeSeconds) <= 60, "$name: $event at $ours, Horizons $horizons")
                }
            }
        }
    }

    @Test
    fun theSunAndTheMoonAreWithinAnArcminuteOfHorizons() {
        for (reference in HORIZONS) {
            val name = "${reference.site}, ${reference.midnight}"
            val sun = Ephemerides.sun(reference.midnight, reference.location).position
            val moon = Ephemerides.moon(reference.midnight, reference.location).position
            val sunError = separationArcsec(sun, reference.sun.position)
            val moonError = separationArcsec(moon, reference.moon.position)
            assertTrue(sunError <= 60, "$name: the Sun is $sunError″ off")
            assertTrue(moonError <= 60, "$name: the Moon is $moonError″ off")
            assertEquals(reference.moonIlluminated, Ephemerides.moonIllumination(reference.midnight), 0.01, name)
        }
    }

    @Test
    fun theMoonlessDarkIsTheAstronomicalNightWithoutTheMoon() {
        // The night of the design at Roque de los Muchachos: the Moon rises at 23:28 UTC, after the end of twilight.
        val reference = HORIZONS.first()
        val night = Ephemerides.night(reference.evening, reference.location)

        val dark = night.moonlessDark.single()
        assertEquals(night.astronomicalDusk, dark.start)
        assertTrue(abs((dark.endExclusive - reference.moonrise!!).inWholeSeconds) <= 60)
    }

    @Test
    fun noAstronomicalNightInANorthernSummer() {
        val reference = HORIZONS.single { it.site == "Metsähovi" && it.evening.monthNumber == 6 }
        val night = Ephemerides.night(reference.evening, reference.location)

        assertNull(night.astronomical)
        assertTrue(night.moonlessDark.isEmpty())
    }

    @Test
    fun theMoonsDistanceToATarget() {
        val reference = HORIZONS.first()
        val moon = reference.moon.position

        // J2000 and of date differ by a third of a degree: the precession since 2000.
        assertEquals(0.0, Ephemerides.moonDistance(moon, reference.midnight, reference.location), 0.5)
        assertEquals(90 - moon.decDeg, Ephemerides.moonDistance(SkyPosition(0.0, 90.0), reference.midnight, reference.location), 0.5)
    }
}
