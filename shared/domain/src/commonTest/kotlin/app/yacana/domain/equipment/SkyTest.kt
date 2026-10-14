// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.domain.profile.HorizonPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkyTest {
    private fun assertAltAz(expected: AltAz, actual: AltAz) {
        assertEquals(expected.altitudeDeg, actual.altitudeDeg, 1e-9, "altitude")
        assertEquals(expected.azimuthDeg, actual.azimuthDeg, 1e-9, "azimuth")
    }

    @Test
    fun knownPositions() {
        // On the meridian, 30° south of the zenith.
        assertAltAz(AltAz(60.0, 180.0), altAz(5.0, 10.0, siderealTimeHours = 5.0, latitudeDeg = 40.0))
        // The celestial pole is at the latitude's altitude, due north.
        assertAltAz(AltAz(40.0, 0.0), altAz(13.0, 90.0, siderealTimeHours = 5.0, latitudeDeg = 40.0))
        // On the celestial equator, six hours before the meridian: rising due east.
        assertAltAz(AltAz(0.0, 90.0), altAz(11.0, 0.0, siderealTimeHours = 5.0, latitudeDeg = 40.0))
        // Six hours after the meridian: setting due west.
        assertAltAz(AltAz(0.0, 270.0), altAz(23.0, 0.0, siderealTimeHours = 5.0, latitudeDeg = 40.0))
        // Too far south to rise at latitude 40°: 10° below the horizon on the meridian.
        assertEquals(-10.0, altAz(5.0, -60.0, siderealTimeHours = 5.0, latitudeDeg = 40.0).altitudeDeg, 1e-9)
    }

    @Test
    fun theAltitudeLimitAppliesEverywhere() {
        val limit = HorizonLimit(minAltitudeDeg = 15.0)

        assertEquals(15.0, limit.altitudeAt(123.0))
        assertTrue(limit.allows(AltAz(15.0, 0.0)))
        assertFalse(limit.allows(AltAz(14.9, 0.0)))
    }

    @Test
    fun theHorizonIsInterpolatedAllTheWayRound() {
        val limit =
            HorizonLimit(
                minAltitudeDeg = 0.0,
                horizon = listOf(HorizonPoint(90.0, 30.0), HorizonPoint(180.0, 10.0), HorizonPoint(350.0, 20.0)),
            )

        assertEquals(30.0, limit.altitudeAt(90.0))
        assertEquals(20.0, limit.altitudeAt(135.0))
        assertEquals(20.0, limit.altitudeAt(350.0))
        // Between 350° and 90°, across north: 100° of azimuth from 20° to 30°.
        assertEquals(21.0, limit.altitudeAt(0.0), 1e-9)
        assertEquals(21.0, limit.altitudeAt(360.0), 1e-9)
        assertEquals(29.0, limit.altitudeAt(80.0), 1e-9)
        assertFalse(limit.allows(AltAz(25.0, 100.0)))
    }

    @Test
    fun theHigherOfTheLimitAndTheHorizonWins() {
        val limit = HorizonLimit(minAltitudeDeg = 15.0, horizon = listOf(HorizonPoint(0.0, 5.0), HorizonPoint(180.0, 40.0)))

        assertEquals(15.0, limit.altitudeAt(0.0))
        assertEquals(40.0, limit.altitudeAt(180.0))
        assertEquals(10.0, HorizonLimit(0.0, listOf(HorizonPoint(42.0, 10.0))).altitudeAt(300.0))
    }
}
