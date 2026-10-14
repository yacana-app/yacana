// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.focus

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Each trigger as N.I.N.A.'s decides, before a light frame, whether autofocus runs first. */
class AutofocusTriggersTest {
    private val start = Instant.parse("2026-10-06T21:00:00Z")

    private fun context(
        after: Duration = Duration.ZERO,
        temperature: Double? = 12.0,
        filter: String? = "IR",
        last: AutofocusResult? = null,
        lights: List<LightFrame> = listOf(light(0.0, 2.0)),
    ) = TriggerContext(start + after, temperature, filter, last, lights)

    private fun light(minutes: Double, hfr: Double, filter: String = "IR") = LightFrame(start + minutes.minutes, filter, hfr)

    private fun autofocus(minutes: Double, temperature: Double? = 12.0, filter: String? = "IR") = AutofocusResult(
        position = 1500,
        hfr = 1.9,
        points = emptyList(),
        fitting = FocusFitting.Hyperbolic,
        initialPosition = 1520,
        temperature = temperature,
        filter = filter,
        at = start + minutes.minutes,
        duration = 70.seconds,
    )

    @Test
    fun temperatureCountsFromTheStartThenFromTheLastAutofocus() {
        val trigger = AutofocusTrigger.AfterTemperatureChange(amount = 5.0)
        trigger.start(context(temperature = 12.0))

        assertFalse(trigger.shouldTrigger(context(temperature = 16.9)))
        assertTrue(trigger.shouldTrigger(context(temperature = 17.0)))
        assertTrue(trigger.shouldTrigger(context(temperature = 7.0)), "either way")
        assertFalse(trigger.shouldTrigger(context(temperature = 7.5, last = autofocus(10.0, temperature = 7.0))))
        assertTrue(trigger.shouldTrigger(context(temperature = 12.0, last = autofocus(10.0, temperature = 7.0))))
    }

    @Test
    fun temperatureNeedsAFrameAndAReading() {
        val trigger = AutofocusTrigger.AfterTemperatureChange()
        trigger.start(context(temperature = 12.0))

        assertFalse(trigger.shouldTrigger(context(temperature = 20.0, lights = emptyList())), "not before the first frame")
        assertFalse(trigger.shouldTrigger(context(temperature = null)))
        assertFalse(trigger.shouldTrigger(context(temperature = 20.0, last = autofocus(10.0, temperature = null))))
    }

    @Test
    fun temperatureWithoutAReadingAtTheStartTakesTheFirstOne() {
        val trigger = AutofocusTrigger.AfterTemperatureChange()
        trigger.start(context(temperature = null))

        assertFalse(trigger.shouldTrigger(context(temperature = 10.0)))
        assertTrue(trigger.shouldTrigger(context(temperature = 15.0)))
    }

    @Test
    fun aNewFilterTriggersOnce() {
        val trigger = AutofocusTrigger.AfterFilterChange()
        trigger.start(context(filter = "IR"))

        assertFalse(trigger.shouldTrigger(context(filter = "IR")))
        assertTrue(trigger.shouldTrigger(context(filter = "LP")))
        assertFalse(trigger.shouldTrigger(context(filter = "LP")), "it remembers the new filter")
    }

    @Test
    fun theFilterIsComparedWithTheLastAutofocus() {
        val trigger = AutofocusTrigger.AfterFilterChange()
        trigger.start(context(filter = "IR"))

        assertFalse(trigger.shouldTrigger(context(filter = "LP", last = autofocus(10.0, filter = "LP"))))
        assertTrue(trigger.shouldTrigger(context(filter = "IR", last = autofocus(10.0, filter = "LP"))))
    }

    @Test
    fun theHfrTrendAgainstTheLowestHfr() {
        val trigger = AutofocusTrigger.AfterHfrIncrease(amount = 5.0)
        val flat = listOf(light(1.0, 2.0), light(2.0, 2.0), light(3.0, 2.0))
        // 2.0, 2.05, 2.1: the line reaches 2.1 at the last frame, 5 % over 2.0, which is not more than 5 %.
        val five = listOf(light(1.0, 2.0), light(2.0, 2.05), light(3.0, 2.1))
        val rising = listOf(light(1.0, 2.0), light(2.0, 2.1), light(3.0, 2.2), light(4.0, 2.3))

        assertFalse(trigger.shouldTrigger(context(lights = flat)))
        assertFalse(trigger.shouldTrigger(context(lights = five)))
        assertTrue(trigger.shouldTrigger(context(lights = rising)))
        assertFalse(trigger.shouldTrigger(context(lights = rising.take(2))), "it needs 3 frames")
    }

    @Test
    fun theHfrTrendTakesTheLastFramesButTheLowestOfAll() {
        val trigger = AutofocusTrigger.AfterHfrIncrease(amount = 5.0, sampleSize = 3)
        // The lowest was 1.8 at the start; the last 3 frames are flat at 2.0, 11 % over it.
        val frames = listOf(light(1.0, 1.8), light(2.0, 2.4), light(3.0, 2.0), light(4.0, 2.0), light(5.0, 2.0))

        assertTrue(trigger.shouldTrigger(context(lights = frames)))
        assertFalse(AutofocusTrigger.AfterHfrIncrease(sampleSize = 3).shouldTrigger(context(lights = frames, last = autofocus(1.5))))
    }

    @Test
    fun theHfrTrendOfTheCurrentFilter() {
        val frames =
            listOf(light(1.0, 2.0, "LP"), light(2.0, 2.0), light(3.0, 2.4, "LP"), light(4.0, 2.0), light(5.0, 2.8, "LP"), light(6.0, 2.0))

        assertFalse(AutofocusTrigger.AfterHfrIncrease().shouldTrigger(context(filter = "IR", lights = frames)))
        assertTrue(AutofocusTrigger.AfterHfrIncrease().shouldTrigger(context(filter = "LP", lights = frames)))
        assertTrue(AutofocusTrigger.AfterHfrIncrease(perFilter = false).shouldTrigger(context(filter = "IR", lights = frames)))
    }

    @Test
    fun framesWithoutStarsDoNotCount() {
        val frames = listOf(light(1.0, 2.0), light(2.0, 0.0), light(3.0, 2.3), light(4.0, Double.NaN))

        assertFalse(AutofocusTrigger.AfterHfrIncrease().shouldTrigger(context(lights = frames)))
    }

    @Test
    fun timeCountsFromTheStartThenFromTheLastAutofocus() {
        val trigger = AutofocusTrigger.AfterTime(minutes = 30.0)
        trigger.start(context())

        assertFalse(trigger.shouldTrigger(context(after = 29.minutes)))
        assertTrue(trigger.shouldTrigger(context(after = 30.minutes)))
        assertFalse(trigger.shouldTrigger(context(after = 60.minutes, last = autofocus(40.0))))
        assertTrue(trigger.shouldTrigger(context(after = 70.minutes, last = autofocus(40.0))))
    }
}
