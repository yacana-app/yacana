// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.domain.sky.FrameSky
import app.yacana.ports.LinearImage
import app.yacana.ports.Preview
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A panel of a mosaic, as the sequence takes its frames: the mosaic's name and centre, where all
 * its panels were framed, which one this is, and where its frames point, from the solution that
 * centred it; without one the panel stays out of the mosaic.
 */
data class MosaicPanel(
    val mosaic: String,
    val center: SkyPosition,
    val panels: List<SkyPosition>,
    val index: Int,
    val solution: Solution? = null,
)

/** The colour the mosaic is shown with: the gains that make a typical star white, or the camera's colour without them. */
data class MosaicColour(val red: Double = 1.0, val green: Double = 1.0, val blue: Double = 1.0, val balanced: Boolean = false)

/**
 * The live mosaic: the whole mosaic at a smaller size, built from the panels' stacks as
 * they grow, calibrated as they are with darks and flats. Each panel goes where its centring solved
 * it, its background is matched to what is already there where they overlap, and it fades into its
 * neighbours over the overlap; then one colour model and one stretch, N.I.N.A.'s, for the whole.
 * The panels are stacked one after another: those done are kept blended on the canvas, and only the
 * one being stacked is kept apart, so that a mosaic of any size takes the canvas's memory alone.
 */
