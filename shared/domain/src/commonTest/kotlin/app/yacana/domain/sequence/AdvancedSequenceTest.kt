// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.imaging.FrameType
import app.yacana.domain.profile.Location
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The advanced sequencer: conditions, parallel blocks and the instructions N.I.N.A. has besides the simple editor's. */
class AdvancedSequenceTest : RunnerFixture() {
    /** Madrid, where M 31 is some 75° high at the tests' half past ten in the evening, UTC. */
    private val madrid = Location(40.42, -3.70, 650.0)

    private fun night(vararg items: SequenceItem) = Sequence("Night", Block("Night", items.toList()))

    private fun notes() = events.filter { it.first == "sequence_note" }.map { it.second["text"] }

    @Test
    fun aBlockRepeatedThreeTimesTakesItsFramesEachRound() = runTest {
        val m31 = Block("M 31", listOf(Exposures(count = 2, exposureSeconds = 10.0)), target = m31, conditions = listOf(Condition.Loop(3)))

        val run = runner().runToTheEnd(night(m31))

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertEquals(6, saved(FrameType.Light).size)
        assertEquals(2, events.count { it.first == "sequence_round" })
        assertEquals(emptyMap(), run.rounds)
    }

    @Test
    fun aTimeConditionIsAskedAfterEachItemAndEndsTheBlockThere() = runTest {
        // From 22:30 UTC, ten minutes at a time: the third wait ends at 23:00, before its note.
        val block = Block("Until 23:00", listOf(WaitFor(10.0), Note("tick")), conditions = listOf(Condition.UntilTime(23 * 60)))

        val run = runner(clock = movingClock()).runToTheEnd(night(block, Note("after")))

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertEquals(listOf("tick", "tick", "after"), notes())
        assertTrue(events.any { it.first == "sequence_block_ended" })
    }

    @Test
    fun aBlockIsSkippedWhenItsConditionDoesNotHoldAsItStarts() = runTest {
        val high = Block("M 31", listOf(Note("high")), target = m31, conditions = listOf(Condition.WhileAbove(10.0), Condition.Loop(2)))
        val zenith = Block("M 31 at the zenith", listOf(Note("zenith")), target = m31, conditions = listOf(Condition.WhileAbove(89.0)))
        val past = Block("Until 22:00", listOf(Note("past")), conditions = listOf(Condition.UntilTime(22 * 60)))

        val run = runner(location = madrid).runToTheEnd(night(high, zenith, past))

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertEquals(listOf("high", "high"), notes())
        assertEquals(2, events.count { it.first == "sequence_block_skipped" })
    }

    @Test
    fun anAltitudeWithoutTheProfilesLocationStopsTheSequenceWithTheReason() = runTest {
        val m31 = Block("M 31", listOf(WaitUntilAbove(30.0)), target = m31)

        val run = runner().runToTheEnd(night(m31))

        assertEquals(RunStatus.Failed, run.status)
        assertEquals("The profile has no location", run.error)
    }

    @Test
    fun aParallelBlockRunsItsItemsAtOnceAndEndsWithTheLast() = runTest {
        val together = Block("Together", listOf(WaitFor(5.0), Unpark), parallel = true)

        val run = runner().runToTheEnd(night(together, Note("after")))

        assertEquals(RunStatus.Finished, run.status, run.error)
        val order = events.filter {
            it.first.startsWith("sequence_item") || it.first == "sequence_note"
        }.map { "${it.first} ${it.second["item"] ?: it.second["text"]}" }
        assertEquals(
            listOf(
                "sequence_item WaitFor(minutes=5.0)",
                "sequence_item Unpark",
                "sequence_item_done Unpark",
                "sequence_item_done WaitFor(minutes=5.0)",
                "sequence_item Note(text=after)",
                "sequence_note after",
                "sequence_item_done Note(text=after)",
            ),
            order,
        )
    }

    @Test
    fun theFilterTheTrackingTheWaitAndTheNoteDoWhatTheySay() = runTest {
        val run = runner().runToTheEnd(night(SwitchFilter("LP"), StopTracking, WaitUntilTime(23 * 60), Note("done")))

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertTrue(seestar.puts.any { it.first == "filterwheel/0/position" && it.second["Position"] == "2" })
        assertTrue(seestar.puts.any { it.first == "telescope/0/tracking" && it.second["Tracking"] == "false" })
        assertEquals(listOf("done"), notes())
    }

    @Test
    fun aFilterChangeWaitsForASlowWheelToStopBeforeTheNext() = runTest {
        // As OmniSim's wheel: no position for half a minute while it turns, and no other taken meanwhile.
        val before = seestar.onPut
        var turning = false
        seestar.onPut = { member, form ->
            before(member, form)
            if (member == "filterwheel/0/position") {
                check(!turning) { "The FilterWheel is already moving" }
                turning = true
                seestar.values[member] = "-1"
                backgroundScope.launch {
                    delay(30_000)
                    seestar.values[member] = form.getValue("Position")
                    turning = false
                }
            }
        }

        val run = runner().runToTheEnd(night(SwitchFilter("LP"), SwitchFilter("IR"), SwitchFilter("LP")))

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertEquals(listOf("2", "1", "2"), seestar.puts.filter { it.first == "filterwheel/0/position" }.map { it.second["Position"] })
    }

