// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Blocked
import app.yacana.domain.sequence.GoToTarget
import app.yacana.domain.sequence.Park
import app.yacana.domain.sequence.RunAutofocus
import app.yacana.domain.sequence.SequenceItem
import app.yacana.domain.sequence.StopTracking
import app.yacana.domain.sequence.Unpark
import app.yacana.domain.sequence.WarmCamera
import app.yacana.domain.sequence.blockAt
import app.yacana.domain.sequence.blockedIn
import app.yacana.domain.sequence.changeAt
import app.yacana.domain.sequence.insertAfter
import app.yacana.domain.sequence.itemAt
import app.yacana.domain.sequence.moveInto
import app.yacana.domain.sequence.moveTo
import app.yacana.domain.sequence.removeAt
import app.yacana.ui.AppServices
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.StatusKind
import app.yacana.ui.design.StatusLine
import app.yacana.ui.design.Touch
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.adv_back_to_simple_about
import app.yacana.ui.resources.adv_blocked_area
import app.yacana.ui.resources.adv_cancel
import app.yacana.ui.resources.adv_close
import app.yacana.ui.resources.adv_delete
import app.yacana.ui.resources.adv_hold_to_simple
import app.yacana.ui.resources.adv_m_copy
import app.yacana.ui.resources.adv_m_down
import app.yacana.ui.resources.adv_m_enter
import app.yacana.ui.resources.adv_m_move
import app.yacana.ui.resources.adv_m_rename
import app.yacana.ui.resources.adv_m_retarget
import app.yacana.ui.resources.adv_m_settings
import app.yacana.ui.resources.adv_m_template
import app.yacana.ui.resources.adv_m_up
import app.yacana.ui.resources.adv_move_about
import app.yacana.ui.resources.adv_move_here
import app.yacana.ui.resources.adv_move_no_target
import app.yacana.ui.resources.adv_move_target
import app.yacana.ui.resources.adv_move_title
import app.yacana.ui.resources.adv_rename_about
import app.yacana.ui.resources.adv_review
import app.yacana.ui.resources.adv_review_about
import app.yacana.ui.resources.adv_review_none
import app.yacana.ui.resources.adv_save
import app.yacana.ui.resources.adv_save_mine
import app.yacana.ui.resources.my_saved_as
import app.yacana.ui.sequence.Muted
import org.jetbrains.compose.resources.stringResource

/**
 * An item's menu, from the ⋮ of its row: enter a block, change a target's target, rename a block,
 * the settings of an instruction, up and down, "Move to…", duplicate, save a block as a template,
 * and delete. What is done or runs moves and deletes no more, and what is done is not copied
 * before what runs.
 */
