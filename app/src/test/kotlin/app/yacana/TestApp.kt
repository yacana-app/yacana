// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.alpaca.CalibratorStatus
import app.yacana.alpaca.CoverStatus
import app.yacana.alpaca.DiscoveryResponse
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentPlatform
import app.yacana.domain.equipment.FlatPanelState
import app.yacana.domain.flats.FilterFlats
import app.yacana.domain.flats.FlatSource
import app.yacana.domain.flats.FlatsAssistant
import app.yacana.domain.flats.FlatsOptions
import app.yacana.domain.flats.FlatsPhase
import app.yacana.domain.flats.FlatsState
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.focus.AutofocusResult
import app.yacana.domain.focus.AutofocusState
import app.yacana.domain.focus.AutofocusStep
import app.yacana.domain.focus.FocusFitting
import app.yacana.domain.focus.FocusPoint
import app.yacana.domain.focus.finalFocus
import app.yacana.domain.imaging.CaptureActivity
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.CaptureSettings
import app.yacana.domain.imaging.DiscardReason
import app.yacana.domain.imaging.FrameStore
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.imaging.HistoryFrame
import app.yacana.domain.imaging.LiveStacking
import app.yacana.domain.polar.MeasureStep
import app.yacana.domain.polar.PolarAlignment
import app.yacana.domain.polar.PolarAlignmentState
import app.yacana.domain.polar.PolarError
import app.yacana.domain.profile.AutofocusOptions
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.HorizonPoint
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.ProfileStore
import app.yacana.domain.profile.SafetyOptions
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Condition
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SavedSequences
import app.yacana.domain.sequence.Sequence
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.SequenceRunner
import app.yacana.domain.sequence.Target
import app.yacana.domain.sequence.Targets
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.areas
import app.yacana.domain.sequence.tonight
import app.yacana.domain.session.GuardState
import app.yacana.domain.session.JournalLines
import app.yacana.domain.session.SafeStopReason
import app.yacana.domain.session.nightDiary
import app.yacana.domain.session.nightSummary
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.sky.Favourites
import app.yacana.domain.sky.Framing
import app.yacana.domain.sky.InstalledCache
import app.yacana.domain.sky.MapLayer
import app.yacana.domain.sky.NINA_CACHES
import app.yacana.domain.sky.NameResolver
import app.yacana.domain.sky.NinaCache
import app.yacana.domain.sky.ONLINE_CACHE
import app.yacana.domain.sky.ObjectSearch
import app.yacana.domain.sky.OnlinePhoto
import app.yacana.domain.sky.SkyCacheFiles
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.sky.SkyLayer
import app.yacana.domain.solver.Arrival
import app.yacana.domain.solver.ArrivalStep
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.PlateSolving
import app.yacana.domain.solver.StarDatabase
import app.yacana.domain.solver.StarDatabaseFiles
import app.yacana.domain.solver.StarDatabaseStore
import app.yacana.domain.solver.StarDatabases
import app.yacana.ports.Bayer
import app.yacana.ports.EngineBuildInfo
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.ImageEngine
import app.yacana.ports.LiveStack
import app.yacana.ports.PlateSolver
import app.yacana.ports.Preview
import app.yacana.ports.SkyCache
import app.yacana.ports.SkyPosition
import app.yacana.ports.SkyTile
import app.yacana.ports.Solution
import app.yacana.ports.SolveRequest
import app.yacana.ui.AppInfo
import app.yacana.ui.AppPermission
import app.yacana.ui.AppServices
import app.yacana.ui.Beeper
import app.yacana.ui.DisplaySettings
import app.yacana.ui.FilePicker
import app.yacana.ui.FirstSteps
import app.yacana.ui.FirstStepsScreen
import app.yacana.ui.FrameFolder
import app.yacana.ui.LogExport
import app.yacana.ui.PermissionStatus
import app.yacana.ui.PermissionsController
import app.yacana.ui.Section
import app.yacana.ui.YacanaApp
import app.yacana.ui.design.ComponentGallery
import app.yacana.ui.equipment.FlatPanelActions
import app.yacana.ui.equipment.FlatPanelContent
import app.yacana.ui.equipment.SafetyMonitorContent
import app.yacana.ui.flats.FlatsProgress
import app.yacana.ui.flats.FlatsSetup
import app.yacana.ui.focus.AutofocusContent
import app.yacana.ui.imaging.FrameViewer
import app.yacana.ui.imaging.Histogram
import app.yacana.ui.navigation.Navigator
import app.yacana.ui.navigation.Screen
import app.yacana.ui.night.NightSummaryCards
import app.yacana.ui.night.RunningButtons
import app.yacana.ui.night.RunningNight
import app.yacana.ui.night.SessionAlert
import app.yacana.ui.night.SessionBar
import app.yacana.ui.night.SummaryButtons
import app.yacana.ui.polar.PolarAlignmentContent
import app.yacana.ui.settings.LanguageSettings
import app.yacana.ui.settings.SystemLanguage
import app.yacana.ui.theme.YacanaTheme
import app.yacana.ui.theme.YacanaThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import android.graphics.Color as AndroidColor

