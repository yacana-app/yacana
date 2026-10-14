// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.imaging.FrameType
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Condition
import app.yacana.domain.sequence.MoveFocuser
import app.yacana.domain.sequence.Note
import app.yacana.domain.sequence.SetSwitch
import app.yacana.domain.sequence.SwitchFilter
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.WaitFor
import app.yacana.domain.sequence.WaitUntilAbove
import app.yacana.domain.sequence.WaitUntilTime
import app.yacana.domain.sequence.areas
import app.yacana.ui.AppServices
import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each instruction, trigger and condition of the advanced editor's catalogue, added to M 33 and set on its sheet. */
class AdvancedItemsTest : FlowTest() {
    private fun atM33(connected: Boolean = false) = show(
        Section.Sequence,
        Screen.SequenceTonight,
        Screen.Advanced(),
        Screen.Advanced(listOf(1)),
        Screen.Advanced(listOf(1, 1)),
        connected = connected,
    )

    /** M 33's container, once tonight is advanced. */
    private fun AppServices.m33() = targets.advanced.value?.areas()?.targets?.items?.get(1) as? Block

    /** Adds the instruction [entry] to M 33 from the catalogue, after [set] sets it on its sheet. */
    private fun add(entry: String, set: () -> Unit = {}) {
        tap("Add to M 33")
        tap(entry)
        set()
        tap("Add")
    }

    @Test
    fun aWaitIsLengthened() {
        val services = atM33()

        add("Wait a while") { tap("+10") }

        assertEquals(WaitFor(15.0), services.m33()?.items?.last())
        assertTrue(appears("15 min"))
    }

    @Test
    fun aWaitUntilTheTargetRisesIsRaised() {
        val services = atM33()

        add("Wait until it rises") { tap("+5") }

        assertEquals(WaitUntilAbove(35.0), services.m33()?.items?.last())
    }

    @Test
    fun aWaitUntilATimeHasItsHourAndMinutes() {
        val services = atM33()

        add("Wait until a time") {
            tap("+1")
            tap("+5")
        }

        assertEquals(WaitUntilTime(22 * 60 + 35), services.m33()?.items?.last())
        assertTrue(appears("Until 22:35"))
    }

    @Test
    fun aNoteIsTyped() {
        val services = atM33()

        add("Note") { type("Clouds to the west") }

        assertEquals(Note("Clouds to the west"), services.m33()?.items?.last())
        assertTrue(appears("«Clouds to the west»"))
    }

    @Test
    fun theFocusersPositionIsTypedOnTheKeypad() {
        val services = atM33()

        add("Move the focuser") {
            tap("1500")
            listOf("2", "0", "0", "0").forEach { tap(it) }
            tap("Got it")
        }

        assertEquals(MoveFocuser(2000), services.m33()?.items?.last())
    }

    @Test
    fun aFilterOfTheWheelIsChosen() {
        val services = atM33(connected = true)

        add("Change filter") { tap("LP") }

        assertEquals(SwitchFilter("LP"), services.m33()?.items?.last())
    }

    @Test
    fun theDewHeaterIsSwitchedOff() {
        val services = atM33()

        add("Set a switch") { tap("Off") }

        assertEquals(SetSwitch("Dew heater", 0.0), services.m33()?.items?.last())
        assertTrue(appears("Dew heater · off"))
    }

    @Test
    fun anAutofocusEverySoOftenIsATrigger() {
        val services = atM33()

        tap("Trigger")
        tap("Autofocus every so often")
        tap("30 min")
        tap("Add")

        assertEquals(listOf<Trigger>(Trigger.AutofocusAfterTime(30.0)), services.m33()?.triggers)
        assertTrue(appears("Every 30 min"))
    }

    @Test
    fun aRecentreTriggerIsAddedAndDeleted() {
        val services = atM33()
        tap("Trigger")
        tap("Recentre if it drifts")
        tap("2′")
        tap("Add")
        assertEquals(listOf<Trigger>(Trigger.RecentreAfterDrift(2.0)), services.m33()?.triggers)

        tap("If M 33 drifts > 2′", substring = true)
        tap("Delete")

        // Without it, tonight is the simple plan again.
        assertNull(services.targets.advanced.value)
        assertFalse(shows("If M 33 drifts > 2′", substring = true))
    }

    @Test
    fun aLoopIsCountedUp() {
        val services = atM33()

        tap("Condition")
        tap("Repeat N times")
        tap("+1")
        tap("Add")

        assertEquals(listOf<Condition>(Condition.Loop(4)), services.m33()?.conditions)
        assertTrue(appears("Repeat 4 times"))
    }

    @Test
    fun exposuresBecomeDarksWithNoFilter() {
        val services = atM33()

        tap("Exposures")
        tap("Dark")
        tap("Save")

        // Darks are still the simple plan's.
        val exposures = services.targets.targets.value[1].exposures.single()
        assertEquals(FrameType.Dark, exposures.type)
        assertNull(exposures.filter)
    }
}
