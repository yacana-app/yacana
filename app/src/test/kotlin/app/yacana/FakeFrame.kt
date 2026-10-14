// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import app.yacana.ports.Bayer
import app.yacana.ports.ColourStretch
import app.yacana.ports.DetectedStar
import app.yacana.ports.Frame
import app.yacana.ports.FrameStatistics
import app.yacana.ports.HeaderCard
import app.yacana.ports.Preview
import app.yacana.ports.Region
import app.yacana.ports.StarDetection
import app.yacana.ports.Stretch
import kotlin.math.exp

/**
 * A Seestar-sized frame drawn in Kotlin, for screenshots: a dark blue sky with coloured stars,
 * so that red mode has colour to remove. The engine does the real drawing on the phone.
 */
class FakeFrame : Frame {
    override val width = 2160
    override val height = 3840
    override val bayer = Bayer.GRBG

    @Volatile var previews = 0
        private set

    private val stars = listOf(Triple(540.0, 900.0, 0), Triple(1500.0, 1400.0, 1), Triple(1100.0, 2600.0, 2), Triple(300.0, 3300.0, 1))

    /** The four glows as N.I.N.A. would mark them, for the viewer's screenshots. */
    val marks =
        stars.mapIndexed { i, (sx, sy, _) ->
            DetectedStar(
                sx.toFloat(),
                sy.toFloat(),
                hfr = 40.0 + i,
                background = 1100.0,
                maxBrightness = 9000.0,
                box = Region(
                    sx - 60,
                    sy - 60,
                    120.0,
                    120.0,
                ),
            )
        }

    override fun statistics() = FrameStatistics(min = 800, max = 65535, mean = 1200.0, median = 1100.0, mad = 40.0, stdDev = 300.0)

    override fun autoStretch() = ColourStretch.linked(Stretch(shadows = 0.0150f, midtones = 0.05f))

    override fun histogram(bins: Int) = LongArray(bins) { i -> (1_000_000 * exp(-((i - bins / 50.0) / 2.0).let { it * it })).toLong() + 1 }

    // As the design: 412 stars with a mean HFR of 2.1 px.
    override fun detectStars(pixelSizeUm: Double?, focalLengthMm: Double?) = StarDetection(
        stars = List(412) { i ->
            DetectedStar(
                x = (i % 20) * 100f + 50f,
                y = (i / 20) * 180f + 90f,
                hfr = 2.1,
                background = 1100.0,
                maxBrightness = 9000.0,
                box = Region((i % 20) * 100.0 + 46, (i / 20) * 180.0 + 86, 9.0, 9.0),
            )
        },
        averageHfr = 2.1,
        hfrStdDev = 0.2,
    )

    override fun preview(stretch: ColourStretch, region: Region, width: Int, height: Int): Preview {
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val fx = region.x + (x + 0.5) * region.width / width
                val fy = region.y + (y + 0.5) * region.height / height
                val i = (y * width + x) * 4
                if (fx !in 0.0..<this.width.toDouble() || fy !in 0.0..<this.height.toDouble()) {
                    rgba[i + 3] = -1
                    continue
                }
                var colour = intArrayOf(10, 14, 40)
                for ((sx, sy, channel) in stars) {
                    val glow = 255 * exp(-((fx - sx) * (fx - sx) + (fy - sy) * (fy - sy)) / 2000.0)
                    colour[channel] = (colour[channel] + glow).toInt().coerceAtMost(255)
                    colour = colour.map { (it + glow / 3).toInt().coerceAtMost(255) }.toIntArray()
                }
                rgba[i] = colour[0].toByte()
                rgba[i + 1] = colour[1].toByte()
                rgba[i + 2] = colour[2].toByte()
                rgba[i + 3] = -1
            }
        }
        previews++
        return Preview(width, height, rgba)
    }

    override fun save(path: String, headers: List<HeaderCard>) = Unit

    override fun writeTo(descriptor: Int, headers: List<HeaderCard>) = Unit

    override fun close() = Unit
}
