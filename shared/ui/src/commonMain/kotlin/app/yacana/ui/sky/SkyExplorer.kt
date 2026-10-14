// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.Location
import app.yacana.domain.sky.Catalogue
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.sky.Favourites
import app.yacana.domain.sky.MapPlace
import app.yacana.domain.sky.ObjectSearch
import app.yacana.domain.sky.OnlinePhoto
import app.yacana.domain.sky.OnlinePhotos
import app.yacana.domain.sky.SkyBrowsing
import app.yacana.domain.sky.SkyCacheFiles
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.sky.SkyHorizon
import app.yacana.domain.sky.SkyObject
import app.yacana.domain.sky.SkyView
import app.yacana.domain.sky.spotName
import app.yacana.domain.solver.Centring
import app.yacana.domain.solver.CentringState
import app.yacana.ports.SkyPosition
import app.yacana.ports.SkyTile
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.equipment.act
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.east_short
import app.yacana.ui.resources.framing_open
import app.yacana.ui.resources.north_short
import app.yacana.ui.resources.sky_altitude
import app.yacana.ui.resources.sky_best
import app.yacana.ui.resources.sky_coordinates
import app.yacana.ui.resources.sky_frame_here
import app.yacana.ui.resources.sky_framing_label
import app.yacana.ui.resources.sky_list
import app.yacana.ui.resources.sky_map
import app.yacana.ui.resources.sky_map_hint
import app.yacana.ui.resources.sky_no_cache
import app.yacana.ui.resources.sky_online_fetching
import app.yacana.ui.resources.sky_survey
import app.yacana.ui.resources.sky_telescope
import app.yacana.ui.resources.sky_zoom_in
import app.yacana.ui.resources.sky_zoom_out
import app.yacana.ui.resources.south_short
import app.yacana.ui.resources.west_short
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Sky section, as "Sky · explorer" and "Sky · Sky Atlas": the map or the [list]
 * of objects, one or the other; the top bar knows which, for its buttons. The list's filters stay,
 * kept by [browsing] also after the app is closed, and their sheet opens from the top bar or from
 * a filter that is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // The map shows the catalogue, the equipment and the cache at the time and place of the night.
fun SkyScreen(
    objects: ObjectSearch,
    equipment: EquipmentController,
    centring: Centring,
    caches: SkyCacheStore?,
    favourites: Favourites,
    browsing: SkyBrowsing,
    clock: Clock,
    zone: () -> TimeZone,
    list: Boolean,
    onList: (Boolean) -> Unit,
    filtersOpen: Boolean,
    onFiltersOpen: () -> Unit,
    onFiltersClose: () -> Unit,
    open: (Screen) -> Unit,
    target: SkyObject? = null,
    onTarget: () -> Unit = {},
) {
    val filters by browsing.filters.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
            SegmentedButton(selected = !list, onClick = { onList(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(Res.string.sky_map))
            }
            SegmentedButton(selected = list, onClick = { onList(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(Res.string.sky_list))
            }
        }
        if (list) {
            SkySearch(objects, open) { shown ->
                SkyAtlasList(
                    objects,
                    equipment,
                    caches,
                    favourites,
                    clock,
                    zone,
                    filters,
                    browsing::filter,
                    filtersOpen,
                    onFiltersOpen,
                    onFiltersClose,
                    open,
                    shown,
                )
            }
        } else {
            SkyExplorer(objects, equipment, centring, caches, browsing, clock, zone, open, target, onTarget)
        }
    }
}

