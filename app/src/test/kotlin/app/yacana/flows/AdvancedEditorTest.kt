// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Condition
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.GoToTarget
import app.yacana.domain.sequence.areas
import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Sequence · advanced editor" as it is touched: levels, catalogue, sheets, menus, "Review" and undo. */
class AdvancedEditorTest : FlowTest() {
    /** M 33, tonight's second target, as the tree numbers it. */
    private val m33 = listOf(1, 1)

    private fun atM33() =
        show(Section.Sequence, Screen.SequenceTonight, Screen.Advanced(), Screen.Advanced(listOf(1)), Screen.Advanced(m33))

    @Test
    fun tonightOpensWithItsThreeAreasAndEachLevelWithATap() {
        show(Section.Sequence, Screen.SequenceTonight)
        tap("Advanced")

        assertTrue(appears("THE SEQUENCE'S AREAS") && shows("Targets") && shows("End"))
        tap("Targets")
        assertTrue(appears("M 31"))
        tap("M 33")
        assertTrue(appears("Go to and centre"))
        assertTrue(appears("Add to M 33"))
    }

    @Test
    fun aConditionFromTheCatalogueRepeatsTheTargetAndTonightBecomesAdvanced() {
        val services = atM33()

        tap("Condition")
        tap("Repeat until a time")
        assertTrue(appears("The times are tonight's", substring = true))
        tap("Add")

        assertTrue(appears("Repeat until 02:00"))
        val m33 = services.targets.advanced.value?.areas()?.targets?.items?.get(1) as? Block
        assertEquals(listOf<Condition>(Condition.UntilTime(120)), m33?.conditions)
    }

    @Test
    fun theStartsCatalogueGreysOutWhatPointsAtATarget() {
        show(
            Section.Sequence,
            Screen.SequenceTonight,
            Screen.Advanced(),
            Screen.Advanced(listOf(0)),
            Screen.AdvancedAdd(listOf(0), "Start"),
        )

        val centre = node("Go to and centre")
        centre.assert(hasText("Only inside a Target"))
        centre.assertIsNotEnabled()
        assertTrue(appears("add a «Target» here", substring = true))
    }

    @Test
    fun aTargetFromCieloComesWithItsCentringAndStaysInThePlan() {
        val services = show(Section.Sequence, Screen.SequenceTonight, Screen.Advanced(), Screen.Advanced(listOf(1)))

        tap("Add to Targets")
        tap("Target")
        assertTrue(appears("Choose a target"))
        tap("M 33")

        // Just as the simple editor makes a target, so the plan keeps it.
        assertNull(services.targets.advanced.value)
        assertEquals(listOf("M 31", "M 33", "M 33"), services.targets.targets.value.map { it.name })
    }

    @Test
    fun exposuresBeforeTheCentringAreWarnedAboutAndUndoTakesThemBack() {
        val services = atM33()

        tapIcon("Options of Exposures")
        tap("Move up")

        assertTrue(appears("«Go to and centre» is missing before it: it will not point at M 33"))
        tapIcon("Review: 1 warnings")
        assertTrue(appears("Review the night"))
        tap("Close")
        tapIcon("Undo")
        assertFalse(shows("«Go to and centre» is missing before it", substring = true))
        assertNull(services.targets.advanced.value)
    }

    @Test
    fun theFramesAreTypedOnTheAppsKeypad() {
        val services = atM33()

        tap("Exposures")
        tap("250")
        listOf("1", "2", "0").forEach { tap(it) }
        tap("Got it")
        tap("Save")

        assertEquals(120, services.targets.targets.value[1].exposures.single().count)
        assertTrue(appears("IR · 60 s × 120 · dither every 3"))
    }

    @Test
    fun anItemMovesIntoANewContainerWhichIsRenamedAndSavedAsATemplate() {
        val services = atM33()

        tap("Add to M 33")
        tap("Sequential container")
        tapIcon("Options of Exposures")
        tap("Move to…")
        tap("Sequential container")

        val m33 = services.targets.advanced.value?.areas()?.targets?.items?.get(1) as? Block
        val box = m33?.items?.last() as? Block
        assertEquals(listOf(GoToTarget(centre = true)), m33?.items?.dropLast(1))
        assertTrue(box?.items?.single() is Exposures)

        tapIcon("Options of Sequential container")
        tap("Rename")
        compose.onNode(hasSetTextAction()).performTextReplacement("IR round")
        tap("Save")
        assertTrue(appears("IR round"))
        tapIcon("Options of IR round")
        tap("Save as a template")
        assertEquals(listOf("IR round"), services.blockTemplates.saved.value.map { it.name })
    }

    @Test
    fun tonightInAdvancedSaysSoAndGoesBackToTheSimplePlanWhenHeld() {
        val services = show(Section.Sequence, Screen.SequenceTonight, advanced = true)

        assertTrue(appears("Tonight is edited in Advanced"))
        assertNotNull(services.targets.advanced.value)
        hold("Hold to go back to the simple plan")

        assertNull(services.targets.advanced.value)
        assertFalse(shows("Tonight is edited in Advanced"))
        compose.onNodeWithText("Advanced").assertExists()
    }
}
