// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence.advanced

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Blocked
import app.yacana.domain.sequence.Condition
import app.yacana.domain.sequence.CoolCamera
import app.yacana.domain.sequence.Entry
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.GoToTarget
import app.yacana.domain.sequence.MoveFocuser
import app.yacana.domain.sequence.Note
import app.yacana.domain.sequence.Park
import app.yacana.domain.sequence.Problem
import app.yacana.domain.sequence.RunAutofocus
import app.yacana.domain.sequence.SequenceItem
import app.yacana.domain.sequence.SetSwitch
import app.yacana.domain.sequence.StopTracking
import app.yacana.domain.sequence.SwitchFilter
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.Unpark
import app.yacana.domain.sequence.Use
import app.yacana.domain.sequence.WaitFor
import app.yacana.domain.sequence.WaitUntilAbove
import app.yacana.domain.sequence.WaitUntilTime
import app.yacana.domain.sequence.WarmCamera
import app.yacana.ui.YacanaIcons
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.adv_blocked_area
import app.yacana.ui.resources.adv_blocked_conditions
import app.yacana.ui.resources.adv_blocked_cooler
import app.yacana.ui.resources.adv_blocked_in_target
import app.yacana.ui.resources.adv_blocked_target
import app.yacana.ui.resources.adv_blocked_triggers
import app.yacana.ui.resources.adv_cat_all
import app.yacana.ui.resources.adv_cat_blocks
import app.yacana.ui.resources.adv_cat_camera
import app.yacana.ui.resources.adv_cat_conditions
import app.yacana.ui.resources.adv_cat_filters
import app.yacana.ui.resources.adv_cat_focus
import app.yacana.ui.resources.adv_cat_mount
import app.yacana.ui.resources.adv_cat_templates
import app.yacana.ui.resources.adv_cat_triggers
import app.yacana.ui.resources.adv_cat_utility
import app.yacana.ui.resources.adv_e_af_filter
import app.yacana.ui.resources.adv_e_af_filter_d
import app.yacana.ui.resources.adv_e_af_hfr
import app.yacana.ui.resources.adv_e_af_hfr_d
import app.yacana.ui.resources.adv_e_af_temp
import app.yacana.ui.resources.adv_e_af_temp_d
import app.yacana.ui.resources.adv_e_af_time
import app.yacana.ui.resources.adv_e_af_time_d
import app.yacana.ui.resources.adv_e_autofocus
import app.yacana.ui.resources.adv_e_autofocus_d
import app.yacana.ui.resources.adv_e_centre
import app.yacana.ui.resources.adv_e_centre_d
import app.yacana.ui.resources.adv_e_cool
import app.yacana.ui.resources.adv_e_cool_d
import app.yacana.ui.resources.adv_e_darks_temp
import app.yacana.ui.resources.adv_e_darks_temp_d
import app.yacana.ui.resources.adv_e_exposures
import app.yacana.ui.resources.adv_e_exposures_d
import app.yacana.ui.resources.adv_e_filter
import app.yacana.ui.resources.adv_e_filter_d
import app.yacana.ui.resources.adv_e_focuser
import app.yacana.ui.resources.adv_e_focuser_d
import app.yacana.ui.resources.adv_e_goto
import app.yacana.ui.resources.adv_e_goto_d
import app.yacana.ui.resources.adv_e_loop
import app.yacana.ui.resources.adv_e_loop_d
import app.yacana.ui.resources.adv_e_note
import app.yacana.ui.resources.adv_e_note_d
import app.yacana.ui.resources.adv_e_parallel
import app.yacana.ui.resources.adv_e_parallel_d
import app.yacana.ui.resources.adv_e_park
import app.yacana.ui.resources.adv_e_park_d
import app.yacana.ui.resources.adv_e_recentre
import app.yacana.ui.resources.adv_e_recentre_d
import app.yacana.ui.resources.adv_e_sequential
import app.yacana.ui.resources.adv_e_sequential_d
import app.yacana.ui.resources.adv_e_stop
import app.yacana.ui.resources.adv_e_stop_d
import app.yacana.ui.resources.adv_e_switch
import app.yacana.ui.resources.adv_e_switch_d
import app.yacana.ui.resources.adv_e_target
import app.yacana.ui.resources.adv_e_target_d
import app.yacana.ui.resources.adv_e_unpark
import app.yacana.ui.resources.adv_e_unpark_d
import app.yacana.ui.resources.adv_e_until
import app.yacana.ui.resources.adv_e_until_d
import app.yacana.ui.resources.adv_e_wait
import app.yacana.ui.resources.adv_e_wait_altitude
import app.yacana.ui.resources.adv_e_wait_altitude_d
import app.yacana.ui.resources.adv_e_wait_d
import app.yacana.ui.resources.adv_e_wait_time
import app.yacana.ui.resources.adv_e_wait_time_d
import app.yacana.ui.resources.adv_e_warm
import app.yacana.ui.resources.adv_e_warm_d
import app.yacana.ui.resources.adv_e_while_high
import app.yacana.ui.resources.adv_e_while_high_d
import app.yacana.ui.resources.adv_p_needs_target
import app.yacana.ui.resources.adv_p_no_cooler
import app.yacana.ui.resources.adv_p_no_filter
import app.yacana.ui.resources.adv_p_no_target_lights
import app.yacana.ui.resources.adv_p_not_aimed
import app.yacana.ui.resources.adv_p_not_tracking
import app.yacana.ui.resources.adv_p_parked_focus
import app.yacana.ui.resources.adv_p_parked_goto
import app.yacana.ui.resources.adv_p_parked_lights
import app.yacana.ui.resources.adv_p_together
import app.yacana.ui.resources.adv_p_warm_darks
import app.yacana.ui.resources.adv_p_warming
import app.yacana.ui.resources.adv_s_af_filter
import app.yacana.ui.resources.adv_s_af_hfr
import app.yacana.ui.resources.adv_s_af_temp
import app.yacana.ui.resources.adv_s_af_time
import app.yacana.ui.resources.adv_s_autofocus
import app.yacana.ui.resources.adv_s_bias
import app.yacana.ui.resources.adv_s_centre
import app.yacana.ui.resources.adv_s_cool
import app.yacana.ui.resources.adv_s_darks
import app.yacana.ui.resources.adv_s_darks_temp
import app.yacana.ui.resources.adv_s_dither
import app.yacana.ui.resources.adv_s_focuser
import app.yacana.ui.resources.adv_s_goto
import app.yacana.ui.resources.adv_s_lights
import app.yacana.ui.resources.adv_s_lights_any
import app.yacana.ui.resources.adv_s_loop
import app.yacana.ui.resources.adv_s_no_target
import app.yacana.ui.resources.adv_s_note
import app.yacana.ui.resources.adv_s_note_empty
import app.yacana.ui.resources.adv_s_recentre
import app.yacana.ui.resources.adv_s_recentre_any
import app.yacana.ui.resources.adv_s_switch_off
import app.yacana.ui.resources.adv_s_switch_on
import app.yacana.ui.resources.adv_s_until
import app.yacana.ui.resources.adv_s_wait
import app.yacana.ui.resources.adv_s_wait_altitude
import app.yacana.ui.resources.adv_s_wait_time
import app.yacana.ui.resources.adv_s_warm
import app.yacana.ui.resources.adv_s_while_high
import app.yacana.ui.resources.adv_s_while_high_any
import app.yacana.ui.resources.adv_use_camera
import app.yacana.ui.resources.adv_use_focuser
import app.yacana.ui.resources.adv_use_mount
import app.yacana.ui.resources.adv_word_blocks
import app.yacana.ui.resources.adv_word_camera
import app.yacana.ui.resources.adv_word_condition
import app.yacana.ui.resources.adv_word_filters
import app.yacana.ui.resources.adv_word_focus
import app.yacana.ui.resources.adv_word_mount
import app.yacana.ui.resources.adv_word_template
import app.yacana.ui.resources.adv_word_trigger
import app.yacana.ui.resources.adv_word_utility
import app.yacana.ui.sequence.clockText
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The catalogue's categories, as N.I.N.A.'s, each with its word for an entry's line. */
internal enum class Category(val title: StringResource, val word: StringResource?) {
    All(Res.string.adv_cat_all, null),
    Camera(Res.string.adv_cat_camera, Res.string.adv_word_camera),
    Mount(Res.string.adv_cat_mount, Res.string.adv_word_mount),
    Focus(Res.string.adv_cat_focus, Res.string.adv_word_focus),
    Filters(Res.string.adv_cat_filters, Res.string.adv_word_filters),
    Utility(Res.string.adv_cat_utility, Res.string.adv_word_utility),
    Blocks(Res.string.adv_cat_blocks, Res.string.adv_word_blocks),
    Triggers(Res.string.adv_cat_triggers, Res.string.adv_word_trigger),
    Conditions(Res.string.adv_cat_conditions, Res.string.adv_word_condition),
    Templates(Res.string.adv_cat_templates, Res.string.adv_word_template),
}