/**
 * The map with what is on it: the source of its photos, zoom and "Frame here" at the side,
 * the coordinates of its centre, how to move it, and the object tapped, which the map centres and
 * whose card opens its page. With a mount, the telescope's field where it points, what it
 * is doing, and under the map going to the framing and the way there. It opens where it was left,
 * which [browsing] keeps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // The map, and the mount on it.
private fun SkyExplorer(
    objects: ObjectSearch,
    equipment: EquipmentController,
    centring: Centring,
    caches: SkyCacheStore?,
    browsing: SkyBrowsing,
    clock: Clock,
    zone: () -> TimeZone,
    open: (Screen) -> Unit,
    target: SkyObject?,
    onTarget: () -> Unit,
) {
    val inUse by (caches?.layers ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val files by (caches?.state ?: remember { MutableStateFlow(SkyCacheFiles()) }).collectAsState()
    val catalogue by objects.objects.collectAsState()
    val state by equipment.state.collectAsState()
    var ra by rememberSaveable { mutableDoubleStateOf(browsing.place.value.raHours) }
    var dec by rememberSaveable { mutableDoubleStateOf(browsing.place.value.decDeg) }
    var field by rememberSaveable { mutableDoubleStateOf(browsing.place.value.fieldDeg) }
    var selected by remember { mutableStateOf<SkyObject?>(null) }
    var goingOnly by remember { mutableStateOf<SkyPosition?>(null) }
    var failure by remember { mutableStateOf<Exception?>(null) }
    val scope = rememberCoroutineScope()
    val mount = (state.connection as? Connection.Connected)?.equipment?.mount
    val mountState = mount?.state?.collectAsState()?.value?.value
    val centringState by centring.state.collectAsState()
    val telescope = mountState?.let { glided(mount.fromMount(SkyPosition(it.rightAscensionHours, it.declinationDeg), clock.now())) }
    val center = SkyPosition(ra, dec)
    // As the sky looks from the profile's site now: the zenith up; north up without a site.
    val site = state.active?.location
    val roll = site?.let { SkyView.zenithUp(center, it.latitude, Ephemerides.localSiderealDeg(clock.now(), it)) } ?: 0.0
    val view = MapView(center, field, roll)
    // Where the map is, kept once it stops moving, and as it is left.
    LaunchedEffect(ra, dec, field) {
        browsing.moveTo(MapPlace(ra, dec, field))
        delay(KEEP_AFTER)
        browsing.save()
    }
    DisposableEffect(browsing) {
        onDispose { browsing.save() }
    }

    fun go(to: MapView) {
        ra = to.center.raHours
        dec = to.center.decDeg
        field = to.fieldDeg
    }

    fun act(block: suspend () -> Unit) = scope.act({
        failure = null
        centring.dismiss()
        block()
    }) { failure = it }
    // An object's photo asked for it on the map: framed in the middle, selected, the telescope left where it is.
    LaunchedEffect(target) {
        val item = target ?: return@LaunchedEffect
        ra = item.position.raHours
        dec = item.position.decDeg
        field = framingField(item, state.active?.optics?.fieldDeg())
        selected = item
        onTarget()
    }
    // "Go only" goes on while the mount slews, once it has started.
    LaunchedEffect(goingOnly, mount) {
        val going = mount ?: return@LaunchedEffect
        if (goingOnly == null) return@LaunchedEffect
        withTimeoutOrNull(SLEW_START) { going.state.first { it.value?.slewing == true } }
        going.state.first { it.value?.slewing != true }
        goingOnly = null
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layers = drawnLayers(inUse, view, size)
    OnlinePhotos(caches, view, size, inUse.filter { it.online }.flatMap { it.tiles }, files.fetchingOnline)
    val shown = remember(view, catalogue) { catalogue?.let { onTheMap(it, view) }.orEmpty() }
    val profile = state.active
    // The horizon from the profile's site, with its own horizon if it has one, again each minute as the sky turns.
    val minute = clock.now().epochSeconds / SECONDS_PER_MINUTE
    val horizon =
        remember(site, profile?.options?.horizon, minute) { site?.let { SkyHorizon(it, clock.now(), profile?.options?.horizon.orEmpty()) } }
    val letters = listOf(Res.string.north_short, Res.string.east_short, Res.string.south_short, Res.string.west_short).map {
        stringResource(it)
    }
    // The field turned as the camera is, which plate solving measured; with the telescope, the framing, as the designs name it.
    val marks = MapMarks(
        profile?.optics?.fieldDeg(),
        if (telescope != null) stringResource(Res.string.sky_framing_label) else profile?.name.orEmpty(),
        telescope,
        stringResource(Res.string.sky_telescope),
        profile?.optics?.measuredRotationDeg ?: 0.0,
        goingTo = (centringState as? CentringState.Working)?.target ?: goingOnly,
        horizon = horizon?.line.orEmpty(),
        cardinals = horizon?.let { letters.zip(it.cardinals) }.orEmpty(),
    )
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp).onSizeChanged { size = it }) {
            SkyMap(view, layers, shown, marks, selected, ::go, { item ->
                selected = item
                // The object tapped goes to the middle, into the framing, where "Go and centre" goes.
                item?.let {
                    ra = it.position.raHours
                    dec = it.position.decDeg
                }
            }, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The telescope's label beside the photos', so the map keeps its room.
                FlowRow(
                    Modifier.widthIn(max = LABELS_WIDTH),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (inUse.isEmpty()) {
                        Label(stringResource(Res.string.sky_no_cache), Modifier.clickable { open(Screen.SkyMapSettings) })
                    } else {
                        SurveyLabel(
                            stringResource(
                                Res.string.sky_survey,
                                inUse.map {
                                    it.cache.survey
                                }.distinct().joinToString(" + "),
                            ),
                            files.fetchingOnline,
                        )
                    }
                    if (mount != null && mountState != null) TelescopeLabel(mountState, mount, ::act)
                }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MapButton(YacanaIcons.Add, stringResource(Res.string.sky_zoom_in)) { go(view.zoomed(ZOOM_STEP)) }
                MapButton(YacanaIcons.Minus, stringResource(Res.string.sky_zoom_out)) { go(view.zoomed(1 / ZOOM_STEP)) }
                // Framing the map's centre, where the field's box is, whatever is there.
                MapButton(YacanaIcons.Mosaic, stringResource(Res.string.sky_frame_here), accent = true) {
                    open(Screen.Framing(spotName(view.center), view.center))
                }
            }
            Label(coordinates(view.center), Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        if (mount != null) {
            MountPanel(
                mount,
                telescope,
                view.center,
                centring,
                centringState,
                goingOnly,
                { goingOnly = it },
                failure,
                clock,
                ::act,
            )
        } else {
            MapHint()
        }
        selected?.let { item ->
            SectionCard(Modifier.padding(bottom = 8.dp), onClick = { open(Screen.SkyObject(item.designation)) }) {
                // As the design's card: what it is and when, and "Frame" beside it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            (listOfNotNull(item.size()) + tonight(item, profile?.location, clock, zone())).ifEmpty {
                                listOf(item.kind())
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AccentButton(onClick = { open(Screen.Framing(item.designation)) }) { Text(stringResource(Res.string.framing_open)) }
                }
            }
        }
    }
}

/**
 * Asks for a photo online of [view], on a map of [size], once it stops moving, when the caches look
 * blurred there and the photos [online] do not cover it; not while one is coming, nor
 * the same one again if it did not come.
 */
