// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.yacana.TEST_WAIT_MILLIS
import app.yacana.domain.flats.FlatSource
import app.yacana.domain.flats.FlatsPhase
import app.yacana.domain.profile.PolarAlignmentOptions
import app.yacana.ui.AppServices
import app.yacana.ui.Section
import app.yacana.ui.flats.SCREEN_LIGHT
import app.yacana.ui.navigation.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The assistants as they are touched: the polar alignment's options and its start, and the flats from their start to their end. */
class AssistantsTest : FlowTest() {
    private fun AppServices.polar(): PolarAlignmentOptions = checkNotNull(equipment.state.value.active).options.polarAlignment

    @Test
    fun thePolarAlignmentsTurnIsPickedWithItsDirection() {
        val services = show(Section.Equipment, Screen.PolarAlignment, connected = true)

        tap("Turn between points")
        tap("30°")
        tap("east")

        assertEquals(30.0, services.polar().stepDeg)
        assertTrue(services.polar().east)
    }

    @Test
    fun thePolarAlignmentSolvesOnlyWithAHintWhenAsked() {
        val services = show(Section.Equipment, Screen.PolarAlignment, connected = true)

        tap("Solve only with a hint")
        tap("Yes")

        assertTrue(services.polar().hintOnly)
    }

    @Test
    fun thePolarAlignmentStartsMeasuringAndIsCancelled() {
        show(Section.Equipment, Screen.PolarAlignment, connected = true)

        tap("Start")
        assertTrue(appears("Don't touch the mount while it measures."))

        tap("Cancel")
        assertTrue(appears("Before starting"))
    }

    @Test
    fun theFlatsStartWithWhatIsChosenAndSayWhyTheyCouldNotGoOn() {
        val services = show(Section.Equipment, Screen.Flats, connected = true)
        val flats = checkNotNull(services.flats)

        tap("50")
        tap("IR")
        tap("Start the flats")
        // The phone itself is the light: first it leaves 30 s to lay it on the telescope.
        assertTrue(appears("Lay the phone face down", substring = true))
        assertNull(flats.state.value)
        compose.mainClock.advanceTimeBy(COUNTDOWN_MILLIS)
        settle()

        val options = checkNotNull(flats.state.value).options
        assertEquals(FlatSource.Screen, options.source)
        assertEquals(50, options.count)
        assertEquals(listOf("LP"), options.filters)
        assertEquals(false, options.darks)
        // The fake Seestar's frames are as dark at any exposure: the flats find none bright enough.
        runBlocking { withTimeout(TEST_WAIT_MILLIS) { flats.state.first { it?.phase == FlatsPhase.Failed } } }
        assertTrue(appears("The flats could not go on"))
        assertTrue(appears("There is too little light", substring = true))
        // A beep as they started, and another as they ended, with the phone face down.
        assertEquals(listOf(false, true), beeps)

        tap("Done")
        assertNull(flats.state.value)
    }

    @Test
    fun thePhoneIsAllWhiteWhileItsFlatsRunAndADoubleTapPausesThem() {
        val services = show(Section.Equipment, Screen.Flats, connected = true)
        val flats = checkNotNull(services.flats)
        // A frame that takes long: the flats are still finding their exposure when the white screen is tapped.
        seestar.holdFrames = true

        tap("Start the flats")
        compose.mainClock.advanceTimeBy(COUNTDOWN_MILLIS)
        settle()

        assertTrue(eventually { compose.onAllNodesWithTag(SCREEN_LIGHT).fetchSemanticsNodes().isNotEmpty() })
        compose.onNodeWithTag(SCREEN_LIGHT).performTouchInput { doubleClick() }
        settle()

        assertEquals(true, flats.state.value?.paused)
        assertTrue(compose.onAllNodesWithTag(SCREEN_LIGHT).fetchSemanticsNodes().isEmpty())
        flats.stop()
    }

    private companion object {
        /** The 30 s to lay the phone on the telescope, and a little more. */
        const val COUNTDOWN_MILLIS = 31_000L
    }
}