    @Test
    fun aFocuserMoveWithoutAFocuserStopsTheSequenceWithTheReason() = runTest {
        val run = runner().runToTheEnd(night(MoveFocuser(1500)))

        assertEquals(RunStatus.Failed, run.status)
        assertEquals("The profile has no focuser connected", run.error)
    }

    @Test
    fun aRunResumedInItsSecondRoundGoesOnFromThereAndFinishes() = runTest {
        val file = Path(directory, "run.json")
        val m31 = Block("M 31", listOf(Exposures(count = 2, exposureSeconds = 10.0)), target = m31, conditions = listOf(Condition.Loop(2)))
        val before = runner(file)
        before.start(night(m31))
        before.state.first { it?.rounds?.get(listOf(0)) == 1 && it.done[listOf(0, 0)] == 1 }
        before.pause("closed")

        val after = runner(file)
        assertTrue(after.restore())
        after.resume()
        val run = after.state.first { it?.status != RunStatus.Running }!!

        assertEquals(RunStatus.Finished, run.status, run.error)
        // Two in the first round, one before the pause and the last after it.
        assertEquals(4, saved(FrameType.Light).size)
    }

    @Test
    fun whatIsAddedWhileItRunsIsRunToo() = runTest {
        val lights = Exposures(count = 2, exposureSeconds = 10.0)
        val runner = runner()
        runner.start(night(Block("M 31", listOf(lights), target = m31)))
        runner.state.first { it?.done?.get(listOf(0, 0)) == 1 }

        runner.update(night(Block("M 31", listOf(lights, Exposures(count = 1, exposureSeconds = 5.0)), target = m31)))
        val run = runner.state.first { it?.status != RunStatus.Running }!!

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertEquals(3, saved(FrameType.Light).size)
    }

    @Test
    fun aRecentreTriggerCentresAgainWhenTheLastLightDriftedTooFar() = runTest {
        // The solver finds every frame at M 31's centre, 8′ south of this.
        val north = SkyTarget("M 31", 0.712, 41.40)
        val m31 =
            Block(
                "M 31",
                listOf(Exposures(count = 2, exposureSeconds = 10.0)),
                target = north,
                triggers = listOf(Trigger.RecentreAfterDrift(1.0)),
            )

        val run = runner().runToTheEnd(night(m31))

        assertEquals(RunStatus.Finished, run.status, run.error)
        // Before the second light: the first one is solved, and is too far.
        assertEquals(1, events.count { it.first == "sequence_recentre" })
    }

    /** How many times a light block recentres when the solver finds its frames [arcsec] south of its target, with a limit of 0.25′. */
    private suspend fun TestScope.recentresAfter(arcsec: Double): Int {
        val target = SkyTarget("M 31", 0.712, 41.27 + arcsec / 3600)
        val m31 =
            Block(
                "M 31",
                listOf(Exposures(count = 2, exposureSeconds = 10.0)),
                target = target,
                triggers = listOf(Trigger.RecentreAfterDrift(0.25)),
            )

        val run = runner().runToTheEnd(night(m31))

        assertEquals(RunStatus.Finished, run.status, run.error)
        return events.count { it.first == "sequence_recentre" }
    }

    @Test
    fun aDriftNoFurtherThanADitherMovesTheFieldIsNoReasonToRecentre() = runTest {
        // More than 0.25′, but under 20 pixels of the camera's 2″.
        assertEquals(0, recentresAfter(30.0))
    }

    @Test
    fun aDriftFurtherThanADitherMovesTheFieldRecentres() = runTest {
        assertEquals(1, recentresAfter(50.0))
    }

    @Test
    fun theLimitIsNeverUnderTwentyPixelsOfTheCamera() {
        // The Seestar's main camera, and its wide angle, whose 20 pixels are 18.5′.
        assertEquals(1.0, Trigger.RecentreAfterDrift(1.0).limitArcmin(2.248))
        assertEquals(18.5, Trigger.RecentreAfterDrift(1.0).limitArcmin(55.5), 1e-9)
        assertEquals(5.0, Trigger.RecentreAfterDrift(5.0).limitArcmin(null))
    }

    @Test
    fun anAdvancedSequenceComesBackFromItsJsonAsItWas() {
        val sequence =
            night(
                Block(
                    "M 31",
                    listOf(WaitUntilAbove(30.0), GoToTarget(centre = false), SwitchFilter("LP"), MoveFocuser(1500)),
                    target = m31,
                    triggers = listOf(Trigger.RecentreAfterDrift(2.0)),
                    conditions = listOf(Condition.Loop(3), Condition.UntilTime(120), Condition.WhileAbove(30.0)),
                ),
                Block("Together", listOf(WaitFor(5.0), WaitUntilTime(1290), StopTracking, Note("hola")), parallel = true),
            )

        assertEquals(sequence, Sequence.fromJson(sequence.toJson()))
    }
}