@Composable
private fun OnlinePhotos(caches: SkyCacheStore?, view: MapView, size: IntSize, online: List<SkyTile>, fetching: Boolean) {
    var tried by remember { mutableStateOf<OnlinePhoto?>(null) }
    LaunchedEffect(view, size, online, fetching) {
        if (caches == null || fetching || size.width == 0) return@LaunchedEffect
        delay(SETTLE)
        val heightDeg = view.fieldDeg * size.height / size.width
        val photo = OnlinePhotos.wanted(view.center, view.fieldDeg, heightDeg, online) ?: return@LaunchedEffect
        if (photo == tried) return@LaunchedEffect
        tried = photo
        caches.fetchOnline(photo)
    }
}

/** A map wide enough for [item] and the camera's [field], with as much sky again around them. */
private fun framingField(item: SkyObject, field: Pair<Double, Double>?): Double {
    val extent = max((item.majorArcmin ?: 0.0) / ARCMIN_PER_DEGREE, field?.let { max(it.first, it.second) } ?: 0.0)
    return (extent * FRAMING_AROUND).coerceIn(MapView.MIN_FIELD, MapPlace().fieldDeg)
}

/**
 * The objects worth marking in [view]: the Messier ones, those large enough to see as more than a
 * dot, and in a narrow view those with a name; the brightest, if there are too many.
 */
