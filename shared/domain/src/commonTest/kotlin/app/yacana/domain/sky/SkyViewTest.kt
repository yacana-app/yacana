// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.alpaca.AlpacaServer
import app.yacana.alpaca.ServerDescription
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.sky.SkyView.Companion.northAt
import app.yacana.ports.SkyPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sky map's projection: north up, east to the left, and the field across the screen. */
class SkyViewTest {
    private val m31 = SkyPosition(0.7123, 41.27)
    private val view = SkyView(m31, fieldDeg = 8.0, width = 1080.0, height = 1600.0)

    @Test
    fun theCentreIsInTheMiddleAndComesBack() {
        val middle = view.project(m31)!!
        assertEquals(540.0, middle.x, 1e-9)
        assertEquals(800.0, middle.y, 1e-9)
        listOf(ScreenPoint(10.0, 20.0), ScreenPoint(1000.0, 1500.0), ScreenPoint(540.0, 0.0)).forEach { point ->
            val back = view.project(view.unproject(point))!!
            assertEquals(point.x, back.x, 1e-6)
            assertEquals(point.y, back.y, 1e-6)
        }
    }

    @Test
    fun turnedItStillComesBack() {
        val turned = view.copy(rollDeg = 40.0)
        listOf(ScreenPoint(10.0, 20.0), ScreenPoint(1000.0, 1500.0), ScreenPoint(540.0, 0.0)).forEach { point ->
            val back = turned.project(turned.unproject(point))!!
            assertEquals(point.x, back.x, 1e-6)
            assertEquals(point.y, back.y, 1e-6)
        }
        // North, turned 40° clockwise: up and to the right.
        val north = turned.project(SkyPosition(m31.raHours, m31.decDeg + 1))!!
        assertTrue(north.x > 540 && north.y < 800)
        assertEquals(40.0, kotlin.math.atan2(north.x - 540, 800 - north.y) * 180 / kotlin.math.PI, 0.1)
    }

    @Test
    fun theZenithUpAsTheSkyIsSeen() {
        // On the meridian south of the zenith, north is up; north of the zenith, below the pole, it is down.
        assertEquals(0.0, SkyView.zenithUp(SkyPosition(6.0, 20.0), 40.0, 90.0), 1e-9)
        assertEquals(180.0, kotlin.math.abs(SkyView.zenithUp(SkyPosition(6.0, 60.0), 40.0, 90.0)), 1e-9)
        // Setting in the west, north leans to the right; rising in the east, to the left.
        assertTrue(SkyView.zenithUp(SkyPosition(6.0, 20.0), 40.0, 135.0) > 0)
        assertTrue(SkyView.zenithUp(SkyPosition(6.0, 20.0), 40.0, 45.0) < 0)
    }

    @Test
    fun northUpAndEastToTheLeft() {
        val north = view.project(SkyPosition(m31.raHours, m31.decDeg + 1))!!
        val east = view.project(SkyPosition(m31.raHours + 0.1, m31.decDeg))!!

        assertTrue(north.y < 800, "north is up")
        assertEquals(540.0, north.x, 1e-6)
        assertTrue(east.x < 540, "east is to the left")
    }

    @Test
    fun theFieldSpansTheWidth() {
        // Half the field to either side of the centre, along the equator, reaches the edges.
        val equator = SkyView(SkyPosition(6.0, 0.0), fieldDeg = 60.0, width = 1000.0, height = 1000.0)
        assertEquals(0.0, equator.project(SkyPosition(6.0 + 2, 0.0))!!.x, 1e-6)
        assertEquals(1000.0, equator.project(SkyPosition(6.0 - 2, 0.0))!!.x, 1e-6)
        assertNull(equator.project(SkyPosition(18.0, 0.0)), "the opposite side of the sky is not drawn")
    }

    @Test
    fun draggingMovesTheSkyUnderTheFinger() {
        val before = view.unproject(ScreenPoint(640.0, 750.0))
        val moved = view.dragged(-100.0, 50.0)

        // What was 100 px right of the centre and 50 above is now there; the rest of the sky turns a little with it.
        assertEquals(0.0, SkyView.separation(before, moved.center), 1e-6)
        val elsewhere = view.unproject(ScreenPoint(700.0, 900.0))
        val now = moved.project(elsewhere)!!
        assertEquals(600.0, now.x, 3.0)
        assertEquals(950.0, now.y, 3.0)
    }

    @Test
    fun northAndScaleWhereAPhotoFalls() {
        val (angle, perDegree) = assertNotNull(view.northAt(m31))
        assertEquals(0.0, angle, 1e-6)
        assertEquals(view.pixelsPerDegree, perDegree, view.pixelsPerDegree * 1e-3)
        // To the left of the centre, north leans to the right: the meridians meet at the pole, above.
        val (side, _) = view.northAt(SkyPosition(m31.raHours + 0.2, m31.decDeg))!!
        assertTrue(side > 0, "$side")
        assertNotNull(view.northAt(SkyPosition(0.0, 89.9)), "near the pole too")
    }

    @Test
    fun theSeestarsFieldFromItsProfile() {
        val seestar = ProfilePresets.seestarS50Pro(
            AlpacaServer("http://seestar:32323", ServerDescription("Seestar", "ZWO", "1", ""), emptyList()),
        )
        val (width, height) = assertNotNull(seestar.optics.fieldDeg())

        assertEquals(1.35, width, 0.01)
        assertEquals(2.40, height, 0.01)
    }
}
