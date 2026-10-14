// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.HorizonLimit
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Profile
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.sky.Night
import app.yacana.domain.sky.ObjectSearch
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.sky.SkyObject
import app.yacana.domain.sky.SkyView
import app.yacana.domain.sky.panels
import app.yacana.domain.solver.Centring
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.framing_open
import app.yacana.ui.resources.mount_go_and_centre
import app.yacana.ui.resources.obj_above
import app.yacana.ui.resources.obj_also
import app.yacana.ui.resources.obj_altitude
import app.yacana.ui.resources.obj_field
import app.yacana.ui.resources.obj_fits
import app.yacana.ui.resources.obj_low
import app.yacana.ui.resources.obj_magnitude
import app.yacana.ui.resources.obj_moon
import app.yacana.ui.resources.obj_mosaic
import app.yacana.ui.resources.obj_never
import app.yacana.ui.resources.obj_no_mount
import app.yacana.ui.resources.obj_no_site
import app.yacana.ui.resources.sky_loading
import app.yacana.ui.resources.sky_map
import app.yacana.ui.resources.sky_no_cache
import app.yacana.ui.resources.sky_survey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * An object's page, as "Sky · object page": its photo from NINA's caches with the profile's
 * field over it, what it is, whether it fits the field, the Moon, its altitude tonight at
 * the profile's site over its own horizon, "Go and centre" with the mount, and "Frame".
 */
@Composable
@Suppress("LongParameterList") // The page shows the object with the equipment, the photos, the site and the time.
fun SkyObjectScreen(
    designation: String,
    objects: ObjectSearch,
    equipment: EquipmentController,
    caches: SkyCacheStore?,
    centring: Centring,
    clock: Clock,
    zone: () -> TimeZone,
    onMap: (SkyObject) -> Unit,
    open: (Screen) -> Unit,
) {
    val catalogue by objects.objects.collectAsState()
    val item = remember(catalogue, designation) { catalogue?.find(designation) }
    val state by equipment.state.collectAsState()
    val profile = state.active
    val site = profile?.location
    val mount = (state.connection as? Connection.Connected)?.equipment?.mount
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item == null) {
            Text(stringResource(Res.string.sky_loading), modifier = Modifier.padding(20.dp))
            return@Column
        }
        Photo(item, profile, caches, clock, open) { onMap(item) }
        val now = clock.now()
        val night = remember(site, now.toLocalDateTime(zone()).date) { site?.let { Ephemerides.night(evening(now, zone()), it) } }
        About(item, night, site, profile?.optics?.fieldDeg())
        if (site != null &&
            night != null
        ) {
            Tonight(item, night, site, profile, now, zone())
        } else {
            Muted(stringResource(Res.string.obj_no_site))
        }
        if (mount == null) Muted(stringResource(Res.string.obj_no_mount))
        // As the design: "Go and centre", and "Frame" as the main action.
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SurfaceButton(
                stringResource(Res.string.mount_go_and_centre),
                {
                    centring.centre(item.position)
                    open(Screen.Device(DeviceRole.Mount))
                },
                Modifier.weight(1f),
                enabled = mount != null,
            )
            AccentButton(onClick = {
                open(Screen.Framing(item.designation))
            }, modifier = Modifier.weight(1.3f).heightIn(min = Touch.Main)) {
                Text(stringResource(Res.string.framing_open))
            }
        }
    }
}

/**
 * The object in the photos of the caches in use, with the profile's field at the centre, as the
 * design: wide enough for both, with some sky around; without a cache, where to install one. A
 * tap opens the sky map framed on it [onMap], as its "Map" chip says.
 */
