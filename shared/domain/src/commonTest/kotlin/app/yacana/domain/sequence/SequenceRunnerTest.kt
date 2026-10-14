// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentPlatform
import app.yacana.domain.equipment.FakeAlpaca
import app.yacana.domain.equipment.fakeHttp
import app.yacana.domain.equipment.seestarMount
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.FakeEngine
import app.yacana.domain.imaging.FakeStore
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.imaging.seestarCamera
import app.yacana.domain.imaging.seestarFilterWheel
import app.yacana.domain.profile.DeviceRef
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileDevices
import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.profile.ProfileStore
import app.yacana.domain.session.Journal
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.PlateSolving
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.HeaderValue
import app.yacana.ports.PlateSolver
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import app.yacana.ports.SolveRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SequenceRunnerTest : RunnerFixture() {
    /** As the simple editor makes a night: start, one target, end with its darks and bias. */
    private val night =
        Exposures(count = 3, exposureSeconds = 20.0, filter = "LP", gain = 80, ditherEvery = 2).let { lights ->
            Sequence(
                "Tonight",
                Block(
                    "Tonight",
                    listOf(
                        Block("Start", listOf(Unpark, SetSwitch("Dew heater", 1.0))),
                        Block("M 31", listOf(GoToTarget(centre = true), lights), target = m31),
                        Block("End", listOf(Park, SetSwitch("Dew heater", 0.0), lights.darks(2), lights.bias(2))),
                    ),
                ),
            )
        }

    @Test
    fun aNightRunsInOrderAndTheJournalKeepsEachStep() = runTest {
        val run = runner().runToTheEnd(night)

        assertEquals(RunStatus.Finished, run.status, run.error)
        assertEquals(
            listOf(
                "Unpark",
                "SetSwitch(name=Dew heater, value=1.0)",
                "GoToTarget(centre=true)",
                night.root.items[1].let { (it as Block).items[1].toString() },
                "Park",
                "SetSwitch(name=Dew heater, value=0.0)",
            ),
            items().take(6),
        )
        val order = seestar.puts.map { it.first }
        assertTrue(order.indexOf("telescope/0/unpark") < order.indexOf("telescope/0/park"))
        assertEquals(listOf("1.0", "0.0"), seestar.puts.filter { it.first == "switch/0/setswitchvalue" }.map { it.second["Value"] })
        assertEquals(listOf("sequence_started"), events.map { it.first }.filter { it == "sequence_started" })
        assertEquals("sequence_finished", events.last { it.first.startsWith("sequence_") }.first)
        assertEquals(3, run.done[listOf(1, 1)])
    }

    @Test
    fun aMosaicsBlockHandsItsPanelsToThemAndEachKnowsWhichItIs() {
        val first = SkyTarget("M 31 Panel 1", 0.70, 41.27)
        val second = SkyTarget("M 31 Panel 2", 0.74, 41.27)
        val lights = Exposures(count = 2, exposureSeconds = 20.0)
        val panels = listOf(Block(first.name, listOf(lights), target = first), Block(second.name, listOf(lights), target = second))
        val mosaic = Block("M 31", panels, target = m31)

        val own = assertNotNull(mosaicOf(mosaic, null))
        assertEquals("M 31", own.mosaic)
        assertEquals(listOf(first.position, second.position), own.panels)
        assertEquals(-1, own.index, "the mosaic's own block is none of its panels")
        assertEquals(1, mosaicOf(panels[1], own)?.index)
        assertEquals(own, mosaicOf(Block("Lights", listOf(lights)), own), "a block inside keeps it")
        assertNull(mosaicOf(Block("M 31", listOf(lights), target = m31), null), "a target alone is no mosaic")
    }

    @Test
    fun darksAndBiasOutsideTheTargetGoToItsFolder() = runTest {
        // As on the second night: darks at the start, before the target, and darks and bias at the end.
        val lights = (night.root.items[1] as Block).items[1] as Exposures
        val start = Block("Start", listOf(Unpark, lights.darks(1)))
        val sequence = night.copy(root = night.root.copy(items = listOf(start) + night.root.items.drop(1)))

        runner().runToTheEnd(sequence)

        val calibration = saved(FrameType.Dark) + saved(FrameType.Bias)
        assertEquals(5, calibration.size, "a dark at the start, and two darks and two bias at the end")
        calibration.forEach { (path, _) -> assertTrue(path.startsWith("2026-10-04/M 31/"), path) }
    }

    @Test
    fun lightsTakeTheTargetAndFilterAndDitherAfterEverySoMany() = runTest {
        runner().runToTheEnd(night)

        val lights = saved(FrameType.Light)
        assertEquals(3, lights.size)
        lights.forEach { (path, headers) ->
            assertTrue(path.startsWith("2026-10-04/M 31/LIGHT/"), path)
            assertEquals("LP", headers.text("FILTER"))
        }
        // After the second frame, not after the last.
        assertEquals(1, events.count { it.first == "sequence_dither" })
        assertTrue(seestar.puts.any { it.first == "telescope/0/pulseguide" })
    }

    @Test
    fun theDarksTriggerTakesDarksWithTheLightsSettingsWhenNoneAreOfTheSensorsTemperature() = runTest {
        val lights = Exposures(count = 3, exposureSeconds = 20.0, filter = "LP", gain = 80)
        val night =
            Sequence(
                "Tonight",
                Block(
                    "Tonight",
                    // A short wait, as unparking takes: the camera's first reading comes meanwhile.
                    listOf(Block("Start", listOf(WaitFor(0.1))), Block("M 31", listOf(lights), target = m31)),
                    triggers = listOf(Trigger.DarksAfterTemperature(amount = 3.0, count = 2)),
                ),
            )

        val run = runner().runToTheEnd(night)

        assertEquals(RunStatus.Finished, run.status, run.error)
        // Before the first light only: the sensor stays within 3 °C of those darks.
        assertEquals(2, saved(FrameType.Dark).size)
        assertEquals(3, saved(FrameType.Light).size)
        saved(FrameType.Dark).forEach { (_, headers) ->
            assertEquals(20.0, headers.real("EXPOSURE"))
            assertEquals("Dark", headers.text("FILTER"))
        }
        saved(FrameType.Light).forEach { (_, headers) -> assertEquals("LP", headers.text("FILTER")) }
        assertEquals(1, events.count { it.first == "sequence_darks" })
    }

    @Test
    fun darksAndBiasTakeTheLightsSettingsWithTheLightBlocked() = runTest {
        runner().runToTheEnd(night)

        val darks = saved(FrameType.Dark)
        val bias = saved(FrameType.Bias)
        assertEquals(2, darks.size)
        assertEquals(2, bias.size)
        darks.forEach { (_, headers) -> assertEquals(20.0, headers.real("EXPOSURE")) }
        // The camera's shortest exposure.
        bias.forEach { (_, headers) -> assertEquals(3e-5, headers.real("EXPOSURE")) }
        (darks + bias).forEach { (_, headers) -> assertEquals("Dark", headers.text("FILTER")) }
        val exposures = seestar.puts.filter { it.first == "camera/0/startexposure" }.map { it.second["Light"] }
        assertEquals(listOf("false", "false", "false", "false"), exposures.takeLast(4))
        assertEquals(
            listOf("80", "80", "80", "80"),
            seestar.puts.filter {
                it.first == "camera/0/gain"
            }.map { it.second["Gain"] }.takeLast(4),
        )
    }

    @Test
    fun anAutofocusTriggerRunsAutofocusBeforeALightAndAFailureWaitsForTheNextExposures() = runTest {
        val sequence =
            Sequence(
                "Triggered",
                Block(
                    "M 31",
                    listOf(Exposures(count = 2, exposureSeconds = 10.0), Exposures(count = 2, exposureSeconds = 20.0)),
                    target = m31,
                    triggers = listOf(Trigger.AutofocusAfterTime(minutes = 0.0)),
                ),
            )

        val run = runner().runToTheEnd(sequence)

        assertEquals(RunStatus.Finished, run.status)
        // The profile has no focuser, so autofocus fails: once for each exposures, not before each frame.
        assertEquals(2, events.count { it.first == "sequence_autofocus_trigger" })
        assertEquals(2, events.count { it.first == "sequence_autofocus_failed" })
        // And the frames are still taken.
        assertEquals(4, saved(FrameType.Light).size)
    }

    @Test
    fun theFilterTriggerFiresWhenTheNextExposuresChangeTheFilter() = runTest {
        val sequence =
            Sequence(
                "Filters",
                Block(
                    "M 31",
                    listOf(
                        Exposures(count = 2, exposureSeconds = 10.0, filter = "IR"),
                        Exposures(count = 2, exposureSeconds = 10.0, filter = "LP"),
                    ),
                    target = m31,
                    triggers = listOf(Trigger.AutofocusAfterFilter),
                ),
            )

        val run = runner().runToTheEnd(sequence)

        assertEquals(RunStatus.Finished, run.status)
        // The wheel starts at IR, so only the change to LP fires it.
        val trigger = events.indexOfFirst { it.first == "sequence_autofocus_trigger" }
        assertEquals(1, events.count { it.first == "sequence_autofocus_trigger" })
        assertEquals("AfterFilterChange", events[trigger].second["triggers"])
    }

    @Test
    fun aFailureStopsTheSequenceWithTheReason() = runTest {
        val sequence = Sequence("Ha", Block("Ha", listOf(Exposures(count = 1, exposureSeconds = 10.0, filter = "Ha"), Park)))

        val run = runner().runToTheEnd(sequence)

        assertEquals(RunStatus.Failed, run.status)
        assertEquals("The filter wheel has no Ha", run.error)
        assertTrue(seestar.puts.none { it.first == "telescope/0/park" })
        assertEquals("The filter wheel has no Ha", events.last().second["reason"])
    }

    @Test
    fun aRunComesBackAfterTheAppClosesLosingAtMostTheFrameInProgress() = runTest {
        val file = Path(directory, "run.json")
        val before = runner(file)
        before.start(night)
        before.state.first { (it?.done?.get(LIGHTS) ?: 0) == 2 }
        // The app closes during the third light: all that is left is what was kept on disk.
        before.pause("closed")

        val after = runner(file)
        assertTrue(after.restore())
        assertEquals(2, after.state.value?.done?.get(LIGHTS))
        after.resume()
        val run = after.state.first { it?.status != RunStatus.Running }!!

        assertEquals(RunStatus.Finished, run.status)
        assertEquals(3, run.done[LIGHTS])
        assertEquals(3, saved(FrameType.Light).size)
        // The start was done before: the mount is not unparked again.
        assertEquals(1, seestar.puts.count { it.first == "telescope/0/unpark" })
        assertTrue(events.any { it.first == "sequence_restored" })
        assertFalse(SystemFileSystem.exists(file))
    }

    @Test
    fun aRunKeptLongAgoOrEndedDoesNotComeBack() {
        val progress = RunProgress(Path(directory, "run.json"))
        val at = clock.now()
        progress.keep(SequenceRun(night, current = LIGHTS, done = mapOf(LIGHTS to 2)), at)

        assertEquals(mapOf(LIGHTS to 2), progress.read(at + 29.minutes)?.done)
        assertNull(progress.read(at + 31.minutes))
        progress.keep(SequenceRun(night, RunStatus.Finished), at)
        assertNull(progress.read(at))
    }

    @Test
    fun aFailureAsTheEquipmentStopsAnsweringIsASafePauseNotAFailedSequence() = runTest {
        val runner = runner()
        runner.start(night)
        runner.state.first { (it?.done?.get(LIGHTS) ?: 0) == 1 }

        // The WiFi goes: the next request fails at once, before any poll notices.
        seestar.down = true
        val run = runner.state.first { it?.status != RunStatus.Running }!!

        assertEquals(RunStatus.Paused, run.status)
    }

    @Test
    fun aSequenceComesBackFromItsJsonAsItWas() {
        assertEquals(night, Sequence.fromJson(night.toJson()))
    }
}

/** M 31's lights in [SequenceRunnerTest]'s night: its second block's second item. */
private val LIGHTS = listOf(1, 1)
