// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Condition
import app.yacana.domain.sequence.CoolCamera
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.GoToTarget
import app.yacana.domain.sequence.MoveFocuser
import app.yacana.domain.sequence.Note
import app.yacana.domain.sequence.SequenceItem
import app.yacana.domain.sequence.SetSwitch
import app.yacana.domain.sequence.SwitchFilter
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.WaitFor
import app.yacana.domain.sequence.WaitUntilAbove
import app.yacana.domain.sequence.WaitUntilTime
import app.yacana.domain.sequence.changeAt
import app.yacana.domain.sequence.tonightAt
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.Stepper
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.adv_cancel
import app.yacana.ui.resources.adv_delete
import app.yacana.ui.resources.adv_f_altitude
import app.yacana.ui.resources.adv_f_bias
import app.yacana.ui.resources.adv_f_count
import app.yacana.ui.resources.adv_f_dark
import app.yacana.ui.resources.adv_f_delta
import app.yacana.ui.resources.adv_f_dither
import app.yacana.ui.resources.adv_f_drift
import app.yacana.ui.resources.adv_f_every
import app.yacana.ui.resources.adv_f_filter
import app.yacana.ui.resources.adv_f_frames
import app.yacana.ui.resources.adv_f_hfr
import app.yacana.ui.resources.adv_f_hour_evening
import app.yacana.ui.resources.adv_f_hour_morning
import app.yacana.ui.resources.adv_f_in
import app.yacana.ui.resources.adv_f_light
import app.yacana.ui.resources.adv_f_minutes
import app.yacana.ui.resources.adv_f_no_dither
import app.yacana.ui.resources.adv_f_none
import app.yacana.ui.resources.adv_f_off
import app.yacana.ui.resources.adv_f_on
import app.yacana.ui.resources.adv_f_position
import app.yacana.ui.resources.adv_f_temperature
import app.yacana.ui.resources.adv_f_text
import app.yacana.ui.resources.adv_f_text_hint
import app.yacana.ui.resources.adv_f_time
import app.yacana.ui.resources.adv_f_times
import app.yacana.ui.resources.adv_f_type
import app.yacana.ui.resources.adv_f_wait
import app.yacana.ui.resources.adv_n_darks_cover
import app.yacana.ui.resources.adv_n_darks_seestar
import app.yacana.ui.resources.adv_n_done
import app.yacana.ui.resources.adv_n_flats
import app.yacana.ui.resources.adv_n_running
import app.yacana.ui.resources.adv_n_target
import app.yacana.ui.resources.adv_n_until
import app.yacana.ui.resources.adv_n_wait_time
import app.yacana.ui.resources.adv_n_window
import app.yacana.ui.resources.adv_ok
import app.yacana.ui.resources.adv_save
import app.yacana.ui.sequence.Muted
import app.yacana.ui.sequence.clockText
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** The settings sheet of an item, trigger or condition already in the tree: "Save" changes it, "Delete" takes it away. */
@Composable
internal fun SettingsSheet(tonight: Tonight, sheet: LevelSheet.Settings, edit: ((Block) -> Block) -> Unit, onClose: () -> Unit) {
    val index = sheet.index ?: return
    val draft = sheet.draft
    val locked = draft is Draft.OfItem && tonight.locked(sheet.holder + index, draft.item is Block)
    BottomSheet(onClose) {
        SettingsBody(
            draft,
            tonight,
            sheet.holder,
            locked = if (locked) tonight.mark(sheet.holder + index, false) else RunMark.None,
            saveLabel = stringResource(Res.string.adv_save),
            onSave = { changed ->
                edit { tree -> tree.changeAt(sheet.holder) { it.replacing(index, changed) } }
                onClose()
            },
            onDelete = {
                edit { tree -> tree.changeAt(sheet.holder) { it.without(draft, index) } }
                onClose()
            },
            onCancel = onClose,
        )
    }
}

/** This block with its item, trigger or condition [index] replaced by [draft]'s. */
internal fun Block.replacing(index: Int, draft: Draft): Block = when (draft) {
    is Draft.OfItem -> copy(items = items.mapIndexed { i, item -> if (i == index) draft.item else item })
    is Draft.OfTrigger -> copy(triggers = triggers.mapIndexed { i, trigger -> if (i == index) draft.trigger else trigger })
    is Draft.OfCondition -> copy(conditions = conditions.mapIndexed { i, condition -> if (i == index) draft.condition else condition })
}

/** This block without its item, trigger or condition [index], of the kind of [draft]. */
internal fun Block.without(draft: Draft, index: Int): Block = when (draft) {
    is Draft.OfItem -> copy(items = items.filterIndexed { i, _ -> i != index })
    is Draft.OfTrigger -> copy(triggers = triggers.filterIndexed { i, _ -> i != index })
    is Draft.OfCondition -> copy(conditions = conditions.filterIndexed { i, _ -> i != index })
}

/** This block with [draft] added: an item at the end of its items, or a trigger or condition. */
internal fun Block.adding(draft: Draft): Block = when (draft) {
    is Draft.OfItem -> copy(items = items + draft.item)
    is Draft.OfTrigger -> copy(triggers = triggers + draft.trigger)
    is Draft.OfCondition -> copy(conditions = conditions + draft.condition)
}

/** What a field typed on the app's keypad is for. */
internal enum class Typed { Count, Position }

/**
 * A draft's settings, as "Sequence · an instruction's settings": its name and line, each of its
 * settings with chips and buttons, the numbers on the app's keypad, and what it needs to say
 * (its target, the hours of tonight, the darks); done or running, only why it can't change.
 */
