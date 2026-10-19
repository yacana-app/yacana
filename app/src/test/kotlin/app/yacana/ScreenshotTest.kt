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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
import app.yacana.domain.imaging.MosaicPanel
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
import app.yacana.domain.profile.StackingOptions
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
import app.yacana.domain.sky.AtlasFilters
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.sky.Favourites
import app.yacana.domain.sky.FrameSky
import app.yacana.domain.sky.Framing
import app.yacana.domain.sky.InstalledCache
import app.yacana.domain.sky.MapLayer
import app.yacana.domain.sky.NINA_CACHES
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
import app.yacana.ports.PlateSolver
import app.yacana.ports.Preview
import app.yacana.ports.Region
import app.yacana.ports.SkyCache
import app.yacana.ports.SkyPosition
import app.yacana.ports.SkyTile
import app.yacana.ports.Solution
import app.yacana.ports.SolveRequest
import app.yacana.ui.AppInfo
import app.yacana.ui.AppPermission
import app.yacana.ui.AppServices
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
import app.yacana.ui.imaging.toImageBitmap
import app.yacana.ui.navigation.Navigator
import app.yacana.ui.navigation.Screen
import app.yacana.ui.night.NightSummaryCards
import app.yacana.ui.night.RunningButtons
import app.yacana.ui.night.RunningNight
import app.yacana.ui.night.SessionAlert
import app.yacana.ui.night.SessionBar
import app.yacana.ui.night.StackCard
import app.yacana.ui.night.SummaryButtons
import app.yacana.ui.polar.PolarAlignmentContent
import app.yacana.ui.settings.AppLanguage
import app.yacana.ui.theme.YacanaTheme
import app.yacana.ui.theme.YacanaThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import android.graphics.Color as AndroidColor

