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

/**
 * "Sequence · advanced editor": N.I.N.A.'s tree as a touch list, one level at a
 * time. The night shows its triggers and its three areas; an area or a block, its card with what
 * it is, its conditions and the buttons to add one or a trigger, then its triggers and its items,
 * each with what it does, whether it is done or running, and what "Review" says of it. A block
 * opens with a tap and an instruction's settings too; the ⋮ of a row opens its menu, and its grip
 * drags it. "Add to …" opens the catalogue. The top bar's menu, [menuOpen], saves tonight or
 * goes back to the simple plan; its warning, [reviewOpen], opens "Review".
 */
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // The level's parts share the tree and its sheets.
fun AdvancedScreen(
    path: List<Int>,
    services: AppServices,
    openPath: (List<Int>) -> Unit,
    openAdd: (path: List<Int>, holder: String, category: String?) -> Unit,
    menuOpen: Boolean = false,
    onMenuClose: () -> Unit = {},
    reviewOpen: Boolean = false,
    onReviewClose: () -> Unit = {},
) {
    val tonight = rememberTonight(services)
    // A level that is no longer there, as after an undo, shows the night.
    val at = path.takeIf { tonight.tree.blockAt(it) != null } ?: emptyList()
    val block = tonight.tree.blockAt(at) ?: tonight.tree
    val title = levelTitle(tonight, at)
    val edit: ((Block) -> Block) -> Unit = { how -> services.saveTonight(how(tonight.tree), tonight.sequence.name) }
    var sheet by remember(at) { mutableStateOf<LevelSheet?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Crumbs(tonight, at, openPath)
            LevelCard(tonight, at, block, title, onAdd = { category -> openAdd(at, title, category) }) { index ->
                sheet = LevelSheet.Settings(Draft.OfCondition(block.conditions[index]), at, index)
            }
            if (block.triggers.isNotEmpty()) {
                Label(stringResource(if (at.isEmpty()) Res.string.adv_triggers_night else Res.string.adv_triggers))
                Rows {
                    block.triggers.forEachIndexed { index, trigger ->
                        val target = tonight.target(at)?.name
                        ItemRow(
                            icon = entryOf(trigger).look().icon,
                            title = stringResource(entryOf(trigger).look().name),
                            summary = summary(trigger, target),
                            warning = tonight.warningsAt(Spot(at, trigger = index)).firstOrNull()?.let {
                                problemText(it.problem) { p -> itemTitle(tonight, p) }
                            },
                            onClick = { sheet = LevelSheet.Settings(Draft.OfTrigger(trigger), at, index) },
                        )
                    }
                }
            }
            Label(stringResource(if (at.isEmpty()) Res.string.adv_areas else Res.string.adv_items))
            if (block.items.isEmpty()) {
                Rows { Muted(stringResource(Res.string.adv_empty), Modifier.padding(16.dp)) }
            } else {
                ItemList(tonight, at, block, onOpen = { index -> openPath(at + index) }, onSettings = { index ->
                    sheet = LevelSheet.Settings(Draft.OfItem(block.items[index]), at, index)
                }, onMenu = { index -> sheet = LevelSheet.Menu(at + index) }) { from, to -> edit { it.moveTo(at + from, to) } }
            }
            if (at.isEmpty()) Muted(stringResource(Res.string.adv_night_caption), Modifier.padding(horizontal = 20.dp))
        }
        if (at.isNotEmpty()) {
            AccentButton(
                onClick = { openAdd(at, title, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = Touch.Main),
            ) {
                Icon(YacanaIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(Res.string.adv_add_to, title), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    when (val shown = sheet) {
        is LevelSheet.Menu -> ItemMenu(tonight, shown.path, services, edit, openPath, onSheet = { sheet = it }) { sheet = null }

        is LevelSheet.Settings -> SettingsSheet(tonight, shown, edit) { sheet = null }

        is LevelSheet.Move -> MoveSheet(tonight, shown.path, edit) { sheet = null }

        is LevelSheet.Rename -> RenameSheet(tonight, shown.path, edit) { sheet = null }

        is LevelSheet.Retarget -> TargetPicker(services, tonight, PickMode.Change, onClose = { sheet = null }) { picked ->
            edit { tree -> tree.changeAt(shown.path) { it.copy(name = picked.name, target = picked.target) } }
            sheet = null
        }

        null -> Unit
    }
    if (menuOpen) NightMenu(services, tonight, onMenuClose)
    if (reviewOpen) ReviewSheet(tonight, openPath, onReviewClose)
}

/** The sheets a level opens over itself. */
internal sealed interface LevelSheet {
    data class Menu(val path: List<Int>) : LevelSheet

    /** The settings of the item, trigger or condition [index] of the block at [holder]. */
    data class Settings(val draft: Draft, val holder: List<Int>, val index: Int?) : LevelSheet

    data class Move(val path: List<Int>) : LevelSheet

    data class Rename(val path: List<Int>) : LevelSheet

    data class Retarget(val path: List<Int>) : LevelSheet
}

/** What a settings sheet changes: an instruction or block, a trigger or a condition. */
internal sealed interface Draft {
    data class OfItem(val item: SequenceItem) : Draft

    data class OfTrigger(val trigger: Trigger) : Draft

    data class OfCondition(val condition: Condition) : Draft
}

/** "Night › Targets › M 31": each level a button back to it. */
@Composable
private fun Crumbs(tonight: Tonight, path: List<Int>, openPath: (List<Int>) -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        for (depth in 0..path.size) {
            val prefix = path.take(depth)
            if (depth > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { openPath(prefix) }, enabled = depth < path.size) {
                Text(
                    levelTitle(tonight, prefix),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (depth == path.size) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The level's card: the night with its dark hours and profile; an area or a block with what it
 * is, a target's hours above 30°, how it repeats, its conditions and the buttons to add.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelCard(tonight: Tonight, path: List<Int>, block: Block, title: String, onAdd: (String) -> Unit, onCondition: (Int) -> Unit) {
    val place = tonight.place(path)
    SectionCard {
        if (place == Place.Night) {
            Text(stringResource(Res.string.adv_tonight), style = MaterialTheme.typography.titleMedium)
            val dark = tonight.night?.let { night ->
                val dusk = night.astronomicalDusk
                val dawn = night.astronomicalDawn
                if (dusk != null &&
                    dawn != null
                ) {
                    stringResource(Res.string.adv_dark, clockTime(dusk, tonight.zone), clockTime(dawn, tonight.zone))
                } else {
                    null
                }
            }
            Muted(listOfNotNull(dark, tonight.profile).joinToString(" · "))
        } else {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Muted(kindLine(block, place, path.size == 1))
            block.target?.let { target -> window(tonight, target.position)?.let { Muted(it, Modifier.padding(top = 2.dp)) } }
            if (place == Place.Start || place == Place.End ||
                place == Place.Targets
            ) {
                Muted(stringResource(areaAbout(place)), Modifier.padding(top = 4.dp))
            }
            if (place.takesConditions()) {
                Muted(repeatText(block, place), Modifier.padding(top = 4.dp))
                if (place == Place.Targets &&
                    tonight.run != null
                ) {
                    Muted(stringResource(Res.string.adv_repeat_running), Modifier.padding(top = 2.dp))
                }
            }
            if (block.parallel) Muted(stringResource(Res.string.adv_parallel_about), Modifier.padding(top = 4.dp))
        }
        val conditionsFixed = place == Place.Targets && tonight.run != null
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            block.conditions.forEachIndexed { index, condition ->
                val warned = tonight.warningsAt(Spot(path, condition = index)).isNotEmpty()
                SettingChip(
                    summary(condition, tonight.target(path)?.name),
                    onClick = { if (!conditionsFixed) onCondition(index) },
                    selected = true,
                    icon = if (warned) YacanaIcons.Alert else entryOf(condition).look().icon,
                )
            }
            if (place.takesConditions() &&
                !conditionsFixed
            ) {
                AddChip(stringResource(Res.string.adv_add_condition)) { onAdd(Category.Conditions.name) }
            }
            if (place == Place.Night ||
                place == Place.Block
            ) {
                AddChip(stringResource(Res.string.adv_add_trigger)) { onAdd(Category.Triggers.name) }
            }
        }
    }
}

private fun Place.takesConditions() = this == Place.Targets || this == Place.Block

private fun areaAbout(place: Place) = when (place) {
    Place.Start -> Res.string.adv_area_start_about
    Place.Targets -> Res.string.adv_area_targets_about
    else -> Res.string.adv_area_end_about
}

@Composable
private fun repeatText(block: Block, place: Place): String = stringResource(
    when {
        block.conditions.isEmpty() && place == Place.Targets -> Res.string.adv_repeat_once_list
        block.conditions.isEmpty() -> Res.string.adv_repeat_once
        block.conditions.size > 1 -> Res.string.adv_repeat_while_all
        else -> Res.string.adv_repeat_while
    },
)

/** The advanced editor's top bar, as its design: how many warnings "Review" has, which opens it, then undo and the menu. */
@Composable
fun AdvancedActions(services: AppServices, onReview: () -> Unit, onMenu: () -> Unit) {
    val tonight = rememberTonight(services)
    val count = tonight.warnings.size
    if (count > 0) {
        val label = stringResource(Res.string.adv_review_count, count)
        Surface(
            onClick = onReview,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.error,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(YacanaIcons.Alert, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(count.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
    IconButton(onClick = { services.targets.undo() }, enabled = tonight.canUndo) {
        Icon(YacanaIcons.Undo, contentDescription = stringResource(Res.string.adv_undo))
    }
    IconButton(onClick = onMenu) { Icon(YacanaIcons.More, contentDescription = stringResource(Res.string.adv_more)) }
}