@Composable
@Suppress("LongParameterList") // The sheet adds and saves, and only an item already there deletes.
internal fun ColumnScope.SettingsBody(
    draft: Draft,
    tonight: Tonight,
    holder: List<Int>,
    locked: RunMark,
    saveLabel: String,
    onSave: (Draft) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var value by remember(draft) { mutableStateOf(draft) }
    var typing by remember(draft) { mutableStateOf<Typed?>(null) }
    val look = when (val shown = value) {
        is Draft.OfItem -> entryOf(shown.item).look()
        is Draft.OfTrigger -> entryOf(shown.trigger).look()
        is Draft.OfCondition -> entryOf(shown.condition).look()
    }
    Text(stringResource(look.name), style = MaterialTheme.typography.titleLarge)
    Muted(stringResource(look.about))
    if (locked != RunMark.None) {
        Muted(
            stringResource(
                if (locked ==
                    RunMark.Done
                ) {
                    Res.string.adv_n_done
                } else {
                    Res.string.adv_n_running
                },
            ),
            Modifier.padding(top = 12.dp),
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(stringResource(Res.string.adv_ok))
        }
        return
    }
    val typed = typing
    if (typed != null) {
        val item = (value as Draft.OfItem).item
        val current = if (item is Exposures) item.count else (item as MoveFocuser).position
        NumberEntry(
            initial = current.toString(),
            unit = "",
            hint = stringResource(if (typed == Typed.Count) Res.string.adv_f_count else Res.string.adv_f_position),
            saveLabel = stringResource(Res.string.adv_ok),
            deleteLabel = stringResource(Res.string.adv_delete),
            decimal = null,
            valid = { if (typed == Typed.Count) it in 1.0..MAX_FRAMES else it in 0.0..MAX_POSITION },
        ) { number ->
            value = Draft.OfItem(if (item is Exposures) item.copy(count = number.toInt()) else MoveFocuser(number.toInt()))
            typing = null
        }
        return
    }
    when (val shown = value) {
        is Draft.OfItem -> ItemFields(shown.item, tonight, { value = Draft.OfItem(it) }) { typing = it }
        is Draft.OfTrigger -> TriggerFields(shown.trigger) { value = Draft.OfTrigger(it) }
        is Draft.OfCondition -> ConditionFields(shown.condition, tonight) { value = Draft.OfCondition(it) }
    }
    Notes(value, tonight, holder)
    if (onDelete != null) {
        TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
            Icon(YacanaIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(Res.string.adv_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
            Text(stringResource(Res.string.adv_cancel))
        }
        AccentButton(onClick = { onSave(value) }, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) { Text(saveLabel) }
    }
}

@Composable
internal fun Field(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> Choices(options: List<T>, chosen: T, text: @Composable (T) -> String, onChoose: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option -> SettingChip(text(option), onClick = { onChoose(option) }, selected = option == chosen) }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod") // One set of fields per kind of instruction.
private fun ItemFields(item: SequenceItem, tonight: Tonight, onChange: (SequenceItem) -> Unit, onType: (Typed) -> Unit) {
    when (item) {
        is Exposures -> ExposuresFields(item, tonight.filters, onChange, onType)

        is CoolCamera -> {
            Field(stringResource(Res.string.adv_f_temperature))
            Stepper(item.temperatureC.toInt(), { onChange(item.copy(temperatureC = it.toDouble())) }, -30..20, listOf(1, 5), { "$it °C" })
            Field(stringResource(Res.string.adv_f_in))
            Stepper(item.minutes.toInt(), { onChange(item.copy(minutes = it.toDouble())) }, 0..60, listOf(1, 5), { "$it min" })
        }

        is MoveFocuser -> {
            Field(stringResource(Res.string.adv_f_position))
            SettingChip(item.position.toString(), onClick = { onType(Typed.Position) })
        }

        is SwitchFilter -> {
            Field(stringResource(Res.string.adv_f_filter))
            Choices((tonight.filters + item.filter).distinct(), item.filter, { it }) { onChange(SwitchFilter(it)) }
        }

        is SetSwitch -> {
            Field(item.name)
            Choices(
                listOf(1.0, 0.0),
                if (item.value >
                    0
                ) {
                    1.0
                } else {
                    0.0
                },
                { stringResource(if (it > 0) Res.string.adv_f_on else Res.string.adv_f_off) },
            ) {
                onChange(item.copy(value = it))
            }
        }

        is WaitUntilTime -> TimeFields(item.minute, tonight) { onChange(WaitUntilTime(it)) }

        is WaitFor -> {
            Field(stringResource(Res.string.adv_f_wait))
            Stepper(item.minutes.toInt(), { onChange(WaitFor(it.toDouble())) }, 1..MAX_WAIT_MINUTES, listOf(1, 10), { "$it min" })
        }

        is WaitUntilAbove -> AltitudeField(item.altitudeDeg) { onChange(WaitUntilAbove(it)) }

        is Note -> {
            Field(stringResource(Res.string.adv_f_text))
            OutlinedTextField(
                item.text,
                { onChange(Note(it.take(MAX_NOTE))) },
                placeholder = { Text(stringResource(Res.string.adv_f_text_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        else -> Muted(stringResource(Res.string.adv_f_none), Modifier.padding(top = 12.dp))
    }
}

private const val MAX_FRAMES = 9_999.0

private const val MAX_POSITION = 999_999.0

private const val MAX_WAIT_MINUTES = 240

private const val MAX_NOTE = 80
