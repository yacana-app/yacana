// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.polar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yacana.domain.polar.PolarAlignment
import app.yacana.domain.polar.PolarAlignmentState
import app.yacana.domain.polar.PolarError
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SurfaceButton
import app.yacana.ui.design.Touch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.format.formatPolarError
import app.yacana.ui.format.polarArcseconds
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.polar_adjusting
import app.yacana.ui.resources.polar_again
import app.yacana.ui.resources.polar_altitude
import app.yacana.ui.resources.polar_azimuth
import app.yacana.ui.resources.polar_centred
import app.yacana.ui.resources.polar_down
import app.yacana.ui.resources.polar_down_little
import app.yacana.ui.resources.polar_finish
import app.yacana.ui.resources.polar_measured
import app.yacana.ui.resources.polar_measured_first
import app.yacana.ui.resources.polar_missed
import app.yacana.ui.resources.polar_total
import app.yacana.ui.resources.polar_turn_east
import app.yacana.ui.resources.polar_turn_east_little
import app.yacana.ui.resources.polar_turn_west
import app.yacana.ui.resources.polar_turn_west_little
import app.yacana.ui.resources.polar_up
import app.yacana.ui.resources.polar_up_little
import app.yacana.ui.resources.polar_within
import app.yacana.ui.resources.polar_zoomed
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * "Polar alignment · still in minutes" and "fine-tuning in seconds": an arrow and a way
 * to turn for each axis while its error, rounded to the second, is not 0″; the error in degrees,
 * minutes and seconds as N.I.N.A. writes it; and the target, enlarged to seconds near the pole.
 * Nothing is taken as good before 00° 00′ 00″.
 */
@Composable
internal fun Adjusting(polar: PolarAlignment, state: PolarAlignmentState.Adjusting, clock: Clock, finish: () -> Unit) {
    val error = state.current
    val within = polar.withinTolerance(state)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(within) { if (within) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    var now by remember { mutableStateOf(clock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds)
            now = clock.now()
        }
    }
    AssistantPage(
        stringResource(Res.string.polar_adjusting),
        buttons = {
            SurfaceButton(stringResource(Res.string.polar_again), polar::start, Modifier.weight(1f))
            AccentButton(onClick = finish, modifier = Modifier.weight(1f).heightIn(min = Touch.Main)) {
                Text(stringResource(Res.string.polar_finish))
            }
        },
    ) {
        AxisCard(Res.string.polar_altitude, altitudeMove(error.altitudeDeg, state.northern), error.altitudeDeg)
        AxisCard(Res.string.polar_azimuth, azimuthMove(error.azimuthDeg, state.northern), error.azimuthDeg)
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.polar_total), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatPolarError(error.totalDeg), style = MaterialTheme.typography.titleMedium)
            }
        }
        val seconds = abs(polarArcseconds(error.totalDeg)) < SECONDS_SCALE_BELOW
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Target(error, state.previous, state.northern, seconds, Modifier.size(TARGET_SIZE))
        }
        val notes =
            listOfNotNull(
                if (seconds) stringResource(Res.string.polar_zoomed) else null,
                state.interval?.let {
                    stringResource(
                        Res.string.polar_measured,
                        (now - state.measuredAt).inWholeSeconds.coerceAtLeast(0),
                        formatDecimal(it.inWholeMilliseconds / MILLIS, 1, decimalComma()),
                    )
                } ?: stringResource(Res.string.polar_measured_first),
                if (state.missed > 0) stringResource(Res.string.polar_missed) else null,
                if (within) stringResource(Res.string.polar_within) else null,
            )
        notes.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** Which way to turn an axis, and how it is said; [icon] null when it is centred. */
private class Move(val icon: ImageVector?, val text: StringResource)

/** As the plugin: in the north a positive altitude error goes down; in the south, up. */
private fun altitudeMove(errorDeg: Double, northern: Boolean): Move {
    val seconds = polarArcseconds(errorDeg)
    val little = abs(seconds) < SECONDS_PER_MINUTE
    return when {
        seconds == 0L -> Move(null, Res.string.polar_centred)
        (seconds > 0) == northern -> Move(YacanaIcons.ArrowBigDown, if (little) Res.string.polar_down_little else Res.string.polar_down)
        else -> Move(YacanaIcons.ArrowBigUp, if (little) Res.string.polar_up_little else Res.string.polar_up)
    }
}

/** As the plugin: a positive azimuth error turns left, west in the north and east in the south. */
private fun azimuthMove(errorDeg: Double, northern: Boolean): Move {
    val seconds = polarArcseconds(errorDeg)
    val little = abs(seconds) < SECONDS_PER_MINUTE
    val towardsWest = (seconds > 0) == northern
    val text =
        when {
            towardsWest && little -> Res.string.polar_turn_west_little
            towardsWest -> Res.string.polar_turn_west
            little -> Res.string.polar_turn_east_little
            else -> Res.string.polar_turn_east
        }
    return when {
        seconds == 0L -> Move(null, Res.string.polar_centred)
        seconds > 0 -> Move(YacanaIcons.ArrowBigLeft, text)
        else -> Move(YacanaIcons.ArrowBigRight, text)
    }
}

