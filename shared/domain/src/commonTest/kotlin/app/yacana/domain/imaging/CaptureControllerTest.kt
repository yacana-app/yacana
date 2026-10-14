// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.domain.equipment.AltAz
import app.yacana.domain.equipment.Atmosphere
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentPlatform
import app.yacana.domain.equipment.FakeAlpaca
import app.yacana.domain.equipment.fakeHttp
import app.yacana.domain.equipment.j2000FromObserved
import app.yacana.domain.equipment.j2000Place
import app.yacana.domain.equipment.refractedAltitude
import app.yacana.domain.equipment.seestarMount
import app.yacana.domain.equipment.separationArcsec
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.focus.AutofocusState
import app.yacana.domain.polar.PolarAlignment
import app.yacana.domain.polar.PolarAlignmentState
import app.yacana.domain.polar.PolarFailure
import app.yacana.domain.polar.Vector3
import app.yacana.domain.polar.toVector
import app.yacana.domain.profile.AutofocusOptions
import app.yacana.domain.profile.DeviceRef
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileDevices
import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.profile.ProfileStore
import app.yacana.domain.profile.StackingOptions
import app.yacana.domain.session.Journal
import app.yacana.domain.sky.cameraRotation
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.CentringFailure
import app.yacana.domain.solver.CentringState
import app.yacana.domain.solver.PlateSolving
import app.yacana.domain.solver.SolveFailure
import app.yacana.domain.solver.SolveState
import app.yacana.ports.Bayer
import app.yacana.ports.ColourStretch
import app.yacana.ports.DetectedStar
import app.yacana.ports.EngineBuildInfo
import app.yacana.ports.EngineException
import app.yacana.ports.Frame
import app.yacana.ports.FrameStatistics
import app.yacana.ports.HeaderCard
import app.yacana.ports.HeaderValue
import app.yacana.ports.ImageEngine
import app.yacana.ports.PlateSolver
import app.yacana.ports.Preview
import app.yacana.ports.Region
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import app.yacana.ports.SolveRequest
import app.yacana.ports.StarDetection
import app.yacana.ports.Stretch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Suppress("LargeClass") // Every way a frame is taken, saved, shown, stacked and calibrated.
class CaptureControllerTest {
    private val seestar =
        FakeAlpaca("seestar").apply { seestarMount() }.apply { seestarCamera() }.apply {
            // A loop of lights dithers, and waits for each pulse to end.
            values["telescope/0/ispulseguiding"] = "false"
        }
    private val directory = Path(SystemTemporaryDirectory, "yacana-capture-${ProfilePresets.newId()}")
    private val engine = FakeEngine()
    private val store = FakeStore()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val journal = Journal { event, details -> events += event to details }
    private val clock =
        object : Clock {
            override fun now() = Instant.parse("2026-10-04T22:30:00Z")
        }

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private lateinit var equipment: EquipmentController

    private suspend fun TestScope.connected(
        options: ProfileOptions = ProfileOptions(sendTimeAndLocation = false),
        wheel: Boolean = false,
        location: Location? = null,
        focuser: Boolean = false,
        stacking: LiveStacking? = null,
    ): CaptureController {
        val http = fakeHttp(StandardTestDispatcher(testScheduler), seestar)
        val platform =
            object : EquipmentPlatform {
                override fun httpClient() = http

                override val discovery = AlpacaDiscovery { emptyList() }

                override fun networkHandle(): Long = 4242

                override suspend fun location() = null
            }
        val equipment = EquipmentController(ProfileStore(directory), platform, backgroundScope, clock, journal)
        this@CaptureControllerTest.equipment = equipment
        equipment.save(
            Profile(
                id = "p",
                name = "Seestar S50 Pro",
                devices =
                ProfileDevices(
                    mount = DeviceRef(seestar.url, 0, "mount-1", "Seestar Telescope"),
                    camera = DeviceRef(seestar.url, 0, "camera-0", "Seestar Telephoto Camera"),
                    filterWheel = if (wheel) DeviceRef(seestar.url, 0, "filterwheel-0", "Seestar Filter Wheel") else null,
                    focuser = if (focuser) DeviceRef(seestar.url, 0, "focuser-0", "Seestar Focuser") else null,
                ),
                options = options,
                location = location,
            ),
        )
        equipment.connect()
        equipment.state.first { it.connection is Connection.Connected }
        return CaptureController(equipment, engine, store, platform, backgroundScope, "Yacana test", clock, {
            TimeZone.UTC
        }, journal, stacking)
    }

