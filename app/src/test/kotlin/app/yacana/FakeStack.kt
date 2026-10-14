// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import app.yacana.ports.DetectedStar
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.LinearImage
import app.yacana.ports.LiveStack
import app.yacana.ports.MasterFrame
import app.yacana.ports.Preview
import app.yacana.ports.Region
import app.yacana.ports.StackAddition
import app.yacana.ports.StackInfo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A live stack drawn in Kotlin, for screenshots: as the design's, 33 frames of a minute in before
 * the frame the test takes, and a galaxy tilted across the field among more stars than a frame
 * shows. The engine does the real stacking on the phone.
 */
class FakeStack : LiveStack {
    private var frames = 33
    private var exposure = 2_020.0

    @Volatile var previews = 0
        private set

    // The same field as the fake frame's, with fainter stars of its own.
    private val stars =
        listOf(540.0 to 900.0, 1500.0 to 1400.0, 1100.0 to 2600.0, 300.0 to 3300.0) +
            List(60) { i -> (i * 733 % 2160).toDouble() to (i * 1471 % 3840).toDouble() }

    override fun add(
        frame: Frame,
        stars: List<DetectedStar>,
        exposureSeconds: Double,
        dark: MasterFrame?,
        flat: MasterFrame?,
    ): StackAddition {
        frames++
        exposure += exposureSeconds
        return StackAddition(true, frames, 0.4, 12.0, 96, dark = dark != null, flat = flat != null)
    }

    override fun info() = StackInfo(2160, 3840, frames, exposure, redGain = 0.71, blueGain = 1.01, balanceStars = 117)

    override fun preview(balanced: Boolean, region: Region, width: Int, height: Int): Preview {
        val rgba = ByteArray(width * height * 4)
        val (c, s) = cos(TILT) to sin(TILT)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val fx = region.x + (x + 0.5) * region.width / width
                val fy = region.y + (y + 0.5) * region.height / height
                val i = (y * width + x) * 4
                rgba[i + 3] = -1
                if (fx !in 0.0..<2160.0 || fy !in 0.0..<3840.0) continue
                // The galaxy: a tilted ellipse, brighter to its core.
                val u = ((fx - 1080) * c + (fy - 1920) * s) / 900
                val v = (-(fx - 1080) * s + (fy - 1920) * c) / 260
                val glow = 150 * exp(-(u * u + v * v) * 2)
                var light = glow
                for ((sx, sy) in stars) {
                    val (dx, dy) = fx - sx to fy - sy
                    if (abs(dx) < STAR_REACH && abs(dy) < STAR_REACH) light += 255 * exp(-(dx * dx + dy * dy) / 900.0)
                }
                rgba[i] = (14 + light * 0.95).coerceAtMost(255.0).toInt().toByte()
                rgba[i + 1] = (12 + light * 0.85).coerceAtMost(255.0).toInt().toByte()
                rgba[i + 2] = (20 + light * 0.75).coerceAtMost(255.0).toInt().toByte()
            }
        }
        previews++
        return Preview(width, height, rgba)
    }

    /** Where the panel being stacked is in the mosaic, in its pixels from the mosaic's centre, for [linear]. */
    @Volatile var panelOffset = 0.0 to 0.0

    /** The linear stack: the same galaxy and stars, in ADU over a sky of 1000, as the panel at [panelOffset] sees them. */
    override fun linear(width: Int, height: Int): LinearImage {
        val values = FloatArray(width * height * 3)
        val (c, s) = cos(TILT) to sin(TILT)
        val (ox, oy) = panelOffset
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Where this pixel is in the mosaic, with the galaxy at its centre.
                val fx = (x + 0.5) * 2160 / width - 1080 + ox
                val fy = (y + 0.5) * 3840 / height - 1920 + oy
                val u = (fx * c + fy * s) / 1800
                val v = (-fx * s + fy * c) / 520
                var light = 8000 * exp(-(u * u + v * v) * 2)
                for ((sx, sy) in stars) {
                    val (dx, dy) = fx - (sx - 1080) * 2 to fy - (sy - 1920) * 2
                    if (abs(dx) < STAR_REACH && abs(dy) < STAR_REACH) light += 20_000 * exp(-(dx * dx + dy * dy) / 900.0)
                }
                val i = (y * width + x) * 3
                values[i] = (1000 + light * 1.1).toFloat()
                values[i + 1] = (1000 + light).toFloat()
                values[i + 2] = (1000 + light * 0.9).toFloat()
            }
        }
        previews++
        return LinearImage(width, height, values)
    }

    override fun save(path: String, headers: List<HeaderCard>) = Unit

    override fun writeTo(descriptor: Int, headers: List<HeaderCard>) = Unit

    override fun saveJpeg(path: String, balanced: Boolean) = Unit

    override fun writeJpegTo(descriptor: Int, balanced: Boolean) = Unit

    override fun checkpoint(path: String, label: String) = Unit

    override fun label() = ""

    override fun close() = Unit

    private companion object {
        const val TILT = -0.6

        /** Past this, in pixels, a star adds nothing that shows. */
        const val STAR_REACH = 120.0
    }
}
