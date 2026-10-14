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
import app.yacana.domain.profile.Location
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The fake Seestar, its journal and saves, and a runner with them, for the sequence's tests. */
abstract class RunnerFixture {
    internal val seestar =
        FakeAlpaca("seestar").apply {
            seestarMount()
            seestarCamera()
            seestarFilterWheel(1)
            dewHeater()
            values["telescope/0/ispulseguiding"] = "false"
            // Parking and unparking happen at once; the camera's own reaction stays.
            val camera = onPut
            onPut = { member, form ->
                camera(member, form)
                when (member) {
                    "telescope/0/park" -> values["telescope/0/atpark"] = "true"
                    "telescope/0/unpark" -> values["telescope/0/atpark"] = "false"
                }
            }
        }
    internal val directory = Path(SystemTemporaryDirectory, "yacana-sequence-${ProfilePresets.newId()}")
    internal val store = FakeStore()
    internal val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    internal val journal = Journal { event, details -> events += event to details }
    internal val clock =
        object : Clock {
            override fun now() = TEST_NIGHT
        }

    /** M 31 in J2000, where the fake solver finds every frame. */
    internal val m31 = SkyTarget("M 31", 0.712, 41.27)

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    internal suspend fun TestScope.runner(
        progress: Path? = null,
        clock: Clock = this@RunnerFixture.clock,
        location: Location? = null,
    ): SequenceRunner {
        val http = fakeHttp(StandardTestDispatcher(testScheduler), seestar)
        val platform =
            object : EquipmentPlatform {
                override fun httpClient() = http

                override val discovery = AlpacaDiscovery { emptyList() }

                override fun networkHandle(): Long? = null

                override suspend fun location() = null
            }
        val equipment = EquipmentController(ProfileStore(directory), platform, backgroundScope, clock, journal)
        equipment.save(
            Profile(
                id = "p",
                name = "Seestar S50 Pro",
                devices =
                ProfileDevices(
                    mount = DeviceRef(seestar.url, 0, "mount-1", "Seestar Telescope"),
                    camera = DeviceRef(seestar.url, 0, "camera-0", "Seestar Telephoto Camera"),
                    filterWheel = DeviceRef(seestar.url, 0, "filterwheel-0", "Seestar Filter Wheel"),
                    switches = DeviceRef(seestar.url, 0, "switch-0", "Seestar Switch"),
                ),
                options = ProfileOptions(sendTimeAndLocation = false),
                location = location,
            ),
        )
        equipment.connect()
        equipment.state.first { it.connection is Connection.Connected }
        val capture =
            CaptureController(equipment, FakeEngine(), store, platform, backgroundScope, "Yacana test", clock, { TimeZone.UTC }, journal)
        val solving = PlateSolving(equipment, capture, M31Solver(), backgroundScope, journal)
        val centring = Centring(equipment, capture, solving, backgroundScope, journal, clock, settle = Duration.ZERO)
        val autofocus = Autofocus(equipment, capture, backgroundScope, journal, clock)
        return SequenceRunner(equipment, capture, centring, autofocus, backgroundScope, journal, clock, Random(7), progress) {
            TimeZone.UTC
        }
    }

    internal suspend fun SequenceRunner.runToTheEnd(sequence: Sequence): SequenceRun {
        start(sequence)
        return state.first { it != null && it.status != RunStatus.Running }!!
    }

    internal fun items() = events.filter { it.first == "sequence_item" }.map { it.second["item"].toString() }

    internal fun saved(type: FrameType) = store.saves.filter { (_, headers) -> headers.text("IMAGETYP") == type.fits }

    internal fun List<HeaderCard>.text(key: String) = (first { it.key == key }.value as? HeaderValue.Text)?.value

    internal fun List<HeaderCard>.real(key: String) = (first { it.key == key }.value as? HeaderValue.Real)?.value

    /** A clock from [TEST_NIGHT] that goes on with the test's virtual time. */
    internal fun TestScope.movingClock(): Clock = object : Clock {
        override fun now() = TEST_NIGHT + testScheduler.currentTime.milliseconds
    }
}

/** When the tests' night is: half past ten in the evening, UTC. */
internal val TEST_NIGHT = Instant.parse("2026-10-04T22:30:00Z")

/** The Seestar's dew heater as spike S3 found it: one switch, on or off. */
private fun FakeAlpaca.dewHeater() {
    devices += Triple("Switch", 0, "switch-0")
    mapOf(
        "connected" to "false",
        "maxswitch" to "1",
        "getswitchname" to "\"Dew heater\"",
        "getswitchdescription" to "\"\"",
        "canwrite" to "true",
        "minswitchvalue" to "0",
        "maxswitchvalue" to "1",
        "switchstep" to "1",
        "getswitchvalue" to "0",
    ).forEach { (member, value) -> values["switch/0/$member"] = value }
}

/** ASTAP finding every frame at M 31. */
private class M31Solver : PlateSolver {
    override fun installedDatabases() = setOf("D50")

    override suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest) =
        Solution(SkyPosition(0.712, 41.27), 35.2, 2.25, yAxisDeg = 183.2)
}
