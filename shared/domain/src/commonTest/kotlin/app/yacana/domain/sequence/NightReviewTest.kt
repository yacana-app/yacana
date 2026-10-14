// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import kotlin.test.Test
import kotlin.test.assertEquals

class NightReviewTest {
    private val seestar = Kit(cooler = false, filters = listOf("Dark", "IR", "LP"))
    private val m31 = Target("M 31", 0.712, 41.27, 0.0, listOf(TargetPanel(1, 0.712, 41.27, 0.0)), newTargetExposures(listOf("IR")))
    private val lights = Exposures(count = 10, exposureSeconds = 60.0, filter = "IR")

    private fun night(start: List<SequenceItem>, targets: List<SequenceItem>, end: List<SequenceItem> = listOf(Park)) =
        Sequence("Night", Block("Night", listOf(Block(START, start)) + targets + Block(END, end)))

    private fun field(vararg items: SequenceItem) = Block("M 31", items.toList(), SkyTarget("M 31", 0.712, 41.27))

    @Test
    fun tonightsPlanAsTheSimpleEditorMakesItHasNothingToSay() {
        assertEquals(emptyList(), tonight(listOf(m31), null, focuser = true).review(seestar))
    }

    @Test
    fun aGoToInTheStartHasNoTargetToGoTo() {
        val warnings = night(listOf(Unpark, GoToTarget()), listOf(field(GoToTarget(), lights))).review(seestar)

        assertEquals(listOf(Warning(Spot(listOf(0, 1)), Problem.NeedsTarget)), warnings)
    }

    @Test
    fun lightsWithTheMountParkedStillOrPointingElsewhereAreWarnedAbout() {
        val parked = night(emptyList(), listOf(field(lights))).review(seestar)
        val notAimed = night(listOf(Unpark), listOf(field(lights))).review(seestar)
        val still = night(listOf(Unpark), listOf(field(GoToTarget(), StopTracking, lights))).review(seestar)

        assertEquals(listOf(Problem.ParkedLights("M 31")), parked.map { it.problem })
        assertEquals(listOf(Problem.NotAimed("M 31")), notAimed.map { it.problem })
        assertEquals(listOf(Warning(Spot(listOf(1, 2)), Problem.NotTracking)), still)
    }

    @Test
    fun whatTheEquipmentCantDoIsWarnedAboutOnlyWhenItIsKnown() {
        val sequence = night(listOf(CoolCamera(-10.0), Unpark), listOf(field(GoToTarget(), lights.copy(filter = "Ha"))))

        assertEquals(listOf(Problem.NoCooler, Problem.NoFilter("Ha")), sequence.review(seestar).map { it.problem })
        assertEquals(emptyList(), sequence.review(Kit()))
    }

    @Test
    fun darksAfterTheCameraWarmsDontMatchTheLights() {
        val cooled = Kit(cooler = true)
        val sequence =
            night(listOf(CoolCamera(-10.0), Unpark), listOf(field(GoToTarget(), lights)), listOf(WarmCamera, lights.darks(10), Park))

        assertEquals(listOf(Warning(Spot(listOf(2, 1)), Problem.WarmDarks)), sequence.review(cooled))
    }

    @Test
    fun twoThingsAtOnceWithTheCameraAreWarnedAboutOnTheirBlock() {
        val together = Block("Together", listOf(RunAutofocus, lights), parallel = true)
        val sequence = night(listOf(Unpark), listOf(field(GoToTarget(), together)))

        assertEquals(
            listOf(Warning(Spot(listOf(1, 1)), Problem.Together(listOf(1, 1, 0), listOf(1, 1, 1), Use.Camera))),
            sequence.review(seestar),
        )
    }

    @Test
    fun aRecentreOrAnAltitudeOutsideATargetPointsAtNothing() {
        val box = Block("Box", triggers = listOf(Trigger.RecentreAfterDrift()), conditions = listOf(Condition.WhileAbove(30.0)))

        val warnings = night(listOf(Unpark, box), emptyList()).review(seestar)

        assertEquals(listOf(Spot(listOf(0, 1), trigger = 0), Spot(listOf(0, 1), condition = 0)), warnings.map { it.spot })
    }
}
