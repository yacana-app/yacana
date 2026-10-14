// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.ports.LinearImage
import app.yacana.ports.Preview
import app.yacana.ports.SkyPosition
import app.yacana.ports.Solution
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live mosaic of two Seestar panels side by side, east and west of the equator's 6h, 15 % overlapping. */
class LiveMosaicTest {
    private val center = SkyPosition(6.0, 0.0)

    /** Half a panel's step east or west: 85 % of a 2160-pixel side of 2.25″ pixels, in hours at the equator. */
    private val half = 2160 * 0.85 * SCALE / 3600 / 2 / 15
    private val west = SkyPosition(6.0 - half, 0.0)
    private val east = SkyPosition(6.0 + half, 0.0)

    private fun solution(at: SkyPosition) = Solution(at, rotationDeg = 0.0, scaleArcsec = SCALE, yAxisDeg = 0.0)

    private fun panel(index: Int, panels: List<SkyPosition> = listOf(west, east)) =
        MosaicPanel("Test", center, panels, index, solution(panels[index]))

    private fun mosaic() = assertNotNull(LiveMosaic.of(panel(0), WIDTH, HEIGHT))

    /** A panel's stack at a quarter: a sky of [sky] ADU with noise of ±100, the same each run, and a star in the middle. */
    private fun stack(sky: Float, star: Boolean = false, seed: Int = 1): LinearImage {
        val (width, height) = 540 to 960
        val noise = Random(seed)
        val values = FloatArray(width * height * 3)
        for (p in 0 until width * height) {
            val x = p % width
            val y = p / width
            val bright = star && abs(x - width / 2) < 3 && abs(y - height / 2) < 3
            val value = sky + noise.nextInt(-100, 101) + if (bright) 20_000f else 0f
            for (c in 0..2) values[p * 3 + c] = value
        }
        return LinearImage(width, height, values)
    }

    /** The canvas pixel of [at], from the outlines: each panel's middle is its framed centre; halfway between them, their overlap. */
    private fun LiveMosaic.locate(at: SkyPosition): Pair<Int, Int> {
        val index = listOf(west, east).indexOf(at)
        if (index < 0) {
            val (a, b) = locate(west) to locate(east)
            return (a.first + b.first) / 2 to (a.second + b.second) / 2
        }
        val corners = outlines[index]
        return (corners.sumOf { it.first } / 4).toInt() to (corners.sumOf { it.second } / 4).toInt()
    }

    /** The mean screen brightness of 41 × 41 pixels by [at], off its star, for its noise to average out; null if transparent. */
    private fun Preview.brightness(mosaic: LiveMosaic, at: SkyPosition): Double? {
        val (cx, cy) = mosaic.locate(at)
        var sum = 0.0
        var n = 0
        for (y in cy + 20 until cy + 61) {
            for (x in cx - 20..cx + 20) {
                val o = (y * width + x) * 4
                if (rgba[o + 3].toInt() == 0) return null
                sum += (0..2).sumOf { rgba[o + it].toInt() and 0xFF }
                n += 3
            }
        }
        return sum / n
    }

    @Test
    fun theCanvasHoldsBothPanelsAtAQuarterOfASeestarFrame() {
        val mosaic = mosaic()

        assertEquals(4.0, mosaic.scale)
        assertEquals(2, mosaic.outlines.size)
        // Two panels of 540 × 960 side by side, 15 % of one overlapping: about 1000 × 960.
        assertTrue(abs(mosaic.width - (540 * 2 - 81)) <= 3, "${mosaic.width}")
        assertTrue(abs(mosaic.height - 960) <= 3, "${mosaic.height}")
        assertEquals(540 to 960, mosaic.imageSize(WIDTH, HEIGHT))
    }

    @Test
    fun aMosaicOfManyPanelsIsDrawnSmallerToFit() {
        // Ten by ten panels, framed edge to edge.
        val step = 2160 * 0.85 * SCALE / 3600
        val panels = (0 until 10).flatMap { row ->
            (0 until 10).map { column ->
                SkyPosition(
                    6.0 + (column - 4.5) * step / 15,
                    (row - 4.5) * step * 3840 / 2160,
                )
            }
        }
        val big = assertNotNull(LiveMosaic.of(MosaicPanel("Big", center, panels, 0, solution(panels[0])), WIDTH, HEIGHT))

        assertTrue(big.scale > 4.0, "${big.scale}")
        assertTrue(maxOf(big.width, big.height) <= LiveMosaic.MAX_SIDE, "${big.width} × ${big.height}")
    }

    @Test
    fun aPanelShowsWhereItWasCentredAndTheOtherStaysEmpty() {
        val mosaic = mosaic()

        mosaic.draw(0, solution(west), WIDTH, HEIGHT, stack(1000f))
        val image = mosaic.image()

        assertNotNull(image.brightness(mosaic, west))
        assertNull(image.brightness(mosaic, east), "the second panel is not stacked yet")
        assertEquals(setOf(0), mosaic.drawn)
        assertEquals(0, mosaic.stacking)
    }

    @Test
    fun aBrighterSkyInTheNextPanelIsMatchedToTheFirst() {
        val mosaic = mosaic()

        mosaic.draw(0, solution(west), WIDTH, HEIGHT, stack(1000f, star = true))
        mosaic.draw(1, solution(east), WIDTH, HEIGHT, stack(1500f, star = true, seed = 2))
        val image = mosaic.image()

        val first = assertNotNull(image.brightness(mosaic, west))
        val second = assertNotNull(image.brightness(mosaic, east))
        assertTrue(abs(first - second) < 3, "the same sky on both sides: $first and $second")
        assertNotNull(image.brightness(mosaic, center), "the overlap is blended")
        assertEquals(setOf(0, 1), mosaic.drawn)
        assertEquals(1, mosaic.stacking)
    }

    @Test
    fun aPanelDoneStaysAsItLastWas() {
        val mosaic = mosaic()
        mosaic.draw(0, solution(west), WIDTH, HEIGHT, stack(1000f, star = true))
        mosaic.draw(1, solution(east), WIDTH, HEIGHT, stack(1000f, star = true))
        val before = assertNotNull(mosaic.image().brightness(mosaic, west))

        // The second panel grows; the first is done and is not drawn again.
        mosaic.draw(1, solution(east), WIDTH, HEIGHT, stack(1000f, star = true))

        assertEquals(before, mosaic.image().brightness(mosaic, west))
    }

    @Test
    fun withoutASolutionThereIsNoMosaic() {
        assertNull(LiveMosaic.of(panel(0).copy(solution = null), WIDTH, HEIGHT))
    }

    private companion object {
        const val WIDTH = 2160
        const val HEIGHT = 3840
        const val SCALE = 2.25
    }
}
