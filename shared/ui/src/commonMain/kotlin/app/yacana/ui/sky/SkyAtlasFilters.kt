// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.yacana.domain.sky.AtlasFilters
import app.yacana.domain.sky.AtlasOrder
import app.yacana.domain.sky.ObjectGroup
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.SettingChip
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.atlas_above
import app.yacana.ui.resources.atlas_above_chip
import app.yacana.ui.resources.atlas_any
import app.yacana.ui.resources.atlas_any_size
import app.yacana.ui.resources.atlas_clusters
import app.yacana.ui.resources.atlas_count
import app.yacana.ui.resources.atlas_done
import app.yacana.ui.resources.atlas_favourites
import app.yacana.ui.resources.atlas_filters
import app.yacana.ui.resources.atlas_filters_hint
import app.yacana.ui.resources.atlas_fit
import app.yacana.ui.resources.atlas_for
import app.yacana.ui.resources.atlas_for_chip
import app.yacana.ui.resources.atlas_from
import app.yacana.ui.resources.atlas_galaxies
import app.yacana.ui.resources.atlas_hours
import app.yacana.ui.resources.atlas_magnitude
import app.yacana.ui.resources.atlas_moon
import app.yacana.ui.resources.atlas_moon_chip
import app.yacana.ui.resources.atlas_nebulae
import app.yacana.ui.resources.atlas_only
import app.yacana.ui.resources.atlas_order
import app.yacana.ui.resources.atlas_order_best
import app.yacana.ui.resources.atlas_order_brightest
import app.yacana.ui.resources.atlas_order_highest
import app.yacana.ui.resources.atlas_order_longest
import app.yacana.ui.resources.atlas_other
import app.yacana.ui.resources.atlas_reset
import app.yacana.ui.resources.atlas_size
import app.yacana.ui.resources.atlas_size_field
import app.yacana.ui.resources.atlas_tonight
import app.yacana.ui.resources.atlas_type
import app.yacana.ui.resources.atlas_up_to
import app.yacana.ui.resources.atlas_up_to_mag
import app.yacana.ui.resources.sequence_remove
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The filters of [filters] that are on and are not as they are at first, beyond Tonight and the
 * favourites, which have their own buttons: each as the list's button with ✕ names it, and the
 * filters without it.
 */
@Composable
internal fun filtersOn(filters: AtlasFilters): List<Pair<String, AtlasFilters>> {
    val first = AtlasFilters()
    return buildList {
        GROUPS.forEach { (group, label) ->
            if (group in filters.groups) add(Pair(stringResource(label), filters.copy(groups = filters.groups - group)))
        }
        filters.minSizeArcmin?.let { add(Pair(sizeText(it), filters.copy(minSizeArcmin = first.minSizeArcmin))) }
        filters.maxMagnitude?.let {
            add(
                Pair(
                    stringResource(Res.string.atlas_up_to_mag, it.roundToInt().toString()),
                    filters.copy(maxMagnitude = first.maxMagnitude),
                ),
            )
        }
        if (filters.fit) add(Pair(stringResource(Res.string.atlas_fit), filters.copy(fit = false)))
        // Tonight's own only count with Tonight on.
        if (filters.tonight) {
            if (filters.minAltitudeDeg != first.minAltitudeDeg) {
                add(
                    Pair(
                        stringResource(Res.string.atlas_above_chip, degrees(filters.minAltitudeDeg)),
                        filters.copy(minAltitudeDeg = first.minAltitudeDeg),
                    ),
                )
            }
            if (filters.minHours != first.minHours) {
                val hours = stringResource(Res.string.atlas_hours, filters.minHours.roundToInt())
                add(Pair(stringResource(Res.string.atlas_for_chip, hours), filters.copy(minHours = first.minHours)))
            }
            if (filters.minMoonDeg != first.minMoonDeg) {
                add(
                    Pair(
                        stringResource(Res.string.atlas_moon_chip, degrees(filters.minMoonDeg)),
                        filters.copy(minMoonDeg = first.minMoonDeg),
                    ),
                )
            }
        }
    }
}

