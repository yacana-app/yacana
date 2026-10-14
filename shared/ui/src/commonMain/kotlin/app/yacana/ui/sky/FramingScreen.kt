// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.Profile
import app.yacana.domain.sequence.Target
import app.yacana.domain.sequence.Targets
import app.yacana.domain.sequence.fillingTheNight
import app.yacana.domain.sequence.newTargetExposures
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.sky.Framing
import app.yacana.domain.sky.ObjectSearch
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.sky.SkyView
import app.yacana.domain.sky.skySpot
import app.yacana.domain.solver.Centring
import app.yacana.ports.SkyPosition
import app.yacana.ui.Section
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.equipment.RoundButton
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.framing_add
import app.yacana.ui.resources.framing_added
import app.yacana.ui.resources.framing_another
import app.yacana.ui.resources.framing_columns
import app.yacana.ui.resources.framing_field
import app.yacana.ui.resources.framing_high
import app.yacana.ui.resources.framing_hint
import app.yacana.ui.resources.framing_less
import app.yacana.ui.resources.framing_low
import app.yacana.ui.resources.framing_more
import app.yacana.ui.resources.framing_no_field
import app.yacana.ui.resources.framing_overlap
import app.yacana.ui.resources.framing_rotation
import app.yacana.ui.resources.framing_rotation_measured
import app.yacana.ui.resources.framing_rotation_unmeasured
import app.yacana.ui.resources.framing_rows
import app.yacana.ui.resources.framing_see_tonight
import app.yacana.ui.resources.mount_go_and_centre
import app.yacana.ui.resources.night_open_profile
import app.yacana.ui.resources.obj_no_mount
import app.yacana.ui.resources.sky_loading
import app.yacana.ui.resources.sky_no_cache
import app.yacana.ui.resources.sky_survey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * "Sky · framing": the object's sky with the profile's field at the centre,
 * dragged by moving the sky and zoomed with two fingers, turned as the camera is, which does not
 * turn; a mosaic of panels overlapping as asked; when it is high tonight; and "Go and centre"
 * or "Add to the sequence" with the framing's centre exactly as framed.
 */
