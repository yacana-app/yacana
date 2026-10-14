// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.ui.Section
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Sky · map with the mount" as it is touched: going to the framing, centring it, and a parked telescope. */
class SkyMountTest : FlowTest() {
    private fun map() = show(Section.Sky, connected = true)

    @Test
    fun goOnlySlewsToTheFramingInTheMountsOwnSystem() {
        map()
        assertTrue(appears("Telescope"))

        tap("Go only")

        assertTrue(sent("telescope/0/slewtocoordinatesasync"))
        val slew = synchronized(seestar.puts) { seestar.puts.last { it.first == "telescope/0/slewtocoordinatesasync" }.second }
        // The map opens on M 31, in J2000; the Seestar takes it in its own system, a little on with the years.
        assertEquals(0.7123, slew.getValue("RightAscension").toDouble(), 0.05)
        assertEquals(41.27, slew.getValue("Declination").toDouble(), 0.5)
    }

    @Test
    fun goAndCentreSlewsAndTakesAFrame() {
        map()

        tap("Go and centre")

        assertTrue(sent("telescope/0/slewtocoordinatesasync"))
        assertTrue(sent("camera/0/startexposure"))
    }

    @Test
    fun aParkedTelescopeIsUnparkedFromItsLabel() {
        seestar.set("telescope/0/atpark", "true")
        map()

        tap("Telescope · parked · Unpark")

        assertTrue(sent("telescope/0/unpark"))
    }
}