/** An entry of the catalogue as it shows: its icon, name, line and category. */
internal class EntryLook(val icon: ImageVector, val name: StringResource, val about: StringResource, val category: Category)

@Suppress("CyclomaticComplexMethod") // One look per entry.
internal fun Entry.look(): EntryLook = when (this) {
    Entry.TakeExposures -> EntryLook(YacanaIcons.Camera, Res.string.adv_e_exposures, Res.string.adv_e_exposures_d, Category.Camera)

    Entry.Cool -> EntryLook(YacanaIcons.Snowflake, Res.string.adv_e_cool, Res.string.adv_e_cool_d, Category.Camera)

    Entry.Warm -> EntryLook(YacanaIcons.Flame, Res.string.adv_e_warm, Res.string.adv_e_warm_d, Category.Camera)

    Entry.UnparkMount -> EntryLook(YacanaIcons.Unpark, Res.string.adv_e_unpark, Res.string.adv_e_unpark_d, Category.Mount)

    Entry.ParkMount -> EntryLook(YacanaIcons.Park, Res.string.adv_e_park, Res.string.adv_e_park_d, Category.Mount)

    Entry.Centre -> EntryLook(YacanaIcons.Target, Res.string.adv_e_centre, Res.string.adv_e_centre_d, Category.Mount)

    Entry.GoTo -> EntryLook(YacanaIcons.Equipment, Res.string.adv_e_goto, Res.string.adv_e_goto_d, Category.Mount)

    Entry.StopMount -> EntryLook(YacanaIcons.Stop, Res.string.adv_e_stop, Res.string.adv_e_stop_d, Category.Mount)

    Entry.Autofocus -> EntryLook(YacanaIcons.Focuser, Res.string.adv_e_autofocus, Res.string.adv_e_autofocus_d, Category.Focus)

    Entry.Focuser -> EntryLook(YacanaIcons.Focuser, Res.string.adv_e_focuser, Res.string.adv_e_focuser_d, Category.Focus)

    Entry.Filter -> EntryLook(YacanaIcons.Filter, Res.string.adv_e_filter, Res.string.adv_e_filter_d, Category.Filters)

    Entry.Switch -> EntryLook(YacanaIcons.ToggleOn, Res.string.adv_e_switch, Res.string.adv_e_switch_d, Category.Utility)

    Entry.WaitTime -> EntryLook(YacanaIcons.Clock, Res.string.adv_e_wait_time, Res.string.adv_e_wait_time_d, Category.Utility)

    Entry.WaitSpan -> EntryLook(YacanaIcons.Hourglass, Res.string.adv_e_wait, Res.string.adv_e_wait_d, Category.Utility)

    Entry.WaitAltitude -> EntryLook(
        YacanaIcons.Mountain,
        Res.string.adv_e_wait_altitude,
        Res.string.adv_e_wait_altitude_d,
        Category.Utility,
    )

    Entry.Annotation -> EntryLook(YacanaIcons.Note, Res.string.adv_e_note, Res.string.adv_e_note_d, Category.Utility)

    Entry.Target -> EntryLook(YacanaIcons.Target, Res.string.adv_e_target, Res.string.adv_e_target_d, Category.Blocks)

    Entry.Sequential -> EntryLook(YacanaIcons.Layers, Res.string.adv_e_sequential, Res.string.adv_e_sequential_d, Category.Blocks)

    Entry.Parallel -> EntryLook(YacanaIcons.Parallel, Res.string.adv_e_parallel, Res.string.adv_e_parallel_d, Category.Blocks)

    Entry.AutofocusTemperature -> EntryLook(YacanaIcons.Bolt, Res.string.adv_e_af_temp, Res.string.adv_e_af_temp_d, Category.Triggers)

    Entry.AutofocusTime -> EntryLook(YacanaIcons.Bolt, Res.string.adv_e_af_time, Res.string.adv_e_af_time_d, Category.Triggers)

    Entry.AutofocusFilter -> EntryLook(YacanaIcons.Bolt, Res.string.adv_e_af_filter, Res.string.adv_e_af_filter_d, Category.Triggers)

    Entry.AutofocusHfr -> EntryLook(YacanaIcons.Bolt, Res.string.adv_e_af_hfr, Res.string.adv_e_af_hfr_d, Category.Triggers)

    Entry.Recentre -> EntryLook(YacanaIcons.Crosshair, Res.string.adv_e_recentre, Res.string.adv_e_recentre_d, Category.Triggers)

    Entry.DarksTemperature -> EntryLook(YacanaIcons.Bolt, Res.string.adv_e_darks_temp, Res.string.adv_e_darks_temp_d, Category.Triggers)

    Entry.Repeat -> EntryLook(YacanaIcons.Repeat, Res.string.adv_e_loop, Res.string.adv_e_loop_d, Category.Conditions)

    Entry.RepeatUntil -> EntryLook(YacanaIcons.Clock, Res.string.adv_e_until, Res.string.adv_e_until_d, Category.Conditions)

    Entry.RepeatWhileHigh -> EntryLook(
        YacanaIcons.Mountain,
        Res.string.adv_e_while_high,
        Res.string.adv_e_while_high_d,
        Category.Conditions,
    )
}

