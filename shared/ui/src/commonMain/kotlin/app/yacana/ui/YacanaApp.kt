// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.yacana.domain.equipment.Connection
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.session.GuardState
import app.yacana.domain.sky.Favourites
import app.yacana.domain.sky.SkyObject
import app.yacana.ports.EngineBuildInfo
import app.yacana.ui.design.LocalSheetHost
import app.yacana.ui.design.SheetHost
import app.yacana.ui.equipment.DeviceScreen
import app.yacana.ui.equipment.EquipmentScreen
import app.yacana.ui.equipment.FindEquipment
import app.yacana.ui.equipment.ProfileScreen
import app.yacana.ui.equipment.title
import app.yacana.ui.flats.FlatsScreen
import app.yacana.ui.flats.ScreenLight
import app.yacana.ui.focus.AutofocusScreen
import app.yacana.ui.focus.AutofocusSettingsScreen
import app.yacana.ui.imaging.HistoryScreen
import app.yacana.ui.imaging.ImagingScreen
import app.yacana.ui.navigation.Navigator
import app.yacana.ui.navigation.Screen
import app.yacana.ui.night.NightDiaryScreen
import app.yacana.ui.night.NightScreen
import app.yacana.ui.night.SessionBar
import app.yacana.ui.polar.PolarAlignmentScreen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.about
import app.yacana.ui.resources.adv_add_to
import app.yacana.ui.resources.adv_title
import app.yacana.ui.resources.alert_mark
import app.yacana.ui.resources.atlas_filters
import app.yacana.ui.resources.autofocus
import app.yacana.ui.resources.back
import app.yacana.ui.resources.camera_settings
import app.yacana.ui.resources.close
import app.yacana.ui.resources.diary_title
import app.yacana.ui.resources.display
import app.yacana.ui.resources.eq_profile
import app.yacana.ui.resources.equipment_ok
import app.yacana.ui.resources.flats_title
import app.yacana.ui.resources.framing_title
import app.yacana.ui.resources.history
import app.yacana.ui.resources.history_only_discarded
import app.yacana.ui.resources.image_files
import app.yacana.ui.resources.imaging_more
import app.yacana.ui.resources.language
import app.yacana.ui.resources.obj_favourite_add
import app.yacana.ui.resources.obj_favourite_remove
import app.yacana.ui.resources.oss_title
import app.yacana.ui.resources.permissions
import app.yacana.ui.resources.plate_solving
import app.yacana.ui.resources.polar_alignment
import app.yacana.ui.resources.profile_add_equipment
import app.yacana.ui.resources.profile_new
import app.yacana.ui.resources.red_mode_turn_off
import app.yacana.ui.resources.red_mode_turn_on
import app.yacana.ui.resources.safety
import app.yacana.ui.resources.section_arrives
import app.yacana.ui.resources.sequence_tonight
import app.yacana.ui.resources.settings
import app.yacana.ui.resources.settings_profile
import app.yacana.ui.resources.sky_open_layers
import app.yacana.ui.resources.sky_settings
import app.yacana.ui.resources.summary_export
import app.yacana.ui.sequence.MySequences
import app.yacana.ui.sequence.SequenceTonight
import app.yacana.ui.sequence.advanced.AdvancedActions
import app.yacana.ui.sequence.advanced.AdvancedCatalogue
import app.yacana.ui.sequence.advanced.AdvancedScreen
import app.yacana.ui.settings.AboutScreen
import app.yacana.ui.settings.CameraSettingsScreen
import app.yacana.ui.settings.DisplayScreen
import app.yacana.ui.settings.ImageFilesScreen
import app.yacana.ui.settings.LanguageScreen
import app.yacana.ui.settings.LicenceScreen
import app.yacana.ui.settings.LicencesScreen
import app.yacana.ui.settings.OPEN_SOURCE
import app.yacana.ui.settings.PermissionsScreen
import app.yacana.ui.settings.SafetySettingsScreen
import app.yacana.ui.settings.SettingsScreen
import app.yacana.ui.sky.FramingScreen
import app.yacana.ui.sky.SkyMapSettingsScreen
import app.yacana.ui.sky.SkyObjectScreen
import app.yacana.ui.sky.SkyScreen
import app.yacana.ui.sky.filtersOn
import app.yacana.ui.solving.PlateSolvingScreen
import app.yacana.ui.theme.YacanaTheme
import app.yacana.ui.theme.YacanaThemeMode
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** Screens of 600 dp or wider, such as a phone in landscape, get a side rail instead of the bottom bar. */
private val WideLayout = 600.dp