/**
 * Reference screenshots of the app in both themes and orientations.
 *
 * - `./gradlew recordRoborazziDebug` records them into src/test/screenshots, and CI verifies them.
 * - In red mode, every test also checks that no pixel has any green or blue.
 * - The equipment screens talk to a [FakeSeestar] with the values of spike S3.
 *
 * The window is square and each screen is drawn in a phone-sized frame, upright or on its side,
 * so one Robolectric configuration covers both orientations.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w900dp-h900dp-mdpi")
class ScreenshotTest(private val case: Case) {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app by lazy { TestApp(scope, directory, case.mode) }
    private val directory: File = Files.createTempDirectory("yacana-profiles").toFile()

    /** One screen of the app: where the navigator is, and what the equipment is doing. */
    data class Scene(
        val name: String,
        val section: Section = Section.Night,
        val screens: List<Screen> = emptyList(),
        val profile: Boolean = false,
        val connected: Boolean = false,
        val firstSteps: Boolean = false,
        /** One frame captured from the fake Seestar and shown in Imaging. */
        val captured: Boolean = false,
        val waitForText: String? = null,
        val gallery: Boolean = false,
        /** "Night · sequence running", drawn on its own. */
        val running: Boolean = false,
        /** "Night · summary", drawn on its own. */
        val summary: Boolean = false,
        /** The session bar, running and paused, drawn on its own. */
        val sessionBar: Boolean = false,
        /** A step of the first steps after the first, drawn on its own. */
        val firstStep: Int = 0,
        /** A screen drawn on its own from given values, such as a device's without the device. */
        val content: (@Composable () -> Unit)? = null,
        /** The session journal's lines, as the design's night for "Night · diary". */
        val journal: List<String> = emptyList(),
        val viewer: Boolean = false,
        /** Text to tap once the screen shows, and text to scroll to, for what is lower on a screen. */
        val click: String? = null,
        /** Text to hold once the screen shows: the start of it, as a row's name with a tag after it. */
        val longClick: String? = null,
        val scrollTo: String? = null,
        /** Text to type in the screen's text field. */
        val type: String? = null,
        /** A step of the polar alignment assistant, drawn on its own. */
        val polar: PolarAlignmentState? = null,
        /** A step of the autofocus assistant, drawn on its own. */
        val autofocus: AutofocusState? = null,
        /** The last autofocus that finished, for the focuser's screen. */
        val lastAutofocus: AutofocusResult? = null,
        /** Each filter's focus offset in the profile. */
        val filterOffsets: Map<String, Int> = emptyMap(),
        /** The session's history, as the design's night. */
        val history: Boolean = false,
        /** The profile's site. */
        val site: Location? = null,
        /** The location's permission granted, so the profile shows the phone's site, Madrid's. */
        val locationAllowed: Boolean = false,
        /** Photos of the sky around M 31, as NINA's cache has them, and how many of them the screen shows. */
        val skyPhotos: Boolean = false,
        val photosShown: Int = Int.MAX_VALUE,
        /** Objects marked with their star. */
        val favourites: Set<String> = emptySet(),
        /** M 31 in a mosaic of 2 and M 33, framed and added to tonight's sequence. */
        val framed: Boolean = false,
        /** Tonight as the advanced editor left it: M 33 repeated until 02:00, which the simple editor can't show. */
        val advanced: Boolean = false,
        /** Texts to tap after [click], one after another, and an icon to tap by what it says. */
        val clicks: List<String> = emptyList(),
        val clickIcon: String? = null,
        /** The profile's own horizon, with the site. */
        val horizon: List<HorizonPoint> = emptyList(),
        /** The session's guard as "Night · alert" shows it, drawn on its own. */
        val guard: GuardState? = null,
        /** Where the mount points, in its own system, and whether it slews, for the map's mount. */
        val pointing: SkyPosition? = null,
        val slewing: Boolean = false,
        /** Live stacking on in the profile, with the design's 34 frames once the scene's frame is in. */
        val stacked: Boolean = false,
        /** The Sky Atlas's filters as they were left. */
        val atlasFilters: AtlasFilters? = null,
        /** Three panels of a 2 × 2 mosaic of M 31 stacked, the third growing, as "Imaging · live stacking, a mosaic". */
        val mosaic: Boolean = false,
    )

    /** A scene in a theme and an orientation; with a [language], its texts are checked instead (see [cases]). */
    data class Case(val scene: Scene, val mode: YacanaThemeMode, val landscape: Boolean, val language: String? = null) {
        override fun toString() =
            "${scene.name}_${mode.name.lowercase()}_${if (landscape) "land" else "port"}" + language?.let { "_$it" }.orEmpty()
    }

    private val defaultLocale = Locale.getDefault()

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun screenshot() {
        val language = case.language
        // The app's texts in [language]: Compose's resources follow the default locale.
        language?.let { Locale.setDefault(Locale.forLanguageTag(it)) }
        val scene = if (language == null) case.scene else TextsFit.translated(case.scene, language)
        if (scene == null) {
            TextsFit.skipped(case)
            return
        }
        val photos = if (scene.skyPhotos) FakeSkyCache() else null
        val services = services(scene, photos)

        compose.setContent {
            Box(Modifier.size(if (case.landscape) LONG else SHORT, if (case.landscape) SHORT else LONG).testTag(FRAME)) {
                SceneContent(scene, services)
            }
        }
        scene.waitForText?.let { text ->
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        interact(scene)
        if (photos != null) {
            // The map asks for its photos as it draws, and draws again as each arrives: a first capture draws it.
            compose.onNodeWithTag(FRAME).captureToImage()
            compose.waitUntil(WAIT_MILLIS) { photos.read.size >= minOf(scene.photosShown, photos.tiles.size) }
            Thread.sleep(VIEWER_SETTLE_MILLIS)
        }
        if (scene.viewer || scene.captured) waitForViewer(scene)
        compose.waitForIdle()
        if (language != null) {
            TextsFit.check(compose, case)
            return
        }

        val frame = compose.onNodeWithTag(FRAME)
        frame.captureRoboImage("src/test/screenshots/$case.png", roborazziOptions = OPTIONS)
        if (case.mode == YacanaThemeMode.Red) assertOnlyRed(frame.captureToImage().toPixelMap())
    }

    /** The app's services as [scene] wants them: its equipment, its frames, its history and its site. */
    private fun services(scene: Scene, photos: FakeSkyCache?): AppServices {
        val equipment = prepared(scene)
        val services =
            app.services(
                equipment,
                firstStepsDone = !scene.firstSteps,
                lastAutofocus = scene.lastAutofocus,
                skyCaches = FakeSkyCaches(photos),
                favourites = scene.favourites,
                framed = scene.framed,
                advanced = scene.advanced,
                locationAllowed = scene.locationAllowed,
            ).copy(journal = JournalLines { scene.journal })
        scene.atlasFilters?.let(services.skyBrowsing::filter)
        if (scene.captured) capture(scene, equipment, services)
        if (scene.history) historyNight().forEach(services.capture.history::add)
        scene.site?.let { site ->
            val active = equipment.state.value.active!!
            equipment.save(active.copy(location = site, options = active.options.copy(horizon = scene.horizon)))
        }
        return services
    }

    /** Takes the scene's frame; with [Scene.stacked], live stacking is on first, and the frame goes into the stack. */
    private fun capture(scene: Scene, equipment: EquipmentController, services: AppServices) {
        if (scene.stacked) {
            val active = equipment.state.value.active!!
            equipment.save(active.copy(options = active.options.copy(stacking = StackingOptions(live = true))))
        }
        if (scene.mosaic) {
            // Panel by panel, as the sequence takes them, each where its centring would put it.
            MOSAIC_OFFSETS.take(MOSAIC_STACKED).forEachIndexed { index, offset ->
                app.fakeStack.panelOffset = offset
                app.capture(
                    services.capture,
                    CaptureSettings(exposureSeconds = 20.0, gain = 80, target = "M 31 Panel ${index + 1}", mosaic = mosaicPanel(index)),
                )
                compose.waitUntil(WAIT_MILLIS) { services.capture.stacking?.state?.value?.mosaic?.drawn?.contains(index) == true }
            }
            return
        }
        app.capture(services.capture)
        if (scene.stacked) compose.waitUntil(WAIT_MILLIS) { services.capture.stacking?.state?.value?.info != null }
    }

    /**
     * Waits until the viewer draws, once the engine, here the fake frame, returns the preview;
     * Imaging also draws the thumbnail beside the shutter. With the stack on, the viewer draws the stack.
     */
    private fun waitForViewer(scene: Scene) {
        val previews = if (scene.captured && !scene.stacked) 2 else 1
        compose.waitUntil(WAIT_MILLIS) { app.fakeFrame.previews >= previews }
        if (scene.stacked) compose.waitUntil(WAIT_MILLIS) { app.fakeStack.previews >= 1 }
        // The mosaic's image is made off the screen's thread once its last panel is in, the cache's photo
        // over a frame is placed once the viewer rests, and a captured frame is drawn once its preview is
        // in: all are drawn as the screen's clock goes on. Recorded before, a portrait frame was left empty.
        if (scene.mosaic || scene.skyPhotos || scene.captured) {
            repeat(SETTLE_STEPS) {
                Thread.sleep(VIEWER_SETTLE_MILLIS)
                compose.mainClock.advanceTimeBy(VIEWER_SETTLE_MILLIS)
                compose.waitForIdle()
            }
        }
        Thread.sleep(VIEWER_SETTLE_MILLIS)
    }

    /** The scene: the whole app at its screens, or one part of it drawn on its own. */
    @Composable
    private fun SceneContent(scene: Scene, services: AppServices) {
        val alone: (@Composable () -> Unit)? =
            when {
                scene.content != null -> scene.content

                scene.firstStep > 0 -> {
                    { FirstStepsScreen(services, startAt = scene.firstStep) }
                }

                scene.gallery -> {
                    { ComponentGallery() }
                }

                scene.polar != null -> {
                    { PolarAlignmentContent(scene.polar, services.polarAlignment, services.equipment, services.clock) {} }
                }

                scene.autofocus != null -> {
                    { AutofocusContent(scene.autofocus, services.autofocus) {} }
                }

                scene.guard != null -> {
                    {
                        // Tonight's sequence paused at M 31's first panel, 41 frames in.
                        val run =
                            SequenceRun(
                                tonight(tonightTargets(), null, focuser = false),
                                RunStatus.Paused,
                                listOf(1, 0, 1),
                                mapOf(listOf(1, 0, 1) to 41),
                            )
                        val clock = object : Clock {
                            override fun now() = NOW
                        }
                        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SessionAlert(scene.guard, run, "Seestar S50 Pro", SafetyOptions(), clock, {}, {}, {}, {})
                        }
                    }
                }

                scene.running -> {
                    {
                        // M 31's first panel, 34 frames of 120 in, the 35th 34 s into its minute.
                        val lights = Exposures(count = 120, exposureSeconds = 60.0, filter = "IR-cut", gain = 80)
                        val m31 = tonightTargets().first().copy(exposures = listOf(lights))
                        val m33 = tonightTargets().last().copy(exposures = listOf(lights.copy(count = 150)))
                        val run =
                            SequenceRun(
                                tonight(listOf(m31, m33), null, focuser = true, listOf(Trigger.AutofocusAfterTime(150.0))),
                                RunStatus.Running,
                                listOf(1, 0, 1),
                                mapOf(listOf(1, 0, 1) to 34),
                            )
                        // Between the history's frames 24 and 25, so its line shows the mark; the next one in 30 min.
                        val focusedAt = Instant.parse("2026-10-04T20:30:30Z")
                        val focused =
                            AutofocusResult(1518, 1.9, emptyList(), FocusFitting.Hyperbolic, 1500, 9.0, "IR-cut", focusedAt, 3.minutes)
                        val site = Location(40.4, -3.7, 650.0)
                        val dawn = Ephemerides.night(LocalDate(2026, 10, 4), site).astronomicalDawn
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // As "with stacking": the stack of the design's 34 frames of a minute.
                                val stack =
                                    if (scene.stacked) {
                                        val thumbnail =
                                            remember {
                                                app.fakeStack.preview(true, Region(0.0, 1110.0, 2160.0, 1620.0), 128, 96).toImageBitmap()
                                            }
                                        StackCard(34, 2_040.0, thumbnail)
                                    } else {
                                        null
                                    }
                                RunningNight(
                                    run,
                                    CaptureActivity.Exposing(NOW - 34.seconds, 60.0),
                                    historyNight(),
                                    focused,
                                    site,
                                    dawn,
                                    NOW,
                                    TimeZone.of("Europe/Madrid"),
                                    DitherOptions(),
                                    stack,
                                )
                            }
                            RunningButtons(paused = false, {}, {}, {})
                        }
                    }
                }

                scene.sessionBar -> {
                    {
                        // M 31's first panel, 34 frames of 120 in, the 35th 34 s into its minute; and paused.
                        val lights = Exposures(count = 120, exposureSeconds = 60.0, filter = "IR-cut", gain = 80)
                        val m31 = tonightTargets().first().copy(exposures = listOf(lights))
                        val run =
                            SequenceRun(
                                tonight(listOf(m31), null, focuser = false),
                                RunStatus.Running,
                                listOf(1, 0, 1),
                                mapOf(listOf(1, 0, 1) to 34),
                            )
                        val clock = object : Clock {
                            override fun now() = NOW
                        }
                        Column {
                            SessionBar(run, CaptureActivity.Exposing(NOW - 34.seconds, 60.0), clock, {}, {}, {})
                            SessionBar(run.copy(status = RunStatus.Paused), CaptureActivity.Idle, clock, {}, {}, {})
                        }
                    }
                }

                scene.summary -> {
                    {
                        val since = Instant.parse("2026-10-04T10:00:00Z")
                        val diary = nightDiary(nightJournal(), historyNight(), since)
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                NightSummaryCards(
                                    nightSummary(diary, historyNight(), since),
                                    diary,
                                    LocalDate(2026, 10, 4),
                                    TimeZone.of("Europe/Madrid"),
                                ) {
                                }
                            }
                            SummaryButtons({}, {})
                        }
                    }
                }

                scene.viewer -> {
                    {
                        Column {
                            FrameViewer(app.fakeFrame, app.fakeFrame.autoStretch(), Modifier.weight(1f), marks = app.fakeFrame.marks)
                            Histogram(app.fakeFrame.histogram(HISTOGRAM_BINS), app.fakeFrame.autoStretch().green)
                        }
                    }
                }

                else -> null
            }
        if (alone != null) {
            YacanaTheme(case.mode) { Surface(Modifier.fillMaxSize()) { alone() } }
        } else {
            YacanaApp(APP, services, navigator = Navigator(scene.section).apply { scene.screens.forEach(::open) })
        }
    }

    /** Whether [node] is inside something that scrolls. */
    private fun isInScroll(node: SemanticsNodeInteraction): Boolean {
        var parent = node.fetchSemanticsNode().parent
        while (parent != null) {
            if (parent.config.contains(SemanticsActions.ScrollBy)) return true
            parent = parent.parent
        }
        return false
    }

    /** The equipment as the scene has it: a profile, its filter offsets, and connected. */
    private fun prepared(scene: Scene): EquipmentController {
        val equipment = app.equipment()
        if (scene.profile) runBlocking { equipment.use(equipment.discover().single()) }
        if (scene.filterOffsets.isNotEmpty()) {
            val profile = equipment.state.value.active!!
            val options = profile.options
            equipment.save(profile.copy(options = options.copy(autofocus = options.autofocus.copy(filterOffsets = scene.filterOffsets))))
        }
        scene.pointing?.let {
            app.seestar.set("telescope/0/rightascension", it.raHours.toString())
            app.seestar.set("telescope/0/declination", it.decDeg.toString())
        }
        if (scene.slewing) app.seestar.set("telescope/0/slewing", "true")
        if (scene.connected) app.connect(equipment)
        return equipment
    }

    /** Taps and scrolls as the scene says, to show what is lower on a screen. */
    private fun interact(scene: Scene) {
        // The click action without a touch, so no ripple is left in the capture.
        scene.click?.let { text ->
            val node = compose.onNodeWithText(text)
            // Scrolled to first when it is in a list, as lower on a screen.
            if (node.fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy) || isInScroll(node)) node.performScrollTo()
            node.performSemanticsAction(SemanticsActions.OnClick)
        }
        scene.clicks.forEach { text ->
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        }
        scene.clickIcon?.let { compose.onNodeWithContentDescription(it).performSemanticsAction(SemanticsActions.OnClick) }
        scene.longClick?.let { text ->
            compose.onNodeWithText(text, substring = true).performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick)
        }
        scene.scrollTo?.let { compose.onNodeWithText(it).performScrollTo() }
        scene.type?.let { text ->
            if (case.mode == YacanaThemeMode.Red) {
                // Red mode types on the app's own keyboard: the search box opens it, then each key.
                compose.onNode(hasText("Search", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
                compose.waitForIdle()
                text.lowercase().forEach { key ->
                    compose.onNodeWithContentDescription(if (key == ' ') "space" else key.toString())
                        .performSemanticsAction(SemanticsActions.OnClick)
                }
            } else {
                compose.onNode(hasSetTextAction()).performTextInput(text)
            }
        }
    }

    private fun assertOnlyRed(pixels: PixelMap) {
        var coloured = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (pixel.green > 0f || pixel.blue > 0f) coloured++
            }
        }
        assertEquals(0, coloured, "$case: pixels with green or blue in red mode")
    }

    companion object {
        /** The screenshots' time. */
        private val NOW = TEST_NOW

        private val SEESTAR_AUTOFOCUS = AutofocusOptions(stepSize = 15, offsetSteps = 7, exposureSeconds = 3.0)

        private const val FRAME = "frame"
        private const val WAIT_MILLIS = TEST_WAIT_MILLIS
        private const val VIEWER_SETTLE_MILLIS = 300L
        private const val HISTOGRAM_BINS = 128
        private val SHORT = 411.dp
        private val LONG = 891.dp
        private val APP = TEST_APP_INFO

        /** Built inside the test: Roborazzi reads its settings from the Robolectric environment. */
        private val OPTIONS by lazy { RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)) }

        private val SCENES =
            Section.entries.filter { it != Section.Equipment }.map { Scene(it.name.lowercase(), section = it) } +
                listOf(
                    Scene("settings", screens = listOf(Screen.Settings)),
                    Scene("display", screens = listOf(Screen.Settings, Screen.Display)),
                    Scene("about", screens = listOf(Screen.Settings, Screen.About)),
                    // As "Settings · open-source licences" and "· open-source licence".
                    Scene("licences", screens = listOf(Screen.Settings, Screen.About, Screen.Licences)),
                    Scene("licence", screens = listOf(Screen.Settings, Screen.About, Screen.Licences, Screen.Licence("tabler"))),
                    Scene("components", gallery = true),
                    Scene("viewer", viewer = true),
                    Scene("first_steps", firstSteps = true),
                    // As "First steps · offline", with the Seestar's profile and nothing downloaded yet.
                    Scene("first_steps_field", profile = true, firstStep = 2),
                    Scene("equipment_find", section = Section.Equipment, waitForText = "Wide angle"),
                    Scene("equipment", section = Section.Equipment, profile = true, connected = true),
                    Scene(
                        "mount",
                        section = Section.Equipment,
                        screens = listOf(Screen.Device(DeviceRole.Mount)),
                        profile = true,
                        connected = true,
                    ),
                    Scene(
                        "mount_go_to",
                        section = Section.Equipment,
                        screens = listOf(Screen.Device(DeviceRole.Mount)),
                        profile = true,
                        connected = true,
                        click = "Go to…",
                    ),
                    Scene(
                        "switches",
                        section = Section.Equipment,
                        screens = listOf(Screen.Device(DeviceRole.Switches)),
                        profile = true,
                        connected = true,
                    ),
                    Scene("profile", section = Section.Equipment, screens = listOf(Screen.Profile), profile = true, locationAllowed = true),
                    // Tonight at Calar Alto, a public observatory in the screenshots' time zone; and a profile without a site.
                    Scene("night_tonight", section = Section.Night, profile = true, site = Location(37.2236, -2.5463, 2168.0)),
                    Scene("night_no_site", section = Section.Night, profile = true),
                    // As "Night · another night": the card's calendar open.
                    Scene(
                        "night_calendar",
                        section = Section.Night,
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        clickIcon = "See another night",
                    ),
                    // As "Night · before starting": connected, with tonight's targets, polar alignment and focus still to do.
                    Scene(
                        "night_prepare",
                        section = Section.Night,
                        profile = true,
                        connected = true,
                        framed = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                    ),
                    // The search as the Sky Atlas starts, an object outside the catalogue, and an object's page at Calar Alto.
                    Scene("sky_map", section = Section.Sky, profile = true, skyPhotos = true),
                    // The mount on the map: the telescope south-west of M 31, and going there to centre it.
                    Scene(
                        "sky_map_mount",
                        section = Section.Sky,
                        profile = true,
                        connected = true,
                        skyPhotos = true,
                        pointing = SkyPosition(0.6, 39.6),
                    ),
                    Scene(
                        "sky_map_going",
                        section = Section.Sky,
                        profile = true,
                        connected = true,
                        skyPhotos = true,
                        pointing = SkyPosition(0.6, 39.6),
                        slewing = true,
                        click = "Go and centre",
                    ),
                    Scene("sky_search", section = Section.Sky, click = "List", type = "velo"),
                    Scene("sky_simbad", section = Section.Sky, click = "List", type = "TON 618"),
                    Scene(
                        "sky_object",
                        section = Section.Sky,
                        screens = listOf(Screen.SkyObject("M 33")),
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        // Trees to the east and a building to the west, as the dashed line of the chart.
                        horizon = listOf(
                            HorizonPoint(0.0, 12.0),
                            HorizonPoint(90.0, 28.0),
                            HorizonPoint(180.0, 10.0),
                            HorizonPoint(270.0, 22.0),
                        ),
                    ),
                    // M 31 in the photos of the cache, a mosaic of 2 for the Seestar, and among the favourites.
                    Scene(
                        "sky_object_photo",
                        section = Section.Sky,
                        screens = listOf(Screen.SkyObject("M 31")),
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        skyPhotos = true,
                        photosShown = 1,
                        favourites = setOf("M 31"),
                    ),
                    // The Sky Atlas: tonight from Calar Alto for the Seestar, the favourites with their photos, and its filters.
                    Scene("sky_atlas", section = Section.Sky, click = "List", profile = true, site = Location(37.2236, -2.5463, 2168.0)),
                    Scene(
                        "sky_atlas_favourites",
                        section = Section.Sky,
                        click = "List",
                        clicks = listOf("Favourites"),
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        skyPhotos = true,
                        photosShown = 1,
                        favourites = setOf("M 31", "M 32", "M 110", "M 33"),
                    ),
                    Scene(
                        "sky_atlas_filtered",
                        section = Section.Sky,
                        click = "List",
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        atlasFilters = ATLAS_FILTERED,
                    ),
                    Scene(
                        "sky_atlas_filters",
                        section = Section.Sky,
                        click = "List",
                        clickIcon = "Filters",
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        atlasFilters = ATLAS_FILTERED,
                    ),
                    // M 31 framed in a mosaic of 2 along its length, as "Sky · framing", and tonight's sequence.
                    Scene(
                        "sky_framing",
                        section = Section.Sky,
                        screens = listOf(Screen.Framing("M 31")),
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        skyPhotos = true,
                        photosShown = 1,
                    ),
                    // As "Sequence · my sequences": tonight's, the saved ones and the templates.
                    // As "Night · alert": the link lost 42 s ago, a third attempt, the safe stop 78 s away.
                    Scene(
                        "night_alert",
                        guard = GuardState.Reconnecting(NOW - 42.seconds, attempt = 2, attempts = 12, stopAt = NOW + 78.seconds),
                    ),
                    // And after a safe stop, the mount parked.
                    Scene("night_safe_stop", guard = GuardState.SafeStop(SafeStopReason.LostLink, done = true, parked = true)),
                    // As "Night · sequence running".
                    Scene("night_running", running = true),
                    Scene("night_running_stack", running = true, stacked = true),
                    // As "Night · summary".
                    Scene("night_summary", summary = true),
                    // The session bar, as under "Sequence · simple editor".
                    Scene("session_bar", sessionBar = true),
                    // As "Equipment · flat panel": the cover open, the light on at 102 of 255.
                    Scene("flat_panel", content = {
                        val state = FlatPanelState(CoverStatus.Open, CalibratorStatus.Ready, 102)
                        FlatPanelContent(state, 255, "Alpaca panel with cover", null, FlatPanelActions({}, {}, {}, {}, {}))
                    }),
                    // As "Equipment · safety monitor".
                    Scene("safety_monitor", content = { SafetyMonitorContent(true, "Alpaca cloud monitor", null) }),
                    // As "Flats · before starting", with the Seestar's wheel and no flat panel.
                    Scene("flats_setup", content = { FlatsSetup(true, 65_535, listOf("Dark", "IR", "LP"), null, "200") {} }),
                    // As "Flats": IR at 1.8 s, 18 of 30 kept; LP next.
                    Scene("flats_running", content = {
                        val options = FlatsOptions(FlatSource.Screen, 30_000, 30, listOf("IR", "LP"))
                        val filters = listOf(FilterFlats("IR", 30, 1.8, 18), FilterFlats("LP", 30))
                        FlatsProgress(FlatsState(options, FlatsPhase.Taking, filters, lastMean = 29_870.0), 0, {}, {}, {})
                    }),
                    // As "Night · diary".
                    // The diary reads its journal in the background: its first event, before the shot.
                    Scene(
                        "night_diary",
                        screens = listOf(Screen.NightDiary),
                        history = true,
                        journal = nightJournal(),
                        waitForText = "The sequence starts",
                    ),
                    // As "Settings · safety".
                    Scene("safety_settings", screens = listOf(Screen.Settings, Screen.SafetySettings), profile = true),
                    Scene("camera_settings", screens = listOf(Screen.Settings, Screen.CameraSettings), profile = true),
                    Scene("my_sequences", section = Section.Sequence, framed = true),
                    // As "Sequence · advanced editor": tonight's plan opened whole, its three areas.
                    Scene(
                        "advanced_night",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight, Screen.Advanced()),
                        framed = true,
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                    ),
                    // As "Sequence · inside a target": M 33 repeated until 02:00, with its hours above 30°.
                    Scene(
                        "advanced_target",
                        section = Section.Sequence,
                        screens = listOf(
                            Screen.SequenceTonight,
                            Screen.Advanced(),
                            Screen.Advanced(listOf(1)),
                            Screen.Advanced(listOf(1, 1)),
                        ),
                        framed = true,
                        advanced = true,
                        profile = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                    ),
                    // As "Sequence · add an instruction": the start's catalogue, what points at a target greyed out.
                    Scene(
                        "advanced_catalogue",
                        section = Section.Sequence,
                        screens = listOf(
                            Screen.SequenceTonight,
                            Screen.Advanced(),
                            Screen.Advanced(listOf(0)),
                            Screen.AdvancedAdd(listOf(0), "Start"),
                        ),
                        framed = true,
                        profile = true,
                    ),
                    // As "Sequence · tonight, advanced".
                    Scene(
                        "tonight_advanced",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight),
                        framed = true,
                        advanced = true,
                        profile = true,
                    ),
                    // As "Sequence · tonight, before starting": the equipment connected, ready to start.
                    Scene(
                        "sequence_ready",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight),
                        framed = true,
                        profile = true,
                        connected = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                    ),
                    // As "Sequence · autofocus rules": the four triggers, the temperature's on.
                    Scene(
                        "sequence_autofocus",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight),
                        framed = true,
                        profile = true,
                        connected = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        click = "Autofocus",
                    ),
                    // As "Sequence · dark rules": when tonight takes darks.
                    Scene(
                        "sequence_darks",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight),
                        framed = true,
                        profile = true,
                        connected = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        click = "Darks",
                    ),
                    // As "Sequence · recentring".
                    Scene(
                        "sequence_recentring",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight),
                        framed = true,
                        profile = true,
                        connected = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        click = "Recentring",
                    ),
                    // As "Sequence · dither".
                    Scene(
                        "sequence_dither",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight),
                        framed = true,
                        profile = true,
                        connected = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        click = "Dither",
                    ),
                    // As "Sequence · dew heater".
                    Scene(
                        "sequence_heater",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight),
                        framed = true,
                        profile = true,
                        connected = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        click = "Dew heater",
                    ),
                    // As "Sequence · edit exposures": M 31's first exposures, for each panel.
                    Scene(
                        "sequence_exposures",
                        section = Section.Sequence,
                        screens = listOf(Screen.SequenceTonight),
                        framed = true,
                        profile = true,
                        connected = true,
                        site = Location(37.2236, -2.5463, 2168.0),
                        click = "IR · 60 s × 45 per panel",
                    ),
                    Scene("imaging_frame", section = Section.Imaging, profile = true, connected = true, captured = true),
                    // As "Imaging · live stacking", "Imaging · stack" and the options with the stack on.
                    Scene("imaging_stack", section = Section.Imaging, profile = true, connected = true, captured = true, stacked = true),
                    Scene(
                        "imaging_mosaic",
                        section = Section.Imaging,
                        profile = true,
                        connected = true,
                        captured = true,
                        stacked = true,
                        mosaic = true,
                    ),
                    Scene(
                        "imaging_stack_sheet",
                        section = Section.Imaging,
                        profile = true,
                        connected = true,
                        captured = true,
                        stacked = true,
                        click = "34 frames · 34 min",
                    ),
                    Scene(
                        "imaging_options",
                        section = Section.Imaging,
                        profile = true,
                        connected = true,
                        captured = true,
                        stacked = true,
                        clickIcon = "More options",
                        scrollTo = "Balance the stack's colour",
                    ),
                    // As "Imaging · reference": the frame solved, and the cache's photo of its field over it.
                    Scene(
                        "imaging_reference",
                        section = Section.Imaging,
                        profile = true,
                        connected = true,
                        captured = true,
                        skyPhotos = true,
                        photosShown = 1,
                        click = "Reference",
                    ),
                    Scene("image_files", screens = listOf(Screen.Settings, Screen.ImageFiles), profile = true),
                    Scene("plate_solving", screens = listOf(Screen.Settings, Screen.PlateSolving), profile = true),
                    Scene(
                        "solver_delete_in_use",
                        screens = listOf(Screen.Settings, Screen.PlateSolving),
                        profile = true,
                        longClick = "D50",
                    ),
                    Scene(
                        "plate_solving_databases",
                        screens = listOf(Screen.Settings, Screen.PlateSolving),
                        profile = true,
                        connected = true,
                        scrollTo = "Import file",
                    ),
                    Scene(
                        "polar_alignment",
                        section = Section.Equipment,
                        screens = listOf(Screen.Device(DeviceRole.Mount), Screen.PolarAlignment),
                        profile = true,
                        connected = true,
                    ),
                    // As the designs: measuring the second point, then still in minutes and refining in seconds.
                    Scene(
                        "polar_measuring",
                        profile = true,
                        polar = PolarAlignmentState.Measuring(2, MeasureStep.Turning, listOf(1.8.seconds)),
                    ),
                    Scene("polar_minutes", profile = true, polar = adjusting(-252.0, -48.0, listOf(-262.0 to -60.0, -270.0 to -70.0))),
                    Scene("polar_seconds", profile = true, polar = adjusting(-7.0, 0.2, listOf(-13.0 to 4.0, -10.0 to 2.0))),
                    Scene(
                        "focuser",
                        section = Section.Equipment,
                        screens = listOf(Screen.Device(DeviceRole.Focuser)),
                        profile = true,
                        connected = true,
                        // As the design: at 22:35 in Madrid, at 12.9 °C.
                        lastAutofocus = autofocusDone().result.copy(at = Instant.parse("2026-10-04T20:35:00Z"), temperature = 12.9),
                        filterOffsets = mapOf("LP" to 12),
                    ),
                    // As the designs: the seventh point of nine, measured from the outside in as NINA does, and done.
                    Scene("autofocus_running", profile = true, autofocus = autofocusRunning()),
                    Scene("autofocus_done", profile = true, autofocus = autofocusDone()),
                    // As the design: 34 frames of IR-cut, an autofocus at 22:26, and #32 through clouds.
                    Scene(
                        "history",
                        section = Section.Imaging,
                        screens = listOf(Screen.History),
                        history = true,
                        lastAutofocus = autofocusDone().result.copy(at = Instant.parse("2026-10-04T20:26:30Z")),
                    ),
                    Scene("history_empty", section = Section.Imaging, screens = listOf(Screen.History)),
                    Scene("autofocus_settings", screens = listOf(Screen.Settings, Screen.AutofocusSettings), profile = true),
                    Scene("sky_settings", screens = listOf(Screen.Settings, Screen.SkyMapSettings)),
                    // The app's languages, the system's in use.
                    Scene("language", screens = listOf(Screen.Settings, Screen.Language)),
                    Scene("sky_cache_delete", screens = listOf(Screen.Settings, Screen.SkyMapSettings), longClick = "Full cache (DSS)"),
                    Scene(
                        "profile_database",
                        section = Section.Equipment,
                        screens = listOf(Screen.Profile),
                        profile = true,
                        click = "D20",
                        locationAllowed = true,
                    ),
                )

        /** A defocused star's HFR at [position], best at 1518. */
        private fun focusPoint(position: Int) = FocusPoint(position.toDouble(), 1.9 * sqrt(1 + ((1518.0 - position) / 40).pow(2)), 0.1)

        /** The journal of the design's night, as JournalFiles writes it: the start, a reconnection, an extra autofocus and the park. */
        private fun nightJournal() = listOf(
            """{"t":"2026-10-04T19:58:00Z","event":"sequence_started","name":"Tonight"}""",
            """{"t":"2026-10-04T19:59:00Z","event":"sequence_item","item":"GoToTarget(centre=true)","target":"M 31 Panel 1"}""",
            """{"t":"2026-10-04T20:00:00Z","event":"centring_done","centred":true,"attempts":2,"offArcsec":8.2}""",
            """{"t":"2026-10-04T20:02:00Z","event":"autofocus_done","position":1518,"hfr":2.0,"points":9,"temperature":9.4}""",
            """{"t":"2026-10-04T20:14:00Z","event":"link_lost","running":true}""",
            """{"t":"2026-10-04T20:14:51Z","event":"link_back","attempts":5}""",
            """{"t":"2026-10-04T20:24:00Z","event":"sequence_autofocus_trigger","triggers":"AfterTemperatureChange"}""",
            """{"t":"2026-10-04T20:25:00Z","event":"autofocus_done","position":1524,"hfr":2.1,"points":9,"temperature":8.1}""",
            """{"t":"2026-10-04T20:41:00Z","event":"sequence_item","item":"Park","target":null}""",
            """{"t":"2026-10-04T20:41:30Z","event":"sequence_finished"}""",
        )

        /** The design's night: a frame of IR-cut every minute from 22:07 to 22:40 in Madrid, #32 through clouds. */
        private fun historyNight(): List<HistoryFrame> = (1..34).map { number ->
            val cloudy = number == 32
            HistoryFrame(
                number = number,
                at = Instant.parse("2026-10-04T20:06:00Z") + number.minutes,
                type = FrameType.Light,
                filter = "IR-cut",
                exposureSeconds = 60.0,
                gain = 80,
                binning = 1,
                hfr = if (cloudy) 3.4 else 2.0 + (number * 7 % 5) * 0.05,
                stars = if (cloudy) 120 else 400 + number * 13 % 21,
                background = if (cloudy) 1400.0 else 1010.0,
                mad = 42.0,
                savedAs = "frame $number",
                thumbnail = starField(number),
                discarded = if (cloudy) DiscardReason.Clouds else null,
                target = "M 31",
            )
        }

        /** A few stars on the night's grey, as the design's thumbnails. */
        private fun starField(seed: Int): Preview {
            val side = 48
            val rgba = ByteArray(side * side * 4) { if (it % 4 == 3) -1 else 22 }
            val random = Random(seed)
            repeat(6) {
                val x = random.nextInt(side - 1)
                val y = random.nextInt(side - 1)
                for ((dx, dy) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
                    val at = ((y + dy) * side + x + dx) * 4
                    for (channel in 0..2) rgba[at + channel] = -36
                }
            }
            return Preview(side, side, rgba)
        }

        private fun autofocusRunning(): AutofocusState.Running {
            val points = (1490..1625 step 15).map(::focusPoint)
            return AutofocusState.Running(
                points = points,
                expected = 15,
                step = AutofocusStep.Exposing,
                position = 1475,
                estimate = finalFocus(points, FocusFitting.Hyperbolic),
                temperature = 12.4,
                filter = "IR-cut",
                options = SEESTAR_AUTOFOCUS,
                initialPosition = 1520,
            )
        }

        private fun autofocusDone(): AutofocusState.Done {
            val points = (1415..1625 step 15).map(::focusPoint)
            val best = finalFocus(points, FocusFitting.Hyperbolic)!!
            return AutofocusState.Done(
                AutofocusResult(
                    position = best.position.toInt(),
                    hfr = best.hfr,
                    points = points,
                    fitting = FocusFitting.Hyperbolic,
                    initialPosition = 1520,
                    temperature = 12.4,
                    filter = "IR-cut",
                    at = NOW,
                    duration = 72.seconds,
                ),
            )
        }

        /** Turning the screws: the error now and before, in arcseconds of altitude and azimuth; measured 2 s ago, every 4.8 s. */
        private fun adjusting(altitude: Double, azimuth: Double, before: List<Pair<Double, Double>>) = PolarAlignmentState.Adjusting(
            northern = true,
            initial = PolarError(-0.2, -0.05),
            current = PolarError(altitude / 3600, azimuth / 3600),
            measuredAt = NOW - 2.seconds,
            previous = before.map { (alt, az) -> PolarError(alt / 3600, az / 3600) },
            interval = 4.8.seconds,
        )

        /**
         * Each scene in both themes and orientations. With `YACANA_TEXTS` set, instead, each scene in
         * both orientations and in every language the app speaks, to find the texts that do not fit
         *: `YACANA_TEXTS=1 ./gradlew :app:testDebugUnitTest --tests "app.yacana.ScreenshotTest"`
         * writes them in `app/build/texts-fit/`.
         */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Case> = if (System.getenv("YACANA_TEXTS") != null) {
            SCENES.flatMap { scene ->
                AppLanguage.entries.flatMap { language ->
                    listOf(false, true).map { landscape -> Case(scene, YacanaThemeMode.Dark, landscape, language.tag) }
                }
            }
        } else {
            SCENES.flatMap { scene ->
                YacanaThemeMode.entries.flatMap { mode -> listOf(false, true).map { landscape -> Case(scene, mode, landscape) } }
            }
        }
    }
}