/** The entry an item, a trigger or a condition comes from, for its icon and name. */
@Suppress("CyclomaticComplexMethod") // One entry per kind of item.
internal fun entryOf(item: SequenceItem): Entry = when (item) {
    is Exposures -> Entry.TakeExposures

    is CoolCamera -> Entry.Cool

    WarmCamera -> Entry.Warm

    Unpark -> Entry.UnparkMount

    Park -> Entry.ParkMount

    is GoToTarget -> if (item.centre) Entry.Centre else Entry.GoTo

    StopTracking -> Entry.StopMount

    RunAutofocus -> Entry.Autofocus

    is MoveFocuser -> Entry.Focuser

    is SwitchFilter -> Entry.Filter

    is SetSwitch -> Entry.Switch

    is WaitUntilTime -> Entry.WaitTime

    is WaitFor -> Entry.WaitSpan

    is WaitUntilAbove -> Entry.WaitAltitude

    is Note -> Entry.Annotation

    is Block -> if (item.parallel) {
        Entry.Parallel
    } else if (item.target != null) {
        Entry.Target
    } else {
        Entry.Sequential
    }
}

internal fun entryOf(trigger: Trigger): Entry = when (trigger) {
    is Trigger.AutofocusAfterTemperature -> Entry.AutofocusTemperature
    is Trigger.AutofocusAfterTime -> Entry.AutofocusTime
    Trigger.AutofocusAfterFilter -> Entry.AutofocusFilter
    is Trigger.AutofocusAfterHfr -> Entry.AutofocusHfr
    is Trigger.RecentreAfterDrift -> Entry.Recentre
    is Trigger.DarksAfterTemperature -> Entry.DarksTemperature
}

