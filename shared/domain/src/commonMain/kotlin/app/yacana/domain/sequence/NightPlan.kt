// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.profile.DitherOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A target on the night's timeline: when it starts and when it ends. */
data class PlannedTarget(val name: String, val start: Instant, val end: Instant)

/**
 * Tonight's plan on a timeline: the targets one after another from [start], each with its
 * end, against [dawn], the end of the astronomical night.
 */
data class NightPlan(val start: Instant, val dawn: Instant?, val targets: List<PlannedTarget>) {
    /** The first target that ends after dawn, which the editor warns about; null when the plan fits. */
    val late: PlannedTarget? get() = dawn?.let { limit -> targets.firstOrNull { it.end > limit } }

    /** When the last target ends. */
    val end: Instant get() = targets.lastOrNull()?.end ?: start

    companion object {
        /**
         * The plan of [targets] [from] now, or from [dusk] if the night has not started; each frame
         * as long as its exposure and its download, each dither with its settling as [dither] says,
         * and each panel's centring.
         */
        fun of(targets: List<Target>, from: Instant, dusk: Instant?, dawn: Instant?, dither: DitherOptions): NightPlan {
            val start = if (dusk != null && dusk > from) dusk else from
            var at = start
            val planned =
                targets.map { target ->
                    val end = at + target.duration(dither)
                    PlannedTarget(target.name, at, end).also { at = end }
                }
            return NightPlan(start, dawn, planned)
        }
    }
}

/** How long a panel's [Exposures] take: each frame and its download, and each dither and its settling. */
fun Exposures.duration(dither: DitherOptions): Duration {
    val dithers = if (ditherEvery > 0) (count - 1) / ditherEvery else 0
    return (exposureSeconds + FRAME_OVERHEAD_SECONDS).seconds * count + (DITHER_PULSE_SECONDS + dither.settleSeconds).seconds * dithers
}

/** How long a target takes: each panel centred, then its exposures. */
fun Target.duration(dither: DitherOptions): Duration {
    val panel = CENTRING + exposures.fold(Duration.ZERO) { sum, step -> sum + step.duration(dither) }
    return panel * panels.size.coerceAtLeast(1)
}

/**
 * The target with its first exposures taking as many frames as fit from [start] to [dawn], for the
 * template "One target all night"; as it is, without a dawn or exposures.
 */
fun Target.fillingTheNight(start: Instant, dawn: Instant?, dither: DitherOptions): Target {
    val first = exposures.firstOrNull()
    if (dawn == null || first == null) return this
    val panels = panels.size.coerceAtLeast(1)
    val available = (dawn - start) / panels - CENTRING
    // From the time a frame and its share of the dithers take, back while that many do not fit.
    val perFrame = first.copy(count = DITHER_SAMPLE).duration(dither) / DITHER_SAMPLE
    var count = (available / perFrame).toInt() + 1
    while (count > 1 && first.copy(count = count).duration(dither) > available) count--
    return copy(exposures = listOf(first.copy(count = count)))
}

/** What a dither's pulses take, about: 5 pixels of a Seestar at half the sidereal rate are 1.5 s. */
private const val DITHER_PULSE_SECONDS = 2.0

/** Frames enough to count each dither's share in a frame's time. */
private const val DITHER_SAMPLE = 60

/** The Seestar's download of a whole frame takes up to 3 s. */
private const val FRAME_OVERHEAD_SECONDS = 3.0

/** Going to a panel and centring it takes about a minute. */
internal val CENTRING = 60.seconds