/**
 * The caches as the design's "Settings · sky map": the full one in use, the starless
 * narrowband one arriving, and the map's [photos] as the full one.
 */
internal class FakeSkyCaches(photos: SkyCache?) : SkyCacheStore {
    override val state =
        MutableStateFlow(
            SkyCacheFiles(
                installed =
                listOf(
                    InstalledCache(NINA_CACHES[0].file, SkyLayer.Dss, inUse = true, NINA_CACHES[0].bytes),
                    InstalledCache(ONLINE_CACHE, SkyLayer.Dss, inUse = true, 12_400_000, folder = true),
                ),
                arriving = mapOf(
                    NINA_CACHES[2].file to Arrival(NINA_CACHES[2].file, ArrivalStep.Downloading, 174_000_000, NINA_CACHES[2].bytes),
                ),
                freeBytes = 21_000_000_000,
                // A photo online on its way where the map has photos, as the design.
                fetchingOnline = photos != null,
            ),
        )

    override val layers = MutableStateFlow(listOfNotNull(photos?.let { MapLayer(it, SkyLayer.Dss, 1f) }))

    override fun download(cache: NinaCache) = Unit

    override fun cancel(name: String) = Unit

    override suspend fun import(location: String, folder: Boolean) = location

    override fun delete(name: String) = Unit