/** What the app says about itself, in About and in the session log. */
data class AppInfo(val versionName: String, val engine: EngineBuildInfo)

/**
 * The display options. The platform keeps them, because in red mode it also hides the
 * system bars and sets the app's own brightness.
 */
interface DisplaySettings {
    val themeMode: StateFlow<YacanaThemeMode>

    /** Screen brightness in red mode, from [MIN_RED_BRIGHTNESS] to 1. */
    val redBrightness: StateFlow<Float>

    fun setThemeMode(mode: YacanaThemeMode)

    fun setRedBrightness(value: Float)

    /** Whether Imaging marks the stars on the frame, as N.I.N.A.'s "Annotate image"; off at first. */
    val markStars: StateFlow<Boolean>

    fun setMarkStars(on: Boolean)

    /** How many of the brightest stars Imaging marks, from [MARKED_STARS]; [DEFAULT_MARKED_STARS] at first. */
    val markedStars: StateFlow<Int>

    fun setMarkedStars(count: Int)

    /** Whether Imaging shows the live stack, rather than the last frame, while there is one; on at first. */
    val showStack: StateFlow<Boolean>

    fun setShowStack(on: Boolean)

    companion object {
        const val MIN_RED_BRIGHTNESS = 0.01f

        /** Fewer than N.I.N.A.'s 200: in a field full of stars, their boxes and HFRs would cover each other. */
        const val DEFAULT_MARKED_STARS = 50
        val MARKED_STARS = 10..500
    }
}

/**
 * The app: five sections in a bottom bar, or a side rail when the screen is wide, each
 * with its own back stack of screens (ARCHITECTURE.md). Settings open from the gear.
 */
