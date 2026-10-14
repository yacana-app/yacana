// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

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
import app.yacana.domain.imaging.HistoryFrame
import kotlinx.datetime.TimeZone
import kotlin.math.max
import kotlin.time.Instant

/**
 * The history's chart, as "Imaging · history" draws it: a bar for the stars of each frame, the
 * HFR as a line over them, a dashed line at each autofocus ([autofocusAt], labelled [af]), and the
 * time of the first, middle and last frames under them.
 */
@Composable
internal fun HistoryChart(frames: List<HistoryFrame>, autofocusAt: List<Instant>, af: String, zone: () -> TimeZone, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = muted, fontSize = 11.sp)
    Canvas(modifier) {
        if (frames.isEmpty()) return@Canvas
        val bottom = size.height - 16.dp.toPx()
        val top = 14.dp.toPx()
        val slot = size.width / frames.size
        val maxStars = max(frames.maxOf { it.stars }, 1)
        val maxHfr = max(frames.maxOf { it.hfr }, 1.0) * HFR_HEADROOM
        drawLine(line, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        frames.forEachIndexed { index, frame ->
            val height = frame.stars.toFloat() / maxStars * (bottom - top)
            val width = max(slot * BAR, 1f)
            drawRect(muted.copy(alpha = BAR_ALPHA), Offset(index * slot + (slot - width) / 2, bottom - height), Size(width, height))
        }
        // The HFR, broken where a frame has no stars.
        val path = Path()
        var drawing = false
        frames.forEachIndexed { index, frame ->
            if (frame.hfr <= 0) {
                drawing = false
            } else {
                val point = Offset(index * slot + slot / 2, (bottom - frame.hfr / maxHfr * (bottom - top)).toFloat())
                if (drawing) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                drawing = true
            }
        }
        drawPath(path, accent, style = Stroke(1.5.dp.toPx()))
        // Each autofocus between the last frame before it and the first after it.
        autofocusAt.forEach { at ->
            val after = frames.indexOfFirst { it.at > at }.takeIf { it >= 0 } ?: frames.size
            if (after == 0) return@forEach
            val x = after * slot
            drawLine(muted, Offset(x, 0f), Offset(x, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            drawText(measurer, af, Offset(x + 3.dp.toPx(), 0f), label)
        }
        val labelled = listOf(0, frames.size / 2, frames.lastIndex).distinct()
        labelled.forEachIndexed { i, index ->
            val text = clockTime(frames[index].at, zone())
            val width = measurer.measure(text, label).size.width
            val x =
                when (i) {
                    0 -> 0f
                    labelled.lastIndex -> size.width - width
                    else -> index * slot + slot / 2 - width / 2
                }
            drawText(measurer, text, Offset(x, bottom + 2.dp.toPx()), label)
        }
    }
}

/** The bar's share of its frame's slot. */
private const val BAR = 0.68f
private const val BAR_ALPHA = 0.35f

/** Room above the highest HFR. */
private const val HFR_HEADROOM = 1.15
