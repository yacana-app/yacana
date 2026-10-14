// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.yacana.domain.imaging.LiveStacking
import app.yacana.domain.imaging.MosaicState
import app.yacana.ports.Preview
import app.yacana.ports.Region
import kotlin.math.floor

/**
 * The live mosaic as the stack, as "Imaging · live stacking, a mosaic": the panels stacked so far,
 * each where it goes, the one being stacked outlined in the accent and those still to come dashed;
 * pinched, dragged and double tapped as the stack. It is drawn again as each frame comes.
 */
@Composable
internal fun MosaicViewer(stacking: LiveStacking, mosaic: MosaicState, balanced: Boolean, modifier: Modifier = Modifier) {
    var image by remember(mosaic.name) { mutableStateOf<Preview?>(null) }
    LaunchedEffect(mosaic.version, balanced) { image = stacking.mosaicImage() }
    var viewport by remember(mosaic.name) { mutableStateOf<Viewport?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = muted, fontSize = 10.sp)
    Box(modifier) {
        ImageViewer(
            imageWidth = mosaic.width,
            imageHeight = mosaic.height,
            identity = mosaic.name,
            content = image,
            draw = { region, width, height -> image?.part(region, width, height) },
            modifier = Modifier.fillMaxSize(),
            onViewport = { viewport = it },
        )
        val at = viewport ?: return@Box
        Canvas(Modifier.fillMaxSize()) {
            fun screen(point: Pair<Double, Double>) = Offset(
                ((point.first - at.centerX) * at.scale + size.width / 2).toFloat(),
                (
                    (point.second - at.centerY) * at.scale +
                        size.height / 2
                    ).toFloat(),
            )
            mosaic.outlines.forEachIndexed { index, corners ->
                if (corners.size < 2) return@forEachIndexed
                val points = corners.map(::screen)
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                val style =
                    when (index) {
                        mosaic.stacking -> Stroke(2.dp.toPx())
                        in mosaic.drawn -> Stroke(1.dp.toPx())
                        else -> Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP)))
                    }
                drawPath(path, if (index == mosaic.stacking) accent else muted.copy(alpha = OUTLINE_ALPHA), style = style)
                val corner = Offset(points.minOf { it.x }, points.minOf { it.y })
                drawText(measurer, "${index + 1}", corner + Offset(4.dp.toPx(), 2.dp.toPx()), label)
            }
        }
    }
}

/** [region] of the image drawn into [width] × [height] pixels, each the image's pixel under its centre; transparent outside it. */
internal fun Preview.part(region: Region, width: Int, height: Int): Preview {
    val out = ByteArray(width * height * RGBA)
    for (y in 0 until height) {
        val sy = floor(region.y + (y + HALF) * region.height / height).toInt()
        if (sy !in 0 until this.height) continue
        for (x in 0 until width) {
            val sx = floor(region.x + (x + HALF) * region.width / width).toInt()
            if (sx !in 0 until this.width) continue
            rgba.copyInto(out, (y * width + x) * RGBA, (sy * this.width + sx) * RGBA, (sy * this.width + sx + 1) * RGBA)
        }
    }
    return Preview(width, height, out)
}

private const val RGBA = 4
private const val HALF = 0.5
private const val DASH = 10f
private const val GAP = 6f

/** The panels done are outlined faintly, so that the mosaic shows through. */
private const val OUTLINE_ALPHA = 0.6f
