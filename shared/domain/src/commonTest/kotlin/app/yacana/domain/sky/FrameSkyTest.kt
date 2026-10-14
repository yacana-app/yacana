// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameSkyTest {
    // ASTAP's CD matrix for a Seestar S50 frame of M 31, 2160 × 3840, written top-down.
    private val cd = doubleArrayOf(-6.2374e-4, -3.4615e-5, 3.4753e-5, -6.2346e-4)
    private val centre = SkyPosition(10.737175 / 15, 41.370115)
    private val width = 2160
    private val height = 3840

    private fun solution(matrix: DoubleArray): Solution {
        val (a, b) = matrix[0] to matrix[1]
        val (c, d) = matrix[2] to matrix[3]
        return Solution(
            centre,
            rotationDeg = 0.0,
            scaleArcsec = sqrt(kotlin.math.abs(a * d - b * c)) * 3600,
            yAxisDeg = (atan2(b, d) * 180 / PI).mod(360.0),
            mirrored = a * d - b * c < 0,
        )
    }

    /** Where the CD matrix puts frame pixel ([x], [y]), as the FITS standard's gnomonic projection does. */
    private fun wcs(matrix: DoubleArray, x: Double, y: Double): SkyPosition {
        val dx = x - width / 2.0
        val dy = y - height / 2.0
        val xi = radians(matrix[0] * dx + matrix[1] * dy)
        val eta = radians(matrix[2] * dx + matrix[3] * dy)
        val ra0 = radians(centre.raHours * 15)
        val dec0 = radians(centre.decDeg)
        val rho = hypot(xi, eta)
        val c = kotlin.math.atan(rho)
        val dec = asin(cos(c) * sin(dec0) + eta * sin(c) * cos(dec0) / rho)
        val ra = ra0 + atan2(xi * sin(c), rho * cos(dec0) * cos(c) - eta * sin(dec0) * sin(c))
        return SkyPosition((ra * 180 / PI / 15).mod(24.0), dec * 180 / PI)
    }

    private fun radians(deg: Double) = deg * PI / 180

    private val corners = listOf(0.0 to 0.0, 2160.0 to 0.0, 0.0 to 3840.0, 2160.0 to 3840.0, 500.0 to 3000.0)

    @Test
    fun eachPixelIsWhereTheSolutionPutsIt() {
        val sky = FrameSky.of(solution(cd), width, height)!!
        assertTrue(!sky.mirrored)
        // Up the screen is half a turn from the y axis: 3.2° east of north.
        assertEquals(3.18, sky.rollDeg, 0.01)
        corners.forEach { (x, y) ->
            // Within a pixel and a half: the solution's scale is a little different across and down.
            assertEquals(0.0, SkyView.separation(wcs(cd, x, y), sky.at(x, y)) * 3600, 3.0, "pixel $x, $y")
        }
    }

    @Test
    fun aMirroredFrameToo() {
        // The same frame flipped left to right: its first column is the other one.
        val flipped = doubleArrayOf(-cd[0], cd[1], -cd[2], cd[3])
        val sky = FrameSky.of(solution(flipped), width, height)!!
        assertTrue(sky.mirrored)
        corners.forEach { (x, y) ->
            assertEquals(0.0, SkyView.separation(wcs(flipped, x, y), sky.at(x, y)) * 3600, 3.0, "pixel $x, $y")
        }
    }

    @Test
    fun theViewShowsTheFrameAsTheViewerDoes() {
        val sky = FrameSky.of(solution(cd), width, height)!!
        // Zoomed in, two screen pixels per frame pixel, around a point off the centre.
        val view = sky.view(700.0, 2500.0, 2.0, 1080.0, 1500.0)
        listOf(700.0 to 2500.0, 500.0 to 2200.0, 900.0 to 2800.0).forEach { (x, y) ->
            val on = view.project(sky.at(x, y))!!
            assertEquals((x - 700) * 2 + 540, on.x, 0.5)
            assertEquals((y - 2500) * 2 + 750, on.y, 0.5)
        }
    }

    @Test
    fun withoutTheYAxisThereIsNone() {
        assertNull(FrameSky.of(Solution(centre, 0.0, 2.25), width, height))
    }
}