@Composable
@Suppress("LongParameterList") // The menu opens other sheets and edits the tree.
internal fun ItemMenu(
    tonight: Tonight,
    path: List<Int>,
    services: AppServices,
    edit: ((Block) -> Block) -> Unit,
    openPath: (List<Int>) -> Unit,
    onSheet: (LevelSheet) -> Unit,
    onClose: () -> Unit,
) {
    val item = tonight.tree.itemAt(path) ?: return
    val parent = path.dropLast(1)
    val index = path.last()
    val siblings = tonight.tree.blockAt(parent)?.items.orEmpty()
    val block = item as? Block
    val mark = tonight.mark(path, block != null)
    val locked = mark != RunMark.None
    val panel = block?.target != null && tonight.chain(parent).any { it.target != null }
    val act: (() -> Unit) -> () -> Unit = { action ->
        {
            action()
            onClose()
        }
    }
    BottomSheet(onClose) {
        Text(itemTitle(tonight, path), style = MaterialTheme.typography.titleLarge)
        Muted(if (block != null) kindLine(block, tonight.place(path), false) else summary(item, tonight.target(path)?.name))
        Rows {
            if (block != null) MenuRow(YacanaIcons.ChevronRight, stringResource(Res.string.adv_m_enter), act { openPath(path) })
            if (block?.target != null && !panel) {
                MenuRow(YacanaIcons.Target, stringResource(Res.string.adv_m_retarget), {
                    onSheet(LevelSheet.Retarget(path))
                }, enabled = !locked)
            }
            if (block != null &&
                block.target == null
            ) {
                MenuRow(YacanaIcons.Pencil, stringResource(Res.string.adv_m_rename), { onSheet(LevelSheet.Rename(path)) })
            }
            if (block == null && hasSettings(item)) {
                MenuRow(YacanaIcons.Adjustments, stringResource(Res.string.adv_m_settings), {
                    onSheet(LevelSheet.Settings(Draft.OfItem(item), parent, index))
                })
            }
            val free = { at: Int -> siblings.getOrNull(at)?.let { !tonight.locked(parent + at, it is Block) } == true }
            MenuRow(
                YacanaIcons.ArrowUp,
                stringResource(Res.string.adv_m_up),
                act { edit { it.moveTo(path, index - 1) } },
                enabled =
                !locked && free(index - 1),
            )
            MenuRow(
                YacanaIcons.ArrowDown,
                stringResource(Res.string.adv_m_down),
                act { edit { it.moveTo(path, index + 1) } },
                enabled =
                !locked && free(index + 1),
            )
            MenuRow(YacanaIcons.MoveInto, stringResource(Res.string.adv_m_move), { onSheet(LevelSheet.Move(path)) }, enabled = !locked)
            MenuRow(
                YacanaIcons.Copy,
                stringResource(Res.string.adv_m_copy),
                act { edit { it.insertAfter(path, item) } },
                enabled =
                mark != RunMark.Done,
            )
            if (block !=
                null
            ) {
                MenuRow(YacanaIcons.Save, stringResource(Res.string.adv_m_template), act { services.blockTemplates.save(block) })
            }
            MenuRow(
                YacanaIcons.Trash,
                stringResource(Res.string.adv_delete),
                act {
                    edit { it.removeAt(path) }
                },
                enabled = !locked,
                danger = true,
            )
        }
    }
}

/** Whether an instruction has settings to change. */
private fun hasSettings(item: SequenceItem) = entryOf(item).let { it.look().category != Category.Blocks } &&
    item !in listOf(WarmCamera, Unpark, Park, StopTracking, RunAutofocus) &&
    item !is GoToTarget

@Composable
private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        danger -> colors.error
        else -> colors.onSurface
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Touch.Main).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.padding(start = 14.dp))
    }
}

/**
 * "Sequence · move to…": every block of the tree but the item and what is inside it, each one
 * the item can't go into greyed out with why; it goes at the end of the one chosen.
 */