    override fun use(name: String, inUse: Boolean) = Unit

    override fun setOpacity(layer: SkyLayer, opacity: Float) = Unit

    override fun setWifiOnly(wifiOnly: Boolean) = Unit

    override fun setFetchOnline(fetch: Boolean) = Unit

    override fun setSaveOnline(save: Boolean) = Unit

    override suspend fun fetchOnline(photo: OnlinePhoto) = false

    override suspend fun export(name: String, location: String) = Unit
}

/**
 * Nine photos of 5° around M 31, as NINA's cache has them: stars on the sky's grey, and the galaxy
 * in the middle one. [read] holds what the map asked for, from its workers.
 */
private class FakeSkyCache : SkyCache {
    val read: MutableSet<SkyTile> = ConcurrentHashMap.newKeySet()

    override val survey = "DSS"

    override val tiles =
        (-1..1).flatMap { row ->
            (-1..1).map { column ->
                val dec = 41.27 + row * 5.0
                SkyTile(10.68 + column * 5.0 / cos(Math.toRadians(dec)), dec, 0.0, 5.0, 5.0, "tile_${row}_$column.jpg", "Hips2FitsSurvey")
            }
        }

    private val jpegs = tiles.associateWith { starField(it.fileName.hashCode(), galaxy = it.fileName == "tile_0_0.jpg") }