@Composable
@Suppress("LongParameterList") // The photo, its caches and where it leads.
private fun Photo(item: SkyObject, profile: Profile?, caches: SkyCacheStore?, clock: Clock, open: (Screen) -> Unit, onMap: () -> Unit) {
    val inUse by (caches?.layers ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val field = profile?.optics?.fieldDeg()
    val rotation = profile?.optics?.measuredRotationDeg ?: 0.0
    // As the framing and the map show it: the sky as seen from the profile's site now, the zenith up.
    val site = profile?.location
    val roll = site?.let { SkyView.zenithUp(item.position, it.latitude, Ephemerides.localSiderealDeg(clock.now(), it)) } ?: 0.0
    // Wide enough for the object and the whole field, each as turned on the screen, with some sky around.
    val (wide, tall) = item.extentDeg(roll)
    val (fieldAcross, fieldDown) = field?.let { turned(max(it.first, it.second), min(it.first, it.second), roll - rotation) }
        ?: (0.0 to 0.0)
    val across = max(fieldAcross, wide) * AROUND
    val down = max(fieldDown, tall) * AROUND
    val aspect = if (size.height == 0) PHOTO_ASPECT.toDouble() else size.width.toDouble() / size.height
    val view = MapView(item.position, max(max(across, down * aspect), MIN_PHOTO_FIELD), roll)
    val layers = drawnLayers(inUse, view, size)
    // As the design's photo, 300 by 140, but never taller than a phone on its side allows.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(minOf(maxWidth / PHOTO_ASPECT, PHOTO_MAX_HEIGHT)).onSizeChanged {
                size = it
            }.clickable(onClick = onMap),
        ) {
            SkyPhoto(view, layers, field, Modifier.fillMaxSize(), rotation)
            MapChip(Modifier.align(Alignment.BottomStart).padding(8.dp))
            if (inUse.isEmpty()) {
                Label(
                    stringResource(Res.string.sky_no_cache),
                    Modifier.align(Alignment.TopStart).padding(8.dp).clickable {
                        open(Screen.SkyMapSettings)
                    },
                )
            } else {
                Label(
                    stringResource(Res.string.sky_survey, inUse.map { it.cache.survey }.distinct().joinToString(" + ")),
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            if (field != null && profile != null) {
                Label(stringResource(Res.string.obj_field, profile.name), Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        }
    }
}

/** The design's "Map" chip on the photo, with the Sky section's stars. */
@Composable
private fun MapChip(modifier: Modifier) {
    Row(
        modifier.background(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            RoundedCornerShape(8.dp),
        ).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(YacanaIcons.Sky, contentDescription = null, modifier = Modifier.size(14.dp))
        Text(stringResource(Res.string.sky_map), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * How much sky the object takes across and down, in degrees, its ellipse turned by its position
 * angle from north through east, with north turned [rollDeg] clockwise on the screen: with north
 * up, M 31 is longer down than across.
 */
private fun SkyObject.extentDeg(rollDeg: Double): Pair<Double, Double> {
    val major = (majorArcmin ?: 0.0) / ARCMIN_PER_DEGREE
    val minor = (minorArcmin ?: majorArcmin ?: 0.0) / ARCMIN_PER_DEGREE
    val angle = ((angleDeg ?: 0.0) - rollDeg) * PI / HALF_TURN
    return hypot(major * sin(angle), minor * cos(angle)) to hypot(major * cos(angle), minor * sin(angle))
}

/** A [wide] × [tall] rectangle turned [turnDeg] on the screen: how much it takes across and down. */
private fun turned(wide: Double, tall: Double, turnDeg: Double): Pair<Double, Double> {
    val angle = turnDeg * PI / HALF_TURN
    return wide * abs(cos(angle)) + tall * abs(sin(angle)) to wide * abs(sin(angle)) + tall * abs(cos(angle))
}

/**
 * "M 33 · Triangulum Galaxy", "Spiral galaxy · mag 5.7 · 70′ × 42′", its other names and
 * designations, whether it fits the field, and the Moon.
 */
@Composable
private fun About(item: SkyObject, night: Night?, site: Location?, field: Pair<Double, Double>?) {
    val comma = decimalComma()
    SectionCard {
        Text(item.title(), style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                item.kind(),
                item.magnitude?.let { stringResource(Res.string.obj_magnitude, formatDecimal(it, 1, comma)) },
                item.size(degrees = false),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val others = item.others()
        if (others.isNotEmpty()) {
            Text(
                stringResource(Res.string.obj_also, others.joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val panels = item.panels(field)
        val middle = night?.sunset?.let { set -> night.sunrise?.let { rise -> set + (rise - set) / 2 } }
        if (panels != null || (middle != null && site != null)) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                panels?.let { Chip(if (it == 1) stringResource(Res.string.obj_fits) else stringResource(Res.string.obj_mosaic, it)) }
                if (middle != null && site != null && night != null) {
                    val distance = Ephemerides.moonDistance(item.position, middle, site).roundToInt()
                    Chip(stringResource(Res.string.obj_moon, distance, (night.moonIllumination * PERCENT).roundToInt()), YacanaIcons.Night)
                }
            }
        }
    }
}

/** "Altitude tonight": the chart, over the profile's own horizon, and when it is above 30°. */
@Composable
private fun Tonight(item: SkyObject, night: Night, site: Location, profile: Profile?, now: Instant, zone: TimeZone) {
    val track = remember(item, night, site) { Ephemerides.track(item.position, night, site) }
    val samples = track.map { it.at to it.altitudeDeg }
    val points = profile?.options?.horizon.orEmpty()
    val horizon = remember(track, points) { HorizonLimit(0.0, points).let { limit -> track.map { limit.altitudeAt(it.azimuthDeg) } } }
    SectionCard {
        Text(
            stringResource(Res.string.obj_altitude),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AltitudeChart(samples, night, now, zone, Modifier.fillMaxWidth().height(CHART_HEIGHT).padding(vertical = 6.dp), horizon)
        val highest = samples.maxByOrNull { it.second }
        val above = samples.filter { it.second >= GOOD }
        Text(
            when {
                highest == null || highest.second < 0 -> stringResource(Res.string.obj_never)

                above.isEmpty() -> stringResource(Res.string.obj_low, highest.second.roundToInt(), clockTime(highest.first, zone))

                else -> stringResource(
                    Res.string.obj_above,
                    clockTime(above.first().first, zone),
                    clockTime(above.last().first, zone),
                    highest.second.roundToInt(),
                    clockTime(highest.first, zone),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The design's chip of information (`ch`): an icon, if any, and a text on the raised surface. */
@Composable
private fun Chip(text: String, icon: ImageVector? = null) {
    Row(
        Modifier.background(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            RoundedCornerShape(8.dp),
        ).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(16.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** The design's chips over a photo (`ch`). */
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
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

private val CHART_HEIGHT = 140.dp

/** As the design's photo, 300 by 140, and its most on a phone on its side. */
private const val PHOTO_ASPECT = 300f / 140f
private val PHOTO_MAX_HEIGHT = 200.dp
private const val HALF_TURN = 180.0

/** The photo shows the field and the object with a third more sky around them. */
private const val AROUND = 1.3

/** Not narrower than this, in degrees: the caches' photos have no more detail. */
private const val MIN_PHOTO_FIELD = 0.5
private const val ARCMIN_PER_DEGREE = 60.0
private const val PERCENT = 100
