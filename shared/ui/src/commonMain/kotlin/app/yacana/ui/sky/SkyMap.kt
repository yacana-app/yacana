// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yacana.domain.sky.Framing
import app.yacana.domain.sky.ScreenPoint
import app.yacana.domain.sky.SkyObject
import app.yacana.domain.sky.SkyView
import app.yacana.domain.sky.SkyView.Companion.northAt
import app.yacana.ports.SkyPosition
import app.yacana.ports.SkyTile
import app.yacana.ui.theme.LocalPhotoFilter
import app.yacana.ui.theme.LocalTelescopeColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the map looks: its centre, in J2000, the degrees of sky across it, and north turned [rollDeg] clockwise, as the zenith is up. */
data class MapView(val center: SkyPosition, val fieldDeg: Double, val rollDeg: Double = 0.0) {
    fun zoomed(by: Double) = copy(fieldDeg = (fieldDeg / by).coerceIn(MIN_FIELD, MAX_FIELD))

    companion object {
        /** A field smaller than the Seestar's, and a wide view of the sky. */
        const val MIN_FIELD = 0.5
        const val MAX_FIELD = 90.0
    }
}

/** A cache's photos as the map draws them, with its layer's opacity, and no larger than [maxPixels], as thumbnails. */
internal class DrawnLayer(val tiles: List<SkyTile>, val images: TileImages, val opacity: Float, val maxPixels: Double = Double.MAX_VALUE)

/**
 * What the map draws besides the photos: the profile's field at the centre, turned as the camera is
 * ([fieldRotationDeg], from north through east), and the telescope, with the same field
 * where it points. While it goes somewhere ([goingTo]), a dashed line joins them.
 */
data class MapMarks(
    val fieldDeg: Pair<Double, Double>?,
    val fieldName: String,
    val telescope: SkyPosition?,
    val telescopeName: String,
    val fieldRotationDeg: Double = 0.0,
    val goingTo: SkyPosition? = null,
    val horizon: List<SkyPosition> = emptyList(),
    val cardinals: List<Pair<String, SkyPosition>> = emptyList(),
)

/** The map's tag, for tests to drag it. */
const val SKY_MAP = "sky-map"

/**
 * The sky map: the photos of the cache where they fall, the catalogue's objects with
 * their outlines and designations, the profile's field at the centre, the telescope, and a scale.
 * Dragging moves it and pinching zooms it, through [onView]; a tap picks the object under it.
 * With a [framing], that is drawn at the centre instead of the field.
 */
@Composable
@Suppress("LongParameterList") // The map draws the view, its photos, the objects and what is on it.
internal fun SkyMap(
    view: MapView,
    layers: List<DrawnLayer>,
    objects: List<SkyObject>,
    marks: MapMarks,
    selected: SkyObject?,
    onView: (MapView) -> Unit,
    onTap: (SkyObject?) -> Unit,
    modifier: Modifier,
    framing: Framing? = null,
) {
    val current by rememberUpdatedState(view)
    val shown by rememberUpdatedState(objects)
    // The gestures outlive a composition: they call what the latest one passed, not the first one's.
    val moved by rememberUpdatedState(onView)
    val tapped by rememberUpdatedState(onTap)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val sky = MaterialTheme.colorScheme.surfaceContainerLowest
    // In red mode the photos go red, as every photo of the app.
    val photoFilter = LocalPhotoFilter.current
    val telescopeColor = LocalTelescopeColor.current
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = muted, fontSize = 11.sp)
    val strong = TextStyle(color = ink, fontSize = 11.sp)
    Canvas(
        modifier
            .testTag(SKY_MAP)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val projection =
                        SkyView(current.center, current.fieldDeg, size.width.toDouble(), size.height.toDouble(), current.rollDeg)
                    moved(current.copy(center = projection.dragged(pan.x.toDouble(), pan.y.toDouble()).center).zoomed(zoom.toDouble()))
                }
            }.pointerInput(Unit) {
                detectTapGestures { tap ->
                    val projection =
                        SkyView(current.center, current.fieldDeg, size.width.toDouble(), size.height.toDouble(), current.rollDeg)
                    val near = TAP_REACH.toPx()
                    tapped(
                        shown
                            .mapNotNull { item -> projection.project(item.position)?.let { item to hypot(it.x - tap.x, it.y - tap.y) } }
                            .filter { it.second < near }
                            .minByOrNull { it.second }
                            ?.first,
                    )
                }
            },
    ) {
        drawRect(sky)
        val projection = SkyView(view.center, view.fieldDeg, size.width.toDouble(), size.height.toDouble(), view.rollDeg)
        layers.filter { it.opacity > 0f }.forEach { layer ->
            layer.images.arrived.intValue
            drawTiles(projection, layer, photoFilter)
        }
        drawHorizon(projection, marks, accent, measurer)
        objects.forEach { drawObject(projection, it, it == selected, muted, accent, measurer, label) }
        if (framing != null) {
            drawFraming(projection, framing, accent, measurer)
        } else {
            marks.fieldDeg?.let { drawField(projection, it, marks.fieldRotationDeg, marks.fieldName, accent, measurer) }
        }
        marks.telescope?.let { drawTelescope(projection, it, marks, telescopeColor, measurer) }
        drawScale(projection, ink, measurer, strong)
    }
}