/**
 * The design's row over the list: Tonight and the favourites, then each filter that is on, whose
 * ✕ takes it off and whose text opens the sheet; on one line that scrolls sideways.
 */
@Composable
internal fun FilterRow(filters: AtlasFilters, onFilters: (AtlasFilters) -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingChip(stringResource(Res.string.atlas_tonight), {
            onFilters(filters.copy(tonight = !filters.tonight))
        }, selected = filters.tonight)
        SettingChip(
            stringResource(Res.string.atlas_favourites),
            { onFilters(filters.copy(favourites = !filters.favourites)) },
            selected = filters.favourites,
            icon = if (filters.favourites) YacanaIcons.StarFilled else YacanaIcons.Star,
        )
        filtersOn(filters).forEach { (label, off) -> OnChip(label, onOpen) { onFilters(off) } }
    }
}

/** A filter that is on: outlined in the accent, its text opens the filters and its ✕ takes it off. */
@Composable
private fun OnChip(label: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    Surface(
        Modifier.heightIn(min = 36.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(
                    role = Role.Button,
                    onClick = onOpen,
                ).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            )
            Icon(
                YacanaIcons.Close,
                contentDescription = stringResource(Res.string.sequence_remove, label),
                modifier = Modifier.clickable(
                    role = Role.Button,
                    onClick = onRemove,
                ).padding(start = 2.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).size(18.dp),
            )
        }
    }
}