@Composable
@Suppress("LongParameterList") // The framing shows the object with the equipment, the photos, the sequence and the time.
fun FramingScreen(
    designation: String,
    objects: ObjectSearch,
    equipment: EquipmentController,
    caches: SkyCacheStore?,
    centring: Centring,
    targets: Targets,
    clock: Clock,
    zone: () -> TimeZone,
    at: SkyPosition? = null,
    open: (Screen) -> Unit,
) {
    val catalogue by objects.objects.collectAsState()
    // A spot of the sky, from the map, or an object of the catalogue.
    val item = remember(catalogue, designation, at) { at?.let(::skySpot) ?: catalogue?.find(designation) }
    val state by equipment.state.collectAsState()
    val field = state.active?.optics?.fieldDeg()
    if (item == null || field == null) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(
                    if (item ==
                        null
                    ) {
                        Res.string.sky_loading
                    } else {
                        Res.string.framing_no_field
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            // Where the field is set, without connecting anything.
            if (item != null) OutlinedButton(onClick = { open(Screen.Profile) }) { Text(stringResource(Res.string.night_open_profile)) }
        }
        return
    }
    // Without a rotator the camera does not turn: the framing takes its rotation, as plate solving measured it.
    val measured = state.active?.optics?.measuredRotationDeg
    var framing by remember(item, field, measured) { mutableStateOf(Framing.of(item, field, measured ?: 0.0)) }
    var viewField by remember(item, field) { mutableDoubleStateOf(max(framing.widthDeg, framing.heightDeg) * AROUND) }
    // A mosaic of other columns or rows shows whole, as the framing does when it opens.
    LaunchedEffect(framing.columns, framing.rows) { viewField = max(framing.widthDeg, framing.heightDeg) * AROUND }
    var added by remember(item) { mutableStateOf(false) }
    val mount = (state.connection as? Connection.Connected)?.equipment?.mount
    Column(Modifier.fillMaxSize()) {
        // As the sky looks from the profile's site now, the zenith up, as the map.
        val site = state.active?.location
        val roll = site?.let { SkyView.zenithUp(framing.center, it.latitude, Ephemerides.localSiderealDeg(clock.now(), it)) } ?: 0.0
        Sky(framing, viewField, roll, caches, open, { framing = it }, { viewField = it }, Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                YacanaIcons.HandMove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(Res.string.framing_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Controls(framing, measured != null, state.active?.location?.let { site -> high(framing, site, clock, zone()) }) { framing = it }
        if (added) {
            // As "Sky · framing, added": on to the next target, or to tonight's plan.
            Muted(stringResource(Res.string.framing_added, item.name()))
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SurfaceButton(stringResource(Res.string.framing_another), { open(Screen.Root(Section.Sky)) }, Modifier.weight(1f))
                AccentButton(onClick = { open(Screen.SequenceTonight) }, modifier = Modifier.weight(1.2f)) {
                    Text(stringResource(Res.string.framing_see_tonight))
                }
            }
            return@Column
        }
        if (mount == null) Muted(stringResource(Res.string.obj_no_mount))
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SurfaceButton(
                stringResource(Res.string.mount_go_and_centre),
                {
                    centring.centre(framing.center)
                    open(Screen.Device(DeviceRole.Mount))
                },
                Modifier.weight(1f),
                enabled = mount != null,
            )
            AccentButton(
                onClick = {
                    val filters = state.active?.devices?.filterNames.orEmpty()
                    val target = Target.of(item.designation, framing, newTargetExposures(filters))
                    targets.add(if (targets.fillTheNight.value) target.fillingTonight(state.active, clock, zone()) else target)
                    added = true
                },
                modifier = Modifier.weight(1.5f),
            ) { Text(stringResource(Res.string.framing_add)) }
        }
    }
}

