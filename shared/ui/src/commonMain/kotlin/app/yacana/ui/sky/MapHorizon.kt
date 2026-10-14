// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yacana.domain.sky.ScreenPoint
import app.yacana.domain.sky.SkyView
import kotlin.math.abs

/**
 * The site's horizon as a clear line in the accent, as "Sky · explorer, low in the sky", with its
 * cardinal points on it, each with a tick up to its letter; where it goes behind the view, it breaks.
 */
internal fun DrawScope.drawHorizon(projection: SkyView, marks: MapMarks, color: Color, measurer: TextMeasurer) {
    if (marks.horizon.isEmpty()) return
    val far = size.maxDimension * HORIZON_REACH
    val path = Path()
    var last: ScreenPoint? = null
    marks.horizon.forEach { position ->
        val point = projection.project(position)?.takeIf { abs(it.x) < far && abs(it.y) < far }
        when {
            point == null -> Unit
            last == null -> path.moveTo(point.x.toFloat(), point.y.toFloat())
            else -> path.lineTo(point.x.toFloat(), point.y.toFloat())
        }
        last = point
    }
    drawPath(path, color.copy(alpha = HORIZON_ALPHA), style = Stroke(width = HORIZON_WIDTH.toPx()))
    val style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    marks.cardinals.forEach { (letter, position) ->
        val point = projection.project(position)?.takeIf { it.x in 0.0..size.width.toDouble() && it.y in 0.0..size.height.toDouble() }
            ?: return@forEach
        val at = Offset(point.x.toFloat(), point.y.toFloat())
        val tick = CARDINAL_TICK.toPx()
        drawLine(color, at, at.copy(y = at.y - tick), strokeWidth = HORIZON_WIDTH.toPx())
        val text = measurer.measure(letter, style)
        drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - tick - text.size.height))
    }
}

/** The horizon: a clear line, and the tick from it up to each cardinal point's letter. */
private val HORIZON_WIDTH = 1.6.dp
private const val HORIZON_ALPHA = 0.85f
private val CARDINAL_TICK = 6.dp

/** Points of the horizon farther than this many screens from the view are left out of its line. */
private const val HORIZON_REACH = 4f
