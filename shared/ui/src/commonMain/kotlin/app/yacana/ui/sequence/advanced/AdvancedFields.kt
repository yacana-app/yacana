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
import app.yacana.ui.resources.adv_f_sensor_delta
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

@Composable
internal fun ExposuresFields(item: Exposures, filters: List<String>, onChange: (SequenceItem) -> Unit, onType: (Typed) -> Unit) {
    Field(stringResource(Res.string.adv_f_type))
    Choices(
        listOf(FrameType.Light, FrameType.Dark, FrameType.Bias),
        if (item.type ==
            FrameType.Flat
        ) {
            FrameType.Light
        } else {
            item.type
        },
        { frameText(it) },
    ) {
        onChange(
            when (it) {
                FrameType.Light -> item.copy(type = it, filter = item.filter ?: filters.firstOrNull())

                FrameType.Bias -> item.copy(type = it, filter = null, ditherEvery = 0, exposureSeconds = 0.0)

                else -> item.copy(
                    type = it,
                    filter = null,
                    ditherEvery = 0,
                    exposureSeconds =
                    item.exposureSeconds.takeIf { s -> s > 0 } ?: DEFAULT_SECONDS,
                )
            },
        )
    }
    if (item.type == FrameType.Light && filters.isNotEmpty()) {
        Field(stringResource(Res.string.adv_f_filter))
        val chosen = item.filter ?: filters.first()
        Choices((filters + chosen).distinct(), chosen, { it }) { onChange(item.copy(filter = it)) }
    }
    if (item.type != FrameType.Bias) {
        Field(stringResource(Res.string.adv_f_time))
        Choices((EXPOSURES + item.exposureSeconds).distinct().sorted(), item.exposureSeconds, {
            "${number(it)} s"
        }) { onChange(item.copy(exposureSeconds = it)) }
    }
    Field(stringResource(Res.string.adv_f_count))
    SettingChip(item.count.toString(), onClick = { onType(Typed.Count) })
    if (item.type == FrameType.Light) {
        Field(stringResource(Res.string.adv_f_dither))
        Choices(DITHERS, item.ditherEvery, {
            if (it ==
                0
            ) {
                stringResource(Res.string.adv_f_no_dither)
            } else {
                stringResource(Res.string.adv_f_frames, it)
            }
        }) {
            onChange(item.copy(ditherEvery = it))
        }
    }
}

@Composable
private fun frameText(type: FrameType) = stringResource(
    when (type) {
        FrameType.Dark -> Res.string.adv_f_dark
        FrameType.Bias -> Res.string.adv_f_bias
        else -> Res.string.adv_f_light
    },
)

/** A time of tonight's clock: its hour and its minutes, each with its buttons, and which day it falls on. */
@Composable
internal fun TimeFields(minute: Int, tonight: Tonight, onChange: (Int) -> Unit) {
    val at = tonightAt(minute, tonight.now, tonight.zone)
    val day = at.toLocalDateTime(tonight.zone).date.day
    val morning = minute < NOON_MINUTES
    Field(stringResource(if (morning) Res.string.adv_f_hour_morning else Res.string.adv_f_hour_evening, clockText(minute), day))
    Stepper(minute / MINUTES_PER_HOUR, {
        onChange((it * MINUTES_PER_HOUR + minute % MINUTES_PER_HOUR).mod(MINUTES_PER_DAY))
    }, 0..23, listOf(1), {
        "${it.toString().padStart(2, '0')} h"
    })
    Field(stringResource(Res.string.adv_f_minutes))
    Stepper(minute % MINUTES_PER_HOUR, { onChange(minute / MINUTES_PER_HOUR * MINUTES_PER_HOUR + it) }, 0..55, listOf(5), {
        "${it.toString().padStart(2, '0')} min"
    })
}

@Composable
internal fun AltitudeField(altitude: Double, onChange: (Double) -> Unit) {
    Field(stringResource(Res.string.adv_f_altitude))
    Stepper(altitude.toInt(), { onChange(it.toDouble()) }, 0..MAX_ALTITUDE, listOf(5), { "$it°" })
}

