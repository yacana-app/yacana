// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yacana.domain.focus.Autofocus
import app.yacana.domain.focus.AutofocusFailure
import app.yacana.domain.focus.AutofocusResult
import app.yacana.domain.focus.AutofocusState
import app.yacana.domain.focus.FocusFitting
import app.yacana.domain.focus.HyperbolicFitting
import app.yacana.domain.focus.QuadraticFitting
import app.yacana.domain.focus.TrendlineFitting
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.equipment.formatTemperature
import app.yacana.ui.format.errorText
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.polar.AssistantPage
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.af_back
import app.yacana.ui.resources.af_best_estimate
import app.yacana.ui.resources.af_cancel
import app.yacana.ui.resources.af_done
import app.yacana.ui.resources.af_done_note
import app.yacana.ui.resources.af_failed_bad_fit
import app.yacana.ui.resources.af_failed_error
import app.yacana.ui.resources.af_failed_no_camera
import app.yacana.ui.resources.af_failed_no_focuser
import app.yacana.ui.resources.af_failed_not_absolute
import app.yacana.ui.resources.af_failed_outside
import app.yacana.ui.resources.af_failed_spread
import app.yacana.ui.resources.af_failed_worse
import app.yacana.ui.resources.af_finish
import app.yacana.ui.resources.af_fit_hyperbolic
import app.yacana.ui.resources.af_fit_in_hyperbolic
import app.yacana.ui.resources.af_fit_in_parabolic
import app.yacana.ui.resources.af_fit_in_trend_hyperbolic
import app.yacana.ui.resources.af_fit_in_trend_parabolic
import app.yacana.ui.resources.af_fit_in_trendlines
import app.yacana.ui.resources.af_fit_parabolic
import app.yacana.ui.resources.af_fit_trend_hyperbolic
import app.yacana.ui.resources.af_fit_trend_parabolic
import app.yacana.ui.resources.af_fit_trendlines
import app.yacana.ui.resources.af_focus
import app.yacana.ui.resources.af_hfr
import app.yacana.ui.resources.af_lowest_hfr
import app.yacana.ui.resources.af_point
import app.yacana.ui.resources.af_point_filter
import app.yacana.ui.resources.af_retry
import app.yacana.ui.resources.af_running_note
import app.yacana.ui.resources.af_temperature
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/**
 * The autofocus assistant, full screen as its designs: "Autofocus" while it measures,
 * with the V curve live, and "Autofocus · done" when it ends, or why it failed. It starts
 * when it opens.
 */
@Composable
fun AutofocusScreen(autofocus: Autofocus, close: () -> Unit) {
    val state by autofocus.state.collectAsState()
    LaunchedEffect(Unit) { if (autofocus.state.value is AutofocusState.Idle) autofocus.start() }
    AutofocusContent(state, autofocus) {
        autofocus.dismiss()
        close()
    }
}

/** The assistant's screen for [state]; also for screenshots of each step. */
@Composable
fun AutofocusContent(state: AutofocusState, autofocus: Autofocus, close: () -> Unit) {
    when (state) {
        AutofocusState.Idle -> Unit
        is AutofocusState.Running -> Running(state) { autofocus.cancel() }
        is AutofocusState.Done -> Done(state.result, close)
        is AutofocusState.Failed -> Failed(state) { autofocus.start() }
    }
}

