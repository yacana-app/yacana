// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.profile.HorizonPoint
import app.yacana.domain.profile.Location
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SkyHorizonTest {
    private val madrid = Location(latitude = 40.4, longitude = -3.7, elevationM = 650.0)
    private val night = Instant.parse("2026-10-10T22:00:00Z")

    @Test
    fun theLineIsAtTheHorizonAllTheWayRound() {
        val horizon = SkyHorizon(madrid, night)

        assertEquals(361, horizon.line.size)
        horizon.line.forEach { point ->
            val altitude = Ephemerides.altitude(point, night, madrid)
            assertTrue(abs(altitude) < 0.05, "$point is at $altitude°")
        }
    }

    @Test
    fun itFollowsTheProfilesOwnHorizon() {
        // Trees of 10° to the south.
        val trees = listOf(HorizonPoint(150.0, 0.0), HorizonPoint(170.0, 10.0), HorizonPoint(190.0, 10.0), HorizonPoint(210.0, 0.0))
        val horizon = SkyHorizon(madrid, night, trees)

        assertEquals(10.0, Ephemerides.altitude(horizon.line[180], night, madrid), 0.05)
        assertEquals(0.0, Ephemerides.altitude(horizon.line[0], night, madrid), 0.05)
    }

    @Test
    fun northIsUnderThePoleAndSouthOppositeIt() {
        val cardinals = SkyHorizon(madrid, night).cardinals
        val (north, east, south) = cardinals
        val west = cardinals[3]

        // The pole is as high as the latitude, so the north point is 90° − 40.4° from it.
        assertEquals(90.0 - madrid.latitude, north.decDeg, 0.3)
        assertEquals(madrid.latitude - 90.0, south.decDeg, 0.3)
        // East and west are on the equator.
        assertEquals(0.0, east.decDeg, 0.3)
        assertEquals(0.0, west.decDeg, 0.3)
    }
}