    override fun jpeg(tile: SkyTile, pixels: Int): ByteArray? = jpegs[tile].also { read += tile }

    override fun close() = Unit

    private fun starField(seed: Int, galaxy: Boolean): ByteArray {
        val side = 500
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(28, 30, 34))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE }
        val random = Random(seed)
        repeat(350) { canvas.drawCircle(random.nextFloat() * side, random.nextFloat() * side, 0.6f + random.nextFloat() * 1.6f, paint) }
        if (galaxy) {
            canvas.save()
            canvas.rotate(-55f, side / 2f, side / 2f)
            listOf(170f to 60, 110f to 90, 60f to 150, 18f to 230).forEach { (radius, alpha) ->
                paint.alpha = alpha
                canvas.drawOval(side / 2f - radius, side / 2f - radius / 3.5f, side / 2f + radius, side / 2f + radius / 3.5f, paint)
            }
            canvas.restore()
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}

/** The Sky Atlas as its design filters it: up to magnitude 8, from 1°. */
private val ATLAS_FILTERED = AtlasFilters(maxMagnitude = 8.0, minSizeArcmin = 60.0)

/**
 * A 2 × 2 mosaic of M 31 for the live mosaic's screenshot: each panel's centre from the mosaic's,
 * in a Seestar frame's pixels, 15 % overlapping.
 */
private val MOSAIC_OFFSETS = listOf(-918.0 to -1632.0, 918.0 to -1632.0, -918.0 to 1632.0, 918.0 to 1632.0)

/** Steps of the screen's clock for the mosaic to be made and drawn, and for the cache's photo to be placed over a frame. */
private const val SETTLE_STEPS = 6

/** The panels stacked in the screenshot: the third is the one growing. */
private const val MOSAIC_STACKED = 3

/** Panel [index] of the mosaic, centred as its centring would solve it: the camera as plate solving measures a Seestar's. */
private fun mosaicPanel(index: Int): MosaicPanel {
    val centre = SkyPosition(0.7123, 41.27)
    val solution = Solution(centre, rotationDeg = 0.0, scaleArcsec = 2.25, yAxisDeg = 0.0)
    val sky = FrameSky.of(solution, 2160, 3840)!!
    val panels = MOSAIC_OFFSETS.map { (dx, dy) -> sky.at(1080 + dx, 1920 + dy) }
    return MosaicPanel("M 31", centre, panels, index, solution.copy(centre = panels[index]))
}
