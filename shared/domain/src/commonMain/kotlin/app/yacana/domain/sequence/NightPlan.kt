// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.imaging.FrameType
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.Pace
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
         * The plan of [targets] [from] now, or from [dusk] if the night has not started; each frame,
         * dither, autofocus and panel's centring as long as the equipment's [pace] says, with the
         * settling [dither] says, and the autofocus runs of [triggers] that can be foreseen.
         */
        fun of(
            targets: List<Target>,
            from: Instant,
            dusk: Instant?,
            dawn: Instant?,
            dither: DitherOptions,
            pace: Pace = Pace(),
            triggers: List<Trigger> = emptyList(),
        ): NightPlan {
            val start = if (dusk != null && dusk > from) dusk else from
            var at = start
            val planned =
                targets.map { target ->
                    val end = at + target.duration(dither, pace, triggers)
                    PlannedTarget(target.name, at, end).also { at = end }
                }
            return NightPlan(start, dawn, planned)
        }
    }
}

/**
 * How long a panel's [Exposures] take: each frame with what the equipment's [pace] measured beyond
 * its exposure, and each dither as measured, or else its move and the settling [dither] says.
 */
fun Exposures.duration(dither: DitherOptions, pace: Pace = Pace()): Duration {
    val dithers = if (ditherEvery > 0) (count - 1) / ditherEvery else 0
    val each = pace.ditherSeconds ?: (DITHER_PULSE_SECONDS + dither.settleSeconds)
    return (exposureSeconds + pace.frameSeconds).seconds * count + each.seconds * dithers
}

/** How long a target takes: each panel centred, then its exposures and the autofocus runs [triggers] foresee in them. */
fun Target.duration(dither: DitherOptions, pace: Pace = Pace(), triggers: List<Trigger> = emptyList()): Duration {
    val focus = pace.autofocusSeconds.seconds * autofocusRuns(exposures, triggers, dither, pace)
    val panel = pace.centringSeconds.seconds + exposures.fold(Duration.ZERO) { sum, step -> sum + step.duration(dither, pace) } + focus
    return panel * panels.size.coerceAtLeast(1)
}

/**
 * The autofocus runs [triggers] make certain in one panel's [exposures]: one every so many minutes
 * of lights, and one at each change of filter between them. Those on the temperature or the HFR
 * cannot be foreseen, and are not counted.
 */
fun autofocusRuns(exposures: List<Exposures>, triggers: List<Trigger>, dither: DitherOptions, pace: Pace): Int {
    val lights = exposures.filter { it.type == FrameType.Light }
    val byTime =
        triggers.filterIsInstance<Trigger.AutofocusAfterTime>().minOfOrNull { it.minutes }?.takeIf { it > 0 }?.let { minutes ->
            (lights.fold(Duration.ZERO) { sum, step -> sum + step.duration(dither, pace) } / minutes.minutes).toInt()
        } ?: 0
    val byFilter = if (Trigger.AutofocusAfterFilter in triggers) lights.zipWithNext().count { (a, b) -> a.filter != b.filter } else 0
    return byTime + byFilter
}

/**
 * The target with its first exposures taking as many frames as fit from [start] to [dawn], for the
 * template "One target all night"; as it is, without a dawn or exposures.
 */
fun Target.fillingTheNight(start: Instant, dawn: Instant?, dither: DitherOptions, pace: Pace = Pace()): Target {
    val first = exposures.firstOrNull()
    if (dawn == null || first == null) return this
    val panels = panels.size.coerceAtLeast(1)
    val available = (dawn - start) / panels - pace.centringSeconds.seconds
    // From the time a frame and its share of the dithers take, back while that many do not fit.
    val perFrame = first.copy(count = DITHER_SAMPLE).duration(dither, pace) / DITHER_SAMPLE
    var count = (available / perFrame).toInt() + 1
    while (count > 1 && first.copy(count = count).duration(dither, pace) > available) count--
    return copy(exposures = listOf(first.copy(count = count)))
}

/** What a dither's move takes before its settling, until a night measures it: a Seestar's small GoTo 1 to 1.6 s. */
private const val DITHER_PULSE_SECONDS = 2.0

/** Frames enough to count each dither's share in a frame's time. */
private const val DITHER_SAMPLE = 60
