// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.profile.DeviceRole
import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Equipment · mount" as it is touched: coordinates typed to go to and to sync, going and centring, and its menu. */
class MountTest : FlowTest() {
    private fun mount() = show(Section.Equipment, Screen.Device(DeviceRole.Mount), connected = true)

    @Test
    fun theMountGoesToCoordinatesTypedOnTheKeypad() {
        mount()

        tap("Go to…")
        // 05h 35m 17s, then +22° 00′ 52″: the right ascension's sixth digit moves on to the declination.
        listOf("0", "5", "3", "5", "1", "7", "2", "2", "0", "0", "5", "2").forEach { tap(it) }
        tap("Go to")

        assertTrue(sent("telescope/0/slewtocoordinatesasync"))
        val slew = synchronized(seestar.puts) { seestar.puts.last { it.first == "telescope/0/slewtocoordinatesasync" }.second }
        assertEquals(5 + 35 / 60.0 + 17 / 3600.0, slew.getValue("RightAscension").toDouble(), 1e-6)
        assertEquals(22 + 52 / 3600.0, slew.getValue("Declination").toDouble(), 1e-6)
    }

    @Test
    fun theMountSyncsWhereItShows() {
        mount()

        tap("Sync")
        // The boxes start at where the mount points.
        tap("Sync")

        assertTrue(sent("telescope/0/synctocoordinates"))
    }

    @Test
    fun theMountGoesAndCentres() {
        mount()

        tap("Go to…")
        tap("Go and centre")

        assertTrue(sent("telescope/0/slewtocoordinatesasync"))
        assertTrue(sent("camera/0/startexposure"))
    }

    @Test
    fun aParkedMountUnparksFromTheParkButtonsPlace() {
        seestar.set("telescope/0/atpark", "true")
        mount()

        assertTrue(appears("Unpark"))
        tap("Unpark")

        assertTrue(sent("telescope/0/unpark"))
    }

    @Test
    fun theMenuSetsTheTrackingRateAndFindsHome() {
        mount()

        tapIcon("More options")
        tap("Lunar")
        assertTrue(sent("telescope/0/trackingrate", "TrackingRate", "1"))

        tap("Find home")
        assertTrue(sent("telescope/0/findhome"))
    }
}
