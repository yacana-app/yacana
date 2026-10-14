// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.sequence.DarkRules
import app.yacana.domain.sequence.TONIGHT_CALIBRATION
import app.yacana.domain.sequence.Trigger
import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertEquals
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
