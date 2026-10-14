// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.alpaca.AlpacaClient
import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.alpaca.server
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentPlatform
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.FakeEngine
import app.yacana.domain.imaging.FakeStore
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.profile.ProfileStore
import app.yacana.domain.session.Journal
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.PlateSolving
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.PlateSolver
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import app.yacana.ports.SolveRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The advanced sequencer against OmniSim when OMNISIM_URL is set: each kind of block and
 * condition, run in N.I.N.A.'s order with the simulated mount, camera, wheel and focuser.
 */
class AdvancedSequenceOmniSimTest {
    private val url = System.getenv("OMNISIM_URL").orEmpty()

    /** Polaris, some 40° high from Madrid all night. */
    private val polaris = SkyTarget("Polaris", 2.53, 89.26)

    @Test
    fun eachKindOfBlockAndConditionRunsInOrder() {
        if (url.isBlank()) {
            println("OMNISIM_URL is not set: skipping the OmniSim integration test")
            return
        }
        HttpClient(OkHttp) { alpacaDefaults() }.use { http ->
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val events = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, Any?>>>())
                val journal = Journal { event, details -> events += event to details }
                val equipment = connect(http, scope)
                val connected = (equipment.state.value.connection as Connection.Connected).equipment
                val runner = runner(http, equipment, scope, journal)
                val position = connected.focuser!!.info.maxStep / 2
                try {
                    runner.start(night(connected.filterWheel!!.names[2], position))
                    val run = withTimeout(5.minutes) { runner.state.first { it != null && it.status != RunStatus.Running } }!!

                    assertEquals(RunStatus.Finished, run.status, run.error)
                    val notes = events.filter { it.first == "sequence_note" }.map { it.second["text"] }
                    assertEquals(listOf("start", "round", "round", "until", "until", "until", "end"), notes)
                    val skipped = events.filter { it.first == "sequence_block_skipped" }.map { it.second["name"] }
                    assertEquals(listOf("Too low", "Past"), skipped)
                    assertEquals(2, connected.filterWheel!!.wheel.position())
                    assertEquals(position, connected.focuser!!.focuser.position())
                } finally {
                    equipment.disconnect()
                    scope.cancel()
                }
            }
        }
    }

    /** OmniSim's devices as a profile in Madrid, connected. */
    private suspend fun connect(http: HttpClient, scope: CoroutineScope): EquipmentController {
        val equipment =
            EquipmentController(
                ProfileStore(Path(SystemTemporaryDirectory, "yacana-omnisim-${ProfilePresets.newId()}")),
                platform(http),
                scope,
            )
        equipment.save(ProfilePresets.forServer(AlpacaClient(http, url).server()).copy(location = Location(40.42, -3.70, 650.0)))
        equipment.connect()
        withTimeout(30.seconds) { equipment.state.first { it.connection is Connection.Connected } }
        return equipment
    }

    /** A runner with OmniSim's equipment, without plate solving, on a clock at ten in the evening whenever the test runs. */
    private fun runner(http: HttpClient, equipment: EquipmentController, scope: CoroutineScope, journal: Journal): SequenceRunner {
        val utc = { TimeZone.UTC }
        val capture =
            CaptureController(equipment, FakeEngine(), FakeStore(), platform(http), scope, "Yacana test", Clock.System, utc, journal)
        val centring = Centring(equipment, capture, PlateSolving(equipment, capture, NoSolver, scope, journal), scope, journal)
        val utcHour = Clock.System.now().toLocalDateTime(TimeZone.UTC).hour
        val zone = FixedOffsetTimeZone(UtcOffset(hours = (22 - utcHour + 36) % 24 - 12))
        return SequenceRunner(equipment, capture, centring, Autofocus(equipment, capture, scope, journal), scope, journal, zone = { zone })
    }

    private fun platform(http: HttpClient) = object : EquipmentPlatform {
        override fun httpClient() = http

        override val discovery = AlpacaDiscovery { emptyList() }

        override fun networkHandle(): Long? = null

        override suspend fun location() = null
    }

    /** Each kind of block and condition: the targets' area once, Polaris twice while high, two skipped, three rounds until a time. */
    private fun night(filter: String, position: Int): Sequence = NightAreas(
        start = Block(START, listOf(Unpark, Note("start"))),
        targets =
        Block(
            TARGETS,
            listOf(
                Block(
                    "Polaris",
                    listOf(
                        GoToTarget(centre = false),
                        SwitchFilter(filter),
                        Exposures(count = 1, exposureSeconds = 1.0),
                        Block("Together", listOf(WaitFor(0.05), MoveFocuser(position)), parallel = true),
                        Note("round"),
                    ),
                    target = polaris,
                    conditions = listOf(Condition.WhileAbove(10.0), Condition.Loop(2)),
                ),
                Block(
                    "Too low",
                    listOf(Note("never")),
                    target = polaris,
                    conditions = listOf(Condition.WhileAbove(85.0)),
                ),
                Block(
                    "Until midnight",
                    listOf(Note("until")),
                    conditions = listOf(Condition.UntilTime(23 * 60 + 59), Condition.Loop(3)),
                ),
                Block("Past", listOf(Note("past")), conditions = listOf(Condition.UntilTime(21 * 60))),
            ),
            conditions = listOf(Condition.Loop(1)),
        ),
        end = Block(END, listOf(StopTracking, Park, Note("end"))),
    ).toSequence("OmniSim")
}

/** No plate solving: this sequence only slews. */
private object NoSolver : PlateSolver {
    override fun installedDatabases() = emptySet<String>()

    override suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest): Solution = error("not solved")
}
