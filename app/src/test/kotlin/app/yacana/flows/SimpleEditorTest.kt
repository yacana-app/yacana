// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.sequence.DarkRules
import app.yacana.domain.sequence.HeaterRule
import app.yacana.domain.sequence.TONIGHT_CALIBRATION
import app.yacana.domain.sequence.Trigger
import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Sequence · simple editor" as it is touched: a target's exposures, removing and undoing, saving tonight. */
class SimpleEditorTest : FlowTest() {
    @Test
    fun aTargetsExposuresChangeOnTheirSheet() {
        val services = show(Section.Sequence, Screen.SequenceTonight)

        tap("IR · 60 s × 250")
        assertTrue(appears("Exposures · M 33"))
        tap("30 s")
        tap("Save")

        val m33 = services.targets.targets.value.last()
        assertEquals(30.0, m33.exposures.single().exposureSeconds)
        assertEquals(250, m33.exposures.single().count)
    }

    @Test
    fun theDarksSheetPutsDarksAtTheStartAndByTheSensorsTemperature() {
        val services = show(Section.Sequence, Screen.SequenceTonight)

        tap("Darks")
        assertTrue(appears("If the sensor's temperature changes"))
        toggle(0)
        toggle(1)

        assertEquals(DarkRules(atStart = true), services.targets.darks.value)
        assertTrue(Trigger.DarksAfterTemperature(3.0, TONIGHT_CALIBRATION) in services.targets.triggers.value)
        assertTrue(appears("The live stack uses the set of the closest temperature.", substring = true))
        tap("5 °C")
        assertTrue(Trigger.DarksAfterTemperature(5.0, TONIGHT_CALIBRATION) in services.targets.triggers.value)
    }

    @Test
    fun aNewPlanRecentresAndItsSheetSetsHowFarOrTurnsItOff() {
        val services = show(Section.Sequence, Screen.SequenceTonight)
        assertTrue(appears("Recentre if it drifts > 1′"))

        tap("Recentring")
        tap("2′")
        assertTrue(Trigger.RecentreAfterDrift(2.0) in services.targets.triggers.value)
        toggle(0)

        assertTrue(services.targets.triggers.value.none { it is Trigger.RecentreAfterDrift })
    }

    @Test
    fun theRecentringSheetSaysItsChoicesInPixelsOfTheConnectedCamera() {
        show(Section.Sequence, Screen.SequenceTonight, connected = true)

        tap("Recentring")

        // The Seestar's main camera, 2.248″ a pixel, as N.I.N.A.'s Center After Drift says them.
        assertTrue(appears("1′ · 27 px"))
        assertTrue(appears("10′ · 267 px"))
        assertFalse(shows("With this camera", substring = true), "its 1′ is more than the 20 px a dither moves")
    }

    @Test
    fun theDitherSheetSetsEveryTargetsLights() {
        val services = show(Section.Sequence, Screen.SequenceTonight)

        tap("Dither")
        tap("Every 5")

        assertEquals(5, services.targets.dither.value)
        assertTrue(services.targets.targets.value.flatMap { it.exposures }.all { it.ditherEvery == 5 })
        assertTrue(appears("Dither every 5 frames"))
    }

    @Test
    fun theDewHeaterSheetLeavesItOffWithTheEquipmentConnected() {
        val services = show(Section.Sequence, Screen.SequenceTonight, connected = true)
        assertTrue(appears("Dew heater on"))

        tap("Dew heater")
        toggle(0)

        assertEquals(HeaterRule(on = false), services.targets.heater.value)
        settle()
        assertTrue(!shows("Dew heater on"), "the rule leaves the plan")
    }

    @Test
    fun aTargetRemovedComesBackWithUndo() {
        val services = show(Section.Sequence, Screen.SequenceTonight)

        tapIcon("Remove M 31")
        assertEquals(listOf("M 33"), services.targets.targets.value.map { it.name })
        tap("Undo")

        assertEquals(listOf("M 31", "M 33"), services.targets.targets.value.map { it.name })
    }

    @Test
    fun tonightIsSavedInMySequencesFromTheMenu() {
        val services = show(Section.Sequence, Screen.SequenceTonight, framed = true)
        val before = services.savedSequences.saved.value.size

        tapIcon("More options")
        tap("Save in my sequences")

        assertTrue(appears("Saved in my sequences: M 31 · M 33"))
        assertEquals(before + 1, services.savedSequences.saved.value.size)
    }

    @Test
    fun aSavedSequenceOpensAsTonightsPlan() {
        val services = show(Section.Sequence, framed = true)

        // "My sequences" keeps each of tonight's targets saved on its own.
        tap("M 33")

        assertTrue(appears("Tonight"))
        assertEquals(listOf("M 33"), services.targets.targets.value.map { it.name })
    }
}