    @Test
    fun aFrameIsExposedDownloadedAndSaved() = runTest {
        val capture = connected()
        capture.update(CaptureSettings(exposureSeconds = 20.0, gain = 80, target = "M 31"))

        capture.capture()
        val state = capture.state.first { it.last != null && it.activity == CaptureActivity.Idle }

        assertEquals(mapOf("Duration" to "20.0", "Light" to "true"), seestar.puts.last { it.first == "camera/0/startexposure" }.second)
        assertEquals(mapOf("Gain" to "80"), seestar.puts.last { it.first == "camera/0/gain" }.second)
        assertEquals("${seestar.url}/api/v1/camera/0/imagearray", engine.urls.single())
        assertEquals(4242L, engine.networks.single())
        assertEquals(Bayer.GRBG, engine.bayers.single())
        val saved = store.saves.single()
        assertEquals("2026-10-04/M 31/LIGHT/2026-10-04_22-30-00_NoFilter_20.00s_0001", saved.first)
        assertEquals(HeaderValue.Text("GRBG"), saved.second.first { it.key == "BAYERPAT" }.value)
        assertEquals("saved/${saved.first}.fits", state.last?.savedAs)
        assertEquals(1, state.saved)
        assertNull(state.error)
    }

    @Test
    fun theJournalRecordsTheConnectionAndEachSavedFrame() = runTest {
        val capture = connected()

        capture.capture()
        capture.state.first { it.last != null && it.activity == CaptureActivity.Idle }

        val names = events.map { it.first }
        assertEquals(listOf("connecting", "connected", "capture_started", "frame_saved", "frame_stars", "capture_stopped"), names)
        assertEquals("saved/${store.saves.single().first}.fits", events.first { it.first == "frame_saved" }.second["file"])
    }

    @Test
    fun theStarsOfTheFrameOnScreenAreFound() = runTest {
        val capture = connected()

        capture.capture()
        val state = capture.state.first { it.stars != null }

        assertEquals(2.4, state.stars?.averageHfr)
        val frame = state.last?.frame as TestFrame
        // The Seestar's 2.9 µm pixels; the test profile has no focal length.
        assertEquals(listOf<Pair<Double?, Double?>>(2.9 to null), frame.scales)
        val event = events.single { it.first == "frame_stars" }.second
        assertEquals(1, event["stars"])
        assertEquals(2.4, event["hfr"])
    }

    @Test
    fun theCamerasSensorStaysInTheProfile() = runTest {
        connected()

        val optics = equipment.state.first { it.active?.optics?.sensorWidthPx != null }.active!!.optics
        assertEquals(2160, optics.sensorWidthPx)
        assertEquals(3840, optics.sensorHeightPx)
        assertEquals(2.9, optics.pixelSizeMicrons)
    }

    @Test
    fun theWheelsFiltersStayInTheProfile() = runTest {
        seestar.seestarFilterWheel(1)
        connected(wheel = true)

        val devices = equipment.state.first { it.active?.devices?.filterNames?.isNotEmpty() == true }.active!!.devices
        assertEquals(listOf("Dark", "IR", "LP"), devices.filterNames)
    }

    @Test
    fun aFrameToSolveLeavesTheDarkFilterForIr() = runTest {
        // The wheel left at Dark after the darks, as on the first night.
        seestar.seestarFilterWheel(0)
        val capture = connected(wheel = true)
        equipment.state.first { it.active?.devices?.filterNames?.isNotEmpty() == true }

        capture.takeForSolving(1.0)

        assertEquals("1", seestar.values["filterwheel/0/position"], "IR, the Seestar's filter for lights")
        assertEquals("IR", events.single { it.first == "dark_filter_left" }.second["filter"])
    }

    @Test
    fun aFrameThroughTheDarkFilterHasNoStars() = runTest {
        seestar.seestarFilterWheel(0)
        val capture = connected(wheel = true)
        equipment.state.first { it.active?.devices?.filterNames?.isNotEmpty() == true }

        capture.capture()
        capture.state.first { it.last != null && it.activity == CaptureActivity.Idle }
        repeat(STAR_WAIT) { if (events.none { it.first == "frame_stars" }) yield() }

        // The noise of a dark is not stars, as the first night showed: "86 stars" through the Dark filter.
        assertEquals("Dark", capture.state.value.last?.capture?.filter)
        assertNull(capture.state.value.stars)
        assertEquals(0, events.single { it.first == "frame_stars" }.second["stars"])
    }

    @Test
    fun theGainStartsAt200NotWhereAnotherAppLeftTheCamera() = runTest {
        // The Seestar app takes its frames at 200; the camera may be left elsewhere, at 80 here.
        seestar.values["camera/0/gain"] = "80"
        val capture = connected()

        assertEquals(200, capture.state.first { it.settings.gain != null }.settings.gain)
        assertNull(capture.state.value.settings.offset, "the Seestar has no offset control")
    }

    @Test
    fun theJournalRecordsOnceHowLongAFrameTookToReachTheScreen() = runTest {
        val capture = connected()

        capture.capture()
        val last = capture.state.first { it.last != null && it.activity == CaptureActivity.Idle }.last!!
        capture.shown(last)
        capture.shown(last)

        val shown = events.filter { it.first == "frame_shown" }
        assertEquals(1, shown.size)
        assertTrue((shown.single().second["shownMs"] as Long) >= 0)
    }

