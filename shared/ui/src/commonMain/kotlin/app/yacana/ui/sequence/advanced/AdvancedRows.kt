// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence.advanced

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Condition
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.Place
import app.yacana.domain.sequence.SequenceItem
import app.yacana.domain.sequence.Spot
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.blockAt
import app.yacana.domain.sequence.changeAt
import app.yacana.domain.sequence.moveTo
import app.yacana.domain.sky.Ephemerides
import app.yacana.ports.SkyPosition
import app.yacana.ui.AppServices
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.Touch
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.adv_add_condition
import app.yacana.ui.resources.adv_add_to
import app.yacana.ui.resources.adv_add_trigger
import app.yacana.ui.resources.adv_area_end
import app.yacana.ui.resources.adv_area_end_about
import app.yacana.ui.resources.adv_area_start
import app.yacana.ui.resources.adv_area_start_about
import app.yacana.ui.resources.adv_area_targets
import app.yacana.ui.resources.adv_area_targets_about
import app.yacana.ui.resources.adv_areas
import app.yacana.ui.resources.adv_count_inside
import app.yacana.ui.resources.adv_count_items
import app.yacana.ui.resources.adv_count_triggers
import app.yacana.ui.resources.adv_dark
import app.yacana.ui.resources.adv_done
import app.yacana.ui.resources.adv_drag
import app.yacana.ui.resources.adv_empty
import app.yacana.ui.resources.adv_items
import app.yacana.ui.resources.adv_kind_area
import app.yacana.ui.resources.adv_kind_mosaic
import app.yacana.ui.resources.adv_kind_parallel
import app.yacana.ui.resources.adv_kind_sequential
import app.yacana.ui.resources.adv_kind_target
import app.yacana.ui.resources.adv_more
import app.yacana.ui.resources.adv_night
import app.yacana.ui.resources.adv_night_caption
import app.yacana.ui.resources.adv_options_of
import app.yacana.ui.resources.adv_parallel_about
import app.yacana.ui.resources.adv_repeat_once
import app.yacana.ui.resources.adv_repeat_once_list
import app.yacana.ui.resources.adv_repeat_running
import app.yacana.ui.resources.adv_repeat_while
import app.yacana.ui.resources.adv_repeat_while_all
import app.yacana.ui.resources.adv_review_count
import app.yacana.ui.resources.adv_running
import app.yacana.ui.resources.adv_tonight
import app.yacana.ui.resources.adv_triggers
import app.yacana.ui.resources.adv_triggers_night
import app.yacana.ui.resources.adv_undo
import app.yacana.ui.resources.adv_window
import app.yacana.ui.sequence.Muted
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun AddChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 36.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(YacanaIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
internal fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 6.dp),
    )
}

/** The editor's list: rows on the lower surface, rounded, inset from the edges. */
@Composable
internal fun Rows(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

/**
 * The block's items, each a row; the grip of each, but at the night's areas, drags it to another
 * place among them, though not past what is done or runs.
 */
@Composable
internal fun ItemList(
    tonight: Tonight,
    path: List<Int>,
    block: Block,
    onOpen: (Int) -> Unit,
    onSettings: (Int) -> Unit,
    onMenu: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    var dragging by remember(block) { mutableIntStateOf(-1) }
    var offset by remember(block) { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val movable = path.isNotEmpty()
    Rows {
        block.items.forEachIndexed { index, item ->
            val at = path + index
            val isBlock = item is Block
            val mark = tonight.mark(at, isBlock)
            val target = tonight.target(at)?.name
            val warning = tonight.warningsAt(Spot(at)).firstOrNull()?.let { problemText(it.problem) { p -> itemTitle(tonight, p) } }
            val inside = if (item is Block) tonight.warningsInside(at) else 0
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                Modifier
                    .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
                    .then(if (dragging == index) Modifier.offset { IntOffset(0, offset.roundToInt()) } else Modifier),
            ) {
                ItemRow(
                    icon = if (path.isEmpty()) YacanaIcons.Layers else entryOf(item).look().icon,
                    title = if (item is Block) levelTitle(tonight, at) else stringResource(entryOf(item).look().name),
                    summary = rowSummary(tonight, item, at, target, path.isEmpty()),
                    mark = mark,
                    container = isBlock,
                    warning = warning ?: inside.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.adv_count_inside, it, it) },
                    grip =
                    if (movable && mark == RunMark.None) {
                        Modifier.pointerInput(block, index) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = index
                                    offset = 0f
                                },
                                onDragEnd = {
                                    val to = (index + (offset / rowHeight).roundToInt()).coerceIn(0, block.items.lastIndex)
                                    // Not into what is done or runs.
                                    val free = (minOf(index, to)..maxOf(index, to)).none {
                                        tonight.locked(path + it, block.items[it] is Block)
                                    }
                                    dragging = -1
                                    offset = 0f
                                    if (to != index && free) onMove(index, to)
                                },
                                onDragCancel = {
                                    dragging = -1
                                    offset = 0f
                                },
                            ) { change, amount ->
                                change.consume()
                                offset += amount.y
                            }
                        }
                    } else {
                        null
                    },
                    onClick = { if (isBlock) onOpen(index) else onSettings(index) },
                    onMenu = if (path.isEmpty()) null else ({ onMenu(index) }),
                )
            }
        }
    }
}

