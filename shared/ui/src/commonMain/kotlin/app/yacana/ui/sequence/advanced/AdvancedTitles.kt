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
import app.yacana.domain.sequence.Place
import app.yacana.domain.sequence.Problem
import app.yacana.domain.sequence.RunAutofocus
import app.yacana.domain.sequence.SequenceItem
import app.yacana.domain.sequence.SetSwitch
import app.yacana.domain.sequence.Spot
import app.yacana.domain.sequence.StopTracking
import app.yacana.domain.sequence.SwitchFilter
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.Unpark
import app.yacana.domain.sequence.Use
import app.yacana.domain.sequence.WaitFor
import app.yacana.domain.sequence.WaitUntilAbove
import app.yacana.domain.sequence.WaitUntilTime
import app.yacana.domain.sequence.WarmCamera
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
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
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
import app.yacana.ui.resources.adv_blocked_area
import app.yacana.ui.resources.adv_blocked_conditions
import app.yacana.ui.resources.adv_blocked_cooler
import app.yacana.ui.resources.adv_blocked_dark_filter
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
import app.yacana.ui.resources.adv_count_inside
import app.yacana.ui.resources.adv_count_items
import app.yacana.ui.resources.adv_count_triggers
import app.yacana.ui.resources.adv_dark
import app.yacana.ui.resources.adv_done
import app.yacana.ui.resources.adv_drag
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
import app.yacana.ui.resources.adv_parallel_about
import app.yacana.ui.resources.adv_repeat_once
import app.yacana.ui.resources.adv_repeat_once_list
import app.yacana.ui.resources.adv_repeat_running
import app.yacana.ui.resources.adv_repeat_while
import app.yacana.ui.resources.adv_repeat_while_all
import app.yacana.ui.resources.adv_review_count
import app.yacana.ui.resources.adv_running
import app.yacana.ui.resources.adv_s_af_filter
import app.yacana.ui.resources.adv_s_af_hfr
import app.yacana.ui.resources.adv_s_af_temp
import app.yacana.ui.resources.adv_s_af_time
import app.yacana.ui.resources.adv_s_autofocus
import app.yacana.ui.resources.adv_s_bias
import app.yacana.ui.resources.adv_s_centre
import app.yacana.ui.resources.adv_s_cool
import app.yacana.ui.resources.adv_s_darks
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
import app.yacana.ui.resources.adv_tonight
import app.yacana.ui.resources.adv_triggers
import app.yacana.ui.resources.adv_triggers_night
import app.yacana.ui.resources.adv_undo
import app.yacana.ui.resources.adv_use_camera
import app.yacana.ui.resources.adv_use_focuser
import app.yacana.ui.resources.adv_use_mount
import app.yacana.ui.resources.adv_window
import app.yacana.ui.resources.adv_word_blocks
import app.yacana.ui.resources.adv_word_camera
import app.yacana.ui.resources.adv_word_condition
import app.yacana.ui.resources.adv_word_filters
import app.yacana.ui.resources.adv_word_focus
import app.yacana.ui.resources.adv_word_mount
import app.yacana.ui.resources.adv_word_template
import app.yacana.ui.resources.adv_word_trigger
import app.yacana.ui.resources.adv_word_utility
import app.yacana.ui.sequence.Muted
import app.yacana.ui.sequence.clockText
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The title of the level at [path]: "Night", an area's name, or the block's. */
@Composable
internal fun levelTitle(tonight: Tonight, path: List<Int>): String = when {
    path.isEmpty() -> stringResource(Res.string.adv_night)
    path.size == 1 -> stringResource(areaName(path[0]))
    else -> tonight.tree.blockAt(path)?.name.orEmpty()
}

internal fun areaName(index: Int) = when (index) {
    0 -> Res.string.adv_area_start
    1 -> Res.string.adv_area_targets
    else -> Res.string.adv_area_end
}

/** The title of the item at [path]: a level's, or an instruction's entry name. */
@Composable
internal fun itemTitle(tonight: Tonight, path: List<Int>): String {
    val item = tonight.tree.blockAt(path.dropLast(1))?.items?.getOrNull(path.lastOrNull() ?: -1)
    return if (item == null || item is Block) levelTitle(tonight, path) else stringResource(entryOf(item).look().name)
}