/**
 * The app as the tests build it: the fake Seestar on the WiFi, a fake engine that returns
 * [fakeFrame], a store that keeps nothing, a fixed clock in Madrid and ASTAP finding M 31, in
 * [mode]; the profiles kept in [directory].
 */
internal class TestApp(
    private val scope: CoroutineScope,
    private val directory: File,
    private val mode: YacanaThemeMode = YacanaThemeMode.Dark,
) {
    val fakeFrame = FakeFrame()

    /** The live stack the fake engine hands out, once stacking is on in the profile. */
    val fakeStack = FakeStack()

    /** The Seestar on the WiFi, which keeps what it is sent. */
    val seestar = FakeSeestar()

    /** How many times the session log was exported. */
    var logExports = 0
        private set

    /** The flats' beeps so far: false as they start, true as they end. */
    val beeps = mutableListOf<Boolean>()

    /** What the star databases' store was asked, as "download D05". */
    val databaseRequests = mutableListOf<String>()

    private fun asked(request: String) {
        synchronized(databaseRequests) { databaseRequests += request }
    }

    fun equipment(): EquipmentController = EquipmentController(ProfileStore(Path(directory.path)), platform, scope)

    val platform: EquipmentPlatform by lazy {
        object : EquipmentPlatform {
            override fun httpClient() = seestar.http

            override fun networkHandle(): Long? = null

            override val discovery = AlpacaDiscovery { listOf(DiscoveryResponse("10.0.0.1", 32323)) }

            override suspend fun location() = Location(latitude = 40.4, longitude = -3.7, elevationM = 650.0)
        }
    }

    /** Takes one frame of M 31 as the Imaging section would, with the fake engine. */
    fun capture(
        capture: CaptureController,
        settings: CaptureSettings = CaptureSettings(exposureSeconds = 20.0, gain = 80, target = "M 31"),
    ) = runBlocking {
        capture.update(settings)
        val before = capture.state.value.last
        withTimeout(TEST_WAIT_MILLIS) {
            // A capture still ending, as the frame before is stacked, ignores a new one: ask until this frame shows.
            while (capture.state.value.last === before) {
                capture.capture()
                delay(CAPTURE_RETRY)
            }
            capture.state.first { it.activity == CaptureActivity.Idle }
        }
    }

    /** Connects and waits until every device has published its first values. */
    fun connect(equipment: EquipmentController) = runBlocking {
        equipment.connect()
        withTimeout(TEST_WAIT_MILLIS) {
            val connected = equipment.state.first { it.connection is Connection.Connected }.connection as Connection.Connected
            with(connected.equipment) {
                mount?.state?.first { it.value != null }
                camera?.state?.first { it.value != null }
                focuser?.state?.first { it.value != null }
                filterWheel?.position?.first { it.value != null }
                switches?.values?.first { it.value != null }
            }
        }
    }

    fun services(
        equipment: EquipmentController,
        firstStepsDone: Boolean,
        lastAutofocus: AutofocusResult?,
        skyCaches: SkyCacheStore,
        favourites: Set<String> = emptySet(),
        framed: Boolean = false,
        advanced: Boolean = false,
        resolver: NameResolver? = null,
        languages: LanguageSettings = SystemLanguage(),
        locationAllowed: Boolean = false,
    ): AppServices {
        val display = display()
        val permissions = permissions(locationAllowed)
        val files = files()
        val engine =
            object : ImageEngine {
                override fun buildInfo() = TEST_APP_INFO.engine

                override suspend fun download(imageArrayUrl: String, network: Long?, bayer: Bayer?, timeout: Duration): Frame = fakeFrame

                override fun newStack(): LiveStack = fakeStack
            }
        val store =
            object : FrameStore {
                override val location = MutableStateFlow("/storage/emulated/0/Astro")

                override suspend fun save(frame: Frame, relativePath: String, headers: List<HeaderCard>) =
                    "${location.value}/$relativePath.fits"

                override suspend fun saveStack(stack: LiveStack, relativePath: String, headers: List<HeaderCard>, balanced: Boolean) =
                    "${location.value}/$relativePath.fits"
            }
        // A fixed clock, so the saved file's name is the same in every run.
        val clock =
            object : Clock {
                override fun now() = TEST_NOW
            }
        val madrid = TimeZone.of("Europe/Madrid")
        val stacking = LiveStacking(engine, store, zone = { madrid })
        val capture =
            CaptureController(equipment, engine, store, platform, scope, "Yacana 0.1.0", clock, zone = { madrid }, stacking = stacking)
        val folder =
            object : FrameFolder {
                override val location = store.location

                override fun change() = Unit
            }
        // ASTAP with D50 on the phone, finding M 31 as on the Seestar frames of spike S1.
        val solver =
            object : PlateSolver {
                override fun installedDatabases() = setOf("D50")

                override suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest) =
                    Solution(SkyPosition(raHours = 0.712_22, decDeg = 41.269), rotationDeg = 35.2, scaleArcsec = 2.248, yAxisDeg = 183.2)
            }
        val solving = PlateSolving(equipment, capture, solver, scope)
        val databases = starDatabases(equipment)
        val centring = Centring(equipment, capture, solving, scope)
        val polar = PolarAlignment(equipment, capture, solving, scope)
        val autofocus = Autofocus(equipment, capture, scope, previous = lastAutofocus)
        // The catalogue the app carries, read from where the build makes it; nothing found online before.
        val objects =
            ObjectSearch({ File(TEST_CATALOGUE).readText() }, Path(directory.path, "found_objects.tsv"), resolver, scope)
                .also { search -> runBlocking { search.objects.first { it != null } } }
        return AppServices(
            display = display,
            permissions = permissions,
            files = files,
            firstSteps = firstSteps(firstStepsDone),
            equipment = equipment,
            capture = capture,
            plateSolving = solving,
            starDatabases = databases,
            centring = centring,
            polarAlignment = polar,
            autofocus = autofocus,
            sequence = SequenceRunner(equipment, capture, centring, autofocus, scope),
            flats = FlatsAssistant(equipment, capture, scope),
            objects = objects,
            favourites = Favourites().apply { favourites.forEach(::toggle) },
            targets = plan(framed, advanced),
            savedSequences = saved(framed),
            skyCaches = skyCaches,
            frameFolder = folder,
            logExport = LogExport { logExports++ },
            beeper = Beeper { beeps += it },
            clock = clock,
            zone = { madrid },
            languages = languages,
        )
    }

    /** The first steps, [done] or not. */
    fun firstSteps(done: Boolean) = object : FirstSteps {
        override val done = MutableStateFlow(done)

        override fun finish() = Unit
    }

    /** A file picker that picks nothing. */
    fun files() = object : FilePicker {
        override fun save(suggestedName: String, text: String) = Unit

        override fun open(onText: (String) -> Unit) = Unit

        override fun openFile(onPicked: (String) -> Unit) = Unit

        override fun openFolder(onPicked: (String) -> Unit) = Unit

        override fun newZip(suggestedName: String, onPicked: (String) -> Unit) = Unit
    }

    /** Dark or red as the case says, and the stars unmarked. */
    fun display(): DisplaySettings = object : DisplaySettings {
        override val themeMode = MutableStateFlow(mode)
        override val redBrightness = MutableStateFlow(0.1f)

        override fun setThemeMode(mode: YacanaThemeMode) = Unit

        override fun setRedBrightness(value: Float) = Unit

        override val markStars = MutableStateFlow(false)

        override fun setMarkStars(on: Boolean) {
            markStars.value = on
        }

        override val markedStars = MutableStateFlow(DisplaySettings.DEFAULT_MARKED_STARS)

        override fun setMarkedStars(count: Int) {
            markedStars.value = count
        }

        override val showStack = MutableStateFlow(true)

        override fun setShowStack(on: Boolean) {
            showStack.value = on
        }
    }

    /**
     * Some granted and some not, so each look shows; the location only if [locationAllowed], as
     * with it the app takes the phone's location in place of each scene's site.
     */
    fun permissions(locationAllowed: Boolean = false): PermissionsController = object : PermissionsController {
        override val statuses =
            MutableStateFlow(
                listOf(
                    PermissionStatus(AppPermission.LocalNetwork, granted = true),
                    PermissionStatus(AppPermission.Location, granted = locationAllowed),
                    PermissionStatus(AppPermission.Notifications, granted = true),
                    PermissionStatus(AppPermission.Battery, granted = false),
                    PermissionStatus(AppPermission.KeepWhenUnused, granted = true),
                    PermissionStatus(AppPermission.Autostart, granted = false),
                ),
            )

        override fun request(permission: AppPermission) = Unit
    }

    /** D50 on the phone and D80 on its way, as in the design "Settings · plate solving (lower)". */
    fun starDatabases(equipment: EquipmentController): StarDatabases {
        val store =
            object : StarDatabaseStore {
                override val state =
                    MutableStateFlow(
                        StarDatabaseFiles(
                            installed = setOf("D50"),
                            arriving = mapOf("D80" to Arrival("", ArrivalStep.Downloading, 425_000_000, 1_213_392_756)),
                            freeBytes = 21_300_000_000,
                        ),
                    )

                override fun download(database: StarDatabase, owner: String) = asked("download ${database.name}")

                override fun cancel(name: String) = asked("cancel $name")

                override suspend fun import(location: String) = "D50"

                override fun delete(name: String) = asked("delete $name")
            }
        return StarDatabases(equipment, store, scope)
    }
}