@Composable
private fun Running(state: AutofocusState.Running, cancel: () -> Unit) {
    val comma = decimalComma()
    val options = state.options
    val measured = state.points.map { it.position }
    val pending =
        (-options.offsetSteps..options.offsetSteps)
            .map { (state.initialPosition + it * options.stepSize).toDouble() }
            .filter { it !in measured }
    val exposure = formatDecimal(options.exposureSeconds, 0, comma)
    val point = minOf(state.points.size + 1, state.expected)
    val step =
        state.filter?.let { stringResource(Res.string.af_point_filter, point, state.expected, exposure, it) }
            ?: stringResource(Res.string.af_point, point, state.expected, exposure)
    AssistantPage(step, buttons = { SurfaceButton(stringResource(Res.string.af_cancel), cancel, Modifier.weight(1f)) }) {
        SectionCard {
            FocusChart(
                state.points,
                pending,
                fittedCurve(state.points, options.fitting),
                state.estimate?.position,
                settled = false,
                axes = true,
                modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
            )
        }
        Metrics(
            stringResource(Res.string.af_best_estimate) to (state.estimate?.position?.toInt()?.toString() ?: "—"),
            stringResource(Res.string.af_lowest_hfr) to
                (state.points.filter { it.hfr > 0 }.minOfOrNull { it.hfr }?.let { "${formatDecimal(it, 1, comma)} px" } ?: "—"),
            stringResource(Res.string.af_temperature) to (state.temperature?.let { "${formatTemperature(it)} °C" } ?: "—"),
        )
        Note(stringResource(Res.string.af_running_note, fittingInSentence(options.fitting), state.expected))
    }
}

@Composable
private fun Done(result: AutofocusResult, close: () -> Unit) {
    val comma = decimalComma()
    AssistantPage(
        stringResource(Res.string.af_done, result.points.size, formatDuration(result.duration)),
        buttons = {
            AccentButton(onClick = close, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
                Text(stringResource(Res.string.af_finish))
            }
        },
    ) {
        SectionCard {
            FocusChart(
                result.points,
                emptyList(),
                fittedCurve(result.points, result.fitting),
                result.position.toDouble(),
                settled = true,
                axes = true,
                modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
            )
        }
        Metrics(
            stringResource(Res.string.af_focus) to result.position.toString(),
            stringResource(Res.string.af_hfr) to "${formatDecimal(result.hfr, 1, comma)} px",
            stringResource(Res.string.af_temperature) to (result.temperature?.let { "${formatTemperature(it)} °C" } ?: "—"),
        )
        Note(
            stringResource(
                Res.string.af_done_note,
                fittingInSentence(result.fitting),
                formatDecimal(rSquared(result), 2, comma),
                result.position,
                result.initialPosition,
            ),
        )
    }
}

@Composable
private fun Failed(state: AutofocusState.Failed, retry: () -> Unit) {
    val comma = decimalComma()
    val reason =
        when (val failure = state.reason) {
            AutofocusFailure.NoFocuser -> stringResource(Res.string.af_failed_no_focuser)

            AutofocusFailure.NoCamera -> stringResource(Res.string.af_failed_no_camera)

            AutofocusFailure.NotAbsolute -> stringResource(Res.string.af_failed_not_absolute)

            AutofocusFailure.NotEnoughSpread -> stringResource(Res.string.af_failed_spread)

            AutofocusFailure.OutsideCurve -> stringResource(Res.string.af_failed_outside)

            is AutofocusFailure.BadFit -> stringResource(
                Res.string.af_failed_bad_fit,
                formatDecimal(failure.rSquared, 2, comma),
                formatDecimal(failure.threshold, 2, comma),
            )

            is AutofocusFailure.Worse -> stringResource(
                Res.string.af_failed_worse,
                formatDecimal(failure.hfr, 1, comma),
                formatDecimal(failure.before, 1, comma),
            )

            is AutofocusFailure.Error -> stringResource(
                Res.string.af_failed_error,
                failure.cause.message?.let { errorText(it) } ?: failure.cause::class.simpleName.orEmpty(),
            )
        }
    AssistantPage(
        "",
        buttons = {
            AccentButton(onClick = retry, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
                Text(stringResource(Res.string.af_retry))
            }
        },
    ) {
        if (state.points.isNotEmpty()) {
            SectionCard {
                FocusChart(
                    state.points,
                    emptyList(),
                    null,
                    null,
                    settled = true,
                    axes = true,
                    modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
                )
            }
        }
        SectionCard {
            Text(reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            state.initialPosition?.let {
                Text(
                    stringResource(Res.string.af_back, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The design's three boxes: a name and its value. */
@Composable
private fun Metrics(vararg metrics: Pair<String, String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.forEach { (name, value) ->
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

private val CHART_HEIGHT = 200.dp
