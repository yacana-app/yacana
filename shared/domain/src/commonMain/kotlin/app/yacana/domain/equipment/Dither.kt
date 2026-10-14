// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.GuideDirection
import app.yacana.domain.profile.DitherOptions
import app.yacana.ports.SkyPosition
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** Where a dither put the field, in pixels east and north of where it began. */
data class DitherOffset(val eastPixels: Double, val northPixels: Double)

/**
 * Dithering without a guider, as N.I.N.A.'s direct guider (its `DirectGuider` and
 * `DitherOffsetSelector`): each dither moves the field to an offset drawn around where it began,
 * never wandering off from it, and the mount gets the guide pulses that take it there from the last
 * one. The sequence dithers so between exposures, and so does Imaging's loop; [reset] makes where
 * the field is now the new centre, as after a GoTo.
 */
class Dither(private val random: Random = Random) {
    private var previous = DitherOffset(0.0, 0.0)

    /** Where the field is now becomes the centre the next offsets are drawn around. */
    fun reset() {
        previous = DitherOffset(0.0, 0.0)
    }

    /**
     * Moves [mount] to a new offset for [options], with [pixelScaleArcsec] of the camera: the guide
     * pulses at half the sidereal rate, or for a mount that cannot pulse guide a slew as far; then
     * the settle time. Returns the new offset.
     */
    suspend fun move(mount: MountMediator, options: DitherOptions, pixelScaleArcsec: Double): DitherOffset {
        val target = offset(previous, options)
        val east = (target.eastPixels - previous.eastPixels) * pixelScaleArcsec / GUIDE_RATE
        val north = (target.northPixels - previous.northPixels) * pixelScaleArcsec / GUIDE_RATE
        if (mount.capabilities.canPulseGuide) {
            if (east != 0.0) mount.pulseGuide(if (east > 0) GuideDirection.East else GuideDirection.West, abs(east).seconds)
            if (north != 0.0 && !options.raOnly) {
                mount.pulseGuide(if (north > 0) GuideDirection.North else GuideDirection.South, abs(north).seconds)
            }
        } else {
            val here = mount.position()
            val decDeg = here.decDeg + north * GUIDE_RATE / ARCSEC_PER_DEGREE
            val raHours = here.raHours + east * GUIDE_RATE / ARCSEC_PER_DEGREE / DEGREES_PER_HOUR / cos(here.decDeg * PI / HALF_TURN)
            mount.goToAndWait(SkyPosition(raHours.mod(HOURS), decDeg))
        }
        delay(options.settleSeconds.seconds)
        previous = target
        return target
    }

    /**
     * The next offset, as N.I.N.A.'s `DitherOffsetSelector`: at a random angle and a distance of a
     * normal distribution with a standard deviation of [DitherOptions.pixels], within three times
     * that, around where the field began; in right ascension alone with [DitherOptions.raOnly]; and
     * at least [DitherOptions.minimumPixels] from [from], or else, after 100 tries, that far towards
     * the centre.
     */
    internal fun offset(from: DitherOffset, options: DitherOptions): DitherOffset {
        val pixels = options.pixels.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val minimum = options.minimumPixels.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(pixels) ?: 0.0
        repeat(TRIES) {
            val angle = random.nextDouble() * PI
            val distance = gaussian(pixels).coerceIn(-SPREAD * pixels, SPREAD * pixels)
            val drawn = DitherOffset(distance * cos(angle), distance * sin(angle))
            val candidate = if (options.raOnly) drawn.copy(northPixels = from.northPixels) else drawn
            val moved =
                if (options.raOnly) {
                    abs(candidate.eastPixels - from.eastPixels)
                } else {
                    hypot(candidate.eastPixels - from.eastPixels, candidate.northPixels - from.northPixels)
                }
            if (moved >= minimum) return candidate
        }
        return fallback(from, minimum, options.raOnly)
    }

    /** N.I.N.A.'s step when no draw moved far enough: [minimum] towards the centre, or east from it. */
    private fun fallback(from: DitherOffset, minimum: Double, raOnly: Boolean): DitherOffset {
        val distance = hypot(from.eastPixels, from.northPixels)
        return when {
            minimum <= 0.0 -> from
            raOnly -> from.copy(eastPixels = from.eastPixels + if (from.eastPixels > 0) -minimum else minimum)
            distance == 0.0 -> DitherOffset(minimum, 0.0)
            else -> DitherOffset(from.eastPixels * (1 - minimum / distance), from.northPixels * (1 - minimum / distance))
        }
    }

    /** A normal deviate of mean 0 and [deviation], by Box and Muller. */
    private fun gaussian(deviation: Double): Double {
        val u = 1.0 - random.nextDouble()
        return deviation * sqrt(2 * -ln(u)) * cos(2 * PI * random.nextDouble())
    }

    private companion object {
        /** Half the sidereal rate, in arcseconds per second, as a guide pulse moves. */
        const val GUIDE_RATE = 7.52
        const val ARCSEC_PER_DEGREE = 3600.0
        const val DEGREES_PER_HOUR = 15.0
        const val HALF_TURN = 180.0
        const val HOURS = 24.0
        const val TRIES = 100

        /** Offsets stay within three standard deviations of the centre. */
        const val SPREAD = 3.0
    }
}
