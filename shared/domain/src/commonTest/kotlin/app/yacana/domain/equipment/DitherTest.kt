// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.domain.profile.DitherOptions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    /** [count] dithers by GoTo one after the other, each where it went: the offsets in thirds of a step. */
    private fun rounds(count: Int, raOnly: Boolean = false): List<Pair<Int, Int>> = List(count) {
        val point = assertNotNull(dither.nextPoint(emptySet(), raOnly))
        dither.visit(point)
        point.offset(3.0, raOnly).let { round(it.eastPixels).toInt() to round(it.northPixels).toInt() }
    }

    @Test
    fun aDitherByGoToGoesOverAGridOfGridsOf81PlacesThenStartsAgain() {
        val places = listOf(0 to 0) + rounds(80)

        // A 3 × 3 grid of steps of 3 thirds, shifted a third after each round: every place of a 9 × 9 square once.
        assertEquals((-4..4).flatMap { east -> (-4..4).map { north -> east to north } }.toSet(), places.toSet())
        assertEquals(81, places.distinct().size)
        // Each one moves a whole step at least in every axis that changes, past a Seestar's dead zone.
        assertTrue(
            places.zipWithNext().all { (a, b) -> listOf(b.first - a.first, b.second - a.second).all { it == 0 || abs(it) >= 3 } },
            places.toString(),
        )
        // Then the first round again: the grid unshifted.
        assertTrue(rounds(9).all { (east, north) -> east % 3 == 0 && north % 3 == 0 })
    }

    @Test
    fun aGridPointThatWasTriedIsNotTriedAgainAndOnlyTheRowInRightAscensionAlone() {
        val tried = Dither.Cell(-1, -1)
        assertTrue(List(50) { dither.nextPoint(setOf(tried), raOnly = false) }.none { it?.cell == tried })
        // In right ascension alone the field keeps its row, and its rounds shift it east only.
        assertTrue(rounds(30, raOnly = true).all { (_, north) -> north == 0 })
        dither.reset()
        val everyCell = (-1..1).flatMap { east -> (-1..1).map { north -> Dither.Cell(east, north) } }.toSet()
        assertEquals(null, dither.nextPoint(everyCell, raOnly = false))
    }

    @Test
    fun aDitherOfNoPixelsLeavesTheFieldWhereItIs() {
        val none = dither.offset(DitherOffset(0.0, 0.0), DitherOptions(pixels = 0.0, minimumPixels = 3.0))

        assertEquals(0.0, hypot(none.eastPixels, none.northPixels), "the minimum is capped at the dither's size")
    }
}