/**
 * A still photo of the sky at [view], as the object page's: the photos of the caches in use and
 * the profile's field at the centre, dashed, without gestures.
 */
@Composable
internal fun SkyPhoto(
    view: MapView,
    layers: List<DrawnLayer>,
    fieldDeg: Pair<Double, Double>?,
    modifier: Modifier,
    fieldRotationDeg: Double = 0.0,
) {
    val accent = MaterialTheme.colorScheme.primary
    val sky = MaterialTheme.colorScheme.surfaceContainerLowest
    val photoFilter = LocalPhotoFilter.current
    Canvas(modifier.clipToBounds()) {
        drawRect(sky)
        val projection = SkyView(view.center, view.fieldDeg, size.width.toDouble(), size.height.toDouble(), view.rollDeg)
        layers.filter { it.opacity > 0f }.forEach { layer ->
            layer.images.arrived.intValue
            drawTiles(projection, layer, photoFilter)
        }
        fieldDeg?.let { drawFieldFrame(projection, it, fieldRotationDeg, accent) }
    }
}

/** The cache's photos in view, each turned and scaled where it falls. */
private fun DrawScope.drawTiles(projection: SkyView, layer: DrawnLayer, filter: ColorFilter?) {
    val reach = projection.reachDeg + TILE_REACH
    val visible = layer.tiles.filter { SkyView.separation(projection.center, it.position) < reach }
    val images = layer.images
    images.show(visible.toSet())
    // Drawn solid and then see-through as a whole, so where photos overlap the layer is no denser.
    val apart = layer.opacity < 1f
    if (apart) drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = layer.opacity }) }
    visible.forEach { tile ->
        val at = projection.project(tile.position) ?: return@forEach
        val (angle, pixelsPerDegree) = projection.northAt(tile.position) ?: return@forEach
        val width = tile.widthDeg * pixelsPerDegree
        val height = tile.heightDeg * pixelsPerDegree
        val image = images.image(tile, minOf(width, layer.maxPixels)) ?: return@forEach
        translate(at.x.toFloat(), at.y.toFloat()) {
            rotate((angle + tile.rotationDeg).toFloat(), pivot = Offset.Zero) {
                drawImage(
                    image,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset((-width / 2).roundToInt(), (-height / 2).roundToInt()),
                    dstSize = IntSize(width.roundToInt() + 1, height.roundToInt() + 1),
                    filterQuality = FilterQuality.Low,
                    colorFilter = filter,
                )
            }
        }
    }
    if (apart) drawIntoCanvas { it.restore() }
}

/** An object's outline, as large as it is on the sky but never too small to see, with its designation. */
@Suppress("LongParameterList")
private fun DrawScope.drawObject(
    projection: SkyView,
    item: SkyObject,
    selected: Boolean,
    color: Color,
    accent: Color,
    measurer: TextMeasurer,
    label: TextStyle,
) {
    val at = projection.project(item.position) ?: return
    val (north, pixelsPerDegree) = projection.northAt(item.position) ?: return
    val major = max((item.majorArcmin ?: 0.0) / ARCMIN_PER_DEGREE * pixelsPerDegree, MIN_MARK.toPx().toDouble()).toFloat()
    val minor = max(
        (item.minorArcmin ?: item.majorArcmin ?: 0.0) / ARCMIN_PER_DEGREE * pixelsPerDegree,
        MIN_MARK.toPx().toDouble(),
    ).toFloat()
    val stroke = if (selected) accent else color.copy(alpha = MARK_ALPHA)
    translate(at.x.toFloat(), at.y.toFloat()) {
        // The position angle goes from north towards east, which is to the left.
        rotate((north - (item.angleDeg ?: 0.0)).toFloat(), pivot = Offset.Zero) {
            drawOval(stroke, Offset(-minor / 2, -major / 2), Size(minor, major), style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()))
        }
    }
    drawText(
        measurer,
        item.designation,
        Offset(at.x.toFloat() + minOf(major, minor) / 2 + 4.dp.toPx(), at.y.toFloat() - 7.dp.toPx()),
        label,
    )
}

