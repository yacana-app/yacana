// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.yacana.ports.Stretch
import kotlin.math.ln
import kotlin.math.max

/**
 * The histogram of a frame, with counts on a logarithmic scale so that faint tails show, and
 * the stretch over it: a line at the shadows and the curve, as N.I.N.A. maps values. For a
 * colour frame stretched per channel, the caller passes the green channel's.
 */
@Composable
fun Histogram(bins: LongArray, stretch: Stretch, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        if (bins.isEmpty()) return@Canvas
        val top = ln(max(2.0, bins.max().toDouble()))
        val barWidth = size.width / bins.size
        bins.forEachIndexed { i, count ->
            if (count > 0) {
                val h = (ln(count.toDouble() + 1) / top * size.height).toFloat()
                drawRect(colors.onSurfaceVariant, topLeft = Offset(i * barWidth, size.height - h), size = Size(barWidth, h))
            }
        }
        val shadows = stretch.shadows * size.width
        drawLine(colors.primary, Offset(shadows, 0f), Offset(shadows, size.height), strokeWidth = 2f)
        // The screen value of each input value, as N.I.N.A. draws the stretch.
        val curve = Path()
        val steps = 64
        for (s in 0..steps) {
            val x = s / steps.toFloat()
            val y = midtones(stretch.midtones, 1 - stretch.highlights + x - stretch.shadows)
            val point = Offset(x * size.width, size.height * (1 - y))
            if (s == 0) curve.moveTo(point.x, point.y) else curve.lineTo(point.x, point.y)
        }
        drawPath(curve, colors.primary, style = Stroke(width = 2f))
    }
}

/** The midtones transfer function, as in the engine. */
internal fun midtones(balance: Float, x: Float): Float = when {
    x <= 0f -> 0f
    x >= 1f -> 1f
    else -> ((balance - 1) * x) / ((2 * balance - 1) * x - balance)
}