@Composable
internal fun MoveSheet(tonight: Tonight, path: List<Int>, edit: ((Block) -> Block) -> Unit, onClose: () -> Unit) {
    val item = tonight.tree.itemAt(path) ?: return
    val places = mutableListOf<List<Int>>()
    fun walk(block: Block, at: List<Int>) {
        block.items.forEachIndexed { index, inner ->
            val there = at + index
            if (inner is Block && there != path) {
                places += there
                walk(inner, there)
            }
        }
    }
    walk(tonight.tree, emptyList())
    BottomSheet(onClose) {
        Text(stringResource(Res.string.adv_move_title, itemTitle(tonight, path)), style = MaterialTheme.typography.titleLarge)
        Muted(stringResource(Res.string.adv_move_about))
        Rows {
            places.forEachIndexed { index, place ->
                val why =
                    when {
                        place == path.dropLast(1) -> stringResource(Res.string.adv_move_here)

                        else -> when (item.blockedIn(tonight.place(place), tonight.chain(place).any { it.target != null })) {
                            Blocked.OnlyInATarget -> stringResource(Res.string.adv_move_no_target)
                            Blocked.AlreadyInATarget -> stringResource(Res.string.adv_move_target)
                            null -> null
                            else -> stringResource(Res.string.adv_blocked_area)
                        }
                    }
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Touch.Min)
                        .clickable(enabled = why == null) {
                            edit { it.moveInto(path, place) }
                            onClose()
                        }.padding(start = 12.dp + INDENT * (place.size - 1), end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val block = tonight.tree.blockAt(place)
                    val alpha = if (why == null) 1f else DISABLED_ALPHA
                    Icon(
                        if (place.size == 1) YacanaIcons.Layers else entryOf(block ?: Block("")).look().icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        levelTitle(tonight, place),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(stringResource(Res.string.adv_cancel))
        }
    }
}

/** A block's new name, to tell it from the others. */
@Composable
internal fun RenameSheet(tonight: Tonight, path: List<Int>, edit: ((Block) -> Block) -> Unit, onClose: () -> Unit) {
    val block = tonight.tree.blockAt(path) ?: return
    var name by remember(path) { mutableStateOf(block.name) }
    BottomSheet(onClose) {
        Text(stringResource(Res.string.adv_m_rename), style = MaterialTheme.typography.titleLarge)
        Muted(stringResource(Res.string.adv_rename_about))
        OutlinedTextField(name, { name = it.take(MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        AccentButton(
            onClick = {
                edit { tree -> tree.changeAt(path) { it.copy(name = name.trim().ifEmpty { block.name }) } }
                onClose()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = Touch.Main),
        ) { Text(stringResource(Res.string.adv_save)) }
    }
}

/**
 * "Sequence · review the night": what would go wrong tonight, each warning with its item and
 * where it is; a tap goes to the level that holds it.
 */
@Composable
internal fun ReviewSheet(tonight: Tonight, openPath: (List<Int>) -> Unit, onClose: () -> Unit) {
    BottomSheet(onClose) {
        Text(stringResource(Res.string.adv_review), style = MaterialTheme.typography.titleLarge)
        Muted(stringResource(Res.string.adv_review_about))
        Rows {
            if (tonight.warnings.isEmpty()) Muted(stringResource(Res.string.adv_review_none), Modifier.padding(16.dp))
            tonight.warnings.forEachIndexed { index, warning ->
                val spot = warning.spot
                val holder = if (spot.trigger != null || spot.condition != null) spot.path else spot.path.dropLast(1)
                val block = tonight.tree.blockAt(spot.path)
                val title =
                    when {
                        spot.trigger != null -> block?.triggers?.getOrNull(spot.trigger!!)?.let { stringResource(entryOf(it).look().name) }

                        spot.condition != null -> block?.conditions?.getOrNull(spot.condition!!)?.let {
                            stringResource(entryOf(it).look().name)
                        }

                        else -> itemTitle(tonight, spot.path)
                    }.orEmpty()
                val where = (1..holder.size).map { levelTitle(tonight, holder.take(it)) }.joinToString(" › ")
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ItemRow(
                    icon = YacanaIcons.Alert,
                    title = title,
                    summary = where,
                    warning = problemText(warning.problem) { itemTitle(tonight, it) },
                    container = true,
                    onClick = {
                        onClose()
                        openPath(holder)
                    },
                )
            }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(stringResource(Res.string.adv_close))
        }
    }
}

/** The top bar's menu: tonight saved in "My sequences", or back to the simple editor's plan, while nothing runs. */
@Composable
internal fun NightMenu(services: AppServices, tonight: Tonight, onClose: () -> Unit) {
    val advanced by services.targets.advanced.collectAsState()
    var savedAs by remember { mutableStateOf<String?>(null) }
    BottomSheet(onClose) {
        savedAs?.let { StatusLine(stringResource(Res.string.my_saved_as, it), StatusKind.Ok) }
        OutlinedButton(onClick = { savedAs = services.savedSequences.save(tonight.sequence) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.adv_save_mine))
        }
        if (advanced != null) {
            Muted(stringResource(Res.string.adv_back_to_simple_about), Modifier.padding(top = 12.dp))
            HoldButton(
                stringResource(Res.string.adv_hold_to_simple),
                null,
                onConfirm = {
                    services.targets.setAdvanced(null)
                    onClose()
                },
                modifier = Modifier.padding(top = 8.dp),
                enabled = tonight.run == null,
                icon = YacanaIcons.Undo,
                danger = true,
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.4f
private const val MAX_NAME = 40
private val INDENT = 18.dp
