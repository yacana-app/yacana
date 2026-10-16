// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.equipment.separationArcsec
import app.yacana.ports.SkyPosition
import kotlinx.coroutines.test.runTest
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

    @Test
    fun aSeestarDithersByAGoToAroundWhereTheFieldBegan() = runTest {
        // Each slew lands 20″ north of where it was sent, as the Seestar's within about 15″.
        val camera = seestar.onPut
        seestar.onPut = { member, form ->
            camera(member, form)
            if (member == "telescope/0/slewtocoordinatesasync") {
                seestar.values["telescope/0/rightascension"] = form.getValue("RightAscension")
                seestar.values["telescope/0/declination"] = (form.getValue("Declination").toDouble() + 20.0 / 3600).toString()
            }
        }
        val target = Block("M 31", listOf(GoToTarget(centre = false), Exposures(6, 10.0, ditherEvery = 1)), target = m31)

        val run = runner().runToTheEnd(night(target))

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertTrue(sent("telescope/0/pulseguide").isEmpty(), "its pulses go astray")
        val slews = sent("telescope/0/slewtocoordinatesasync").map {
            SkyPosition(it.getValue("RightAscension").toDouble(), it.getValue("Declination").toDouble())
        }
        val dithers = slews.drop(1)
        assertEquals(5, dithers.size)
        assertEquals(5, dithers.distinct().size)
        // Within 3 × 5 pixels of 2″ of where the GoTo to M 31 landed: the slews' 20″ do not add up.
        val began = slews.first().let { SkyPosition(it.raHours, it.decDeg + 20.0 / 3600) }
        assertTrue(dithers.all { separationArcsec(it, began) <= 30.0 + 1e-6 }, dithers.map { separationArcsec(it, began) }.toString())
    }
}