/** What a row says under its title: a block's kind and size, or the instruction's line with its progress. */
@Composable
private fun rowSummary(tonight: Tonight, item: SequenceItem, at: List<Int>, target: String?, area: Boolean): String = when {
    item is Block -> {
        val conditions = item.conditions.map { summary(it, tonight.target(at)?.name) }
        (listOf(kindLine(item, if (area) Place.Start else Place.Block, area)) + conditions).joinToString(" · ")
    }

    item is Exposures && tonight.mark(at, false) == RunMark.Running -> "${summary(item, target)} · ${tonight.done[at] ?: 0}/${item.count}"

    else -> summary(item, target)
}

/**
 * A row of the tree, as designed: its grip, icon, title with a done or running mark,
 * its line, a warning in red, and a chevron for a block or a ⋮ for its menu.
 */
@Composable
@Suppress("LongParameterList") // A row shows each of these.
internal fun ItemRow(
    icon: ImageVector,
    title: String,
    summary: String,
    warning: String?,
    onClick: () -> Unit,
    mark: RunMark = RunMark.None,
    container: Boolean = false,
    grip: Modifier? = null,
    onMenu: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.Main)
            .then(
                if (mark == RunMark.Running ||
                    mark == RunMark.Holds
                ) {
                    Modifier.background(colors.secondaryContainer.copy(alpha = RUNNING_ALPHA))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dragLabel = stringResource(Res.string.adv_drag)
        if (grip != null) {
            Box(
                Modifier.size(width = 32.dp, height = 44.dp).then(grip).semantics {
                    contentDescription = dragLabel
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(YacanaIcons.Grip, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        } else {
            Box(Modifier.width(12.dp))
        }
        val faded = mark == RunMark.Done || !enabled
        Icon(
            icon,
            contentDescription = null,
            tint = colors.primary.copy(alpha = if (faded) FADED_ALPHA else 1f),
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = if (container) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface.copy(alpha = if (faded) FADED_ALPHA else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when (mark) {
                    RunMark.Done -> Badge(YacanaIcons.Check, stringResource(Res.string.adv_done))
                    RunMark.Running -> Badge(YacanaIcons.Lock, stringResource(Res.string.adv_running))
                    RunMark.Holds -> Badge(null, stringResource(Res.string.adv_running))
                    RunMark.None -> Unit
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant.copy(alpha = if (faded) FADED_ALPHA else 1f),
            )
            if (warning != null) {
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        YacanaIcons.Alert,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(16.dp).padding(top = 1.dp),
                    )
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        if (container) {
            Icon(
                YacanaIcons.ChevronRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (onMenu != null) {
            IconButton(onClick = onMenu) { Icon(YacanaIcons.More, contentDescription = stringResource(Res.string.adv_options_of, title)) }
        }
    }
}

@Composable
private fun Badge(icon: ImageVector?, text: String) {
    Row(
        Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

private const val FADED_ALPHA = 0.55f

private const val RUNNING_ALPHA = 0.35f