/** The profile's field at the centre, dashed, turned as the camera is, with its name under it, as the design draws the Seestar's. */
@Suppress("LongParameterList") // The field, how it is turned, and its name.
private fun DrawScope.drawField(
    projection: SkyView,
    fieldDeg: Pair<Double, Double>,
    rotationDeg: Double,
    name: String,
    accent: Color,
    measurer: TextMeasurer,
) {
    val frame = drawFieldFrame(projection, fieldDeg, rotationDeg, accent)
    val below = Offset(size.width / 2 - frame.width / 2, size.height / 2 + maxOf(frame.width, frame.height) / 2)
    drawText(measurer, name, below + Offset(3.dp.toPx(), 2.dp.toPx()), TextStyle(color = accent, fontSize = 11.sp))
}

/**
 * The field's frame at the centre, its long side across at no rotation as the designs draw it,
 * turned [rotationDeg] counterclockwise from the sky's north, and with the map's roll; its size.
 */
private fun DrawScope.drawFieldFrame(projection: SkyView, fieldDeg: Pair<Double, Double>, rotationDeg: Double, accent: Color): Rect {
    val wide = (max(fieldDeg.first, fieldDeg.second) * projection.pixelsPerDegree).toFloat()
    val tall = (minOf(fieldDeg.first, fieldDeg.second) * projection.pixelsPerDegree).toFloat()
    val frame = Rect(Offset((size.width - wide) / 2, (size.height - tall) / 2), Size(wide, tall))
    rotate((projection.rollDeg - rotationDeg).toFloat(), pivot = frame.center) {
        drawRect(accent, frame.topLeft, frame.size, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(DASHES)))
    }
    return frame
}

/**
 * A framing at the centre, as "Sky · framing": each panel's frame, turned by the framing's
 * rotation, faintly filled so that the overlaps show darker, and numbered in a mosaic.
 */
private fun DrawScope.drawFraming(projection: SkyView, framing: Framing, accent: Color, measurer: TextMeasurer) {
    val wide = (framing.longDeg * projection.pixelsPerDegree).toFloat()
    val tall = (framing.shortDeg * projection.pixelsPerDegree).toFloat()
    val middle = Offset(size.width / 2, size.height / 2)
    // The framing turns from the sky's north, and the sky with the map's roll.
    val turnDeg = framing.rotationDeg - projection.rollDeg
    val turn = turnDeg * PI / HALF_TURN
    val centres =
        (0 until framing.rows).flatMap { row ->
            (0 until framing.columns).map { column ->
                Offset(
                    (column - (framing.columns - 1) / 2f) * wide * (1 - framing.overlap.toFloat()),
                    ((framing.rows - 1) / 2f - row) * tall * (1 - framing.overlap.toFloat()),
                )
            }
        }
    rotate(-turnDeg.toFloat(), pivot = middle) {
        centres.forEach { (right, up) ->
            val topLeft = Offset(middle.x + right - wide / 2, middle.y - up - tall / 2)
            drawRect(accent.copy(alpha = PANEL_FILL), topLeft, Size(wide, tall))
            drawRect(accent, topLeft, Size(wide, tall), style = Stroke(1.5.dp.toPx()))
        }
    }
    // The numbers upright, where each panel's centre turned.
    if (centres.size > 1) {
        val style = TextStyle(color = accent, fontSize = 13.sp)
        centres.forEachIndexed { index, (right, up) ->
            val x = middle.x + (right * cos(turn) - up * sin(turn)).toFloat()
            val y = middle.y - (right * sin(turn) + up * cos(turn)).toFloat()
            val number = measurer.measure("${index + 1}", style)
            drawText(number, topLeft = Offset(x - number.size.width / 2f, y - number.size.height / 2f))
        }
    }
}

/**
 * Where the telescope points: the camera's field there, solid and faintly filled, turned as
 * the camera is, with its name above it; a reticle when the field would be too small to see, or
 * the profile has none. While it goes somewhere, a dashed line joins it to where it goes.
 */
