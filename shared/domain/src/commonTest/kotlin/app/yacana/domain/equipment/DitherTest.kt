// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.domain.profile.DitherOptions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** N.I.N.A.'s dither offsets (its DitherOffsetSelector): around where the field began, never wandering off. */
class DitherTest {
    private val dither = Dither(Random(42))

    /** [count] offsets one after the other, each drawn from the last. */
    private fun walk(options: DitherOptions, count: Int = 2000): List<DitherOffset> =
        generateSequence(DitherOffset(0.0, 0.0)) { dither.offset(it, options) }.drop(1).take(count).toList()

    @Test
    fun theOffsetsStayAroundWhereTheFieldBeganWithinThreeDeviations() {
        val offsets = walk(DitherOptions(pixels = 5.0))

        assertTrue(offsets.all { hypot(it.eastPixels, it.northPixels) <= 15.0 + 1e-9 }, "within 3 × 5 pixels")
        // A normal distance at a uniform angle: about 5 pixels of deviation on each axis together, around 0.
        val east = offsets.map { it.eastPixels }
        assertTrue(abs(east.average()) < 0.5, "centred: ${east.average()}")
        val spread = sqrt(offsets.map { it.eastPixels * it.eastPixels + it.northPixels * it.northPixels }.average())
        assertTrue(spread in 4.0..6.0, "spread $spread")
    }

    @Test
    fun eachDitherMovesAtLeastTheMinimum() {
        val offsets = walk(DitherOptions(pixels = 5.0, minimumPixels = 3.0))

        assertTrue(offsets.zipWithNext().all { (a, b) -> hypot(b.eastPixels - a.eastPixels, b.northPixels - a.northPixels) >= 3.0 - 1e-9 })
    }

    @Test
    fun inRightAscensionAloneTheDeclinationStaysAsItWas() {
        val offsets = walk(DitherOptions(pixels = 5.0, raOnly = true), 200)

        assertTrue(offsets.all { it.northPixels == 0.0 })
        assertTrue(offsets.map { it.eastPixels }.distinct().size > 100, "the right ascension moves")
    }

    @Test
    fun aDitherByGoToGoesToAnotherPointOfTheGridEachTime() {
        val centre = Dither.Cell(0, 0)
        val points = generateSequence(centre) { dither.nextCell(it, emptySet(), raOnly = false) }.take(500).toList()

        assertTrue(points.zipWithNext().all { (a, b) -> a != b }, "each one moves")
        assertEquals(9, points.distinct().size, "all of the 3 × 3 grid")
        // Not those tried already; in its row in right ascension alone; none when all were tried.
        assertEquals(
            Dither.Cell(1, 1),
            dither.nextCell(
                centre,
                (-1..1).flatMap { e -> (-1..0).map { Dither.Cell(e, it) } }.toSet() + Dither.Cell(-1, 1) + Dither.Cell(0, 1),
                false,
            ),
        )
        assertTrue(List(50) { dither.nextCell(Dither.Cell(1, -1), emptySet(), raOnly = true) }.all { it?.north == -1 })
        assertEquals(null, dither.nextCell(centre, points.toSet(), raOnly = false))
    }

    @Test
    fun aDitherOfNoPixelsLeavesTheFieldWhereItIs() {
        val none = dither.offset(DitherOffset(0.0, 0.0), DitherOptions(pixels = 0.0, minimumPixels = 3.0))

        assertEquals(0.0, hypot(none.eastPixels, none.northPixels), "the minimum is capped at the dither's size")
    }
}
