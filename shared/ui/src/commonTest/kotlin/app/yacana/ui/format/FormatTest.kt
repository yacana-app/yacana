// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun rightAscension() {
        assertEquals("00h 42m 44s", formatHms(0.712222))
        assertEquals("23h 59m 59s", formatHms(23.99972))
        // Rounds up to the next day, which is 00h again.
        assertEquals("00h 00m 00s", formatHms(23.99999))
        assertEquals("12h 00m 00s", formatHms(-12.0))
    }

    @Test
    fun signedAngles() {
        assertEquals("+41° 16′ 09″", formatDms(41.269167))
        assertEquals("−05° 23′ 28″", formatDms(-5.391111))
        // Too small to show a sign.
        assertEquals("+00° 00′ 00″", formatDms(-0.0001))
        assertEquals("+90° 00′ 00″", formatDms(89.99999))
    }

    @Test
    fun altitudeAndAzimuth() {
        assertEquals("52° 10′", formatDm(52.1667))
        assertEquals("−3° 05′", formatDm(-3.0833))
        assertEquals("359° 59′", formatDm(359.99))
    }

    @Test
    fun decimalsFollowTheLanguage() {
        assertEquals("12,4", formatDecimal(12.36, 1, comma = true))
        assertEquals("12.4", formatDecimal(12.36, 1, comma = false))
        assertEquals("−0,05", formatDecimal(-0.049, 2, comma = true))
        assertEquals("3", formatDecimal(2.6, 0, comma = true))
        assertEquals("0,0", formatDecimal(-0.01, 1, comma = true))
    }

    @Test
    fun typedCoordinates() {
        assertEquals(2.5, parseSexagesimal("2.5"))
        assertEquals(-41.27, parseSexagesimal("-41,27"))
        assertEquals(0.712222, parseSexagesimal("0 42 44")!!, 1e-6)
        assertEquals(0.712222, parseSexagesimal("00h 42m 44s")!!, 1e-6)
        assertEquals(41.269167, parseSexagesimal("+41° 16′ 09″")!!, 1e-6)
        assertEquals(-5.391111, parseSexagesimal("−05 23 28")!!, 1e-6)
        assertEquals(null, parseSexagesimal("north"))
        assertEquals(null, parseSexagesimal("10 75 00"))
        assertEquals(null, parseSexagesimal(""))
    }
}
