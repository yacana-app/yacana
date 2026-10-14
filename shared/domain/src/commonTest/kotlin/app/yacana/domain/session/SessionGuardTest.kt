// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.session

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
import app.yacana.domain.imaging.seestarCamera
import app.yacana.domain.imaging.seestarFilterWheel
import app.yacana.domain.profile.DeviceRef
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileDevices
import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.profile.ProfileStore
import app.yacana.domain.profile.SafetyOptions
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.Sequence
import app.yacana.domain.sequence.SequenceRunner
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.PlateSolving
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.PlateSolver
import app.yacana.ports.SolveRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SessionGuardTest {
    private val seestar =
        FakeAlpaca("seestar").apply {
            seestarMount()
            seestarCamera()
            seestarFilterWheel(1)
            val camera = onPut
            onPut = { member, form ->
                camera(member, form)
                if (member == "telescope/0/park") values["telescope/0/atpark"] = "true"
            }
        }
    private val directory = Path(SystemTemporaryDirectory, "yacana-guard-${ProfilePresets.newId()}")
    private val store = FakeStore()
    private val events = mutableListOf<String>()
    private val journal = Journal { event, _ -> events += event }
    private var batteryPercent: Int? = 80

    private lateinit var equipment: EquipmentController
    private lateinit var runner: SequenceRunner
    private lateinit var guard: SessionGuard

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    /** The fake Seestar connected, with a runner and its guard, on a clock that follows the test's time. */
    private suspend fun TestScope.connected(safety: SafetyOptions = SafetyOptions()) {
        val clock =
            object : Clock {
                override fun now() = Instant.parse("2026-10-07T21:00:00Z") + testScheduler.currentTime.let { Duration.parse("${it}ms") }
            }
        // An exposure takes its time, so that the link can be lost in the middle of a sequence.
        val before = seestar.onPut
        seestar.onPut = { member, form ->
            before(member, form)
            if (member == "camera/0/startexposure") {
                seestar.values["camera/0/imageready"] = "false"
                val seconds = form.getValue("Duration").toDouble()
                backgroundScope.launch {
                    delay(seconds.seconds)
                    seestar.values["camera/0/imageready"] = "true"
                }
            }
        }
        val http = fakeHttp(StandardTestDispatcher(testScheduler), seestar)
        val platform =
            object : EquipmentPlatform {
                override fun httpClient() = http

                override val discovery = AlpacaDiscovery { emptyList() }

                override fun networkHandle(): Long? = null

                override suspend fun location() = null
            }
        equipment = EquipmentController(ProfileStore(directory), platform, backgroundScope, clock, journal)
        equipment.save(
            Profile(
                id = "p",
                name = "Seestar S50 Pro",
                devices =
                ProfileDevices(
                    mount = DeviceRef(seestar.url, 0, "mount-1", "Seestar Telescope"),
                    camera = DeviceRef(seestar.url, 0, "camera-0", "Seestar Telephoto Camera"),
                    filterWheel = DeviceRef(seestar.url, 0, "filterwheel-0", "Seestar Filter Wheel"),
                ),
                options = ProfileOptions(sendTimeAndLocation = false, safety = safety),
            ),
        )
        equipment.connect()
        equipment.state.first { it.connection is Connection.Connected }
        val capture =
            CaptureController(equipment, FakeEngine(), store, platform, backgroundScope, "Yacana test", clock, { TimeZone.UTC }, journal)
        val solving = PlateSolving(equipment, capture, NoSolver, backgroundScope, journal)
        val centring = Centring(equipment, capture, solving, backgroundScope, journal, clock, settle = Duration.ZERO)
        val autofocus = Autofocus(equipment, capture, backgroundScope, journal, clock)
        runner = SequenceRunner(equipment, capture, centring, autofocus, backgroundScope, journal, clock)
        guard = SessionGuard(equipment, runner, capture, { batteryPercent }, backgroundScope, journal, clock)
    }

    private fun lights(count: Int, filter: String? = null) =
        Sequence("Tonight", Block("M 31", listOf(Exposures(count = count, exposureSeconds = 10.0, filter = filter))))

    private fun parked() = seestar.puts.any { it.first == "telescope/0/park" }

    @Test
    fun aLostLinkPausesTheSequenceAndItGoesOnWhenTheLinkIsBack() = runTest {
        connected()
        runner.start(lights(6))
        delay(25.seconds)

        seestar.down = true
        delay(35.seconds)
        assertIs<GuardState.Reconnecting>(guard.state.value)
        assertEquals(RunStatus.Paused, runner.state.value?.status)

        // After a minute without the equipment, back within 30 s of it.
        delay(25.seconds)
        seestar.down = false
        delay(30.seconds)
        assertEquals(GuardState.Watching, guard.state.value)
        val run = runner.state.first { it?.status == RunStatus.Finished }!!
        assertEquals(6, run.done.values.single())
        assertTrue(!parked())
        assertTrue(
            events.containsAll(listOf("link_lost", "sequence_paused", "reconnect", "link_back", "sequence_resumed", "sequence_finished")),
        )
    }

    @Test
    fun aSeestarSlowToAnswerForAFewSecondsIsNotALostLink() = runTest {
        connected()
        runner.start(lights(6))
        delay(25.seconds)

        // As while it slews for a point of the polar alignment or hands over a frame.
        seestar.down = true
        delay(20.seconds)
        seestar.down = false
        delay(10.seconds)

        assertEquals(GuardState.Watching, guard.state.value)
        assertTrue("link_lost" !in events)
        assertEquals(RunStatus.Running, runner.state.value?.status)
    }

    @Test
    fun reconnectingLeavesTheDevicesConnected() = runTest {
        connected()
        val timeSent = seestar.puts.count { it.first == "telescope/0/utcdate" }
        runner.start(lights(6))
        delay(25.seconds)

        seestar.down = true
        delay(35.seconds)
        seestar.down = false
        delay(30.seconds)

        // Their connected state is the device's: disconnecting the old connection would disconnect the new one.
        assertTrue("link_back" in events)
        assertTrue(seestar.puts.none { (member, form) -> member.endsWith("/connected") && form["Connected"] == "false" })
        // Nor does it send the time and the site again, as another app may be using the mount meanwhile.
        assertEquals(timeSent, seestar.puts.count { it.first == "telescope/0/utcdate" })
        assertEquals(6, runner.state.first { it?.status == RunStatus.Finished }!!.done.values.single())
    }

    @Test
    fun aSequencePausedByHandStaysPausedWhenTheLinkIsBack() = runTest {
        connected()
        runner.start(lights(6))
        delay(25.seconds)
        runner.pause()

        seestar.down = true
        delay(30.seconds)
        seestar.down = false
        delay(30.seconds)

        assertEquals(GuardState.Watching, guard.state.value)
        assertEquals(RunStatus.Paused, runner.state.value?.status)
        assertTrue("sequence_resumed" !in events)
    }

    @Test
    fun aLinkLostLongerThanTheWaitStopsSafelyOnceItIsBack() = runTest {
        connected()
        runner.start(lights(20))
        delay(15.seconds)

        seestar.down = true
        delay(3.minutes)
        assertEquals(RunStatus.Stopped, runner.state.value?.status)
        assertEquals(GuardState.SafeStop(SafeStopReason.LostLink, done = false, parked = false), guard.state.value)
        assertTrue(!parked())

        seestar.down = false
        delay(30.seconds)
        assertEquals(GuardState.SafeStop(SafeStopReason.LostLink, done = true, parked = true), guard.state.value)
        assertTrue(parked())
    }

    @Test
    fun aSequenceThatFailsStopsTheTrackingAsTheProfileSays() = runTest {
        connected(SafetyOptions(park = false))

        runner.start(lights(2, filter = "Ha"))
        delay(10.seconds)

        assertEquals(RunStatus.Failed, runner.state.value?.status)
        assertEquals(GuardState.SafeStop(SafeStopReason.SequenceFailed, done = true, parked = false), guard.state.value)
        assertEquals("false", seestar.puts.last { it.first == "telescope/0/tracking" }.second["Tracking"])
        assertTrue(!parked())
    }

    @Test
    fun aLowBatteryStopsSafely() = runTest {
        connected()
        runner.start(lights(20))
        delay(15.seconds)

        batteryPercent = 18
        delay(5.seconds)

        assertEquals(RunStatus.Stopped, runner.state.value?.status)
        assertEquals(GuardState.SafeStop(SafeStopReason.LowBattery, done = true, parked = true), guard.state.value)
        assertTrue(parked())
    }

    @Test
    fun withoutASequenceItOnlyReconnects() = runTest {
        connected()

        seestar.down = true
        delay(60.seconds)
        assertIs<GuardState.Reconnecting>(guard.state.value)
        seestar.down = false
        delay(30.seconds)

        assertEquals(GuardState.Watching, guard.state.value)
        assertIs<Connection.Connected>(equipment.state.value.connection)
        assertTrue(!parked())
    }
}

/** No star database: nothing here centres. */
private object NoSolver : PlateSolver {
    override fun installedDatabases() = emptySet<String>()

    override suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest) = null
}