/**
 * "Sky · Sky Atlas · filters": each filter as chips, without the keyboard, under a head
 * that stays and counts the [count] objects they leave, with Reset and Done. The list changes as
 * they are chosen.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod") // The design's groups, one after another.
internal fun FiltersSheet(
    filters: AtlasFilters,
    count: Int,
    field: Pair<Double, Double>?,
    onFilters: (AtlasFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(onDismiss = onDismiss, header = {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.atlas_filters), style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(Res.plurals.atlas_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            onFilters(AtlasFilters())
        }, enabled = filters != AtlasFilters()) { Text(stringResource(Res.string.atlas_reset)) }
        AccentButton(onClick = onDismiss) { Text(stringResource(Res.string.atlas_done)) }
    }) {
        Group(Res.string.atlas_order) {
            AtlasOrder.entries.forEach { order ->
                SettingChip(stringResource(order.label), { onFilters(filters.copy(order = order)) }, selected = filters.order == order)
            }
        }
        Group(Res.string.atlas_type) {
            GROUPS.forEach { (group, label) ->
                val on = group in filters.groups
                SettingChip(stringResource(label), {
                    onFilters(filters.copy(groups = if (on) filters.groups - group else filters.groups + group))
                }, selected = on)
            }
        }
        Group(Res.string.atlas_size) {
            // For the field, a tenth of its short side; without one, the 10′ that SkyAtlas takes.
            val forField =
                field?.let {
                    stringResource(Res.string.atlas_size_field, "${(minOf(it.first, it.second) * ARCMIN / FIELD_SHARE).roundToInt()}′")
                }
                    ?: sizeText(DEFAULT_SIZE)
            listOf(0.0 to stringResource(Res.string.atlas_any), null to forField).plus(
                SIZES.map {
                    it to sizeText(it)
                },
            ).forEach { (size, label) ->
                SettingChip(label, { onFilters(filters.copy(minSizeArcmin = size)) }, selected = filters.minSizeArcmin == size)
            }
        }
        Group(Res.string.atlas_magnitude) {
            (
                listOf(null to stringResource(Res.string.atlas_any)) +
                    MAGNITUDES.map { it to stringResource(Res.string.atlas_up_to, it.roundToInt().toString()) }
                )
                .forEach { (magnitude, label) ->
                    SettingChip(label, { onFilters(filters.copy(maxMagnitude = magnitude)) }, selected = filters.maxMagnitude == magnitude)
                }
        }
        Group(Res.string.atlas_only) {
            SettingChip(
                stringResource(Res.string.atlas_favourites),
                { onFilters(filters.copy(favourites = !filters.favourites)) },
                selected = filters.favourites,
                icon = if (filters.favourites) YacanaIcons.StarFilled else YacanaIcons.Star,
            )
            SettingChip(stringResource(Res.string.atlas_fit), { onFilters(filters.copy(fit = !filters.fit)) }, selected = filters.fit)
        }
        Tonight(filters, onFilters)
    }
}

/** Tonight's switch and what only counts with it on: the altitude, the hours up and the Moon. */
@Composable
private fun Tonight(filters: AtlasFilters, onFilters: (AtlasFilters) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.atlas_tonight), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(Res.string.atlas_filters_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(filters.tonight, { onFilters(filters.copy(tonight = it)) })
    }
    // Off, they stay in sight but dimmed, and do nothing.
    val on = filters.tonight
    Column(Modifier.alpha(if (on) 1f else OFF_ALPHA), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Group(Res.string.atlas_above) {
            ALTITUDES.forEach { altitude ->
                SettingChip(
                    degrees(altitude),
                    { if (on) onFilters(filters.copy(minAltitudeDeg = altitude)) },
                    selected =
                    filters.minAltitudeDeg == altitude,
                )
            }
        }
        Group(Res.string.atlas_for) {
            HOURS.forEach { hours ->
                SettingChip(
                    stringResource(Res.string.atlas_hours, hours.roundToInt()),
                    { if (on) onFilters(filters.copy(minHours = hours)) },
                    selected = filters.minHours == hours,
                )
            }
        }
        Group(Res.string.atlas_moon) {
            (listOf(0.0 to stringResource(Res.string.atlas_any)) + MOON.map { it to degrees(it) }).forEach { (distance, label) ->
                SettingChip(label, { if (on) onFilters(filters.copy(minMoonDeg = distance)) }, selected = filters.minMoonDeg == distance)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Group(label: StringResource, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

/** "From 15′", "From 1°", or "Any size" for none. */
@Composable
private fun sizeText(arcmin: Double): String = when {
    arcmin <= 0.0 -> stringResource(Res.string.atlas_any_size)
    arcmin < ARCMIN -> stringResource(Res.string.atlas_from, "${arcmin.roundToInt()}′")
    else -> stringResource(Res.string.atlas_from, "${(arcmin / ARCMIN).roundToInt()}°")
}

private fun degrees(value: Double) = "${value.roundToInt()}°"

/** The order's name, as its chip in the filters and the list's note say it. */
internal val AtlasOrder.label: StringResource
    get() =
        when (this) {
            AtlasOrder.BestTime -> Res.string.atlas_order_best
            AtlasOrder.Highest -> Res.string.atlas_order_highest
            AtlasOrder.LongestUp -> Res.string.atlas_order_longest
            AtlasOrder.Largest -> Res.string.atlas_size
            AtlasOrder.Brightest -> Res.string.atlas_order_brightest
        }

private val GROUPS = listOf(
    ObjectGroup.Galaxies to Res.string.atlas_galaxies,
    ObjectGroup.Nebulae to Res.string.atlas_nebulae,
    ObjectGroup.Clusters to Res.string.atlas_clusters,
    ObjectGroup.Other to Res.string.atlas_other,
)
private val SIZES = listOf(15.0, 30.0, 60.0)
private val MAGNITUDES = listOf(8.0, 10.0, 12.0)
private val ALTITUDES = listOf(20.0, 30.0, 45.0)
private val HOURS = listOf(1.0, 2.0, 4.0)
private val MOON = listOf(30.0, 60.0, 90.0)
private const val ARCMIN = 60.0

/** As SkyAtlas: a tenth of the field's short side, or 10′ without a field. */
private const val FIELD_SHARE = 10.0
private const val DEFAULT_SIZE = 10.0

/** Tonight's filters while Tonight is off. */
private const val OFF_ALPHA = 0.38f
