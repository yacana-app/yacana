// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.profile.StackingOptions
import app.yacana.ports.ColourStretch
import app.yacana.ports.FrameStatistics
import app.yacana.ports.HeaderValue
import app.yacana.ports.Region
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import app.yacana.ports.Stretch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TestTimeSource

class LiveStackingTest {
    private val engine = FakeEngine().apply { stacking = true }
    private val store = FakeStore()
    private val time = TestTimeSource()
    private val options = ProfileOptions(stacking = StackingOptions(live = true))

    private fun stacking(checkpoint: String? = "files/stack.bin") =
        LiveStacking(engine, store, checkpoint, zone = { TimeZone.UTC }, time = time)

    /** A saved light of [target]: 20 s with the LP filter, at 00:30 UTC, the night of 4 October. */
    private fun captured(target: String = "M 31", hfr: Double = 2.4, exposure: Double = 20.0, mosaic: MosaicPanel? = null) = CapturedFrame(
        frame = TestFrame(hfr),
        capture =
        Capture(
            type = FrameType.Light,
            start = Instant.parse("2026-10-05T00:30:00Z"),
            exposureSeconds = exposure,
            camera = SEESTAR_CAMERA,
            cameraName = "Seestar",
            gain = 80,
            target = target,
            mosaic = mosaic,
            filter = "LP",
            software = "Yacana test",
        ),
        statistics = FrameStatistics(0, 65535, 1000.0, 1000.0, 10.0, 20.0),
        autoStretch = ColourStretch.linked(Stretch(0.01f, 0.1f)),
        histogram = LongArray(0),
        savedAs = "saved",
    )

    private suspend fun LiveStacking.offer(frame: CapturedFrame, discarded: DiscardReason? = null) =
        offer(frame, frame.frame.detectStars(null, null), discarded, options)

    @Test
    fun goodFramesGoInAndTheOthersAreCountedOut() = runTest {
        val stacking = stacking()

        repeat(3) { stacking.offer(captured()) }
        stacking.offer(captured(), DiscardReason.FewStars)
        stacking.offer(captured(hfr = NOT_ALIGNED))

        val state = stacking.state.value
        assertEquals(StackKey(LocalDate(2026, 10, 4), "M 31", "LP", 20.0, 80, 1), state.key)
        assertEquals(3, state.frames)
        assertEquals(60.0, state.info?.exposureSeconds)
        assertEquals(mapOf(DiscardReason.FewStars to 1), state.discarded)
        assertEquals(1, state.notAligned)
        assertEquals(5, state.offered)
        assertEquals(0.4, state.rotationDeg)
        assertEquals(4, state.version, "each frame offered to the engine draws it again")
        assertEquals(1, engine.stacks.size)
    }

    /** Panel [index] of a mosaic of two of M 31, side by side, centred as its centring solved it. */
    private fun panel(index: Int): MosaicPanel {
        val panels = listOf(SkyPosition(0.70, 41.27), SkyPosition(0.74, 41.27))
        return MosaicPanel("M 31", SkyPosition(0.72, 41.27), panels, index, Solution(panels[index], 0.0, 2.25, yAxisDeg = 0.0))
    }

    @Test
    fun aMosaicsPanelsBuildTheLiveMosaicOneAfterAnother() = runTest {
        val stacking = stacking()

        repeat(2) { stacking.offer(captured(target = "M 31 Panel 1", mosaic = panel(0))) }
        val first = assertNotNull(stacking.state.value.mosaic)
        assertEquals("M 31", first.name)
        assertEquals(setOf(0), first.drawn)
        assertEquals(0, first.stacking)
        assertEquals(2, first.outlines.size)
        assertNotNull(stacking.mosaicImage())

        // The next panel: its own stack, the same mosaic, which keeps the first and is kept as a JPG.
        stacking.offer(captured(target = "M 31 Panel 2", mosaic = panel(1)))

        val both = assertNotNull(stacking.state.value.mosaic)
        assertEquals(setOf(0, 1), both.drawn)
        assertEquals(1, both.stacking)
        assertTrue(both.version > first.version)
        assertEquals("M 31 Panel 2", stacking.state.value.key?.target)
        assertEquals("2026-10-04/M 31/STACK/2026-10-05_00-30-00_LP_20.00s_STACK_MOSAIC", store.jpegs.last())
    }

    @Test
    fun anotherTargetEndsTheMosaicAndAFrameOutsideOneHasNone() = runTest {
        val stacking = stacking()
        stacking.offer(captured(target = "M 31 Panel 1", mosaic = panel(0)))

        stacking.offer(captured(target = "M 33"))

        assertNull(stacking.state.value.mosaic)
        assertNull(stacking.mosaicImage())
        assertTrue(store.jpegs.isNotEmpty(), "the mosaic is kept as it ends")
    }

    @Test
    fun aPanelWithoutASolutionStaysOutOfTheMosaic() = runTest {
        val stacking = stacking()

        stacking.offer(captured(target = "M 31 Panel 1", mosaic = panel(0).copy(solution = null)))

        assertNull(stacking.state.value.mosaic)
        assertEquals(1, stacking.state.value.frames, "its frames are stacked all the same")
    }