/** The sky around the framing, with its chips: where the photos come from, and the field's size. */
@Composable
@Suppress("LongParameterList") // The sky draws the framing and moves it.
private fun Sky(
    framing: Framing,
    viewField: Double,
    roll: Double,
    caches: SkyCacheStore?,
    open: (Screen) -> Unit,
    onFraming: (Framing) -> Unit,
    onViewField: (Double) -> Unit,
    modifier: Modifier,
) {
    val inUse by (caches?.layers ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val view = MapView(framing.center, viewField, roll)
    val layers = drawnLayers(inUse, view, size)
    val comma = decimalComma()
    Box(modifier.onSizeChanged { size = it }) {
        SkyMap(
            view,
            layers,
            emptyList(),
            MapMarks(null, "", null, ""),
            null,
            onView = { moved ->
                onFraming(framing.copy(center = moved.center))
                onViewField(moved.fieldDeg)
            },
            onTap = {},
            modifier = Modifier.fillMaxSize(),
            framing = framing,
        )
        Label(
            if (inUse.isEmpty()) {
                stringResource(Res.string.sky_no_cache)
            } else {
                stringResource(Res.string.sky_survey, inUse.map { it.cache.survey }.distinct().joinToString(" + "))
            },
            Modifier.align(Alignment.TopStart).padding(8.dp).clickable(enabled = inUse.isEmpty()) { open(Screen.SkyMapSettings) },
        )
        Label(
            stringResource(Res.string.framing_field, formatDecimal(framing.longDeg, 2, comma), formatDecimal(framing.shortDeg, 2, comma)),
            Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
    }
}

/**
 * The camera's rotation, which the framing takes, and why; "Mosaic" and "Overlap", each with its
 * buttons; and when the framing is high tonight.
 */
@Composable
private fun Controls(framing: Framing, measured: Boolean, high: String?, onFraming: (Framing) -> Unit) {
    val comma = decimalComma()
    SectionCard {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.framing_rotation), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${formatDecimal(framing.rotationDeg, 1, comma)}°", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(if (measured) Res.string.framing_rotation_measured else Res.string.framing_rotation_unmeasured),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // Columns along the field's long side and rows across it, each from one, as many as wanted, as NINA's.
        Setting(
            stringResource(Res.string.framing_columns),
            "${framing.columns}",
            onLess = if (framing.columns > 1) ({ onFraming(framing.copy(columns = framing.columns - 1)) }) else null,
            onMore = { onFraming(framing.copy(columns = framing.columns + 1)) },
        )
        Setting(
            stringResource(Res.string.framing_rows),
            "${framing.rows}",
            onLess = if (framing.rows > 1) ({ onFraming(framing.copy(rows = framing.rows - 1)) }) else null,
            onMore = { onFraming(framing.copy(rows = framing.rows + 1)) },
        )
        val overlap = (framing.overlap * PERCENT).roundToInt()
        Setting(
            stringResource(Res.string.framing_overlap),
            "$overlap %",
            onLess = if (overlap >
                MIN_OVERLAP
            ) {
                ({ onFraming(framing.copy(overlap = (overlap - OVERLAP_STEP) / PERCENT.toDouble())) })
            } else {
                null
            },
            onMore = if (overlap <
                MAX_OVERLAP
            ) {
                ({ onFraming(framing.copy(overlap = (overlap + OVERLAP_STEP) / PERCENT.toDouble())) })
            } else {
                null
            },
        )
        high?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A setting as the design's rows: its name, and its value between − and +. */
@Composable
private fun Setting(label: String, value: String, onLess: (() -> Unit)?, onMore: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RoundButton(enabled = onLess != null, onClick = { onLess?.invoke() }) {
            Icon(YacanaIcons.Minus, contentDescription = stringResource(Res.string.framing_less, label))
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = VALUE_WIDTH).padding(horizontal = 8.dp),
        )
        RoundButton(enabled = onMore != null, onClick = { onMore?.invoke() }) {
            Icon(YacanaIcons.Add, contentDescription = stringResource(Res.string.framing_more, label))
        }
    }
}

/** "Above 30° from 21:50 to 04:30" tonight at the profile's site, for the framing's centre. */
@Composable
private fun high(framing: Framing, site: app.yacana.domain.profile.Location, clock: Clock, zone: TimeZone): String {
    val now = clock.now()
    val night = remember(site, now.toLocalDateTime(zone).date) { Ephemerides.night(evening(now, zone), site) }
    val samples = remember(framing.center, night) { Ephemerides.altitudes(framing.center, night, site) }
    val above = samples.filter { it.second >= GOOD }
    return if (above.isEmpty()) {
        stringResource(Res.string.framing_low)
    } else {
        stringResource(Res.string.framing_high, clockTime(above.first().first, zone), clockTime(above.last().first, zone))
    }
}

/** The design's chips over the sky (`ch`). */
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

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

/** The sky shows the framing with half as much again around it. */
private const val AROUND = 1.6
private const val FULL_TURN = 360.0
private const val PERCENT = 100
private const val OVERLAP_STEP = 5
private const val MIN_OVERLAP = 5
private const val MAX_OVERLAP = 50
private val VALUE_WIDTH = 64.dp

/** [this] target filling the night of [profile]'s site from now, or from dusk, to dawn; as it is without a site. */
private fun Target.fillingTonight(profile: Profile?, clock: Clock, zone: TimeZone): Target {
    val site = profile?.location ?: return this
    val now = clock.now()
    val night = Ephemerides.night(evening(now, zone), site)
    val dusk = night.astronomicalDusk
    val start = if (dusk != null && dusk > now) dusk else now
    return fillingTheNight(start, night.astronomicalDawn, profile.options.dither)
}
