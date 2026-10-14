// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.focus

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yacana.domain.focus.FocusFitting
import app.yacana.domain.focus.FocusPoint
import app.yacana.domain.focus.HyperbolicFitting
import app.yacana.domain.focus.QuadraticFitting
import app.yacana.domain.focus.TrendlineFitting
import kotlin.math.max

/** The HFR the [fitting] gives at each position for [points], to draw; null where it has none. */
internal fun fittedCurve(points: List<FocusPoint>, fitting: FocusFitting): ((Double) -> Double?)? {
    if (points.size < MIN_POINTS) return null
    return when (fitting) {
        FocusFitting.Hyperbolic, FocusFitting.TrendHyperbolic -> HyperbolicFitting(points).let { fit -> { x -> fit.hfrAt(x) } }

        FocusFitting.Parabolic, FocusFitting.TrendParabolic -> QuadraticFitting(points).let { fit -> { x -> fit.hfrAt(x) } }

        FocusFitting.Trendlines -> TrendlineFitting(points).let { fit ->
            { x -> max(fit.left.hfrAt(x), fit.right.hfrAt(x)).takeIf { fit.left.points.size > 1 && fit.right.points.size > 1 } }
        }
    }
}

/**
 * The V curve as the designs draw it: the points measured, the positions still to measure dashed
 * ([pending]), the fitted [curve] (dashed while it may change, [settled] when done), and the best
 * focus marked. With [axes], the HFR axis and the first, middle and last positions.
 */
@Composable
internal fun FocusChart(
    points: List<FocusPoint>,
    pending: List<Double>,
    curve: ((Double) -> Double?)?,
    best: Double?,
    settled: Boolean,
    axes: Boolean,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = muted, fontSize = 11.sp)
    Canvas(modifier) {
        val positions = points.map { it.position } + pending
        if (positions.isEmpty()) return@Canvas
        val left = positions.min()
        val right = max(positions.max(), left + 1)
        val top = max(points.maxOfOrNull { it.hfr } ?: 1.0, 1.0) * HEADROOM
        val inset = if (axes) 20.dp.toPx() else 10.dp.toPx()
        val bottom = size.height - (if (axes) 18.dp.toPx() else 4.dp.toPx())
        val plotTop = if (axes) 20.dp.toPx() else 0f
        fun x(position: Double) = (inset + (position - left) / (right - left) * (size.width - 2 * inset)).toFloat()
        fun y(hfr: Double) = (bottom - hfr / top * (bottom - plotTop)).toFloat()

        drawLine(line, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        if (axes) {
            drawLine(line, Offset(inset / 2, plotTop), Offset(inset / 2, bottom), 1.dp.toPx())
            drawText(measurer, "HFR", Offset(inset / 2, 0f), label)
            listOf(left, (left + right) / 2, right).forEach { position ->
                val text = position.toInt().toString()
                val width = measurer.measure(text, label).size.width
                drawText(measurer, text, Offset(x(position) - width / 2, bottom + 2.dp.toPx()), label)
            }
        }
        curve?.let { f ->
            val path = Path()
            var started = false
            for (i in 0..CURVE_STEPS) {
                val position = left + (right - left) * i / CURVE_STEPS
                val hfr = f(position) ?: continue
                val point = Offset(x(position), y(hfr).coerceAtLeast(plotTop))
                if (started) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                started = true
            }
            val effect = if (settled) null else PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
            drawPath(path, accent, style = Stroke(1.5.dp.toPx(), pathEffect = effect))
        }
        best?.let { position ->
            drawLine(
                muted,
                Offset(x(position), plotTop),
                Offset(x(position), bottom),
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
            if (!axes) drawText(measurer, position.toInt().toString(), Offset(x(position) + 4.dp.toPx(), 0f), label)
        }
        // Still to measure: where the curve so far puts them.
        pending.forEach { position ->
            val height = curve?.invoke(position)?.let { y(it).coerceAtLeast(plotTop) } ?: (bottom - 8.dp.toPx())
            val dashed = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            drawCircle(muted, 4.dp.toPx(), Offset(x(position), height), style = dashed)
        }
        points.forEach { drawCircle(accent, (if (axes) 4 else 3).dp.toPx(), Offset(x(it.position), y(it.hfr))) }
    }
}

private const val MIN_POINTS = 3
private const val CURVE_STEPS = 60

/** Room above the highest point. */
private const val HEADROOM = 1.15