    @Test
    fun aFrameOfAnotherStackSavesTheOneBeforeAndStartsItsOwn() = runTest {
        val stacking = stacking()
        repeat(2) { stacking.offer(captured()) }

        stacking.offer(captured(target = "M 33"))

        val saved = store.stackSaves.single()
        assertEquals("2026-10-04/M 31/STACK/2026-10-05_00-30-00_LP_20.00s_STACK", saved.path)
        assertEquals(2, saved.frames)
        assertTrue(saved.balanced)
        assertTrue(engine.stacks.first().closed, "the stack before is freed")
        assertEquals("M 33", stacking.state.value.key?.target)
        assertEquals(1, stacking.state.value.frames)
        // Another exposure is another stack too.
        stacking.offer(captured(target = "M 33", exposure = 60.0))
        assertEquals(60.0, stacking.state.value.key?.exposureSeconds)
        assertEquals(3, engine.stacks.size)
    }

    @Test
    fun theHeaderSaysHowManyFramesAndHowLongWithoutTheBayerPattern() = runTest {
        val stacking = stacking()
        repeat(3) { stacking.offer(captured()) }

        assertEquals("saved/2026-10-04/M 31/STACK/2026-10-05_00-30-00_LP_20.00s_STACK.fits", stacking.save())

        val headers = store.stackSaves.single().headers.associate { it.key to it.value }
        assertEquals(HeaderValue.Integer(3), headers["NCOMBINE"])
        assertEquals(HeaderValue.Integer(3), headers["STACKCNT"])
        assertEquals(HeaderValue.Real(60.0), headers["LIVETIME"])
        assertEquals(HeaderValue.Real(20.0), headers["EXPTIME"], "a mean keeps each frame's scale")
        assertNull(headers["BAYERPAT"])
        assertEquals(HeaderValue.Text("M 31"), headers["OBJECT"])
        assertEquals(3, stacking.state.value.savedFrames)
    }

    @Test
    fun itIsKeptAtOnceThenKeptAndSavedEveryTenMinutes() = runTest {
        val stacking = stacking()

        stacking.offer(captured())
        assertEquals(1, engine.checkpoints.getValue("files/stack.bin").frames, "the first frame replaces an old checkpoint")
        assertTrue(store.stackSaves.isEmpty(), "one frame is no stack")

        time += 5.minutes
        stacking.offer(captured())
        assertEquals(1, engine.checkpoints.getValue("files/stack.bin").frames)
        time += 6.minutes
        stacking.offer(captured())

        assertEquals(3, engine.checkpoints.getValue("files/stack.bin").frames)
        assertEquals(3, store.stackSaves.single().frames)
        stacking.saveIfGrown()
        assertEquals(1, store.stackSaves.size, "it has not grown since")
    }

    @Test
    fun afterTheAppWasClosedItGoesOnIfTheNextFrameIsOfTheSameStack() = runTest {
        val before = stacking()
        repeat(2) { before.offer(captured()) }
        time += 11.minutes
        before.offer(captured())

        val after = stacking()
        after.offer(captured())
        assertEquals(4, after.state.value.frames)
        assertEquals(80.0, after.state.value.info?.exposureSeconds)

        val another = stacking()
        another.offer(captured(target = "M 33"))
        assertEquals(1, another.state.value.frames, "a checkpoint of another stack is not continued")
        assertTrue(engine.stacks[engine.stacks.lastIndex - 1].closed, "and it is freed")
    }

    @Test
    fun startingAgainThrowsTheStackAwayAndTurningItOffSavesAndFreesIt() = runTest {
        val stacking = stacking()
        repeat(3) { stacking.offer(captured()) }

        stacking.reset()
        assertTrue(engine.stacks.single().closed)
        assertEquals("M 31", stacking.state.value.key?.target)
        assertEquals(0, stacking.state.value.frames)
        assertTrue(store.stackSaves.isEmpty())

        repeat(2) { stacking.offer(captured()) }
        stacking.end()
        assertEquals(2, store.stackSaves.single().frames)
        assertTrue(engine.stacks.last().closed)
        assertNull(stacking.state.value.key)
        assertNull(stacking.preview(true, Region(0.0, 0.0, 10.0, 10.0), 10, 10))
    }

    @Test
    fun aLightIsCalibratedWithItsDarkAndTheStackSaysSo() = runTest {
        val stacking = stacking()
        stacking.offer(captured())
        assertEquals(StackCalibration(), stacking.state.value.calibration, "no darks yet")
        val dark = captured().let {
            CapturedFrame(
                it.frame,
                it.capture.copy(type = FrameType.Dark, sensorTemperature = 17.6),
                it.statistics,
                it.autoStretch,
                it.histogram,
                "saved",
            )
        }
        repeat(2) { stacking.calibration.offer(dark) }

        stacking.offer(captured())

        val (usedDark, usedFlat) = engine.stacks.single().calibrations.last()
        assertEquals(2, usedDark?.frames())
        assertNull(usedFlat)
        assertEquals(StackCalibration(dark = true, darkTemperatureC = 17.6), stacking.state.value.calibration)
    }

    @Test
    fun aFrameThatCannotBeAddedIsSaidAndTheStackGoesOn() = runTest {
        val stacking = stacking(checkpoint = null)
        stacking.offer(captured())
        val closed = captured().also { (it.frame as TestFrame).closed = true }

        stacking.offer(closed)

        assertEquals("closed", stacking.state.value.error)
        assertEquals(1, stacking.state.value.frames)
        assertTrue(engine.checkpoints.isEmpty(), "no checkpoint without a file for it")
        stacking.offer(captured())
        assertNotNull(stacking.state.value.info)
        assertEquals(2, stacking.state.value.frames)
    }
}