/** The app as the tests show it: version 0.1.0, with its engine for the phone. */
internal val TEST_APP_INFO = AppInfo(versionName = "0.1.0", engine = EngineBuildInfo(version = "0.1.0", arch = "aarch64"))

/** The tests' time: half past ten at night in October, UTC. */
internal val TEST_NOW: Instant = Instant.parse("2026-10-04T22:30:00Z")

/** The catalogue the app carries, from the app module's folder, where the tests run. */
internal const val TEST_CATALOGUE = "../shared/ui/src/commonMain/composeResources/files/catalogue.tsv"

/** How often a test asks again for a frame while the capture before is still ending. */
private val CAPTURE_RETRY = 20.milliseconds

/** How long a test waits at most for the app to show what it waits for: CI's runners are much slower than a PC. */
internal const val TEST_WAIT_MILLIS = 30_000L

/** As "Sequence · my sequences": each of tonight's targets saved on its own, when it has them. */
internal fun saved(framed: Boolean) = SavedSequences().apply {
    if (framed) tonightTargets().forEach { target -> save(tonight(listOf(target), null, focuser = false), listOf(target)) }
}

/** Tonight's plan: tonight's targets when [framed], and the advanced night with them when [advanced]. */
internal fun plan(framed: Boolean, advanced: Boolean) = Targets().apply {
    if (framed) tonightTargets().forEach(::add)
    if (advanced) setAdvanced(advancedTonight())
}

/** Tonight's targets with M 33 repeated until 02:00, as the advanced editor leaves it. */
internal fun advancedTonight(): Sequence {
    val areas = tonight(tonightTargets(), null, focuser = true).areas()
    val m33 = (areas.targets.items[1] as Block).copy(conditions = listOf(Condition.UntilTime(120)))
    return areas.copy(targets = areas.targets.copy(items = listOf(areas.targets.items[0], m33))).toSequence("Tonight")
}

/**
 * Tonight's targets as the design "Sequence · tonight, before starting": M 31 in a mosaic of
 * 2, and M 33, which ends after dawn.
 */
internal fun tonightTargets() = listOf(
    Target.of(
        "M 31",
        Framing(SkyPosition(0.7123, 41.27), 125.0, 1.35 to 2.40, columns = 2),
        listOf(Exposures(45, 60.0, "IR", ditherEvery = 3), Exposures(15, 60.0, "LP", ditherEvery = 3)),
    ),
    Target.of("M 33", Framing(SkyPosition(1.5641, 30.66), 23.0, 1.35 to 2.40), listOf(Exposures(250, 60.0, "IR", ditherEvery = 3))),
)
