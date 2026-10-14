// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.polar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.polar.MeasureStep
import app.yacana.domain.polar.PolarAlignment
import app.yacana.domain.polar.PolarAlignmentState
import app.yacana.domain.polar.PolarFailure
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.errorText
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.polar_before
import app.yacana.ui.resources.polar_cancel
import app.yacana.ui.resources.polar_east
import app.yacana.ui.resources.polar_failed_cannot_move
import app.yacana.ui.resources.polar_failed_error
import app.yacana.ui.resources.polar_failed_no_mount
import app.yacana.ui.resources.polar_failed_no_site
import app.yacana.ui.resources.polar_failed_not_solved
import app.yacana.ui.resources.polar_frames
import app.yacana.ui.resources.polar_frames_blind
import app.yacana.ui.resources.polar_frames_hint
import app.yacana.ui.resources.polar_hands_off
import app.yacana.ui.resources.polar_measuring
import app.yacana.ui.resources.polar_point
import app.yacana.ui.resources.polar_point_exposing
import app.yacana.ui.resources.polar_point_pending
import app.yacana.ui.resources.polar_point_solved
import app.yacana.ui.resources.polar_point_solving
import app.yacana.ui.resources.polar_point_turning
import app.yacana.ui.resources.polar_retry
import app.yacana.ui.resources.polar_start
import app.yacana.ui.resources.polar_west
import app.yacana.ui.solving.solveFailureText
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * The polar alignment assistant, full screen as its designs: "before starting"
 * with its options, "measuring" while the three points are measured, and "still in minutes" and
 * "fine-tuning in seconds" while the screws turn.
 */
@Composable
fun PolarAlignmentScreen(polar: PolarAlignment, equipment: EquipmentController, clock: Clock, close: () -> Unit) {
    val state by polar.state.collectAsState()
    PolarAlignmentContent(state, polar, equipment, clock, close)
}

/** The assistant's screen for [state]; also for screenshots of each step. */
@Composable
fun PolarAlignmentContent(
    state: PolarAlignmentState,
    polar: PolarAlignment,
    equipment: EquipmentController,
    clock: Clock,
    close: () -> Unit,
) {
    when (val current = state) {
        PolarAlignmentState.Idle -> BeforeStart(polar, equipment)

        is PolarAlignmentState.Measuring -> Measuring(polar, current)

        is PolarAlignmentState.Adjusting -> Adjusting(polar, current, clock) {
            polar.stop()
            close()
        }

        is PolarAlignmentState.Failed -> Failed(current.reason) { polar.start() }
    }
}

/** The screen's frame: a muted step line, the content, and the buttons at the bottom. */
@Composable
internal fun AssistantPage(step: String, buttons: @Composable RowScope.() -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                step,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            content()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons()
        }
    }
}

@Composable
private fun BeforeStart(polar: PolarAlignment, equipment: EquipmentController) {
    AssistantPage(
        stringResource(Res.string.polar_before),
        buttons = {
            AccentButton(onClick = polar::start, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
                Text(stringResource(Res.string.polar_start))
            }
        },
    ) { PolarOptions(equipment) }
}

@Composable
private fun Measuring(polar: PolarAlignment, state: PolarAlignmentState.Measuring) {
    val options = polar.options
    val comma = decimalComma()
    val direction = stringResource(if (options.east) Res.string.polar_east else Res.string.polar_west)
    val step = formatDecimal(options.stepDeg, 0, comma)
    AssistantPage(
        stringResource(Res.string.polar_measuring, state.point),
        buttons = { SurfaceButton(stringResource(Res.string.polar_cancel), polar::stop, Modifier.weight(1f)) },
    ) {
        SectionCard { PointsArc(state.point, state.step == MeasureStep.Turning, step) }
        SectionCard {
            for (point in 1..POINTS) {
                val (icon, tint, text) =
                    when {
                        point < state.point -> Triple(
                            YacanaIcons.CircleCheck,
                            MaterialTheme.colorScheme.tertiary,
                            stringResource(
                                Res.string.polar_point_solved,
                                formatDecimal(state.solveTimes[point - 1].inWholeMilliseconds / MILLIS, 1, comma),
                            ),
                        )

                        point == state.point -> Triple(
                            YacanaIcons.Loader,
                            MaterialTheme.colorScheme.primary,
                            when (state.step) {
                                MeasureStep.Turning -> stringResource(Res.string.polar_point_turning, step, direction)
                                MeasureStep.Exposing -> stringResource(Res.string.polar_point_exposing)
                                MeasureStep.Solving -> stringResource(Res.string.polar_point_solving)
                            },
                        )

                        else -> Triple(
                            YacanaIcons.Circle,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            stringResource(Res.string.polar_point_pending),
                        )
                    }
                Row(
                    Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Column {
                        Text(stringResource(Res.string.polar_point, point), style = MaterialTheme.typography.bodyLarge)
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(YacanaIcons.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.polar_hands_off), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            stringResource(
                Res.string.polar_frames,
                formatDecimal(options.exposureSeconds, 0, comma),
                options.binning,
                stringResource(if (options.hintOnly) Res.string.polar_frames_hint else Res.string.polar_frames_blind),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun Failed(reason: PolarFailure, retry: () -> Unit) {
    val text =
        when (reason) {
            PolarFailure.NoMount -> stringResource(Res.string.polar_failed_no_mount)

            PolarFailure.NoSite -> stringResource(Res.string.polar_failed_no_site)

            PolarFailure.CannotMoveAxis -> stringResource(Res.string.polar_failed_cannot_move)

            is PolarFailure.NotSolved -> stringResource(Res.string.polar_failed_not_solved, reason.point, solveFailureText(reason.reason))

            is PolarFailure.Error -> stringResource(
                Res.string.polar_failed_error,
                reason.cause.message?.let { errorText(it) } ?: reason.cause::class.simpleName.orEmpty(),
            )
        }
    AssistantPage(
        stringResource(Res.string.polar_before),
        buttons = {
            AccentButton(onClick = retry, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
                Text(stringResource(Res.string.polar_retry))
            }
        },
    ) {
        SectionCard { Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
    }
}

private const val POINTS = 3
private const val MILLIS = 1000.0
