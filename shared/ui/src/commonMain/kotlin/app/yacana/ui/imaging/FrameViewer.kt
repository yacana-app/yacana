// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.yacana.ports.ColourStretch
import app.yacana.ports.DetectedStar
import app.yacana.ports.Frame
import app.yacana.ports.Preview
import app.yacana.ports.Region
import app.yacana.ports.Stretch
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.theme.LocalPhotoFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Screen pixels as a Compose bitmap; the platform copies them in. */
expect fun Preview.toImageBitmap(): ImageBitmap

/**
 * What the screen shows of a frame: [scale] screen pixels per frame pixel, centred on frame
 * pixel ([centerX], [centerY]).
 */
data class Viewport(val scale: Double, val centerX: Double, val centerY: Double) {
    /** The rectangle of the frame that a [width] × [height] screen shows. */
    fun region(width: Int, height: Int) = Region(
        centerX - width / (2 * scale),
        centerY - height / (2 * scale),
        width / scale,
        height / scale,
    )

    companion object {
        /** The whole frame, as large as fits. */
        fun fit(frameWidth: Int, frameHeight: Int, width: Int, height: Int) =
            Viewport(min(width.toDouble() / frameWidth, height.toDouble() / frameHeight), frameWidth / 2.0, frameHeight / 2.0)
    }
}

/**
 * A frame from the engine, with pinch to zoom, drag to pan and a double tap to go between the
 * whole frame and one frame pixel per screen pixel (ARCHITECTURE.md).
 *
 * The engine draws the visible region at screen size once a gesture rests; meanwhile the last
 * drawing is moved and scaled, so gestures stay smooth whatever the camera. Red mode tints it.
 * [onShown] runs once per frame, when its first drawing is ready. [marks] are drawn over it as
 * N.I.N.A. annotates a frame: each star's measured box, a cross on its centre as long as its HFR,
 * and its HFR. [onViewport] hears what the screen shows of the frame, for the reference to follow.
 */
@Composable
fun FrameViewer(
    frame: Frame,
    stretch: ColourStretch,
    modifier: Modifier = Modifier,
    marks: List<DetectedStar> = emptyList(),
    onShown: () -> Unit = {},
    onViewport: (Viewport) -> Unit = {},
) = ImageViewer(
    imageWidth = frame.width,
    imageHeight = frame.height,
    identity = frame,
    content = stretch,
    draw = { region, width, height -> frame.preview(stretch, region, width, height) },
    modifier = modifier,
    marks = marks,
    onShown = onShown,
    onViewport = onViewport,
)

/**
 * [FrameViewer] for any image the engine draws: [draw] gives [imageWidth] × [imageHeight] pixels'
 * region at screen size, or null with nothing to draw. A new [identity] shows the whole image again;
 * a new [content], as a frame added to the live stack, draws it again where the screen is.
 */
