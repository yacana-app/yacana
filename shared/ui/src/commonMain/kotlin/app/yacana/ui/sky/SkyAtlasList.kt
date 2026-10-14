// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.sky.AtlasEntry
import app.yacana.domain.sky.AtlasFilters
import app.yacana.domain.sky.AtlasNight
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.sky.Favourites
import app.yacana.domain.sky.ObjectSearch
import app.yacana.domain.sky.SkyAtlas
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.sky.SkyObject
import app.yacana.domain.sky.panels
import app.yacana.ui.YacanaIcons
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.atlas_empty
import app.yacana.ui.resources.atlas_fits
import app.yacana.ui.resources.atlas_mosaic
import app.yacana.ui.resources.atlas_no_site
import app.yacana.ui.resources.atlas_note
import app.yacana.ui.resources.atlas_reset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.time.Clock

/**
 * The Sky Atlas, as "Sky · Sky Atlas": tonight's best for the profile's field,
 * as the filters leave the catalogue, by the time each is highest, each with its photo from the
 * caches, its altitude through the dark night and that time. Over it, Tonight, the favourites and
 * each filter that is on, and how many objects they leave; the sheet of [filtersOpen] has them
 * all, also while a search hides the list ([shown]). The list keeps its place when it comes back,
 * and goes to its top when the filters change.
 */