class LiveMosaic private constructor(
    private val canvas: FrameSky,
    /** Each framed panel's corners on the canvas, in its pixels, for the screen to outline them. */
    val outlines: List<List<Pair<Double, Double>>>,
    /** Native pixels of a frame per pixel of the canvas and of the panels' images. */
    val scale: Double,
) {
    val width: Int get() = canvas.width
    val height: Int get() = canvas.height

    /** The panels done, weighted, and their weights: the canvas without the panel being stacked. */
    private val sums = FloatArray(width * height * CHANNELS)
    private val weights = FloatArray(width * height)
    private var current: Current? = null
    private val done = mutableSetOf<Int>()
    private val rgba = ByteArray(width * height * RGBA)

    /** The panels in it: those done and the one being stacked. */
    val drawn: Set<Int> get() = done + listOfNotNull(current?.index)

    /** The panel being stacked, if any. */
    val stacking: Int? get() = current?.index

    /** The size of a panel's image for a frame of [frameWidth] × [frameHeight]: the frame at the mosaic's [scale]. */
    fun imageSize(frameWidth: Int, frameHeight: Int): Pair<Int, Int> =
        max(1, (frameWidth / scale).roundToInt()) to max(1, (frameHeight / scale).roundToInt())

    /**
     * Draws panel [index], whose frames point as [solution] says, with its stack's latest linear
     * [image] of a [frameWidth] × [frameHeight] frame. Another panel than the last one drawn means
     * that one is done: it stays on the canvas as it last was.
     */
    fun draw(index: Int, solution: Solution, frameWidth: Int, frameHeight: Int, image: LinearImage) {
        val now = current?.takeIf { it.index == index } ?: run {
            current?.let(::bake)
            done -= index
            Current(index, place(solution, frameWidth, frameHeight) ?: return)
        }
        now.image = image
        now.offsets = offsets(now)
        current = now
    }

    /** The mosaic as the screen draws it, in [colour]: four bytes a pixel; transparent where no panel is yet. */
    fun image(colour: MosaicColour = MosaicColour()): Preview {
        val model = model(colour)
        val lookup = stretch(model)
        val now = current
        for (i in 0 until width * height) {
            val o = i * RGBA
            val values = blended(i, now)
            if (values == null) {
                rgba.fill(0, o, o + RGBA)
                continue
            }
            for (c in 0 until CHANNELS) rgba[o + c] = lookup.screen(model.apply(c, values[c])).toByte()
            rgba[o + CHANNELS] = MAX_BYTE.toByte()
        }
        return Preview(width, height, rgba.copyOf())
    }

    /** Where panel pixels land: for each canvas pixel of its box, the frame pixel it shows, or NaN outside the frame. */
    private fun place(solution: Solution, frameWidth: Int, frameHeight: Int): Placement? {
        val sky = FrameSky.of(solution, frameWidth, frameHeight)
        val corners = sky?.let { frame -> corners(frameWidth, frameHeight).mapNotNull { (x, y) -> canvas.pixel(frame.at(x, y)) } }.orEmpty()
        val box =
            Box(
                max(0, floor(corners.minOfOrNull { it.first } ?: 0.0).toInt()),
                max(0, floor(corners.minOfOrNull { it.second } ?: 0.0).toInt()),
                min(width, ceil(corners.maxOfOrNull { it.first } ?: 0.0).toInt()),
                min(height, ceil(corners.maxOfOrNull { it.second } ?: 0.0).toInt()),
            )
        return if (sky == null || corners.size < CORNERS || box.isEmpty) null else placement(sky, box, frameWidth, frameHeight)
    }

    /** For each canvas pixel of [box], the pixel of the frame of [sky] it shows, or NaN outside the frame. */
    private fun placement(sky: FrameSky, box: Box, frameWidth: Int, frameHeight: Int): Placement {
        val xs = FloatArray(box.size)
        val ys = FloatArray(box.size)
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                val at = sky.pixel(canvas.at(x + HALF, y + HALF))
                val i = box.index(x, y)
                val inside = at != null && at.first in 0.0..frameWidth.toDouble() && at.second in 0.0..frameHeight.toDouble()
                xs[i] = if (inside) at.first.toFloat() else Float.NaN
                ys[i] = if (inside) at.second.toFloat() else Float.NaN
            }
        }
        return Placement(box, xs, ys, frameWidth, frameHeight)
    }

    /** Panel [now] put on the canvas for good, as it last was. */
    private fun bake(now: Current) {
        val box = now.placement.box
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                val sample = now.sample(x, y) ?: continue
                val i = y * width + x
                for (c in 0 until CHANNELS) sums[i * CHANNELS + c] += sample.values[c] * sample.weight
                weights[i] += sample.weight
            }
        }
        done += now.index
        current = null
    }

    /**
     * How much to add to each channel of [now] so that its background matches what is on the
     * canvas: the median difference where they overlap; with no overlap, the median of the canvas
     * less its own, so that the panels' sky is alike; nothing for the first.
     */
    private fun offsets(now: Current): FloatArray {
        val differences = Array(CHANNELS) { mutableListOf<Float>() }
        val box = now.placement.box
        for (y in box.top until box.bottom step SAMPLE_STEP) {
            for (x in box.left until box.right step SAMPLE_STEP) {
                val i = y * width + x
                val raw = if (weights[i] > 0f) now.raw(x, y) else null
                if (raw != null) for (c in 0 until CHANNELS) differences[c] += sums[i * CHANNELS + c] / weights[i] - raw[c]
            }
        }
        return when {
            differences[0].size >= MIN_OVERLAP -> FloatArray(CHANNELS) { median(differences[it]) }

            done.isEmpty() -> FloatArray(CHANNELS)

            // Apart from the rest, as a panel framed away from the others.
            else -> {
                val canvasBackground = FloatArray(CHANNELS) { c -> median(canvasSamples(c)) }
                val own = now.backgrounds()
                FloatArray(CHANNELS) { canvasBackground[it] - own[it] }
            }
        }
    }

    private fun canvasSamples(channel: Int): MutableList<Float> {
        val out = mutableListOf<Float>()
        for (i in 0 until width * height step SAMPLE_STEP * SAMPLE_STEP) {
            if (weights[i] > 0f) out += sums[i * CHANNELS + channel] / weights[i]
        }
        return out
    }

    /** The canvas's linear value at pixel [i]: the panels done and [now] fading into each other; null where none reaches. */
    private fun blended(i: Int, now: Current?): FloatArray? {
        val x = i % width
        val y = i / width
        val sample = now?.sample(x, y)
        val weight = weights[i] + (sample?.weight ?: 0f)
        if (weight <= 0f) return null
        return FloatArray(CHANNELS) { c -> (sums[i * CHANNELS + c] + (sample?.let { it.values[c] * it.weight } ?: 0f)) / weight }
    }

    /** One colour model for the whole mosaic: each channel's background brought to the green's, and scaled by its gain. */
    private fun model(colour: MosaicColour): ColourModel {
        if (!colour.balanced) return ColourModel(FloatArray(CHANNELS), floatArrayOf(1f, 1f, 1f))
        val now = current
        val samples = Array(CHANNELS) { mutableListOf<Float>() }
        for (i in 0 until width * height step SAMPLE_STEP * SAMPLE_STEP) {
            val values = blended(i, now) ?: continue
            for (c in 0 until CHANNELS) samples[c] += values[c]
        }
        val backgrounds = FloatArray(CHANNELS) { median(samples[it]) }
        return ColourModel(backgrounds, floatArrayOf(colour.red.toFloat(), colour.green.toFloat(), colour.blue.toFloat()))
    }

    /** N.I.N.A.'s automatic stretch of the mosaic's green, after the colour model, for all three channels. */
    private fun stretch(model: ColourModel): Stretch {
        val now = current
        val greens = mutableListOf<Float>()
        for (i in 0 until width * height step SAMPLE_STEP * SAMPLE_STEP) {
            val values = blended(i, now) ?: continue
            greens += model.apply(1, values[1]).coerceIn(0f, FULL_SCALE)
        }
        if (greens.isEmpty()) return Stretch(0.0, TARGET_BACKGROUND, 1.0)
        val median = median(greens) / FULL_SCALE
        val mad = median(greens.map { abs(it / FULL_SCALE - median) }.toMutableList())
        return if (median > HALF) {
            // As N.I.N.A., a frame so bright is taken as inverted or overexposed.
            val highlights = median - SHADOWS_CLIPPING * mad * MAD_TO_SIGMA
            Stretch(0.0, mtf(TARGET_BACKGROUND, 1.0 - (highlights - median)), highlights)
        } else {
            val shadows = median + SHADOWS_CLIPPING * mad * MAD_TO_SIGMA
            Stretch(shadows, mtf(TARGET_BACKGROUND, median - shadows), 1.0)
        }
    }

    /** A rectangle of canvas pixels, from [left], [top] up to [right], [bottom] left out. */
    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = right <= left || bottom <= top
        val size: Int get() = (right - left) * (bottom - top)

        fun index(x: Int, y: Int) = (y - top) * (right - left) + (x - left)

        fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    }

    /** Where a panel's frame pixels land in [box] of the canvas. */
    private class Placement(val box: Box, val xs: FloatArray, val ys: FloatArray, val frameWidth: Int, val frameHeight: Int)

    private class Sample(val values: FloatArray, val weight: Float)

    /** The panel being stacked: where it goes, its stack's latest image, and what its background needs to match. */
    private class Current(val index: Int, val placement: Placement) {
        var image: LinearImage? = null
        var offsets = FloatArray(CHANNELS)

        /** Its linear value at canvas pixel ([x], [y]), without the offsets; null outside it or where its stack does not reach. */
        fun raw(x: Int, y: Int): FloatArray? {
            val shown = image
            val i = if (placement.box.contains(x, y)) placement.box.index(x, y) else -1
            val fx = if (i >= 0) placement.xs[i] else Float.NaN
            return if (shown == null || fx.isNaN()) {
                null
            } else {
                // The image is the frame at the mosaic's scale: its pixel centres are half a pixel in.
                val px = (fx / placement.frameWidth * shown.width - HALF.toFloat()).coerceIn(0f, shown.width - 1f)
                val py = (placement.ys[i] / placement.frameHeight * shown.height - HALF.toFloat()).coerceIn(0f, shown.height - 1f)
                bilinear(shown, px, py)
            }
        }

        /** Its value there with the offsets, and how much it counts: less towards its edges, where it meets the next. */
        fun sample(x: Int, y: Int): Sample? {
            val values = raw(x, y) ?: return null
            val i = placement.box.index(x, y)
            val fx = placement.xs[i]
            val fy = placement.ys[i]
            val edge = min(min(fx, placement.frameWidth - fx), min(fy, placement.frameHeight - fy))
            val weight = (edge / (FEATHER * min(placement.frameWidth, placement.frameHeight))).coerceIn(MIN_WEIGHT, 1f)
            for (c in 0 until CHANNELS) values[c] += offsets[c]
            return Sample(values, weight)
        }

        /** Each channel's median over the panel: its sky. */
        fun backgrounds(): FloatArray {
            val shown = image ?: return FloatArray(CHANNELS)
            return FloatArray(CHANNELS) { c ->
                median(
                    (c until shown.values.size step CHANNELS * SAMPLE_STEP).map {
                        shown.values[it]
                    }.filterNot { it.isNaN() }.toMutableList(),
                )
            }
        }
    }

    private class ColourModel(val backgrounds: FloatArray, val gains: FloatArray) {
        fun apply(channel: Int, value: Float): Float = (value - backgrounds[channel]) * gains[channel] + backgrounds[1]
    }

    /** N.I.N.A.'s stretch as a 16-bit table to the screen's 8 bits. */
    private class Stretch(shadows: Double, midtones: Double, highlights: Double) {
        private val table = ByteArray(TABLE_SIZE) { value ->
            val x = value.toDouble() / (TABLE_SIZE - 1)
            (mtf(midtones, 1.0 - highlights + x - shadows) * MAX_BYTE).roundToInt().coerceIn(0, MAX_BYTE).toByte()
        }

        fun screen(value: Float): Int =
            table[(value / FULL_SCALE * (TABLE_SIZE - 1)).roundToInt().coerceIn(0, TABLE_SIZE - 1)].toInt() and MAX_BYTE
    }

    companion object {
        /**
         * The mosaic of [panel], whose frames are [frameWidth] × [frameHeight], laid out from where its
         * panels were framed, turned as the panel's solution says the camera is; null without one.
         * Its long side is at most [maxSide] pixels, and a frame at most [frameSide] across it, so a
         * mosaic of many panels is drawn smaller and takes no more memory.
         */
        fun of(panel: MosaicPanel, frameWidth: Int, frameHeight: Int, maxSide: Int = MAX_SIDE, frameSide: Int = FRAME_SIDE): LiveMosaic? {
            val solution = panel.solution
            // Every panel as the camera points: turned as this one, centred where it was framed.
            val skies = solution?.let {
                panel.panels.mapNotNull { at -> FrameSky.of(it.copy(centre = at), frameWidth, frameHeight) }
            }.orEmpty()
            // A canvas of one pixel to measure on, centred on the mosaic, with the frames' pixels.
            val unit = solution?.let { FrameSky.of(it.copy(centre = panel.center), 1, 1) }
            val native = skies.map { sky -> corners(frameWidth, frameHeight).mapNotNull { (x, y) -> unit?.pixel(sky.at(x, y)) } }
            val whole = skies.isNotEmpty() && skies.size == panel.panels.size && native.all { it.size == CORNERS }
            return if (solution != null && unit != null && whole) {
                laidOut(solution, unit, skies, native.flatten(), frameWidth to frameHeight, maxSide, frameSide)
            } else {
                null
            }
        }

        /**
         * The mosaic whose panels, seen as [skies], have their corners at [all] on the [unit] canvas:
         * as large as [maxSide] and [frameSide] let it be, centred where the panels' middle is.
         */
        @Suppress("LongParameterList") // The panels, where they are, and the sizes the mosaic keeps to.
        private fun laidOut(
            solution: Solution,
            unit: FrameSky,
            skies: List<FrameSky>,
            all: List<Pair<Double, Double>>,
            frame: Pair<Int, Int>,
            maxSide: Int,
            frameSide: Int,
        ): LiveMosaic? {
            val (frameWidth, frameHeight) = frame
            val (minX, maxX) = all.minOf { it.first } to all.maxOf { it.first }
            val (minY, maxY) = all.minOf { it.second } to all.maxOf { it.second }
            val scale = max(max(frameWidth, frameHeight).toDouble() / frameSide, max(maxX - minX, maxY - minY) / maxSide)
            val width = max(1, ceil((maxX - minX) / scale).toInt())
            val height = max(1, ceil((maxY - minY) / scale).toInt())
            // Centred where the panels' middle is, which a mosaic framed off its centre moves.
            val middle = unit.at((minX + maxX) / 2, (minY + maxY) / 2)
            return FrameSky.of(solution.copy(centre = middle, scaleArcsec = solution.scaleArcsec * scale), width, height)?.let { canvas ->
                val outlines = skies.map { sky -> corners(frameWidth, frameHeight).mapNotNull { (x, y) -> canvas.pixel(sky.at(x, y)) } }
                LiveMosaic(canvas, outlines, scale)
            }
        }

        /** The mosaic's long side at most, in pixels: its canvas then takes some 50 MB. */
        const val MAX_SIDE = 2400

        /** A frame across the mosaic at most, in pixels: a Seestar's at a quarter. */
        const val FRAME_SIDE = 960

        private const val CHANNELS = 3
        private const val RGBA = 4
        private const val CORNERS = 4
        private const val MAX_BYTE = 255
        private const val HALF = 0.5
        private const val TABLE_SIZE = 65_536
        private const val FULL_SCALE = 65_535f

        /** Canvas pixels left between those sampled for medians. */
        private const val SAMPLE_STEP = 2

        /** Overlap samples enough to match a panel's background by them. */
        private const val MIN_OVERLAP = 50

        /** A panel fades out over this share of its short side towards its edges, where it meets the next. */
        private const val FEATHER = 0.08f

        /** Even at its very edge a panel counts a little, so that no gap opens where only it reaches. */
        private const val MIN_WEIGHT = 0.001f

        /** N.I.N.A.'s defaults: the background at 20 % grey, black 2.8 MADs below the median. */
        private const val TARGET_BACKGROUND = 0.2
        private const val SHADOWS_CLIPPING = -2.8
        private const val MAD_TO_SIGMA = 1.4826

        private fun corners(width: Int, height: Int) =
            listOf(0.0 to 0.0, width.toDouble() to 0.0, width.toDouble() to height.toDouble(), 0.0 to height.toDouble())

        /** The midtones transfer function: maps [x] in 0..1 so that [midtones] goes to 0.5. */
        private fun mtf(midtones: Double, x: Double): Double = when {
            x <= 0.0 -> 0.0
            x >= 1.0 -> 1.0
            else -> ((midtones - 1) * x) / ((2 * midtones - 1) * x - midtones)
        }

        private fun median(values: MutableList<Float>): Float {
            if (values.isEmpty()) return 0f
            values.sort()
            return values[values.size / 2]
        }

        private fun bilinear(image: LinearImage, x: Float, y: Float): FloatArray? {
            val x0 = x.toInt().coerceAtMost(image.width - 1)
            val y0 = y.toInt().coerceAtMost(image.height - 1)
            val x1 = min(x0 + 1, image.width - 1)
            val y1 = min(y0 + 1, image.height - 1)
            val fx = x - x0
            val fy = y - y0
            val out = FloatArray(CHANNELS)
            for (c in 0 until CHANNELS) {
                fun at(px: Int, py: Int) = image.values[(py * image.width + px) * CHANNELS + c]
                val top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx
                val bottom = at(x0, y1) * (1 - fx) + at(x1, y1) * fx
                val value = top * (1 - fy) + bottom * fy
                if (value.isNaN()) return null
                out[c] = value
            }
            return out
        }
    }
}