@OptIn(FlowPreview::class)
@Composable
@Suppress("LongParameterList") // A viewer's image, how it is drawn, and what it says.
internal fun ImageViewer(
    imageWidth: Int,
    imageHeight: Int,
    identity: Any,
    content: Any?,
    draw: (Region, Int, Int) -> Preview?,
    modifier: Modifier = Modifier,
    marks: List<DetectedStar> = emptyList(),
    onShown: () -> Unit = {},
    onViewport: (Viewport) -> Unit = {},
) {
    val filter = LocalPhotoFilter.current
    val markColor = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = markColor)
    val measurer = rememberTextMeasurer()
    val comma = decimalComma()
    val labels = remember(marks, comma) { marks.map { formatDecimal(it.hfr, 2, comma) } }
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val fit = remember(identity, width, height) { Viewport.fit(imageWidth, imageHeight, width, height) }
        var viewport by remember(identity, width, height) { mutableStateOf(fit) }
        var shown by remember(identity) { mutableStateOf<Pair<ImageBitmap, Region>?>(null) }
        val heard by rememberUpdatedState(onViewport)
        val drawing by rememberUpdatedState(draw)
        LaunchedEffect(identity, width, height) { snapshotFlow { viewport }.collect { heard(it) } }

        LaunchedEffect(identity, content, width, height) {
            snapshotFlow { viewport }.debounce(GESTURE_REST_MILLIS).collectLatest { current ->
                val region = current.region(width, height)
                // A newer frame may replace and free this one meanwhile; then there is nothing to draw.
                val preview =
                    withContext(Dispatchers.Default) {
                        try {
                            drawing(region, width, height)?.toImageBitmap()
                        } catch (_: IllegalStateException) {
                            null
                        }
                    }
                if (preview != null) {
                    val first = shown == null
                    shown = preview to region
                    if (first) onShown()
                }
            }
        }

        fun clamp(next: Viewport): Viewport {
            val scale = next.scale.coerceIn(fit.scale, max(fit.scale, MAX_SCALE))
            return Viewport(scale, next.centerX.coerceIn(0.0, imageWidth.toDouble()), next.centerY.coerceIn(0.0, imageHeight.toDouble()))
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(identity, width, height) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val current = viewport
                        val scale = (current.scale * zoom).coerceIn(fit.scale, max(fit.scale, MAX_SCALE))
                        // The frame point under the fingers stays under them.
                        val pointX = current.centerX + (centroid.x - width / 2.0) / current.scale
                        val pointY = current.centerY + (centroid.y - height / 2.0) / current.scale
                        viewport =
                            clamp(
                                Viewport(
                                    scale,
                                    pointX - (centroid.x - width / 2.0) / scale - pan.x / scale,
                                    pointY - (centroid.y - height / 2.0) / scale - pan.y / scale,
                                ),
                            )
                    }
                }.pointerInput(identity, width, height) {
                    detectTapGestures(onDoubleTap = { tap: Offset ->
                        val current = viewport
                        viewport =
                            if (current.scale > fit.scale * ZOOMED) {
                                fit
                            } else {
                                clamp(
                                    Viewport(
                                        1.0,
                                        current.centerX + (tap.x - width / 2.0) / current.scale,
                                        current.centerY + (tap.y - height / 2.0) / current.scale,
                                    ),
                                )
                            }
                    })
                },
        ) {
            val (bitmap, region) = shown ?: return@Canvas
            // Where the drawn region is under the current viewport.
            val current = viewport
            val left = (region.x - current.centerX) * current.scale + width / 2.0
            val top = (region.y - current.centerY) * current.scale + height / 2.0
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize((region.width * current.scale).roundToInt(), (region.height * current.scale).roundToInt()),
                colorFilter = filter,
            )
            val line = 1.dp.toPx()
            marks.forEachIndexed { i, star ->
                val x = ((star.box.x - current.centerX) * current.scale + width / 2.0).toFloat()
                val y = ((star.box.y - current.centerY) * current.scale + height / 2.0).toFloat()
                val box = Size((star.box.width * current.scale).toFloat(), (star.box.height * current.scale).toFloat())
                if (!Rect(Offset(x, y), box).overlaps(Rect(Offset.Zero, size))) return@forEachIndexed
                drawRect(markColor, topLeft = Offset(x, y), size = box, style = Stroke(line))
                val cx = ((star.x - current.centerX) * current.scale + width / 2.0).toFloat()
                val cy = ((star.y - current.centerY) * current.scale + height / 2.0).toFloat()
                val arm = (star.hfr * current.scale).toFloat()
                drawLine(markColor, Offset(cx - arm, cy), Offset(cx + arm, cy), line)
                drawLine(markColor, Offset(cx, cy - arm), Offset(cx, cy + arm), line)
                drawText(measurer, labels[i], Offset(x, y + box.height + line), labelStyle)
            }
        }
    }
}

/** A gesture rests after this long without moving. */
private const val GESTURE_REST_MILLIS = 80L

/** Zoomed in up to 4 screen pixels per frame pixel. */
private const val MAX_SCALE = 4.0

/** Above this many times the fitted scale, a double tap goes back to the whole frame. */
private const val ZOOMED = 1.5