@Composable
@Suppress("LongParameterList") // The list shows the catalogue with the profile, the photos, the favourites and the time.
internal fun SkyAtlasList(
    objects: ObjectSearch,
    equipment: EquipmentController,
    caches: SkyCacheStore?,
    favourites: Favourites,
    clock: Clock,
    zone: () -> TimeZone,
    filters: AtlasFilters,
    onFilters: (AtlasFilters) -> Unit,
    filtersOpen: Boolean,
    onFiltersOpen: () -> Unit,
    onFiltersClose: () -> Unit,
    open: (Screen) -> Unit,
    shown: Boolean = true,
) {
    val catalogue by objects.objects.collectAsState()
    val state by equipment.state.collectAsState()
    val marked by favourites.marked.collectAsState()
    val site = state.active?.location
    val field = state.active?.optics?.fieldDeg()
    val now = clock.now()
    val date = now.toLocalDateTime(zone()).date
    val night = remember(site, date) { site?.let { AtlasNight.of(Ephemerides.night(evening(now, zone()), it), it) } }
    val entries = remember(catalogue, night) { catalogue?.objects?.map { night?.entry(it) ?: AtlasEntry(it, FloatArray(0), -1) }.orEmpty() }
    val list = remember(entries, night, filters, field, marked) { SkyAtlas.list(entries, night, filters, field, marked) }
    if (filtersOpen) FiltersSheet(filters, list.size, field, onFilters, onFiltersClose)
    if (!shown) return
    val layers = thumbnailLayers(caches)
    Column {
        FilterRow(filters, onFilters, onFiltersOpen)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (night != null) Icon(YacanaIcons.Imaging, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(14.dp))
            Text(
                if (night !=
                    null
                ) {
                    pluralStringResource(Res.plurals.atlas_note, list.size, list.size, stringResource(filters.order.label))
                } else {
                    stringResource(Res.string.atlas_no_site)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (list.isEmpty() && catalogue != null) {
            Text(
                stringResource(Res.string.atlas_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
            TextButton(onClick = { onFilters(AtlasFilters()) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(Res.string.atlas_reset))
            }
        }
        // Sorted or filtered again, the list starts at its top: kept by their keys, the rows in view would stay
        // there. Back from an object, with the same filters, it stays where it was.
        val scroll = rememberLazyListState()
        var listed by remember { mutableStateOf(filters) }
        LaunchedEffect(filters) {
            if (filters != listed) scroll.scrollToItem(0)
            listed = filters
        }
        LazyColumn(state = scroll, contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp)) {
            itemsIndexed(list, key = { _, entry -> entry.item.designation }) { index, entry ->
                AtlasRow(entry, night, layers, field, zone(), first = index == 0, last = index == list.lastIndex) {
                    open(Screen.SkyObject(entry.item.designation))
                }
            }
        }
    }
}

/** The photos of the caches in use for the list's thumbnails: one loader for all, of NINA's thumbnails only. */
@Composable
private fun thumbnailLayers(caches: SkyCacheStore?): List<DrawnLayer> {
    val inUse by (caches?.layers ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val kept = inUse.filter { !it.online }
    val scope = rememberCoroutineScope()
    val images = remember(kept.map { it.cache }) { kept.map { TileImages(it.cache, scope, TILE_BUDGET / max(kept.size, 1), many = true) } }
    return kept.zip(images) { layer, photos -> DrawnLayer(layer.tiles, photos, layer.opacity, THUMBNAIL_PIXELS) }
}

/** A row as the design's: the photo, the name, designation, size and fit, and its altitude tonight with its best time. */
@Composable
@Suppress("LongParameterList") // A row of the list, with what it draws.
private fun AtlasRow(
    entry: AtlasEntry,
    night: AtlasNight?,
    layers: List<DrawnLayer>,
    field: Pair<Double, Double>?,
    zone: TimeZone,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val item = entry.item
    val name = item.name()
    val fit = item.panels(field)?.let {
        if (it ==
            1
        ) {
            stringResource(Res.string.atlas_fits)
        } else {
            stringResource(Res.string.atlas_mosaic, it)
        }
    }
    val details = listOfNotNull(item.designation.takeIf { it != name }, item.size(), fit).joinToString(" · ")
    val corner = 20.dp
    val shape =
        RoundedCornerShape(
            topStart = if (first) corner else 0.dp,
            topEnd = if (first) corner else 0.dp,
            bottomStart = if (last) corner else 0.dp,
            bottomEnd = if (last) corner else 0.dp,
        )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(THUMBNAIL).clip(RoundedCornerShape(8.dp))) {
            SkyPhoto(MapView(item.position, thumbnailField(item)), layers, null, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (night != null && entry.best >= 0) {
            Column(horizontalAlignment = Alignment.End) {
                Sparkline(entry, Modifier.size(SPARK_WIDTH, SPARK_HEIGHT))
                Text(
                    clockTime(night.times[entry.best], zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The thumbnail's sky: the object with half as much again around it, never less than half a degree. */
private fun thumbnailField(item: SkyObject): Double = max((item.majorArcmin ?: 0.0) / ARCMIN_PER_DEGREE * AROUND, MIN_THUMBNAIL_FIELD)

/** The object's altitude through the dark night, from the horizon to the zenith, as the design's small curve. */
@Composable
private fun Sparkline(entry: AtlasEntry, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    val altitudes = remember(entry) { entry.altitudes() }
    Canvas(modifier) {
        val bottom = size.height - 1.dp.toPx()
        drawLine(line, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        if (altitudes.size < 2) return@Canvas
        val path = Path()
        altitudes.forEachIndexed { i, altitude ->
            val point =
                Offset(i * size.width / altitudes.lastIndex, (bottom - altitude.coerceAtLeast(0.0) / RIGHT_ANGLE * bottom).toFloat())
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, accent, style = Stroke(1.5.dp.toPx()))
    }
}

/** As the design's thumbnails. */
private val THUMBNAIL = 44.dp
private val SPARK_WIDTH = 56.dp
private val SPARK_HEIGHT = 22.dp

/** NINA's largest thumbnail: the list never decodes a full photo. */
private const val THUMBNAIL_PIXELS = 500.0
private const val AROUND = 1.5
private const val MIN_THUMBNAIL_FIELD = 0.5
private const val ARCMIN_PER_DEGREE = 60.0

private const val RIGHT_ANGLE = 90.0
