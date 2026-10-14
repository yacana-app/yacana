// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.EquatorialSystem
import app.yacana.ports.SkyPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class EpochsTest {
    /** 2028 November 13.19 (JD 2462088.69), the date of Meeus's examples 21.b and 23.a. */
    private val meeusDate = Instant.parse("2028-11-13T04:33:36Z")

    @Test
    fun thetaPerseiAsInMeeus() {
        // Example 21.b: θ Persei in J2000 with its proper motion to that date already applied.
        val j2000 = SkyPosition(raHours = 41.054063 / 15, decDeg = 49.227750)

        val apparent = apparentPlace(j2000, meeusDate)

        // Example 23.a: 2h 46m 14.390s, +49° 21′ 07.45″. Meeus's shorter nutation is good to 0.5″.
        val expected = SkyPosition(raHours = (2 + 46.0 / 60 + 14.390 / 3600), decDeg = 49 + 21.0 / 60 + 7.45 / 3600)
        assertTrue(separationArcsec(expected, apparent) < 1.0, "${separationArcsec(expected, apparent)}″ from Meeus")
    }

    @Test
    fun backToJ2000() {
        listOf(SkyPosition(0.712_22, 41.269), SkyPosition(20.98, 44.33), SkyPosition(23.99, -89.2), SkyPosition(6.0, 0.0)).forEach {
            val there = apparentPlace(it, meeusDate)
            assertTrue(separationArcsec(it, j2000Place(there, meeusDate)) < 0.01, "$it")
        }
    }

    @Test
    fun j2000MountsTakeJ2000AsItIs() {
        val m31 = SkyPosition(0.712_22, 41.269)

        assertEquals(m31, toMountSystem(m31, EquatorialSystem.J2000, meeusDate))
        // Topocentric is JNow: 28 years of precession move M 31 by about 23′.
        val jNow = toMountSystem(m31, EquatorialSystem.Topocentric, meeusDate)
        assertTrue(separationArcsec(m31, jNow) in 1_200.0..1_500.0, "${separationArcsec(m31, jNow)}″")
        assertTrue(separationArcsec(m31, fromMountSystem(jNow, EquatorialSystem.Topocentric, meeusDate)) < 0.01)
    }

    @Test
    fun separations() {
        assertEquals(3600.0, separationArcsec(SkyPosition(1.0, 10.0), SkyPosition(1.0, 11.0)), 1e-6)
        // Across 0h: 2 minutes of right ascension on the equator are half a degree.
        assertEquals(1800.0, separationArcsec(SkyPosition(23.983_333_33, 0.0), SkyPosition(0.0166666667, 0.0)), 1e-3)
        assertEquals(-0.5, hoursBetween(0.25, 23.75), 1e-12)
    }
}
