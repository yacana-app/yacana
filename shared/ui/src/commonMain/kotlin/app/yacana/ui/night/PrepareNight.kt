// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.night

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentState
import app.yacana.domain.focus.AutofocusResult
import app.yacana.domain.polar.PolarAlignmentState
import app.yacana.domain.sequence.NightPlan
import app.yacana.domain.sequence.Target
import app.yacana.ui.Section
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatPolarError
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.eq_connect
import app.yacana.ui.resources.eq_devices
import app.yacana.ui.resources.night_adjust
import app.yacana.ui.resources.night_ago
import app.yacana.ui.resources.night_align
import app.yacana.ui.resources.night_autofocus
import app.yacana.ui.resources.night_choose
import app.yacana.ui.resources.night_connecting
import app.yacana.ui.resources.night_fits
import app.yacana.ui.resources.night_focus_hfr
import app.yacana.ui.resources.night_late
import app.yacana.ui.resources.night_mosaic
import app.yacana.ui.resources.night_no_targets
import app.yacana.ui.resources.night_none
import app.yacana.ui.resources.night_not_connected
import app.yacana.ui.resources.night_not_done
import app.yacana.ui.resources.night_prepare
import app.yacana.ui.resources.night_refine
import app.yacana.ui.resources.night_step_equipment
import app.yacana.ui.resources.night_step_focus
import app.yacana.ui.resources.night_step_polar
import app.yacana.ui.resources.night_step_sequence
import app.yacana.ui.resources.night_step_targets
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Instant

/** How far along a step of the night is: done, done but worth another look, or not done. */
private enum class Step { Done, Again, Pending }

/**
 * "Prepare the night", as "Night · before starting": the equipment, the polar alignment, the
 * focus, the targets and the sequence, each done or not, with a button to do it. None is
 * compulsory: the night can start whatever is left.
 */
@Composable
@Suppress("LongParameterList") // Each step reads its own part of the session.
internal fun PrepareCard(
    equipment: EquipmentState,
    polar: PolarAlignmentState.Adjusting?,
    focus: AutofocusResult?,
    targets: List<Target>,
    plan: NightPlan,
    now: Instant,
    open: (Screen) -> Unit,
    onConnect: () -> Unit,
) {
    val connection = equipment.connection
    val profile = equipment.active
    SectionCard {
        Text(
            stringResource(Res.string.night_prepare),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (connection) {
            is Connection.Connected -> {
                val devices = connection.equipment.profile.devices.byRole().size - connection.equipment.failures.size
                PrepareRow(
                    Step.Done,
                    stringResource(Res.string.night_step_equipment),
                    "${connection.equipment.profile.name} · ${pluralStringResource(Res.plurals.eq_devices, devices, devices)}",
                )
            }

            Connection.Connecting -> PrepareRow(
                Step.Pending,
                stringResource(Res.string.night_step_equipment),
                stringResource(Res.string.night_connecting),
            )

            else -> PrepareRow(
                Step.Pending,
                stringResource(Res.string.night_step_equipment),
                stringResource(Res.string.night_not_connected),
                stringResource(Res.string.eq_connect),
                onAction = onConnect,
            )
        }
        if (polar != null) {
            PrepareRow(
                Step.Again,
                stringResource(Res.string.night_step_polar),
                stringResource(Res.string.night_ago, formatPolarError(polar.current.totalDeg), span(now - polar.measuredAt)),
                stringResource(Res.string.night_refine),
            ) { open(Screen.PolarAlignment) }
        } else {
            PrepareRow(
                Step.Pending,
                stringResource(Res.string.night_step_polar),
                stringResource(Res.string.night_not_done),
                stringResource(Res.string.night_align),
            ) { open(Screen.PolarAlignment) }
        }
        // Only with a focuser to focus.
        if (profile?.devices?.focuser != null) {
            if (focus != null) {
                val hfr = stringResource(Res.string.night_focus_hfr, formatDecimal(focus.hfr, 1, decimalComma()))
                PrepareRow(
                    Step.Done,
                    stringResource(Res.string.night_step_focus),
                    stringResource(Res.string.night_ago, hfr, span(now - focus.at)),
                )
            } else {
                PrepareRow(
                    Step.Pending,
                    stringResource(Res.string.night_step_focus),
                    stringResource(Res.string.night_not_done),
                    stringResource(Res.string.night_autofocus),
                ) { open(Screen.Autofocus) }
            }
        }
        TargetsRow(targets, open)
        SequenceRow(targets, plan, open)
    }
}

@Composable
private fun TargetsRow(targets: List<Target>, open: (Screen) -> Unit) {
    if (targets.isEmpty()) {
        PrepareRow(
            Step.Pending,
            stringResource(Res.string.night_step_targets),
            stringResource(Res.string.night_none),
            stringResource(Res.string.night_choose),
        ) { open(Screen.Root(Section.Sky)) }
        return
    }
    val names =
        targets.map { target ->
            if (target.panels.size > 1) stringResource(Res.string.night_mosaic, target.name, target.panels.size) else target.name
        }
    PrepareRow(Step.Done, stringResource(Res.string.night_step_targets), names.joinToString(" · "))
}

/** Whether tonight's sequence ends before dawn, and how long it lasts. */
@Composable
private fun SequenceRow(targets: List<Target>, plan: NightPlan, open: (Screen) -> Unit) {
    val title = stringResource(Res.string.night_step_sequence)
    val length = span(plan.end - plan.start)
    when {
        targets.isEmpty() -> PrepareRow(Step.Pending, title, stringResource(Res.string.night_no_targets), last = true)

        plan.late != null -> PrepareRow(
            Step.Again,
            title,
            stringResource(Res.string.night_late, length),
            stringResource(Res.string.night_adjust),
            last = true,
        ) { open(Screen.SequenceTonight) }

        else -> PrepareRow(Step.Done, title, stringResource(Res.string.night_fits, length), last = true)
    }
}

/** "25 s", "12 min" or "1 h 20 min": how long ago something was done, or how long something lasts. */
internal fun span(duration: Duration): String = when {
    duration.inWholeHours > 0 -> hoursAndMinutes(duration)
    duration.inWholeMinutes > 0 -> "${duration.inWholeMinutes} min"
    else -> "${duration.inWholeSeconds.coerceAtLeast(0)} s"
}

/**
 * One step, as the design's rows: its mark, its name over how it stands, and the button that does
 * it, if any; a line under it unless it is the [last].
 */
@Composable
@Suppress("LongParameterList") // The mark, the texts and the button.
private fun PrepareRow(
    step: Step,
    title: String,
    status: String,
    action: String? = null,
    last: Boolean = false,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint) =
        when (step) {
            Step.Done -> YacanaIcons.CircleCheck to colors.tertiary
            Step.Again -> YacanaIcons.Target to colors.primary
            Step.Pending -> YacanaIcons.Circle to colors.onSurfaceVariant
        }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Touch.Min).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepIcon(icon, tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (action != null) SettingChip(action, onAction)
        }
        if (!last) HorizontalDivider(color = colors.outline)
    }
}

@Composable
private fun StepIcon(icon: ImageVector, tint: Color) = Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