@Composable
internal fun TriggerFields(trigger: Trigger, onChange: (Trigger) -> Unit) {
    when (trigger) {
        is Trigger.AutofocusAfterTemperature -> {
            Field(stringResource(Res.string.adv_f_delta))
            Choices((DEGREES + trigger.amount).distinct().sorted(), trigger.amount, {
                "${number(it)} °C"
            }) { onChange(trigger.copy(amount = it)) }
        }

        is Trigger.AutofocusAfterTime -> {
            Field(stringResource(Res.string.adv_f_every))
            Choices((PERIODS + trigger.minutes).distinct().sorted(), trigger.minutes, {
                "${number(it)} min"
            }) { onChange(trigger.copy(minutes = it)) }
        }

        is Trigger.AutofocusAfterHfr -> {
            Field(stringResource(Res.string.adv_f_hfr))
            Choices((PERCENTS + trigger.amount).distinct().sorted(), trigger.amount, {
                "${number(it)} %"
            }) { onChange(trigger.copy(amount = it)) }
        }

        is Trigger.RecentreAfterDrift -> {
            Field(stringResource(Res.string.adv_f_drift))
            Choices((ARCMINUTES + trigger.arcminutes).distinct().sorted(), trigger.arcminutes, {
                "${number(it)}′"
            }) { onChange(trigger.copy(arcminutes = it)) }
        }

        Trigger.AutofocusAfterFilter -> Muted(stringResource(Res.string.adv_f_none), Modifier.padding(top = 12.dp))

        is Trigger.DarksAfterTemperature -> {
            Field(stringResource(Res.string.adv_f_sensor_delta))
            Choices((DARK_DEGREES + trigger.amount).distinct().sorted(), trigger.amount, {
                "${number(it)} °C"
            }) { onChange(trigger.copy(amount = it)) }
            Field(stringResource(Res.string.adv_f_count))
            Choices((DARK_COUNTS + trigger.count).distinct().sorted(), trigger.count, { "$it" }) { onChange(trigger.copy(count = it)) }
        }
    }
}

@Composable
internal fun ConditionFields(condition: Condition, tonight: Tonight, onChange: (Condition) -> Unit) {
    when (condition) {
        is Condition.Loop -> {
            Field(stringResource(Res.string.adv_f_times))
            Stepper(condition.times, { onChange(Condition.Loop(it)) }, 1..MAX_ROUNDS, listOf(1, 10), { it.toString() })
        }

        is Condition.UntilTime -> TimeFields(condition.minute, tonight) { onChange(Condition.UntilTime(it)) }

        is Condition.WhileAbove -> AltitudeField(condition.altitudeDeg) { onChange(Condition.WhileAbove(it)) }
    }
}

/** What a draft needs to say: the target it goes to and its hours above 30°, how tonight's hours work, and what darks need. */
@Composable
internal fun Notes(draft: Draft, tonight: Tonight, holder: List<Int>) {
    val target = tonight.target(holder)
    val needsTarget = when (draft) {
        is Draft.OfItem -> draft.item is GoToTarget || draft.item is WaitUntilAbove
        is Draft.OfTrigger -> draft.trigger is Trigger.RecentreAfterDrift
        is Draft.OfCondition -> draft.condition is Condition.WhileAbove
    }
    val high =
        (draft is Draft.OfItem && draft.item is WaitUntilAbove) || (draft is Draft.OfCondition && draft.condition is Condition.WhileAbove)
    val notes = mutableListOf<String>()
    if (needsTarget && target != null) notes += stringResource(Res.string.adv_n_target, target.name)
    if (high &&
        target != null
    ) {
        high(tonight, target.position)?.let { (from, to) ->
            notes +=
                stringResource(Res.string.adv_n_window, target.name, from, to)
        }
    }
    if (draft is Draft.OfItem && draft.item is WaitUntilTime) notes += stringResource(Res.string.adv_n_wait_time)
    if (draft is Draft.OfCondition && draft.condition is Condition.UntilTime) notes += stringResource(Res.string.adv_n_until)
    val item = (draft as? Draft.OfItem)?.item
    if (item is Exposures && item.type != FrameType.Light) {
        notes += stringResource(if (tonight.cooler == true) Res.string.adv_n_darks_cover else Res.string.adv_n_darks_seestar)
        notes += stringResource(Res.string.adv_n_flats)
    }
    notes.forEach { Muted(it, Modifier.padding(top = 10.dp)) }
}

/** The frames a new exposures takes, and their choices. */
private const val DEFAULT_SECONDS = 60.0

private val EXPOSURES = listOf(5.0, 10.0, 20.0, 30.0, 60.0, 120.0, 180.0, 300.0)

private val DITHERS = listOf(0, 2, 3, 5, 10)

private val DEGREES = listOf(0.5, 1.0, 2.0, 3.0, 5.0)

private val PERIODS = listOf(15.0, 30.0, 60.0, 120.0)

private val PERCENTS = listOf(5.0, 10.0, 15.0, 20.0, 30.0)

private val ARCMINUTES = listOf(1.0, 2.0, 5.0, 10.0)

/** The darks trigger's choices, as the simple editor's: degrees of the sensor, and darks each time. */
internal val DARK_DEGREES = listOf(2.0, 3.0, 5.0)
internal val DARK_COUNTS = listOf(5, 10, 20)

private const val MAX_ALTITUDE = 85

private const val MAX_ROUNDS = 100

private const val MINUTES_PER_HOUR = 60

private const val MINUTES_PER_DAY = 1440

private const val NOON_MINUTES = 720