internal fun entryOf(condition: Condition): Entry = when (condition) {
    is Condition.Loop -> Entry.Repeat
    is Condition.UntilTime -> Entry.RepeatUntil
    is Condition.WhileAbove -> Entry.RepeatWhileHigh
}

/** A number as the screens write it: whole, or with a decimal in the user's way. */
@Composable
internal fun number(value: Double): String = formatDecimal(value, if (value % 1.0 == 0.0) 0 else 1, decimalComma())

/** What an instruction does, in a line, with the [target] of its block. */
@Composable
@Suppress("CyclomaticComplexMethod") // One line per kind of instruction.
internal fun summary(item: SequenceItem, target: String?): String = when (item) {
    is Exposures -> exposuresSummary(item)

    is CoolCamera -> stringResource(Res.string.adv_s_cool, number(item.temperatureC), number(item.minutes))

    WarmCamera -> stringResource(Res.string.adv_s_warm)

    is GoToTarget ->
        target?.let { stringResource(if (item.centre) Res.string.adv_s_centre else Res.string.adv_s_goto, it) }
            ?: stringResource(Res.string.adv_s_no_target)

    RunAutofocus -> stringResource(Res.string.adv_s_autofocus)

    is MoveFocuser -> stringResource(Res.string.adv_s_focuser, item.position)

    is SwitchFilter -> item.filter

    is SetSwitch -> stringResource(if (item.value > 0) Res.string.adv_s_switch_on else Res.string.adv_s_switch_off, item.name)

    is WaitUntilTime -> stringResource(Res.string.adv_s_wait_time, clockText(item.minute))

    is WaitFor -> stringResource(Res.string.adv_s_wait, number(item.minutes))

    is WaitUntilAbove ->
        target?.let { stringResource(Res.string.adv_s_wait_altitude, it, number(item.altitudeDeg)) }
            ?: stringResource(Res.string.adv_s_no_target)

    is Note -> if (item.text.isBlank()) stringResource(Res.string.adv_s_note_empty) else stringResource(Res.string.adv_s_note, item.text)

    is Block, Unpark, Park, StopTracking -> stringResource(entryOf(item).look().about)
}