    @Test
    fun theFrameOnScreenIsSolvedNearTheMountFirst() = runTest {
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D50"))
        val solver = FakeSolver(solveNearMount = true)
        val solving = PlateSolving(equipment, capture, solver, backgroundScope, journal)
        capture.capture()
        capture.state.first { it.last != null && it.activity == CaptureActivity.Idle }

        solving.solveLastFrame()
        val state = solving.state.first { it is SolveState.Solved } as SolveState.Solved

        assertEquals(false, state.blind)
        val request = solver.requests.single()
        assertEquals(5.0, request.radiusDeg)
        assertTrue(request.hint != null, "the mount's position is the hint")
        assertEquals(0.0, request.fieldHeightDeg, "the test profile has no focal length")
        assertEquals(1, events.count { it.first == "plate_solve" })
        assertEquals(state.solution.rotationDeg, events.single { it.first == "plate_solve" }.second["rotationDeg"])
        // The camera's rotation stays in the profile, for the framing.
        val rotation = equipment.state.value.active?.optics?.measuredRotationDeg
        assertTrue(rotation != null && kotlin.math.abs(rotation - cameraRotation(183.2, state.frameWidth, state.frameHeight)) < 1e-9)
    }

    @Test
    fun withoutASolutionNearTheMountItSolvesBlind() = runTest {
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D50"))
        val solver = FakeSolver(solveNearMount = false)
        val solving = PlateSolving(equipment, capture, solver, backgroundScope, journal)
        capture.capture()
        capture.state.first { it.last != null && it.activity == CaptureActivity.Idle }

        solving.solveLastFrame()
        val state = solving.state.first { it is SolveState.Solved } as SolveState.Solved

        assertEquals(true, state.blind)
        assertEquals(listOf(5.0, 180.0), solver.requests.map { it.radiusDeg })
        assertEquals(null, solver.requests.last().hint)
    }

    @Test
    fun aMissingDatabaseIsSaid() = runTest {
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D80"))
        val solving = PlateSolving(equipment, capture, FakeSolver(solveNearMount = true), backgroundScope, journal)
        capture.capture()
        capture.state.first { it.last != null && it.activity == CaptureActivity.Idle }

        solving.solveLastFrame()

        assertEquals(SolveState.Failed(SolveFailure.NoDatabase("D80")), solving.state.value)
    }

    @Test
    fun withoutAFrameThereIsNothingToSolve() = runTest {
        val capture = connected()
        val solving = PlateSolving(equipment, capture, FakeSolver(solveNearMount = true), backgroundScope, journal)

        solving.solveLastFrame()

        assertEquals(SolveState.Failed(SolveFailure.NoFrame), solving.state.value)
    }

    /** M 31 in J2000, the centring target. */
    private val m31 = SkyPosition(0.712_22, 41.269)

    @Test
    fun centringSyncsUntilTheTargetIsWithinTheTolerance() = runTest {
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D50"))
        val sky = MisalignedSky(errorRaHours = 0.02, errorDecDeg = 0.3)
        val centring =
            Centring(
                equipment,
                capture,
                PlateSolving(equipment, capture, sky.solver, backgroundScope, journal),
                backgroundScope,
                journal,
                clock,
            )

        centring.centre(m31)
        val end = centring.state.first { it is CentringState.Centred || it is CentringState.Failed }

        assertIs<CentringState.Centred>(end)
        assertTrue(end.attempts <= 3, "${end.attempts} attempts")
        assertTrue(end.offArcsec <= 30.0, "${end.offArcsec}″ off")
        assertTrue(seestar.puts.any { it.first == "telescope/0/synctocoordinates" })
        assertTrue(store.saves.isEmpty(), "centring frames are not saved")
    }

    @Test
    fun aMountThatCannotSyncIsAimedOffTheOtherWay() = runTest {
        seestar.values["telescope/0/cansync"] = "false"
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D50"))
        val sky = MisalignedSky(errorRaHours = -0.01, errorDecDeg = -0.2)
        val centring =
            Centring(
                equipment,
                capture,
                PlateSolving(equipment, capture, sky.solver, backgroundScope, journal),
                backgroundScope,
                journal,
                clock,
            )

        centring.centre(m31)
        val end = centring.state.first { it is CentringState.Centred || it is CentringState.Failed }

        assertIs<CentringState.Centred>(end)
        assertTrue(end.attempts <= 3, "${end.attempts} attempts")
        assertTrue(seestar.puts.none { it.first == "telescope/0/synctocoordinates" })
    }

    @Test
    fun afterASyncTheMountSaysItIsOnTheSolution() = runTest {
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D50"))
        val sky = MisalignedSky(errorRaHours = 0.05, errorDecDeg = 1.0)
        val centring =
            Centring(
                equipment,
                capture,
                PlateSolving(equipment, capture, sky.solver, backgroundScope, journal),
                backgroundScope,
                journal,
                clock,
            )

        // Solved where it really points, a degree from where it believes.
        val off = centring.sync(Solution(j2000Place(sky.pointing(), clock.now()), rotationDeg = 0.0, scaleArcsec = 2.4))

        assertTrue(off <= 30.0, "$off″ off")
        assertTrue(separationArcsec(sky.reported(), sky.pointing()) < 1.0, "the mount now says where it points")
    }