@Composable
fun YacanaApp(app: AppInfo, services: AppServices, navigator: Navigator = remember { Navigator() }) {
    val display = services.display
    val themeMode by display.themeMode.collectAsState()
    val firstStepsDone by services.firstSteps.done.collectAsState()
    // The screen whose top bar menu is open: Imaging's options or the mount's, as in their designs;
    // for the history, its filter.
    var menuFor by remember { mutableStateOf<Screen?>(null) }
    // The advanced editor's level whose "Review" is open, from the top bar's warning.
    var reviewFor by remember { mutableStateOf<Screen?>(null) }
    val guardAlert = (services.guard?.state?.collectAsState()?.value ?: GuardState.Watching) != GuardState.Watching
    val connection = services.equipment.state.collectAsState().value.connection
    // The sky as seen from where the phone is now, without waiting to connect.
    val locationAllowed = services.permissions.statuses.collectAsState().value.any { it.permission == AppPermission.Location && it.granted }
    LaunchedEffect(locationAllowed) { if (locationAllowed) services.equipment.refreshPhoneLocation() }
    val nightMark =
        when {
            guardAlert -> NightMark.Alert
            (connection as? Connection.Connected)?.equipment?.failures?.isEmpty() == true -> NightMark.Ok
            else -> NightMark.None
        }
    // The Sky section shows its map or its list, and the top bar its buttons for each.
    var skyList by rememberSaveable { mutableStateOf(false) }
    // The object whose photo asked to see it on the map, until the map has framed it.
    var mapTarget by remember { mutableStateOf<SkyObject?>(null) }

    YacanaTheme(themeMode) {
        Surface(Modifier.fillMaxSize()) {
            if (!firstStepsDone) {
                FirstStepsScreen(services)
                return@Surface
            }
            val sheetHost = remember { SheetHost() }
            CompositionLocalProvider(LocalSheetHost provides sheetHost) {
                BoxWithConstraints {
                    val wide = maxWidth >= WideLayout
                    // Settings and its screens have no bottom bar or rail, as their designs.
                    val inSettings = navigator.backStack.last().let { it in SETTINGS_SCREENS + ASSISTANTS || it is Screen.Licence }
                    Row {
                        if (wide && !inSettings) SectionRail(navigator)
                        Scaffold(
                            topBar = {
                                TopBar(navigator, themeMode, display::setThemeMode, menuFor, services, skyList, onReview = {
                                    reviewFor = it
                                }, onMenu = {
                                    // The diary's button exports at once; the others open their menus.
                                    if (it == Screen.NightDiary) {
                                        services.logExport.export()
                                    } else {
                                        menuFor = if (menuFor == it) null else it
                                    }
                                }, mark = nightMark) {
                                    // Closing an assistant ends what it does.
                                    services.polarAlignment.stop()
                                    services.autofocus.cancel()
                                    services.autofocus.dismiss()
                                    services.flats?.stop()
                                    services.flats?.dismiss()
                                    navigator.back()
                                }
                            },
                            bottomBar = {
                                Column {
                                    // On every screen but Night's, which shows the run whole.
                                    if (navigator.backStack.last() != Screen.Root(Section.Night)) {
                                        RunningSessionBar(services) {
                                            navigator.select(Section.Night)
                                            if (navigator.backStack.size > 1) navigator.select(Section.Night)
                                        }
                                    }
                                    if (!wide && !inSettings) SectionBar(navigator)
                                }
                            },
                        ) { padding ->
                            key(navigator.section) {
                                NavDisplay(
                                    backStack = navigator.backStack,
                                    modifier = Modifier.padding(padding),
                                    onBack = {
                                        // Leaving the polar alignment ends it.
                                        if (navigator.backStack.last() == Screen.PolarAlignment) services.polarAlignment.stop()
                                        if (navigator.backStack.last() == Screen.Autofocus) {
                                            services.autofocus.cancel()
                                            services.autofocus.dismiss()
                                        }
                                        if (navigator.backStack.last() == Screen.Flats) {
                                            services.flats?.stop()
                                            services.flats?.dismiss()
                                        }
                                        navigator.back()
                                    },
                                    entryProvider =
                                    entryProvider {
                                        entry<Screen.Root> {
                                            SectionRoot(it.section, services, navigator, menuFor == it, skyList, {
                                                skyList = it
                                            }, mapTarget, {
                                                mapTarget =
                                                    null
                                            }, onMenuOpen = { menuFor = it }) {
                                                menuFor =
                                                    null
                                            }
                                        }
                                        entry<Screen.Settings> {
                                            SettingsScreen(
                                                navigator::open,
                                                services.equipment,
                                                services.skyCaches,
                                                services.languages,
                                                services.logExport,
                                            )
                                        }
                                        entry<Screen.Display> { DisplayScreen(display) }
                                        entry<Screen.Language> { LanguageScreen(services.languages) }
                                        entry<Screen.About> { AboutScreen(app, navigator::open) }
                                        entry<Screen.Licences> { LicencesScreen(navigator::open) }
                                        entry<Screen.Licence> { LicenceScreen(it.id) }
                                        entry<Screen.Permissions> { PermissionsScreen(services.permissions) }
                                        entry<Screen.ImageFiles> {
                                            ImageFilesScreen(services.equipment, services.frameFolder, services.clock, services.zone)
                                        }
                                        entry<Screen.PlateSolving> {
                                            PlateSolvingScreen(
                                                services.equipment,
                                                services.plateSolving,
                                                services.centring,
                                                services.starDatabases,
                                                services.files,
                                            )
                                        }
                                        entry<Screen.Profile> {
                                            ProfileScreen(
                                                services.equipment,
                                                services.starDatabases,
                                                services.files,
                                                services.permissions,
                                                navigator::open,
                                            ) {
                                                navigator.back()
                                            }
                                        }
                                        entry<Screen.NewProfile> {
                                            // As "First steps · your equipment": the server found becomes the new profile, in use.
                                            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                                                FindEquipment(services.equipment, onUsed = {
                                                    navigator.back()
                                                })
                                            }
                                        }
                                        entry<Screen.AddEquipment> {
                                            // As "Equipment · add device": each device the profile lacks joins it with a tap.
                                            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                                                FindEquipment(services.equipment)
                                            }
                                        }
                                        entry<Screen.PolarAlignment> {
                                            PolarAlignmentScreen(services.polarAlignment, services.equipment, services.clock) {
                                                navigator.back()
                                            }
                                        }
                                        entry<Screen.Autofocus> { AutofocusScreen(services.autofocus) { navigator.back() } }
                                        entry<Screen.Flats> {
                                            services.flats?.let { flats ->
                                                FlatsScreen(flats, services.equipment, services.capture, services.beeper) {
                                                    flats.dismiss()
                                                    navigator.back()
                                                }
                                            }
                                        }
                                        entry<Screen.AutofocusSettings> { AutofocusSettingsScreen(services.equipment) }
                                        entry<Screen.SkyMapSettings> {
                                            services.skyCaches?.let { SkyMapSettingsScreen(it, services.files) }
                                        }
                                        entry<Screen.SkyObject> {
                                            SkyObjectScreen(
                                                it.designation,
                                                services.objects,
                                                services.equipment,
                                                services.skyCaches,
                                                services.centring,
                                                services.clock,
                                                services.zone,
                                                onMap = { item ->
                                                    mapTarget = item
                                                    skyList = false
                                                    navigator.root(Section.Sky)
                                                },
                                            ) { screen ->
                                                navigator.go(screen)
                                            }
                                        }
                                        entry<Screen.Framing> {
                                            FramingScreen(
                                                it.designation,
                                                services.objects,
                                                services.equipment,
                                                services.skyCaches,
                                                services.centring,
                                                services.targets,
                                                services.clock,
                                                services.zone,
                                                it.at,
                                            ) { screen ->
                                                navigator.go(screen)
                                            }
                                        }
                                        entry<Screen.SafetySettings> { SafetySettingsScreen(services.equipment) }
                                        entry<Screen.CameraSettings> { CameraSettingsScreen(services.equipment) }
                                        entry<Screen.NightDiary> { NightDiaryScreen(services) }
                                        entry<Screen.SequenceTonight> {
                                            SequenceTonight(
                                                services.targets,
                                                services.sequence,
                                                services.equipment,
                                                services.clock,
                                                services.zone,
                                                open = { screen ->
                                                    navigator.go(screen)
                                                },
                                                saved = services.savedSequences,
                                                menuOpen = menuFor == it,
                                            ) { menuFor = null }
                                        }
                                        entry<Screen.Advanced> {
                                            AdvancedScreen(
                                                it.path,
                                                services,
                                                openPath = { path -> navigator.openAdvanced(path) },
                                                openAdd = { path, holder, category ->
                                                    navigator.open(Screen.AdvancedAdd(path, holder, category))
                                                },
                                                menuOpen = menuFor == it,
                                                onMenuClose = { menuFor = null },
                                                reviewOpen = reviewFor == it,
                                            ) { reviewFor = null }
                                        }
                                        entry<Screen.AdvancedAdd> { AdvancedCatalogue(it.path, it.category, services) { navigator.back() } }
                                        entry<Screen.History> {
                                            HistoryScreen(
                                                services.capture,
                                                services.autofocus,
                                                services.zone,
                                                onlyDiscarded = menuFor == it,
                                            )
                                        }
                                        entry<Screen.Device> {
                                            DeviceScreen(
                                                it.role,
                                                services.equipment,
                                                services.centring,
                                                services.autofocus,
                                                services.zone,
                                                open = { screen ->
                                                    navigator.go(screen)
                                                },
                                                menuOpen = menuFor == it,
                                            ) { menuFor = null }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                sheetHost.Show()
                ScreenLight(services.flats)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    navigator: Navigator,
    themeMode: YacanaThemeMode,
    onThemeMode: (YacanaThemeMode) -> Unit,
    menuFor: Screen?,
    services: AppServices,
    skyList: Boolean,
    onReview: (Screen) -> Unit,
    onMenu: (Screen) -> Unit,
    mark: NightMark,
    onCloseAssistant: () -> Unit,
) {
    val screen = navigator.backStack.last()
    TopAppBar(
        title = { Text(title(screen)) },
        navigationIcon = {
            // An assistant closes, as its designs draw it.
            if (screen in ASSISTANTS) {
                IconButton(onClick = onCloseAssistant) { Icon(YacanaIcons.Close, contentDescription = stringResource(Res.string.close)) }
            } else if (navigator.backStack.size > 1) {
                IconButton(onClick = { navigator.back() }) { Icon(YacanaIcons.Back, contentDescription = stringResource(Res.string.back)) }
            }
        },
        actions = {
            // Each section's actions are its design's; screens inside a section have none yet.
            val settings = @Composable {
                IconButton(onClick = { navigator.open(Screen.Settings) }) {
                    Icon(YacanaIcons.Settings, contentDescription = stringResource(Res.string.settings))
                }
            }
            when (screen) {
                Screen.Root(Section.Night) -> {
                    // As "Night · alert", a warning while the guard reconnects or stops safely; otherwise, connected, the shield.
                    when (mark) {
                        NightMark.Alert -> Icon(
                            YacanaIcons.Alert,
                            contentDescription = stringResource(Res.string.alert_mark),
                            tint = MaterialTheme.colorScheme.error,
                        )

                        NightMark.Ok -> Icon(
                            YacanaIcons.ShieldCheck,
                            contentDescription = stringResource(Res.string.equipment_ok),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )

                        NightMark.None -> Unit
                    }
                    RedModeButton(themeMode, onThemeMode)
                    settings()
                }

                Screen.Root(Section.Equipment) -> {
                    IconButton(onClick = { navigator.open(Screen.AddEquipment) }) {
                        Icon(YacanaIcons.Add, contentDescription = stringResource(Res.string.profile_add_equipment))
                    }
                    RedModeButton(themeMode, onThemeMode)
                    settings()
                }

                // The sky's layers, as "Sky · explorer": its caches and their opacity.
                // The list's filters, as "Sky · Sky Atlas", or the map's layers.
                Screen.Root(Section.Sky) -> {
                    if (skyList) {
                        // How many filters are on, beyond Tonight and the favourites, as the list's buttons with ✕.
                        val on = filtersOn(services.skyBrowsing.filters.collectAsState().value).size
                        IconButton(onClick = { onMenu(screen) }) {
                            BadgedBox(
                                badge = {
                                    if (on > 0) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        ) {
                                            Text("$on")
                                        }
                                    }
                                },
                            ) {
                                Icon(YacanaIcons.Adjustments, contentDescription = stringResource(Res.string.atlas_filters))
                            }
                        }
                    } else {
                        IconButton(onClick = { navigator.open(Screen.SkyMapSettings) }) {
                            Icon(YacanaIcons.Layers, contentDescription = stringResource(Res.string.sky_open_layers))
                        }
                    }
                    RedModeButton(themeMode, onThemeMode)
                }

                Screen.PolarAlignment, Screen.Autofocus, Screen.Flats -> RedModeButton(themeMode, onThemeMode)

                // The framing's sky layers, as "Sky · framing".
                is Screen.Framing -> {
                    IconButton(onClick = { navigator.open(Screen.SkyMapSettings) }) {
                        Icon(YacanaIcons.Layers, contentDescription = stringResource(Res.string.sky_open_layers))
                    }
                    RedModeButton(themeMode, onThemeMode)
                }

                // The object's star, filled while it is a favourite, as the design's page.
                is Screen.SkyObject -> {
                    val marked by services.favourites.marked.collectAsState()
                    val favourite = screen.designation in marked
                    IconButton(onClick = { services.favourites.toggle(screen.designation) }) {
                        Icon(
                            if (favourite) YacanaIcons.StarFilled else YacanaIcons.Star,
                            contentDescription =
                            stringResource(if (favourite) Res.string.obj_favourite_remove else Res.string.obj_favourite_add),
                            tint = if (favourite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    RedModeButton(themeMode, onThemeMode)
                }

                // Imaging's menu holds Settings.
                Screen.Root(Section.Imaging) -> {
                    RedModeButton(themeMode, onThemeMode)
                    IconButton(onClick = { onMenu(screen) }) {
                        Icon(YacanaIcons.More, contentDescription = stringResource(Res.string.imaging_more))
                    }
                }

                // Tonight's "Advanced" opens the advanced editor; its menu saves it in "My sequences".
                Screen.SequenceTonight -> {
                    TextButton(onClick = { navigator.openAdvanced(emptyList()) }) { Text(stringResource(Res.string.adv_title)) }
                    IconButton(onClick = { onMenu(screen) }) {
                        Icon(YacanaIcons.More, contentDescription = stringResource(Res.string.imaging_more))
                    }
                }

                // The advanced editor's warnings, its undo and its menu, as its design.
                is Screen.Advanced -> AdvancedActions(services, onReview = { onReview(screen) }) { onMenu(screen) }

                // The diary shares the session's log.
                Screen.NightDiary -> IconButton(onClick = { onMenu(screen) }) {
                    Icon(YacanaIcons.FileExport, contentDescription = stringResource(Res.string.summary_export))
                }

                // The history's filter leaves only the discarded frames.
                Screen.History -> IconButton(onClick = { onMenu(screen) }) {
                    Icon(
                        YacanaIcons.Filter,
                        contentDescription = stringResource(Res.string.history_only_discarded),
                        tint = if (menuFor == screen) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }

                // The mount's menu holds what is used less: the tracking rate, unpark and home.
                Screen.Device(DeviceRole.Mount) -> IconButton(onClick = { onMenu(screen) }) {
                    Icon(YacanaIcons.More, contentDescription = stringResource(Res.string.imaging_more))
                }

                else -> Unit
            }
        },
    )
}

@Composable
private fun title(screen: Screen): String = when (screen) {
    is Screen.Root -> stringResource(screen.section.title)
    Screen.Settings -> stringResource(Res.string.settings)
    Screen.Display -> stringResource(Res.string.display)
    Screen.Language -> stringResource(Res.string.language)
    Screen.About -> stringResource(Res.string.about)
    Screen.Licences -> stringResource(Res.string.oss_title)
    is Screen.Licence -> OPEN_SOURCE.first { it.id == screen.id }.name
    Screen.Permissions -> stringResource(Res.string.permissions)
    Screen.ImageFiles -> stringResource(Res.string.image_files)
    Screen.PlateSolving -> stringResource(Res.string.plate_solving)
    Screen.Profile -> stringResource(Res.string.settings_profile)
    Screen.AddEquipment -> stringResource(Res.string.profile_add_equipment)
    Screen.NewProfile -> stringResource(Res.string.profile_new)
    is Screen.Device -> stringResource(screen.role.title)
    Screen.PolarAlignment -> stringResource(Res.string.polar_alignment)
    Screen.Autofocus, Screen.AutofocusSettings -> stringResource(Res.string.autofocus)
    Screen.History -> stringResource(Res.string.history)
    is Screen.SkyObject -> screen.designation
    is Screen.Framing -> stringResource(Res.string.framing_title, screen.designation)
    Screen.SequenceTonight -> stringResource(Res.string.sequence_tonight)
    Screen.SafetySettings -> stringResource(Res.string.safety)
    Screen.CameraSettings -> stringResource(Res.string.camera_settings)
    Screen.NightDiary -> stringResource(Res.string.diary_title)
    Screen.Flats -> stringResource(Res.string.flats_title)
    Screen.SkyMapSettings -> stringResource(Res.string.sky_settings)
    is Screen.Advanced -> stringResource(Res.string.adv_title)
    is Screen.AdvancedAdd -> stringResource(Res.string.adv_add_to, screen.holder)
}

@Composable
private fun SectionRail(navigator: Navigator) {
    NavigationRail {
        Section.entries.forEach { item ->
            NavigationRailItem(
                selected = item == navigator.section,
                onClick = { navigator.select(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { SectionLabel(stringResource(item.title)) },
                colors =
                NavigationRailItemDefaults.colors(
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** The session bar while a sequence goes on or waits in a pause; nothing otherwise. */
@Composable
private fun RunningSessionBar(services: AppServices, onOpen: () -> Unit) {
    val run = services.sequence.state.collectAsState().value
    if (run == null || (run.status != RunStatus.Running && run.status != RunStatus.Paused)) return
    val activity = services.capture.state.collectAsState().value.activity
    SessionBar(run, activity, services.clock, onPause = {
        services.sequence.pause()
    }, onResume = { services.sequence.resume() }, onOpen = onOpen)
}

@Composable
private fun SectionBar(navigator: Navigator) {
    // As the designs' bottom bar: the selected section in the accent fill, its name in the main text colour.
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Section.entries.forEach { item ->
            NavigationBarItem(
                selected = item == navigator.section,
                onClick = { navigator.select(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { SectionLabel(stringResource(item.title)) },
                colors =
                NavigationBarItemDefaults.colors(
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** A section's name under its icon, on one line, a little smaller where the language's word is long, as "Equipamento". */
@Composable
private fun SectionLabel(text: String) {
    val style = LocalTextStyle.current
    val size = if (style.fontSize.isSpecified) style.fontSize else LABEL_SIZE
    BasicText(
        text,
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = MIN_LABEL_SIZE, maxFontSize = size, stepSize = LABEL_STEP),
    )
}

@Composable
private fun RedModeButton(mode: YacanaThemeMode, onChange: (YacanaThemeMode) -> Unit) {
    val red = mode == YacanaThemeMode.Red
    IconButton(onClick = { onChange(if (red) YacanaThemeMode.Dark else YacanaThemeMode.Red) }) {
        Icon(
            YacanaIcons.RedMode,
            contentDescription = stringResource(if (red) Res.string.red_mode_turn_off else Res.string.red_mode_turn_on),
        )
    }
}

@Composable
@Suppress("LongParameterList") // Each section's root, with its menu and the Sky's list or map.
private fun SectionRoot(
    section: Section,
    services: AppServices,
    navigator: Navigator,
    menuOpen: Boolean,
    skyList: Boolean,
    onSkyList: (Boolean) -> Unit,
    mapTarget: SkyObject?,
    onMapTarget: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuClose: () -> Unit,
) {
    when (section) {
        Section.Night ->
            NightScreen(services) { screen -> navigator.go(screen) }

        Section.Equipment -> EquipmentScreen(services.equipment, navigator::open)

        Section.Sky -> SkyScreen(
            services.objects,
            services.equipment,
            services.centring,
            services.skyCaches,
            services.favourites,
            services.skyBrowsing,
            services.clock,
            services.zone,
            skyList,
            onSkyList,
            menuOpen,
            onMenuOpen,
            onMenuClose,
            navigator::open,
            mapTarget,
            onMapTarget,
        )

        Section.Imaging -> ImagingScreen(
            services.capture,
            services.equipment,
            services.display,
            services.plateSolving,
            services.centring,
            open = { screen -> navigator.go(screen) },
            optionsOpen = menuOpen,
            onOptionsClose = onMenuClose,
            caches = services.skyCaches,
        )

        Section.Sequence ->
            MySequences(
                services.targets,
                services.savedSequences,
                services.sequence,
                services.equipment,
                services.clock,
                services.zone,
                open = { screen -> navigator.go(screen) },
            )

        else -> SectionPlaceholder(section)
    }
}

/** A section that a later block or phase fills in: what it is for, and when it arrives. */
@Composable
private fun SectionPlaceholder(section: Section) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(section.purpose), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(Res.string.section_arrives, section.phase),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Settings and the screens it opens, which the designs draw without the sections' bars. */
private val SETTINGS_SCREENS: Set<Screen> =
    setOf(
        Screen.Settings,
        Screen.Profile,
        Screen.NewProfile,
        Screen.ImageFiles,
        Screen.PlateSolving,
        Screen.AutofocusSettings,
        Screen.SkyMapSettings,
        Screen.Display,
        Screen.Language,
        Screen.Permissions,
        Screen.About,
        Screen.Licences,
    )

/**
 * Opens [screen] where it belongs: a section's root by selecting the section, which shows its root
 * when it is already selected; tonight's sequence over "My sequences"; any other over the screen shown.
 */
private fun Navigator.go(screen: Screen) {
    when (screen) {
        is Screen.Root -> select(screen.section)

        Screen.SequenceTonight -> {
            if (section != Section.Sequence) select(Section.Sequence)
            open(screen)
        }

        is Screen.Advanced -> {
            if (section != Section.Sequence) select(Section.Sequence)
            openAdvanced(screen.path)
        }

        else -> open(screen)
    }
}

/** The mark in Night's top bar: nothing, the equipment without problems, or a warning. */
private enum class NightMark { None, Ok, Alert }

/** The full-screen assistants: no bottom bar, and a ✕ that ends what they do. */
private val ASSISTANTS = setOf(Screen.PolarAlignment, Screen.Autofocus, Screen.Flats)

private val LABEL_SIZE = 12.sp
private val MIN_LABEL_SIZE = 9.sp
private val LABEL_STEP = 0.5.sp