/** "Target · 4 instructions · 1 trigger". */
@Composable
internal fun kindLine(block: Block, place: Place, area: Boolean): String {
    val kind =
        when {
            area || place == Place.Night -> Res.string.adv_kind_area
            block.parallel -> Res.string.adv_kind_parallel
            block.target != null && block.items.any { it is Block && it.target != null } -> Res.string.adv_kind_mosaic
            block.target != null -> Res.string.adv_kind_target
            else -> Res.string.adv_kind_sequential
        }
    val parts = mutableListOf(stringResource(kind), pluralStringResource(Res.plurals.adv_count_items, block.items.size, block.items.size))
    if (block.triggers.isNotEmpty()) parts += pluralStringResource(Res.plurals.adv_count_triggers, block.triggers.size, block.triggers.size)
    return parts.joinToString(" · ")
}

/** "Above 30° from 20:10 to 05:20": when a target is above 30° tonight; null without a site or if it never is. */
@Composable
internal fun window(tonight: Tonight, position: SkyPosition): String? =
    high(tonight, position)?.let { (from, to) -> stringResource(Res.string.adv_window, from, to) }

/** When a target is above 30° tonight, from and to, as the screens write the time; null without a site or if it never is. */
@Composable
internal fun high(tonight: Tonight, position: SkyPosition): Pair<String, String>? {
    val site = tonight.site
    val night = tonight.night
    val above =
        remember(position, night, site) {
            if (site == null ||
                night == null
            ) {
                emptyList()
            } else {
                Ephemerides.altitudes(position, night, site).filter { it.second >= HIGH_DEG }.map { it.first }
            }
        }
    if (above.isEmpty()) return null
    return clockTime(above.first(), tonight.zone) to clockTime(above.last(), tonight.zone)
}

private const val HIGH_DEG = 30.0

/** Why an entry or an item can't go where the editor is; [holder] names where that is. */
@Composable
internal fun blockedText(blocked: Blocked, holder: String): String = when (blocked) {
    Blocked.EnterAnArea -> stringResource(Res.string.adv_blocked_area)
    Blocked.TriggersForTheNight -> stringResource(Res.string.adv_blocked_triggers)
    Blocked.NoConditions -> stringResource(Res.string.adv_blocked_conditions, holder)
    Blocked.OnlyInATarget -> stringResource(Res.string.adv_blocked_target)
    Blocked.AlreadyInATarget -> stringResource(Res.string.adv_blocked_in_target)
    Blocked.NoCooler -> stringResource(Res.string.adv_blocked_cooler)
    Blocked.NoDarkFilter -> stringResource(Res.string.adv_blocked_dark_filter)
}

/** What "Review" says of a problem; [title] names the item at a path, for two at once. */
@Composable
internal fun problemText(problem: Problem, title: @Composable (List<Int>) -> String): String = when (problem) {
    Problem.NeedsTarget -> stringResource(Res.string.adv_p_needs_target)
    Problem.NoCooler -> stringResource(Res.string.adv_p_no_cooler)
    is Problem.NoFilter -> stringResource(Res.string.adv_p_no_filter, problem.filter)
    Problem.ParkedGoTo -> stringResource(Res.string.adv_p_parked_goto)
    Problem.ParkedAutofocus -> stringResource(Res.string.adv_p_parked_focus)
    is Problem.ParkedLights -> stringResource(Res.string.adv_p_parked_lights, problem.target)
    is Problem.NotAimed -> stringResource(Res.string.adv_p_not_aimed, problem.target)
    Problem.NotTracking -> stringResource(Res.string.adv_p_not_tracking)
    Problem.LightsWithoutTarget -> stringResource(Res.string.adv_p_no_target_lights)
    Problem.Warming -> stringResource(Res.string.adv_p_warming)
    Problem.WarmDarks -> stringResource(Res.string.adv_p_warm_darks)
    is Problem.Together -> stringResource(Res.string.adv_p_together, title(problem.first), title(problem.second), useText(problem.use))
}

@Composable
private fun useText(use: Use): String = stringResource(
    when (use) {
        Use.Camera -> Res.string.adv_use_camera
        Use.Mount -> Res.string.adv_use_mount
        Use.Focuser -> Res.string.adv_use_focuser
    },
)
