// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yacana.domain.sky.Night
import app.yacana.ui.imaging.clockTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The object's altitude through the night, as the object page's design draws it: twilight shaded
 * at both ends, the horizon and 30°, the curve above the horizon, now, and the time at the start,
 * the middle and the end. With the profile's own [horizon], the altitude of its trees and
 * buildings where the object is at each sample, that line too, dashed.
 */
@Composable
@Suppress("LongParameterList") // The chart draws the object's night, now, and the horizon where it goes.
internal fun AltitudeChart(
    samples: List<Pair<Instant, Double>>,
    night: Night,
    now: Instant,
    zone: TimeZone,
    modifier: Modifier,
    horizon: List<Double> = emptyList(),
) {
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val twilight = MaterialTheme.colorScheme.surfaceContainerHighest
    val nowColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = muted, fontSize = 11.sp)
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val start = samples.first().first
        val span = (samples.last().first - start).inWholeSeconds.toFloat()
        val bottom = size.height - 16.dp.toPx()
        fun x(at: Instant) = ((at - start).inWholeSeconds / span).coerceIn(0f, 1f) * size.width
        fun y(altitude: Double) = (bottom - altitude / RIGHT_ANGLE * bottom).toFloat()
        // Twilight at both ends: before the astronomical night and after it.
        night.astronomicalDusk?.let { drawRect(twilight, Offset.Zero, Size(x(it), bottom)) }
        night.astronomicalDawn?.let { drawRect(twilight, Offset(x(it), 0f), Size(size.width - x(it), bottom)) }
        drawLine(line, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        drawLine(
            line,
            Offset(0f, y(GOOD)),
            Offset(size.width, y(GOOD)),
            1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        drawText(measurer, "30°", Offset(4.dp.toPx(), y(GOOD) - 16.dp.toPx()), label)
        if (horizon.any { it > 0 }) {
            val ground = Path()
            samples.zip(horizon).forEachIndexed { index, (sample, altitude) ->
                val point = Offset(x(sample.first), y(altitude.coerceAtLeast(0.0)))
                if (index == 0) ground.moveTo(point.x, point.y) else ground.lineTo(point.x, point.y)
            }
            drawPath(ground, muted, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))))
        }
        val path = Path()
        var drawing = false
        samples.forEach { (at, altitude) ->
            if (altitude < 0) {
                drawing = false
            } else {
                if (drawing) path.lineTo(x(at), y(altitude)) else path.moveTo(x(at), y(altitude))
                drawing = true
            }
        }
        drawPath(path, accent, style = Stroke(2.dp.toPx()))
        if (now > start && now < samples.last().first) drawLine(nowColor, Offset(x(now), 0f), Offset(x(now), bottom), 1.5.dp.toPx())
        val times = listOf(samples.first().first, samples[samples.size / 2].first, samples.last().first)
        times.forEachIndexed { index, at ->
            val text = clockTime(at, zone)
            val width = measurer.measure(text, label).size.width
            val left =
                when (index) {
                    0 -> 0f
                    times.lastIndex -> size.width - width
                    else -> x(at) - width / 2
                }
            drawText(measurer, text, Offset(left, bottom + 2.dp.toPx()), label)
        }
    }
}

/** A good altitude to image at, the design's dashed line. */
internal const val GOOD = 30.0
private const val RIGHT_ANGLE = 90.0
