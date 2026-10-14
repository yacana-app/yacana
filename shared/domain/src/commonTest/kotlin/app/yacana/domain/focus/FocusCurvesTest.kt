// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// The cases come from N.I.N.A.'s tests of its autofocus fittings (HyperbolicFittingTest,
// QuadraticFittingTest, TrendlineFittingTest), Copyright © Stefan Berg and the N.I.N.A.
// contributors, MPL-2.0.

package app.yacana.domain.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusCurvesTest {
    private val tolerance = 1e-12

    private fun points(vararg xy: Pair<Number, Number>) = xy.map { (x, y) -> FocusPoint(x.toDouble(), y.toDouble(), 1.0) }

    /** A perfect V with one lowest point: (x − 5)² + 2. */
    private val v = points(1 to 18, 2 to 11, 3 to 6, 4 to 3, 5 to 2, 6 to 3, 7 to 6, 8 to 11, 9 to 18)

    @Test
    fun theHyperbolaOfAPerfectV() {
        val fit = HyperbolicFitting(v)

        assertEquals(5.0, fit.minimum!!.position, tolerance)
        assertEquals(1.2, fit.minimum!!.hfr, tolerance)
        assertEquals(fit.minimum!!.hfr, fit.hfrAt(fit.minimum!!.position))
    }

    @Test
    fun theHyperbolaGivesUpOnBadPoints() {
        listOf(
            points(1000 to 18, 1100 to 0, 1200 to 0),
            points(1000 to 18, 1000 to 18, 1000 to 18, 1100 to 0, 1200 to 0),
            points(900 to 18, 1000 to 18, 1000 to 18, 1100 to 0, 1200 to 0),
            points(800 to 18, 900 to 0, 1000 to 0, 1000 to 18, 1000 to 18, 1100 to 0, 1200 to 0),
        ).forEach {
            val fit = HyperbolicFitting(it)
            assertNull(fit.minimum)
            assertNull(fit.hfrAt(1000.0))
        }
    }

    @Test
    fun theParabolaOfAPerfectV() {
        val fit = QuadraticFitting(v)

        assertEquals(5.0, fit.minimum.position, tolerance)
        assertEquals(2.0, fit.minimum.hfr, tolerance)
        assertEquals(1.0, fit.rSquared, tolerance)
    }

    @Test
    fun theTrendLinesMeetAtTheTip() {
        val left = points(1 to 10, 2 to 8, 3 to 6, 4 to 4)
        val right = points(9 to 10, 8 to 8, 7 to 6, 6 to 4)

        val fit = TrendlineFitting(points(5 to 2) + left + right)

        assertEquals(5.0, fit.intersection.position, tolerance)
        assertEquals(2.0, fit.intersection.hfr, tolerance)
        assertEquals(left, fit.left.points)
        assertEquals(right, fit.right.points)
    }

    @Test
    fun aFlatTipLeavesItsPointsOutOfTheLines() {
        val left = points(1 to 10, 2 to 8, 3 to 6, 4 to 4)
        val right = points(11 to 10, 10 to 8, 9 to 6, 8 to 4)

        val fit = TrendlineFitting(points(5 to 2.1, 6 to 2, 7 to 2.1) + left + right)

        assertEquals(6.0, fit.intersection.position, tolerance)
        assertEquals(0.0, fit.intersection.hfr, tolerance)
        assertEquals(left, fit.left.points)
        assertEquals(right, fit.right.points)
    }

    @Test
    fun theCombinationsAverageTheLinesAndTheCurve() {
        val trend = TrendlineFitting(v).intersection
        val hyperbola = HyperbolicFitting(v).minimum!!

        val combined = finalFocus(v, FocusFitting.TrendHyperbolic)!!

        assertEquals((trend.hfr + hyperbola.hfr) / 2, combined.hfr, tolerance)
        assertEquals(5.0, combined.position)
    }
}
