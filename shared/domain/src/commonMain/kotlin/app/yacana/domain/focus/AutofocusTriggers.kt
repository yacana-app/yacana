// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.focus

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A light frame as the triggers see it: when it was taken, with which filter, and its stars' HFR (0 without stars). */
data class LightFrame(val at: Instant, val filter: String?, val hfr: Double)

/**
 * What the triggers look at before the next light frame: the time, the focuser's [temperature],
 * the wheel's [filter], the last autofocus that finished, and the light frames taken so far,
 * oldest first.
 */
data class TriggerContext(
    val now: Instant,
    val temperature: Double?,
    val filter: String?,
    val lastAutofocus: AutofocusResult?,
    val lights: List<LightFrame>,
)

/**
 * N.I.N.A.'s autofocus triggers. The sequence asks each one, before a light frame, whether
 * autofocus should run first; [start] is called when the block that holds it starts. Safety and
 * the meridian flip, which also hold a trigger back in NINA, are the sequence's to check.
 */
sealed class AutofocusTrigger {
    open fun start(context: TriggerContext) = Unit

    abstract fun shouldTrigger(context: TriggerContext): Boolean

    /**
     * When the focuser's temperature has changed by [amount] °C or more since the last autofocus,
     * or since the start if there has been none. Not before the first frame of the session.
     */
    class AfterTemperatureChange(val amount: Double = 5.0) : AutofocusTrigger() {
        private var initial: Double? = null

        override fun start(context: TriggerContext) {
            initial = context.temperature
        }

        override fun shouldTrigger(context: TriggerContext): Boolean {
            val now = context.temperature
            if (context.lights.isEmpty() || now == null) return false
            val last = context.lastAutofocus
            val before = if (last == null) initial ?: now.also { initial = it } else last.temperature
            return before != null && abs(before - now) >= amount
        }
    }

    /** When the filter is not the one of the last autofocus, or the one at the start if there has been none. */
    class AfterFilterChange : AutofocusTrigger() {
        private var last: String? = null

        override fun start(context: TriggerContext) {
            last = context.filter
        }

        override fun shouldTrigger(context: TriggerContext): Boolean {
            context.lastAutofocus?.let { last = it.filter }
            val changed = last != null && last != context.filter
            last = context.filter
            return changed
        }
    }

    /**
     * When the HFR has grown by more than [amount] %: the trend of the last [sampleSize] frames
     * since the last autofocus, a straight line fitted to them, against the lowest HFR among those
     * frames. With [perFilter], only the frames of the current filter count. It needs 3 frames.
     */
    class AfterHfrIncrease(val amount: Double = 5.0, val sampleSize: Int = 10, val perFilter: Boolean = true) : AutofocusTrigger() {
        override fun shouldTrigger(context: TriggerContext): Boolean {
            val since = context.lastAutofocus?.at
            val frames =
                context.lights.filter { frame ->
                    frame.hfr > 0 &&
                        (since == null || frame.at > since) &&
                        !(perFilter && context.filter != null && frame.filter != context.filter)
                }
            val sample = frames.drop(max(0, frames.size - sampleSize))
            if (sample.size < MIN_FRAMES) return false
            val lowest = frames.minOf { it.hfr }
            return increase(sample.map { it.hfr }, lowest) > amount
        }

        /** NINA's HFRTrendPercentage: the line through [hfrs] at the last frame, against [lowest], rounded to 0.01 %. */
        private fun increase(hfrs: List<Double>, lowest: Double): Double {
            val n = hfrs.size
            val xs = (1..n).map { it.toDouble() }
            val meanX = xs.average()
            val meanY = hfrs.average()
            val slope = xs.indices.sumOf { (xs[it] - meanX) * (hfrs[it] - meanY) } / xs.sumOf { (it - meanX) * (it - meanX) }
            val trend = meanY + slope * (n - meanX)
            return round((trend / lowest - 1) * PERCENT * PERCENT) / PERCENT
        }
    }

    /** When [minutes] have gone by since the last autofocus, or since the start if there has been none. */
    class AfterTime(val minutes: Double = 30.0) : AutofocusTrigger() {
        private var initial: Instant? = null

        override fun start(context: TriggerContext) {
            initial = context.now
        }

        override fun shouldTrigger(context: TriggerContext): Boolean {
            val since = context.lastAutofocus?.at ?: initial ?: context.now.also { initial = it }
            return context.now - since >= minutes.minutes
        }
    }

    private companion object {
        const val MIN_FRAMES = 3
        const val PERCENT = 100.0
    }
}