private fun onTheMap(catalogue: Catalogue, view: MapView): List<SkyObject> {
    val reach = view.fieldDeg * REACH
    val large = view.fieldDeg * ARCMIN_PER_DEGREE / VISIBLE_SHARE
    return catalogue.objects
        .filter { item ->
            val worth = item.designation.startsWith("M ") || (item.majorArcmin ?: 0.0) >= large ||
                (view.fieldDeg <= NAMED_FIELD && (item.spanishName != null || item.englishNames.isNotEmpty()))
            worth && SkyView.separation(view.center, item.position) < reach
        }.sortedBy { it.magnitude ?: Double.MAX_VALUE }
        .take(MAX_MARKS)
}

/** As the design's card: "alt 52°" now and "best 00:40", when it is highest tonight; nothing without a site. */
@Composable
private fun tonight(item: SkyObject, site: Location?, clock: Clock, zone: TimeZone): List<String> {
    if (site == null) return emptyList()
    val now = clock.now()
    val best =
        remember(item, site, evening(now, zone)) {
            Ephemerides.altitudes(item.position, Ephemerides.night(evening(now, zone), site), site).maxByOrNull { it.second }?.first
        }
    val altitude = Ephemerides.altitude(item.position, now, site).roundToInt()
    return listOfNotNull(
        stringResource(Res.string.sky_altitude, altitude),
        best?.let { stringResource(Res.string.sky_best, clockTime(it, zone)) },
    )
}

/** "RA 00h 43m · Dec +41°". */
@Composable
private fun coordinates(center: SkyPosition): String {
    val minutes = (center.raHours * MINUTES_PER_HOUR).roundToInt().mod(HOURS * MINUTES_PER_HOUR)
    val ra = "${(minutes / MINUTES_PER_HOUR).toString().padStart(2, '0')}h ${(minutes % MINUTES_PER_HOUR).toString().padStart(2, '0')}m"
    val dec = (if (center.decDeg < 0) "−" else "+") + "${abs(center.decDeg).roundToInt()}°"
    return stringResource(Res.string.sky_coordinates, ra, dec)
}

/** The design's chips over the map (`ch`). */
@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            RoundedCornerShape(8.dp),
        ).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** The photos' label, with a small wheel turning in it while a photo online is on its way, as the design. */
@Composable
private fun SurveyLabel(text: String, fetching: Boolean) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
        if (fetching) {
            val fetchingText = stringResource(Res.string.sky_online_fetching)
            CircularProgressIndicator(
                Modifier.size(FETCHING_SIZE).semantics { contentDescription = fetchingText },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 1.5.dp,
            )
        }
    }
}

/** How to move the map, under it, as the design, when there is no mount to move. */
@Composable
private fun MapHint() {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            YacanaIcons.HandMove,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            stringResource(Res.string.sky_map_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** The design's round buttons at the map's side (`zm`); the [accent] one in gold, as "Go and centre". */
@Composable
private fun MapButton(icon: ImageVector, description: String, accent: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp).background(if (accent) colors.secondaryContainer else colors.surfaceContainerHigh, CircleShape),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (accent) colors.onSecondaryContainer else LocalContentColor.current,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** How long the map stays still before where it is goes into the file. */
private val KEEP_AFTER = 1.seconds
private const val ZOOM_STEP = 1.5
private const val SECONDS_PER_MINUTE = 60L

/** The sky around an object framed from its photo. */
private const val FRAMING_AROUND = 2.0
private val FETCHING_SIZE = 12.dp

/** The labels over the map stop short of its buttons. */
private val LABELS_WIDTH = 260.dp

/** How long "Go only" waits for the mount to say it slews. */
private val SLEW_START = 3.seconds
private const val REACH = 0.9
private const val ARCMIN_PER_DEGREE = 60.0

/** An object is marked by its size from a fortieth of the view's width. */
private const val VISIBLE_SHARE = 40.0

/** From this field down, objects with a name are marked too. */
private const val NAMED_FIELD = 15.0
private const val MAX_MARKS = 150
private const val MINUTES_PER_HOUR = 60

/** How long the map stays still before it asks for a photo online. */
private val SETTLE = 700.milliseconds
private const val HOURS = 24
