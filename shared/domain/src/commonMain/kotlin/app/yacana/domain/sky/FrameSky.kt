// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.sky.SkyView.Companion.northAt
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import kotlin.math.abs

/**
 * The sky of a solved [width] × [height] frame as the screen shows it, its first row at the top,
 * for Imaging's "Reference". The frame is written top-down and ASTAP takes its first row
 * as the bottom one, so the solution's y axis runs down the screen and up is half a turn from it;
 * a [mirrored] frame has east on the other side, as the cache's photo is drawn flipped.
 */
class FrameSky private constructor(solution: Solution, yAxisDeg: Double, val width: Int, val height: Int) {
    /** North turned clockwise from up, as [SkyView.rollDeg] takes it. */
    val rollDeg: Double = (yAxisDeg + HALF_TURN).mod(FULL_TURN)

    val mirrored: Boolean = solution.mirrored

    private val degreesPerPixel = abs(solution.scaleArcsec) / ARCSEC_PER_DEGREE
    private val whole = SkyView(solution.centre, width * degreesPerPixel, width.toDouble(), height.toDouble(), rollDeg)

    /** The sky at frame pixel ([x], [y]), counted from the top left. */
    fun at(x: Double, y: Double): SkyPosition = whole.unproject(ScreenPoint(if (mirrored) width - x else x, y))

    /** The frame pixel, counted from the top left, where [position] is: the inverse of [at]; null opposite the frame. */
    fun pixel(position: SkyPosition): Pair<Double, Double>? =
        whole.project(position)?.let { (if (mirrored) width - it.x else it.x) to it.y }

    /**
     * The sky a [screenWidth] × [screenHeight] screen shows of the frame at [scale] screen pixels per
     * frame pixel, centred on frame pixel ([x], [y]), as the frame viewer does; drawn flipped when
     * [mirrored].
     */
    fun view(x: Double, y: Double, scale: Double, screenWidth: Double, screenHeight: Double): SkyView {
        val centre = at(x, y)
        // Away from the frame's centre north turns a little, as the meridians close in.
        val (north, pixelsPerDegree) = whole.northAt(centre) ?: (rollDeg to 1 / degreesPerPixel)
        return SkyView(centre, screenWidth / scale / pixelsPerDegree, screenWidth, screenHeight, north)
    }

    companion object {
        /** The sky of a frame solved as [solution]; null when the solution does not say where its y axis points. */
        fun of(solution: Solution, width: Int, height: Int): FrameSky? = solution.yAxisDeg?.let { FrameSky(solution, it, width, height) }

        private const val HALF_TURN = 180.0
        private const val FULL_TURN = 360.0
        private const val ARCSEC_PER_DEGREE = 3600.0
    }
}
