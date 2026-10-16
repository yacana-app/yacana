// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.TEST_WAIT_MILLIS
import app.yacana.domain.imaging.CaptureSettings
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.profile.StackingOptions
import app.yacana.ui.AppServices
import app.yacana.ui.Section
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Imaging · manual capture" as it is touched: its setting chips and sheets, the shutter, solving the frame and the options. */
class ImagingTest : FlowTest() {
    /** Imaging with the fake Seestar connected, at 20 s and gain 80. */
    private fun imaging(): AppServices {
        val services = show(Section.Imaging, connected = true)
        services.capture.update(CaptureSettings(exposureSeconds = 20.0, gain = 80))
        settle()
        return services
    }

    @Test
    fun theExposureSheetSetsTheExposure() {
        val services = imaging()

        tap("20 s")
        tap("10 s")

        assertEquals(10.0, services.capture.state.value.settings.exposureSeconds)
        assertTrue(appears("10 s"))
    }

    @Test
    fun anotherExposureIsTypedOnTheAppsKeypad() {
        val services = imaging()

        tap("20 s")
        tap("Other")
        listOf("4", "5").forEach { tap(it) }
        tap("Save")

        assertEquals(45.0, services.capture.state.value.settings.exposureSeconds)
    }

    @Test
    fun theGainIsTypedOnTheAppsKeypad() {
        val services = imaging()

        tap("Gain 80")
        listOf("1", "2", "0").forEach { tap(it) }
        tap("Save")

        assertEquals(120, services.capture.state.value.settings.gain)
    }

    @Test
    fun theFilterSheetTurnsTheWheel() {
        imaging()
        // Slow to say where its wheel is, the Seestar answers after the sheet has closed.
        seestar.slow["filterwheel/0/position"] = 1_000

        tap("IR")
        tap("LP")

        assertTrue(sent("filterwheel/0/position", "Position", "2"))
    }

    @Test
    fun theShutterTakesAFrameAndSolveSaysWhereItPoints() {
        val services = imaging()

        tapLabelled("Capture")
        // The frame is taken and downloaded off the screen's clock, as the app does.
        runBlocking { withTimeout(TEST_WAIT_MILLIS) { services.capture.state.first { it.last != null } } }
        settle()
        assertTrue(sent("camera/0/startexposure"))

        tap("Solve")
        assertTrue(appears("Where the frame points"))
        assertTrue(appears("Solved with ASTAP", substring = true))
    }

    @Test
    fun theLastFramesDetailsOpenFromItsStars() {
        val services = imaging()
        tapLabelled("Capture")
        // Its stars are found once it is on the screen.
        runBlocking { withTimeout(TEST_WAIT_MILLIS) { services.capture.state.first { it.stars != null } } }
        settle()

        tapLabelled("Last frame")

        assertTrue(appears("Last frame"))
        assertTrue(appears("Mean ", substring = true))
    }

    @Test
    fun theOptionsNameTheTargetAndStopSavingSingleFrames() {
        val services = imaging()

        tapIcon("More options")
        type("NGC 7000")
        toggle(0)

        assertEquals("NGC 7000", services.capture.state.value.settings.target)
        assertEquals(false, services.capture.state.value.settings.save)
    }

    @Test
    fun liveStackingStartsOffAndTheOptionsTurnItOn() {
        val services = imaging()
        assertEquals(false, services.equipment.state.value.active?.options?.stacking?.live)

        tapIcon("More options")
        assertTrue(appears("Stack the light frames live"))
        toggle(3)

        assertEquals(true, services.equipment.state.value.active?.options?.stacking?.live)
        assertEquals(true, services.equipment.state.value.active?.options?.stacking?.balanced)
    }

    @Test
    fun theStacksCountOpensItsSheetWhichStartsItAgain() {
        val services = imaging()
        val active = services.equipment.state.value.active!!
        services.equipment.save(active.copy(options = active.options.copy(stacking = StackingOptions(live = true))))
        val stacking = services.capture.stacking!!

        tapLabelled("Capture")
        runBlocking { withTimeout(TEST_WAIT_MILLIS) { stacking.state.first { it.info != null } } }
        settle()
        assertTrue(appears("Stack"))
        tap("34 frames · 34 min")

        assertTrue(appears("Stack · IR"))
        assertTrue(appears("balanced on the stars"))
        hold("Hold to start again")
        assertTrue(eventually { stacking.state.value.info == null })
        assertFalse(shows("34 frames · 34 min"))
    }

    @Test
    fun theStacksSheetSaysWhatCalibratesItAndTakesDarksBehindTheDarkFilter() {
        val services = imaging()
        val active = services.equipment.state.value.active!!
        services.equipment.save(active.copy(options = active.options.copy(stacking = StackingOptions(live = true))))
        val stacking = services.capture.stacking!!
        tapLabelled("Capture")
        runBlocking { withTimeout(TEST_WAIT_MILLIS) { stacking.state.first { it.info != null } } }
        settle()
        tap("34 frames · 34 min")

        assertTrue(appears("no darks of 20 s"))
        // Without a master flat of IR in FLATS, the sheet says where to take one.
        assertTrue(appears("no flat of IR · take flats in Sequence › Flats"))
        tap("Take 10 darks of 20 s")

        assertTrue(sent("camera/0/startexposure", "Light", "false"))
        assertTrue(sent("filterwheel/0/position", "Position", "0"), "behind the Seestar's Dark filter")
    }

    @Test
    fun aLoopOfLightsDithersEveryThreeFramesUnlessChangedInTheOptions() {
        val services = imaging()
        assertEquals(3, services.capture.state.value.settings.ditherEvery, "as a sequence's target")

        tapIcon("More options")
        assertTrue(appears("Dither loops of lights every (frames)"))
        tap("10")

        assertEquals(10, services.capture.state.value.settings.ditherEvery)
        tap("Off")
        assertEquals(0, services.capture.state.value.settings.ditherEvery)
    }

    @Test
    fun theStretchIsSetByHandWhenNotAutomatic() {
        imaging()

        tapIcon("More options")
        toggle(1)

        assertTrue(appears("Shadows"))
        assertTrue(appears("Midtones"))
    }

    @Test
    fun theStarsMarkedAreTheBrightestFiftyUntilChanged() {
        val services = imaging()

        tapIcon("More options")
        assertFalse(shows("How many"))
        toggle(2)
        assertTrue(services.display.markStars.value)
        assertTrue(appears("How many"))
        assertTrue(appears("50"))

        tap("+10")

        assertEquals(60, services.display.markedStars.value)
    }

    @Test
    fun theOptionsChangeTheFrameType() {
        val services = imaging()

        tapIcon("More options")
        assertTrue(appears("Capture options"))
        tap("Dark")

        assertEquals(FrameType.Dark, services.capture.state.value.settings.type)
    }
}