private fun DrawScope.drawTelescope(projection: SkyView, at: SkyPosition, marks: MapMarks, color: Color, measurer: TextMeasurer) {
    val point = projection.project(at) ?: return
    val center = Offset(point.x.toFloat(), point.y.toFloat())
    marks.goingTo?.let(projection::project)?.let { end ->
        drawLine(color, center, Offset(end.x.toFloat(), end.y.toFloat()), 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(DOTS))
    }
    val label = measurer.measure(marks.telescopeName, TextStyle(color = color, fontSize = 11.sp))
    val north = projection.northAt(at)
    val pixelsPerDegree = north?.second ?: 0.0
    val wide = marks.fieldDeg?.let { (max(it.first, it.second) * pixelsPerDegree).toFloat() } ?: 0f
    val tall = marks.fieldDeg?.let { (minOf(it.first, it.second) * pixelsPerDegree).toFloat() } ?: 0f
    if (north == null || wide < MIN_FRAME.toPx()) {
        drawReticle(center, label, color)
    } else {
        // As the field at the centre: turned from the sky's north, and the sky as it is there.
        drawTelescopeFrame(center, north.first - marks.fieldRotationDeg, Size(wide, tall), label, color)
    }
}

/** The telescope's field, solid and faintly filled, turned by [turnDeg], with its name upright above it. */
private fun DrawScope.drawTelescopeFrame(center: Offset, turnDeg: Double, frame: Size, label: TextLayoutResult, color: Color) {
    rotate(turnDeg.toFloat(), pivot = center) {
        val topLeft = center - Offset(frame.width / 2, frame.height / 2)
        drawRect(color.copy(alpha = TELESCOPE_FILL), topLeft, frame)
        drawRect(color, topLeft, frame, style = Stroke(2.dp.toPx()))
    }
    val radians = turnDeg * PI / HALF_TURN
    val across = (abs(frame.width / 2 * cos(radians)) + abs(frame.height / 2 * sin(radians))).toFloat()
    val down = (abs(frame.width / 2 * sin(radians)) + abs(frame.height / 2 * cos(radians))).toFloat()
    drawText(label, topLeft = Offset(center.x - across + 3.dp.toPx(), center.y - down - 3.dp.toPx() - label.size.height))
}

/** The telescope as a reticle, when its field would be too small to see, with its name beside it. */
private fun DrawScope.drawReticle(center: Offset, label: TextLayoutResult, color: Color) {
    val radius = RETICLE.toPx()
    val width = 1.5.dp.toPx()
    drawCircle(color, radius, center, style = Stroke(width))
    listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f)).forEach { direction ->
        drawLine(color, center + direction * (radius * INNER), center + direction * (radius * OUTER), width)
    }
    drawText(label, topLeft = center + Offset(radius * OUTER + 4.dp.toPx(), -label.size.height / 2f))
}

/** A bar of a round angle, a fifth of the width or so, and how much sky it is. */
private fun DrawScope.drawScale(projection: SkyView, color: Color, measurer: TextMeasurer, style: TextStyle) {
    val wanted = size.width / SCALE_SHARE / projection.pixelsPerDegree
    val (degrees, text) = SCALES.lastOrNull { it.first <= wanted } ?: SCALES.first()
    val length = (degrees * projection.pixelsPerDegree).toFloat()
    val y = size.height - 12.dp.toPx()
    val x = 12.dp.toPx()
    drawLine(color, Offset(x, y), Offset(x + length, y), 2.dp.toPx())
    drawText(measurer, text, Offset(x + length + 6.dp.toPx(), y - 8.dp.toPx()), style)
}

private const val HALF_TURN = 180.0

/** A panel's faint fill: where two overlap, it shows twice as dark. */
private const val PANEL_FILL = 0.08f

private val SkyTile.position get() = SkyPosition(raDeg / DEGREES_PER_HOUR, decDeg)

/** The bars the scale can be: a round angle and how it reads. */
private val SCALES =
    listOf(
        1.0 / 6 to "10′",
        0.5 to "30′",
        1.0 to "1°",
        2.0 to "2°",
        5.0 to "5°",
        10.0 to "10°",
        20.0 to "20°",
    )

private val TAP_REACH = 32.dp

private val DASHES = floatArrayOf(10f, 6f)

/** The line to where the telescope goes. */
private val DOTS = floatArrayOf(6f, 6f)

/** The telescope's field, faint enough to see the sky through it. */
private const val TELESCOPE_FILL = 0.07f

/** Below this across, the telescope's field is a reticle. */
private val MIN_FRAME = 16.dp
private val MIN_MARK = 8.dp
private val RETICLE = 7.dp
private const val INNER = 0.55f
private const val OUTER = 1.6f
private const val MARK_ALPHA = 0.8f
private const val SCALE_SHARE = 5
private const val ARCMIN_PER_DEGREE = 60.0
private const val DEGREES_PER_HOUR = 15.0

/** How far from its centre a photo of the cache reaches: half the diagonal of 5°. */
private const val TILE_REACH = 3.6
