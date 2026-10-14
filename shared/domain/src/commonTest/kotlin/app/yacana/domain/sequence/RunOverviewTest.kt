// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.profile.DitherOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RunOverviewTest {
    private val now = Instant.parse("2026-10-07T23:00:00Z")

    /** Ten one-minute frames, each 63 s with its download: 630 s, and 60 s to centre each field. */
    private val lights = Exposures(count = 10, exposureSeconds = 60.0, filter = "IR")

    private fun field(name: String) = Block(name, listOf(GoToTarget(centre = true), lights), SkyTarget(name, 0.71, 41.3))

    /** As tonight's: the start, a mosaic of two, another target, and the end, which parks. */
    private val night =
        Sequence(
            "Tonight",
            Block(
                "Tonight",
                listOf(
                    Block("Start", listOf(Unpark)),
                    Block("M 31", listOf(field("M 31 Panel 1"), field("M 31 Panel 2")), SkyTarget("M 31", 0.71, 41.3)),
                    field("M 33"),
                    Block("End", listOf(Park)),
                ),
            ),
        )

    @Test
    fun aMosaicsSecondPanelShowsItsFrameTheNextTargetAndThePark() {
        val run = SequenceRun(night, current = listOf(1, 1, 1), done = mapOf(listOf(1, 0, 1) to 10, listOf(1, 1, 1) to 4))

        val overview = run.overview(now, DitherOptions())

        assertEquals("M 31", overview.name)
        assertEquals("M 31 Panel 2", overview.target?.name)
        assertEquals(2, overview.panel)
        assertEquals(2, overview.panels)
        assertEquals(lights, overview.exposures)
        assertEquals(5, overview.frame)
        // Six frames left here, then M 33: 378 s, then 690 s; of 2070 s in all.
        assertEquals("M 33" to now + 378.seconds, overview.next)
        assertEquals(now + 1068.seconds, overview.park)
        assertEquals(now + 1068.seconds, overview.end)
        assertEquals(1 - 1068.0 / 2070, overview.progress, 1e-9)
    }

    @Test
    fun aSingleFieldHasNoPanelAndTheLastTargetNoNext() {
        val run = SequenceRun(night, current = listOf(2, 0))

        val overview = run.overview(now, DitherOptions())

        assertEquals("M 33", overview.name)
        assertEquals(null, overview.panel)
        assertEquals(1, overview.panels)
        assertEquals(null, overview.next)
        assertEquals(now + 690.seconds, overview.end)
    }

    @Test
    fun aBlockRepeatedThreeTimesTakesThreeRoundsAndCountsThoseLeft() {
        val m33 =
            Block("M 33", listOf(GoToTarget(centre = true), lights), SkyTarget("M 33", 1.56, 30.7), conditions = listOf(Condition.Loop(3)))
        val sequence = Sequence("Tonight", Block("Tonight", listOf(Block("Start", listOf(Unpark)), m33, Block("End", listOf(Park)))))
        // In the second round, at its frames, four of them taken.
        val run = SequenceRun(sequence, current = listOf(1, 1), done = mapOf(listOf(1, 1) to 4), rounds = mapOf(listOf(1) to 1))

        val overview = run.overview(now, DitherOptions())

        // Six frames of this round, 378 s, and one more round of 690 s; of 2070 s in all.
        assertEquals(now + 1068.seconds, overview.end)
        assertEquals(1 - 1068.0 / 2070, overview.progress, 1e-9)
    }

    @Test
    fun targetsThatRepeatAsAListStillNameTheTargetAndTheNext() {
        val targets = Block(TARGETS, listOf(field("M 31"), field("M 33")), conditions = listOf(Condition.Loop(2)))
        val sequence = Sequence("Tonight", Block("Tonight", listOf(Block("Start", listOf(Unpark)), targets, Block("End", listOf(Park)))))
        val run = SequenceRun(sequence, current = listOf(1, 0, 1))

        val overview = run.overview(now, DitherOptions())

        assertEquals("M 31", overview.name)
        assertEquals(null, overview.panel)
        assertEquals("M 33", overview.next?.first)
    }
}