    @Test
    fun aFrameThatDoesNotSolveStopsCentring() = runTest {
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D50"))
        val sky = MisalignedSky(errorRaHours = 0.0, errorDecDeg = 0.0, solves = false)
        val centring =
            Centring(
                equipment,
                capture,
                PlateSolving(equipment, capture, sky.solver, backgroundScope, journal),
                backgroundScope,
                journal,
                clock,
            )

        centring.centre(m31)
        val end = centring.state.first { it is CentringState.Centred || it is CentringState.Failed }

        assertIs<CentringState.Failed>(end)
        assertIs<CentringFailure.NotSolved>(end.reason)
        assertEquals(1, end.attempts)
    }

    /**
     * The fake Seestar's mount as a real one before it is aligned: it believes it points
     * [errorRaHours] and [errorDecDeg] away from where it does, until a sync. Its [solver] finds
     * where it really points, unless it [solves] nothing.
     */
    private inner class MisalignedSky(var errorRaHours: Double, var errorDecDeg: Double, solves: Boolean = true) {
        private val t = "telescope/0/"

        init {
            // The camera's own handler stays: it makes each exposure ready.
            val camera = seestar.onPut
            seestar.onPut = { member, form ->
                camera(member, form)
                when (member) {
                    t + "slewtocoordinatesasync" -> report(
                        form.getValue("RightAscension").toDouble(),
                        form.getValue("Declination").toDouble(),
                    )

                    t + "synctocoordinates" -> {
                        val real = pointing()
                        val ra = form.getValue("RightAscension").toDouble()
                        val dec = form.getValue("Declination").toDouble()
                        errorRaHours = ra - real.raHours
                        errorDecDeg = dec - real.decDeg
                        report(ra, dec)
                    }
                }
            }
        }

        private fun report(ra: Double, dec: Double) {
            seestar.values[t + "rightascension"] = ra.toString()
            seestar.values[t + "declination"] = dec.toString()
        }

        /** Where the mount says it points. */
        fun reported() =
            SkyPosition(seestar.values.getValue(t + "rightascension").toDouble(), seestar.values.getValue(t + "declination").toDouble())

        /** Where the mount really points, in its own system (JNow). */
        fun pointing() = SkyPosition(
            seestar.values.getValue(t + "rightascension").toDouble() - errorRaHours,
            seestar.values.getValue(t + "declination").toDouble() - errorDecDeg,
        )

        val solver =
            object : PlateSolver {
                override fun installedDatabases() = setOf("D50")

                override suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest): Solution? =
                    if (solves) Solution(j2000Place(pointing(), clock.now()), rotationDeg = 0.0, scaleArcsec = 2.4) else null
            }
    }

    private val site = Location(40.0, 0.0, 250.0)

    @Test
    fun polarAlignmentMeasuresTheAxisAndFollowsTheScrews() = runTest {
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D50"), location = site)
        // The axis 0.1° higher than the refracted pole and 0.05° east of north.
        val wedge = TurningMount(AltAz(refractedAltitude(40.0, Atmosphere.STANDARD) + 0.1, 0.05))
        val solving = PlateSolving(equipment, capture, wedge.solver, backgroundScope, journal)
        val polar = PolarAlignment(equipment, capture, solving, backgroundScope, journal, clock, settle = Duration.ZERO)

        polar.start()
        val adjusting = polar.state.first { it is PolarAlignmentState.Adjusting } as PolarAlignmentState.Adjusting

        assertEquals(0.1, adjusting.initial.altitudeDeg, 2.0 / 3600)
        assertEquals(0.05, adjusting.initial.azimuthDeg, 2.0 / 3600)
        assertEquals(2, wedge.turns, "two turns of 15° between three points")
        assertTrue(store.saves.isEmpty(), "the frames are not saved")

        // The screws turn the axis onto the pole: the error follows.
        wedge.correct()
        val done =
            polar.state.first { it is PolarAlignmentState.Adjusting && it.current.totalDeg < 0.05 } as PolarAlignmentState.Adjusting
        assertEquals(0.0, done.current.altitudeDeg, 2.0 / 3600)
        assertEquals(0.0, done.current.azimuthDeg, 2.0 / 3600)

        polar.stop()
        assertEquals(PolarAlignmentState.Idle, polar.state.value)
        // The report reads how the screws left it from the log.
        val finished = events.single { it.first == "polar_alignment_finished" }.second
        assertTrue((finished["totalArcsec"] as Double) < 0.05 * 3600)
        assertEquals(hypot(0.1, 0.05) * 3600, finished["initialTotalArcsec"] as Double, 2.0)
    }

    @Test
    fun withoutASiteThereIsNoPoleToMeasureFrom() = runTest {
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, starDatabase = "D50"))
        val wedge = TurningMount(AltAz(40.1, 0.0))
        val polar =
            PolarAlignment(equipment, capture, PlateSolving(equipment, capture, wedge.solver, backgroundScope, journal), backgroundScope)

        polar.start()

        assertEquals(PolarAlignmentState.Failed(PolarFailure.NoSite), polar.state.first { it is PolarAlignmentState.Failed })
    }

    /**
     * The fake Seestar on a wedge whose axis points at [axis]: turning the right ascension axis
     * turns the telescope around it, and [solver] finds where the telescope points. The clock
     * stands still, so the sky does too.
     */
    private inner class TurningMount(private var axis: AltAz) {
        private val t = "telescope/0/"
        private var direction = AltAz(55.0, 20.0)
        var turns = 0

        init {
            val camera = seestar.onPut
            seestar.onPut = { member, form ->
                camera(member, form)
                if (member == t + "moveaxis" && form["Rate"]?.toDouble() != 0.0) {
                    // Turns 15° at once: the mount reports it, and the telescope turns around the axis.
                    val rate = form.getValue("Rate").toDouble()
                    val ra = seestar.values.getValue(t + "rightascension").toDouble()
                    seestar.values[t + "rightascension"] = ((ra + sign(rate)).mod(24.0)).toString()
                    direction = direction.toVector().rotate(axis.toVector(), -15.0 * sign(rate)).toAltAz()
                    turns++
                }
            }
        }

        /** The screws turn: the axis goes onto the refracted pole, and the telescope with it. */
        fun correct() {
            val pole = AltAz(refractedAltitude(40.0, Atmosphere.STANDARD), 0.0)
            val up = Vector3(0.0, 0.0, 1.0)
            val byAzimuth = pole.azimuthDeg - axis.azimuthDeg
            var turned = direction.toVector().rotate(up, -byAzimuth)
            val tilted = axis.toVector().rotate(up, -byAzimuth)
            val horizontalAxis = up.cross(tilted).unit()
            turned = turned.rotate(horizontalAxis, -(pole.altitudeDeg - axis.altitudeDeg))
            direction = turned.toAltAz()
            axis = pole
        }

        val solver =
            object : PlateSolver {
                override fun installedDatabases() = setOf("D50")

                override suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest): Solution? =
                    Solution(j2000FromObserved(direction, clock.now() + 0.5.seconds, site, Atmosphere.STANDARD), 0.0, 2.4)
            }
    }

    /** The Seestar's telephoto focuser, 0 to 2,600 steps (spike S3), at [position]; a move ends at once. */
    private fun seestarFocuser(position: Int) {
        val f = "focuser/0/"
        seestar.devices += Triple("Focuser", 0, "focuser-0")
        mapOf(
            "connected" to "false",
            "absolute" to "true",
            "maxstep" to "2600",
            "maxincrement" to "2600",
            "tempcompavailable" to "false",
            "temperature" to "12.4",
            "position" to "$position",
            "ismoving" to "false",
        ).forEach { (member, value) -> seestar.values[f + member] = value }
        seestar.notImplemented += f + "stepsize"
        val before = seestar.onPut
        seestar.onPut = { member, form ->
            before(member, form)
            if (member == f + "move") seestar.values[f + "position"] = form.getValue("Position")
        }
    }

    private fun focuserPosition() = seestar.values.getValue("focuser/0/position").toDouble()

    /** The star's HFR as a defocused telescope gives it: a hyperbola with its best focus at [best]. */
    private fun hyperbola(best: Double, a: Double = 1.9, b: Double = 40.0): () -> Double = {
        val x = (best - focuserPosition()) / b
        a * sqrt(1 + x * x)
    }

    @Test
    fun autofocusFindsTheBestFocusAsNinaWould() = runTest {
        seestarFocuser(1500)
        engine.hfr = hyperbola(best = 1505.0)
        val capture =
            connected(
                ProfileOptions(sendTimeAndLocation = false, autofocus = AutofocusOptions(stepSize = 30, exposureSeconds = 3.0)),
                focuser = true,
            )
        val autofocus = Autofocus(equipment, capture, backgroundScope, journal, clock)

        autofocus.start()
        val done = autofocus.state.first { it is AutofocusState.Done || it is AutofocusState.Failed }

        assertIs<AutofocusState.Done>(done, "$done")
        // NINA's search for the hyperbola lands within a step of the true focus.
        assertEquals(1505.0, done.result.position.toDouble(), 1.0)
        assertEquals(done.result.position.toDouble(), focuserPosition(), "the focuser ends at the best focus")
        assertEquals(1.9, done.result.hfr, 0.02)
        // From 4 steps out to 4 steps in, as NINA: 9 points, 30 steps apart, 4 on each side of the lowest.
        assertEquals((1380..1620 step 30).map(Int::toDouble), done.result.points.map { it.position })
        assertEquals(done.result, autofocus.last.value)
        assertTrue(store.saves.isEmpty(), "autofocus frames are not saved")
        // The report reads the fit from the log.
        val logged = events.single { it.first == "autofocus_done" }.second
        assertEquals("Hyperbolic", logged["fitting"])
        assertTrue((logged["rSquared"] as Double) > 0.99, "$logged")
    }

    @Test
    fun aBestFocusFarAwayAddsPointsOnThatSide() = runTest {
        seestarFocuser(1500)
        engine.hfr = hyperbola(best = 1420.0)
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, autofocus = AutofocusOptions(stepSize = 30)), focuser = true)
        val autofocus = Autofocus(equipment, capture, backgroundScope, journal, clock)

        autofocus.start()
        val done = autofocus.state.first { it is AutofocusState.Done || it is AutofocusState.Failed }

        assertIs<AutofocusState.Done>(done, "$done")
        assertEquals(1420.0, done.result.position.toDouble(), 1.0)
        // Four points left of the lowest one.
        assertTrue(done.result.points.count { it.position < 1410 } >= 4, "${done.result.points.map { it.position }}")
    }

    @Test
    fun aFlatCurveFailsAndTheFocuserGoesBack() = runTest {
        seestarFocuser(1500)
        engine.hfr = { 2.0 }
        val capture = connected(ProfileOptions(sendTimeAndLocation = false), focuser = true)
        val autofocus = Autofocus(equipment, capture, backgroundScope, journal, clock)

        autofocus.start()
        val end = autofocus.state.first { it is AutofocusState.Done || it is AutofocusState.Failed }

        assertIs<AutofocusState.Failed>(end)
        assertEquals(1500.0, focuserPosition(), "back where it started")
    }

    @Test
    fun backlashIsTakenUpByOvershooting() = runTest {
        seestarFocuser(1500)
        engine.hfr = hyperbola(best = 1500.0)
        val capture =
            connected(
                ProfileOptions(sendTimeAndLocation = false, autofocus = AutofocusOptions(stepSize = 30, backlashOut = 20)),
                focuser = true,
            )
        val autofocus = Autofocus(equipment, capture, backgroundScope, journal, clock)

        autofocus.start()
        autofocus.state.first { it is AutofocusState.Done || it is AutofocusState.Failed }

        val moves = seestar.puts.filter { it.first == "focuser/0/move" }.map { it.second.getValue("Position").toInt() }
        // Moving out to the first point, it goes 20 past it and back.
        assertEquals(listOf(1640, 1620), moves.take(2))
    }

    /** ASTAP as the tests need it: D50 installed, and a solution blind or, if asked, near the mount. */
    private class FakeSolver(private val solveNearMount: Boolean) : PlateSolver {
        val requests = mutableListOf<SolveRequest>()

        override fun installedDatabases() = setOf("D50")

        override suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest): Solution? {
            requests += request
            // The y axis as ASTAP's CD matrix gives it for the Seestar's frames of M 31: nearly south.
            return if (request.hint == null || solveNearMount) Solution(SkyPosition(0.712, 41.27), 35.2, 2.25, yAxisDeg = 183.2) else null
        }
    }

    @Test
    fun aSnapshotIsNotSavedUnlessAsked() = runTest {
        val capture = connected()
        capture.update(CaptureSettings(exposureSeconds = 1.0, save = false))

        capture.capture()
        val state = capture.state.first { it.last != null && it.activity == CaptureActivity.Idle }

        assertTrue(store.saves.isEmpty())
        assertNull(state.last?.savedAs)
        assertEquals(0, state.saved)
    }

    @Test
    fun aLoopSavesEveryFrameAndFreesTheOldOnes() = runTest {
        val capture = connected()
        capture.update(CaptureSettings(exposureSeconds = 5.0, save = false))

        capture.startLoop()
        capture.state.first { it.saved >= 3 }
        capture.stop()
        val state = capture.state.first { it.activity == CaptureActivity.Idle && !it.looping }

        assertTrue(store.saves.size >= 3, "loops save even with save off: ${store.saves.size}")
        assertEquals(listOf("0001", "0002", "0003"), store.saves.take(3).map { it.first.takeLast(4) })
        assertTrue(engine.frames.dropLast(1).all { it.closed }, "every frame but the one on screen is freed")
        assertTrue(state.last?.frame?.let { !(it as TestFrame).closed } == true)
    }

    @Test
    fun aLoopOfLightsDithersEveryThreeFramesAndOneOfDarksNever() = runTest {
        val capture = connected()
        capture.update(CaptureSettings(exposureSeconds = 5.0))

        capture.startLoop()
        capture.state.first { it.saved >= 7 }
        capture.stop()
        capture.state.first { it.activity == CaptureActivity.Idle && !it.looping }

        // After frames 3 and 6, with the profile's pulse, as the sequence dithers; the walking noise of M 42 had none.
        assertEquals(2, events.count { it.first == "loop_dither" })
        assertTrue(seestar.puts.any { it.first == "telescope/0/pulseguide" })

        events.clear()
        capture.update(CaptureSettings(exposureSeconds = 5.0, type = FrameType.Dark))
        val before = capture.state.value.saved
        capture.startLoop()
        capture.state.first { it.saved >= before + 4 }
        capture.stop()
        capture.state.first { it.activity == CaptureActivity.Idle && !it.looping }

        assertTrue(events.none { it.first == "loop_dither" }, "darks are not dithered")
    }

    @Test
    fun aLoopWaitsForTheSeestarAndGoesOnInsteadOfStopping() = runTest {
        val capture = connected()
        capture.update(CaptureSettings(exposureSeconds = 5.0, save = false))
        capture.startLoop()
        capture.state.first { it.saved >= 2 }

        // Longer than a frame and its margin: the frame fails, as the loops of M 45 and M 42 did on the first night.
        seestar.down = true
        delay(90.seconds)
        assertTrue(capture.state.value.looping, "the loop waits")
        seestar.down = false
        val before = capture.state.value.saved
        capture.state.first { it.saved >= before + 2 }
        capture.stop()

        assertTrue(events.any { it.first == "loop_waiting" } && events.any { it.first == "loop_resumed" })
        assertNull(capture.state.value.error)
    }

    @Test
    fun withLiveStackingOnALoopsLightsGoIntoTheStackWhichIsSavedWhenItEnds() = runTest {
        engine.stacking = true
        val stacking = LiveStacking(engine, store, zone = { TimeZone.UTC })
        val capture = connected(ProfileOptions(sendTimeAndLocation = false, stacking = StackingOptions(live = true)), stacking = stacking)
        // The gain set, since the camera's comes a moment later and another gain is another stack.
        capture.update(CaptureSettings(exposureSeconds = 5.0, gain = 80, target = "M 31"))

        capture.startLoop()
        stacking.state.first { it.frames >= 3 }
        capture.stop()
        capture.state.first { it.activity == CaptureActivity.Idle && !it.looping }

        val state = stacking.state.value
        assertEquals("M 31", state.key?.target)
        assertEquals(capture.history.frames.value.size, state.frames, "every saved light")
        assertEquals(state.frames, state.savedFrames, "the loop's end saves it")
        assertTrue(
            store.stackSaves.single().path.endsWith("/STACK/2026-10-04_22-30-00_NoFilter_5.00s_STACK"),
            store.stackSaves.single().path,
        )
        assertTrue(engine.frames.dropLast(1).all { it.closed }, "frames are still freed once stacked")
        assertEquals(1, engine.stacks.size)
    }

    @Test
    fun darksTakenBeforeLiveStackingIsOnCalibrateTheStackThatComes() = runTest {
        engine.stacking = true
        val stacking = LiveStacking(engine, store, zone = { TimeZone.UTC })
        val capture = connected(stacking = stacking)
        // As on the second night: the sequence's darks first, live stacking turned on with the lights.
        capture.update(CaptureSettings(exposureSeconds = 5.0, gain = 200, type = FrameType.Dark))
        capture.startLoop()
        capture.history.frames.first { it.size >= 3 }
        capture.stop()
        capture.state.first { it.activity == CaptureActivity.Idle && !it.looping }
        val profile = equipment.state.value.active!!
        equipment.save(profile.copy(options = profile.options.copy(stacking = StackingOptions(live = true))))

        capture.update(CaptureSettings(exposureSeconds = 5.0, gain = 200, target = "M 45"))
        capture.startLoop()
        stacking.state.first { it.frames >= 2 }
        capture.stop()
        capture.state.first { it.activity == CaptureActivity.Idle && !it.looping }

        assertEquals(true, stacking.state.value.calibration.dark, "the darks taken before")
    }

    @Test
    fun withLiveStackingOffOrForDarksNothingIsStacked() = runTest {
        engine.stacking = true
        val stacking = LiveStacking(engine, store, zone = { TimeZone.UTC })
        val capture = connected(stacking = stacking)
        capture.update(CaptureSettings(exposureSeconds = 5.0, target = "M 31"))
        capture.startLoop()
        capture.history.frames.first { it.size >= 2 }
        capture.stop()
        capture.state.first { it.activity == CaptureActivity.Idle && !it.looping }

        assertTrue(engine.stacks.isEmpty(), "off at first")
        assertNull(stacking.state.value.key)
    }

    @Test
    fun savedFramesGoIntoTheHistoryWithTheirQuality() = runTest {
        val capture = connected()
        capture.update(CaptureSettings(exposureSeconds = 5.0, gain = 80, save = false))
        capture.capture()
        capture.state.first { it.activity == CaptureActivity.Idle && it.stars != null }
        assertTrue(capture.history.frames.value.isEmpty(), "a frame not saved is not in the history")

        capture.startLoop()
        capture.history.frames.first { it.size >= 2 }
        capture.stop()

        val first = capture.history.frames.value.first()
        assertEquals(1, first.number)
        assertEquals(FrameType.Light, first.type)
        assertEquals(5.0, first.exposureSeconds)
        assertEquals(80, first.gain)
        assertEquals(2.4, first.hfr)
        assertEquals(1, first.stars)
        assertEquals(1000.0, first.background)
        assertEquals("saved/${store.saves.first().first}.fits", first.savedAs)
        assertEquals(64, first.thumbnail?.width)
    }

    @Test
    fun aFailureStopsTheLoopAndSaysWhy() = runTest {
        val capture = connected()
        engine.failure = EngineException("Network: the Seestar did not answer")

        capture.startLoop()
        val state = capture.state.first { it.error != null && it.activity == CaptureActivity.Idle }

        assertEquals("Network: the Seestar did not answer", state.error)
        assertTrue(!state.looping)
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun darksAndBiasUseTheDarkFilterAndLightsGoBack() = runTest {
        seestar.seestarFilterWheel(position = 1)
        val capture = connected(wheel = true)

        capture.setType(FrameType.Dark)
        awaitWheel("0")
        capture.setType(FrameType.Bias)
        awaitWheel("0")
        capture.setType(FrameType.Light)
        awaitWheel("1")

        assertEquals(FrameType.Light, capture.state.value.settings.type)
        assertEquals(2, seestar.puts.count { it.first == "filterwheel/0/position" }, "to Dark and back to IR only")
    }

    @Test
    fun imagingTakesDarksForTheStackBehindTheDarkFilterAndGoesBack() = runTest {
        seestar.seestarFilterWheel(position = 1)
        engine.stacking = true
        val stacking = LiveStacking(engine, store, zone = { TimeZone.UTC })
        val capture =
            connected(
                ProfileOptions(sendTimeAndLocation = false, stacking = StackingOptions(live = true)),
                wheel = true,
                stacking = stacking,
            )
        capture.update(CaptureSettings(exposureSeconds = 5.0, gain = 80, target = "M 31"))
        assertEquals(0, capture.darkFilter())

        capture.takeDarks(3)
        capture.state.first { it.darks?.done == 3 }
        capture.state.first { it.darks == null && it.activity == CaptureActivity.Idle }

        val darks = store.saves.filter { (_, headers) -> headers.any { it.key == "IMAGETYP" && it.value == HeaderValue.Text("DARK") } }
        assertEquals(3, darks.size)
        assertTrue(darks.all { (path, _) -> "/DARK/" in path }, darks.toString())
        awaitWheel("1")
        assertEquals(FrameType.Light, capture.state.value.settings.type, "Imaging still takes lights")
        assertEquals(3, (engine.masters.single()).frames, "one set of darks for the stack")
    }

    @Test
    fun aLightLeavesTheDarkFilterForTheFirstOtherOne() = runTest {
        seestar.seestarFilterWheel(position = 0)
        val capture = connected(wheel = true)

        capture.setType(FrameType.Flat)

        awaitWheel("1")
    }

    @Test
    fun aFilterChangeMovesTheFocuserByTheNewOffsetMinusTheOldOne() = runTest {
        seestar.seestarFilterWheel(position = 1)
        seestarFocuser(1500)
        val offsets = mapOf("Dark" to -5, "LP" to 12)
        connected(
            ProfileOptions(sendTimeAndLocation = false, autofocus = AutofocusOptions(filterOffsets = offsets)),
            wheel = true,
            focuser = true,
        )
        val wheel = (equipment.state.value.connection as Connection.Connected).equipment.filterWheel!!

        wheel.select(2)
        assertEquals(1512.0, focuserPosition(), "IR at 0 to LP at +12")
        wheel.select(0)
        assertEquals(1495.0, focuserPosition(), "LP to Dark at −5")
        wheel.select(0)
        assertEquals(1495.0, focuserPosition(), "the same filter does not move it")
        val profile = equipment.state.value.active!!
        equipment.save(profile.copy(options = profile.options.copy(autofocus = AutofocusOptions(filterOffsets = mapOf("LP" to 20)))))
        wheel.select(2)
        assertEquals(1515.0, focuserPosition(), "offsets changed while connected count")
    }

    /** Waits, in the test's time, for the wheel to reach [position]. */
    private suspend fun awaitWheel(position: String) = withTimeout(10.seconds) {
        while (seestar.values["filterwheel/0/position"] != position) delay(100.milliseconds)
    }

    @Test
    fun withoutACameraItSaysSo() = runTest {
        val http = fakeHttp(StandardTestDispatcher(testScheduler), seestar)
        val platform =
            object : EquipmentPlatform {
                override fun httpClient() = http

                override val discovery = AlpacaDiscovery { emptyList() }

                override fun networkHandle(): Long? = null

                override suspend fun location() = null
            }
        val equipment = EquipmentController(ProfileStore(directory), platform, backgroundScope, clock)
        val capture = CaptureController(equipment, engine, store, platform, backgroundScope, "Yacana test", clock)

        capture.capture()

        assertEquals("Connect a camera in Equipment first", capture.state.first { it.error != null }.error)
    }
}

/** Turns of the test's dispatcher for a frame's stars to be found once it is shown. */
private const val STAR_WAIT = 100