/** An axis as the design: its arrow, or the centred mark; its name and way; and its error, readable at a metre. */
@Composable
private fun AxisCard(name: StringResource, move: Move, errorDeg: Double) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                move.icon ?: YacanaIcons.CircleDot,
                contentDescription = null,
                tint = if (move.icon == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Column {
                Text(stringResource(name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(move.text), style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            formatPolarError(errorDeg),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The target: the mount's axis seen from the pole as it is looked at, with the errors before it
 * fading behind. Its rings are 30″, 1′ and 5′; in [seconds], 10″ and 30″.
 */
@Composable
private fun Target(error: PolarError, previous: List<PolarError>, northern: Boolean, seconds: Boolean, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = muted, fontSize = 11.sp)
    Canvas(modifier) {
        val radius = size.minDimension / 2 * OUTER
        val outer = if (seconds) 30.0 else 300.0
        val rings = if (seconds) listOf(10.0 to "10″", 30.0 to "30″") else listOf(30.0 to null, 60.0 to "1′", 300.0 to "5′")
        rings.forEach { (arcsec, text) ->
            val r = (radius * arcsec / outer).toFloat()
            drawCircle(line, r, center, style = Stroke(1.dp.toPx()))
            if (text != null) drawText(measurer, text, center + Offset(r * LABEL, r * LABEL), label)
        }
        drawLine(line, center - Offset(size.width / 2, 0f), center + Offset(size.width / 2, 0f), 1.dp.toPx())
        drawLine(line, center - Offset(0f, size.height / 2), center + Offset(0f, size.height / 2), 1.dp.toPx())
        fun place(e: PolarError): Offset {
            // Looking at the pole: east of it on the right in the north, west of it on the right in the south.
            val x = e.azimuthDeg * ARCSEC_PER_DEGREE
            val up = (if (northern) e.altitudeDeg else -e.altitudeDeg) * ARCSEC_PER_DEGREE
            val distance = hypot(x, up)
            val scale = radius / outer * (if (distance > outer) outer / distance else 1.0)
            return center + Offset((x * scale).toFloat(), (-up * scale).toFloat())
        }
        previous.reversed().forEachIndexed { index, e ->
            drawCircle(muted.copy(alpha = TRAIL_ALPHA * (index + 1)), 2.5.dp.toPx(), place(e))
        }
        drawCircle(accent, 5.dp.toPx(), place(error))
    }
}

/**
 * The three points on the arc the axis turns, as "Polar alignment · measuring": done, in progress
 * (dashed while the axis turns towards it) and to come, with the step between them.
 */
@Composable
internal fun PointsArc(current: Int, turning: Boolean, step: String) {
    val line = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = muted, fontSize = 11.sp)
    Canvas(Modifier.fillMaxWidth().height(118.dp)) {
        // The design's drawing, 260 × 118, scaled to the card.
        val sx = size.width / ARC_WIDTH
        val sy = size.height / ARC_HEIGHT
        fun at(x: Float, y: Float) = Offset(x * sx, y * sy)
        val arc =
            Path().apply {
                moveTo(40f * sx, 104f * sy)
                quadraticTo(130f * sx, -20f * sy, 220f * sx, 104f * sy)
            }
        drawPath(arc, line, style = Stroke(2.dp.toPx()))
        val points = listOf(at(85f, 57.5f), at(130f, 42f), at(175f, 57.5f))
        points.forEachIndexed { index, point ->
            val number = index + 1
            when {
                number < current || (number == current && !turning) -> drawCircle(accent, 7.dp.toPx(), point)

                number == current -> drawCircle(
                    accent,
                    8.dp.toPx(),
                    point,
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )

                else -> drawCircle(muted, 7.dp.toPx(), point, style = Stroke(2.dp.toPx()))
            }
            drawText(measurer, "$number", point + Offset(-4.dp.toPx(), 12.dp.toPx()), label)
        }
        drawText(measurer, "$step°", at(96f, 26f), label)
        drawText(measurer, "$step°", at(150f, 26f), label)
    }
}

private const val OUTER = 0.9f
private const val ARC_WIDTH = 260f
private const val ARC_HEIGHT = 118f
private const val LABEL = 0.72f
private const val TRAIL_ALPHA = 0.25f
private const val ARCSEC_PER_DEGREE = 3600.0
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_SCALE_BELOW = 60
private const val MILLIS = 1000.0
private val TARGET_SIZE = 180.dp
