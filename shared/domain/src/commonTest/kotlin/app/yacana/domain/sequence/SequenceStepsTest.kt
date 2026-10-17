// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.ports.SkyPosition
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The equipment's instructions of a sequence: the cooler, a switch by its name, and
 * dithering with a mount that cannot pulse guide or a Seestar, whose pulses go astray.
 */
class SequenceStepsTest : RunnerFixture() {
    private fun night(vararg items: SequenceItem) = Sequence("Tonight", Block("Tonight", items.toList()))

    private fun sent(member: String) = seestar.puts.filter { it.first == member }.map { it.second }

    @Test
    fun aCameraWithoutACoolerSkipsCoolingAndWarming() = runTest {
        val run = runner().runToTheEnd(night(CoolCamera(-10.0), WarmCamera))

        assertEquals(RunStatus.Finished, run.status)
        assertTrue(events.any { (event, details) -> event == "sequence_skipped" && details["reason"] == "the camera has no cooler" })
        assertTrue(sent("camera/0/cooleron").isEmpty())
    }

    @Test
    fun aCoolerGoesToItsTemperatureAndOffAgainWhenWarmed() = runTest {
        seestar.values["camera/0/cansetccdtemperature"] = "true"
        seestar.values["camera/0/cooleron"] = "false"

        val run = runner().runToTheEnd(night(CoolCamera(-10.0, minutes = 1.0), WarmCamera))

        assertEquals(RunStatus.Finished, run.status)
        assertEquals(-10.0, sent("camera/0/setccdtemperature").single()["SetCCDTemperature"]?.toDouble())
        assertEquals(listOf("true", "false"), sent("camera/0/cooleron").map { it["CoolerOn"] })
        // The fake camera stays at its 24.7 °C: after the minute, the night goes on and says so.
        assertTrue(events.any { it.first == "sequence_cooling_timeout" })
    }

    @Test
    fun aSwitchIsSetByItsName() = runTest {
        val run = runner().runToTheEnd(night(SetSwitch("dew heater", 1.0)))

        assertEquals(RunStatus.Finished, run.status)
        assertTrue(sent("switch/0/setswitchvalue").isNotEmpty() || sent("switch/0/setswitch").isNotEmpty())
    }

    @Test
    fun aSwitchThatIsNotThereFailsTheNight() = runTest {
        val run = runner().runToTheEnd(night(SetSwitch("Heater 2", 1.0)))

        assertEquals(RunStatus.Failed, run.status)
        assertEquals("There is no switch called Heater 2", run.error)
    }

    @Test
    fun aMountThatCannotPulseGuideDithersWithASlew() = runTest {
        seestar.values["telescope/0/canpulseguide"] = "false"
        val target = Block("M 31", listOf(GoToTarget(centre = false), Exposures(2, 10.0, ditherEvery = 1)), target = m31)

        val run = runner().runToTheEnd(night(target))

        assertEquals(RunStatus.Finished, run.status)
        assertTrue(sent("telescope/0/pulseguide").isEmpty())
        // The GoTo to M 31, then a slew to dither after each frame but the last.
        assertTrue(sent("telescope/0/slewtocoordinatesasync").size >= 2, seestar.puts.map { it.first }.toString())
    }

    /**
     * Makes each slew land 20″ north of where it was sent, as a Seestar's within about 15″, but
     * those numbered in [undone], which leave it where it was.
     */
    private fun landing(undone: Set<Int> = emptySet()) {
        val camera = seestar.onPut
        var slews = 0
        seestar.onPut = { member, form ->
            camera(member, form)
            if (member == "telescope/0/slewtocoordinatesasync" && ++slews !in undone) {
                seestar.values["telescope/0/rightascension"] = form.getValue("RightAscension")
                seestar.values["telescope/0/declination"] = (form.getValue("Declination").toDouble() + 20.0 / 3600).toString()
            }
        }
    }

    private fun slews() = sent("telescope/0/slewtocoordinatesasync").map {
        SkyPosition(it.getValue("RightAscension").toDouble(), it.getValue("Declination").toDouble())
    }

    /** Steps of 27″ east and north of [began], to a thousandth. */
    private fun steps(at: SkyPosition, began: SkyPosition): Pair<Double, Double> {
        val east = (at.raHours - began.raHours) * 15 * 3600 * cos(began.decDeg * PI / 180) / 27
        val north = (at.decDeg - began.decDeg) * 3600 / 27
        return round(east * 1000) / 1000 to round(north * 1000) / 1000
    }

    @Test
    fun aSeestarDithersFromEachLightsSolveToTheTargetPlusAPointOfTheGrid() = runTest {
        landing()
        val target = Block("M 31", listOf(GoToTarget(centre = false), Exposures(6, 10.0, ditherEvery = 1)), target = m31)

        val run = runner().runToTheEnd(night(target))

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertTrue(sent("telescope/0/pulseguide").isEmpty(), "its pulses go astray")
        // Synced on each light's solve before its dither, as the Seestar's own app aims its dithers.
        assertEquals(5, sent("telescope/0/synctocoordinates").size)
        val slews = slews()
        val points = slews.drop(1).map { steps(it, slews.first()) }
        // Whole steps of 27″ around M 31 itself, not around where the slews landed 20″ north: the drift is undone.
        assertEquals(5, points.size)
        assertTrue(points.all { (east, north) -> east in setOf(-1.0, 0.0, 1.0) && north in setOf(-1.0, 0.0, 1.0) }, points.toString())
        assertTrue((listOf(0.0 to 0.0) + points).zipWithNext().all { (a, b) -> a != b }, "each moves: $points")
        val dithers = events.filter { it.first == "sequence_dither" }.map { it.second }
        assertEquals(List(5) { "1" }, dithers.map { it["goTos"].toString() })
        assertTrue(dithers.all { it["aimed"] == true })
    }

    @Test
    fun aLightThatDoesNotSolveLeavesItsDitherBlind() = runTest {
        landing()
        val target = Block("M 31", listOf(GoToTarget(centre = false), Exposures(3, 10.0, ditherEvery = 1)), target = m31)

        val run = runner(solver = CloudedSolver()).runToTheEnd(night(target))

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertTrue(sent("telescope/0/synctocoordinates").isEmpty())
        // Around where the GoTo to M 31 landed, 20″ north, as before.
        val began = slews().first().let { SkyPosition(it.raHours, it.decDeg + 20.0 / 3600) }
        val points = slews().drop(1).map { steps(it, began) }
        assertTrue(points.all { (east, north) -> east in setOf(-1.0, 0.0, 1.0) && north in setOf(-1.0, 0.0, 1.0) }, points.toString())
        assertTrue(events.filter { it.first == "sequence_dither" }.all { it.second["aimed"] == false })
    }

    @Test
    fun aGoToThatLeavesTheSeestarWhereItWasIsTriedAtAnotherPoint() = runTest {
        // The GoTo to M 31 is done, the first dither's not.
        landing(undone = setOf(2))
        val target = Block("M 31", listOf(GoToTarget(centre = false), Exposures(3, 10.0, ditherEvery = 1)), target = m31)

        val run = runner().runToTheEnd(night(target))

        assertEquals(RunStatus.Finished, run.status, run.error)
        val began = slews().first().let { SkyPosition(it.raHours, it.decDeg + 20.0 / 3600) }
        val points = slews().drop(1).map { steps(it, began) }
        assertEquals(3, points.size, "two dithers, the first in two GoTos")
        assertTrue(points[0] != points[1], points.toString())
        assertEquals(listOf("2", "1"), events.filter { it.first == "sequence_dither" }.map { it.second["goTos"].toString() })
    }
}