@Composable
private fun exposuresSummary(item: Exposures): String {
    val time = number(item.exposureSeconds)
    return when (item.type) {
        FrameType.Bias -> stringResource(Res.string.adv_s_bias, item.count)

        FrameType.Dark -> stringResource(Res.string.adv_s_darks, time, item.count)

        else -> {
            val lights = item.filter?.let { stringResource(Res.string.adv_s_lights, it, time, item.count) }
                ?: stringResource(Res.string.adv_s_lights_any, time, item.count)
            if (item.ditherEvery > 0) stringResource(Res.string.adv_s_dither, lights, item.ditherEvery) else lights
        }
    }
}

/** What a trigger does, in a line, with the [target] of its block. */
@Composable
internal fun summary(trigger: Trigger, target: String?): String = when (trigger) {
    is Trigger.AutofocusAfterTemperature -> stringResource(Res.string.adv_s_af_temp, number(trigger.amount))

    is Trigger.AutofocusAfterTime -> stringResource(Res.string.adv_s_af_time, number(trigger.minutes))

    Trigger.AutofocusAfterFilter -> stringResource(Res.string.adv_s_af_filter)

    is Trigger.AutofocusAfterHfr -> stringResource(Res.string.adv_s_af_hfr, number(trigger.amount))

    is Trigger.RecentreAfterDrift ->
        target?.let { stringResource(Res.string.adv_s_recentre, it, number(trigger.arcminutes)) }
            ?: stringResource(Res.string.adv_s_recentre_any, number(trigger.arcminutes))

    is Trigger.DarksAfterTemperature -> stringResource(Res.string.adv_s_darks_temp, number(trigger.amount), trigger.count)
}

/** What a condition does, in a line, with the [target] of its block. */
@Composable
internal fun summary(condition: Condition, target: String?): String = when (condition) {
    is Condition.Loop -> stringResource(Res.string.adv_s_loop, condition.times)

    is Condition.UntilTime -> stringResource(Res.string.adv_s_until, clockText(condition.minute))

    is Condition.WhileAbove ->
        target?.let { stringResource(Res.string.adv_s_while_high, it, number(condition.altitudeDeg)) }
            ?: stringResource(Res.string.adv_s_while_high_any, number(condition.altitudeDeg))
}
