// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.alpaca.SwitchInfo
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.Pace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TonightTest {
    private val heater = SwitchInfo(0, "Dew heater", "", canWrite = true, min = 0.0, max = 1.0, step = 1.0)
    private val ir = Exposures(count = 45, exposureSeconds = 60.0, filter = "IR", gain = 80, ditherEvery = 3)
    private val lp = ir.copy(count = 15, filter = "LP", exposureSeconds = 120.0)
    private val m31 =
        Target("M 31", 0.712, 41.27, 93.2, listOf(TargetPanel(1, 0.70, 41.0, 93.2), TargetPanel(2, 0.72, 41.5, 93.2)), listOf(ir, lp))
    private val m33 = Target("M 33", 1.564, 30.66, 93.2, listOf(TargetPanel(1, 1.564, 30.66, 93.2)), listOf(ir))

    @Test
    fun itStartsTakesEachTargetAndEnds() {
        val root = tonight(listOf(m31, m33), heater, focuser = true).root

        val blocks = root.items.map { it as Block }
        val (start, mosaic, single) = blocks
        val end = blocks.last()
        assertEquals(listOf(Unpark, SetSwitch("Dew heater", 1.0)), start.items)
        assertEquals(listOf(Park, SetSwitch("Dew heater", 0.0)), end.items.takeLast(2))
        assertEquals(listOf(Trigger.AutofocusAfterTemperature(1.0)), root.triggers)
        // Each panel of a mosaic is a block of its own, named as NINA names it, with the target's exposures.
        assertEquals(listOf("M 31 Panel 1", "M 31 Panel 2"), mosaic.items.map { (it as Block).target?.name })
        assertEquals(SkyTarget("M 31 Panel 2", 0.72, 41.5, 93.2), (mosaic.items[1] as Block).target)
        // The mosaic keeps its own centre, to open again as it was framed.
        assertEquals(SkyTarget("M 31", 0.712, 41.27, 93.2), mosaic.target)
        assertEquals(listOf(GoToTarget(centre = true), ir, lp), (mosaic.items[0] as Block).items)
        assertEquals(SkyTarget("M 33", 1.564, 30.66, 93.2), single.target)
    }

    @Test
    fun aNewPlanRecentresInEachTargetsBlockAndReadsItBack() {
        val sequence = tonight(listOf(m31, m33), heater, focuser = true)

        val (mosaic, single) = sequence.root.items.drop(1).dropLast(1).map { it as Block }
        // A target's trigger, as the advanced editor and N.I.N.A. have it: the mosaic's panels go by it too.
        assertEquals(listOf(Trigger.RecentreAfterDrift(1.0)), mosaic.triggers)
        assertEquals(listOf(Trigger.RecentreAfterDrift(1.0)), single.triggers)
        assertEquals(DEFAULT_TRIGGERS, sequence.planTriggers())
        assertEquals(emptyList(), sequence.review(Kit()), "nothing to say about it")
    }

    @Test
    fun theDewHeaterIsLeftOffOrTurnedToItsPower() {
        val off = tonight(listOf(m33), heater, focuser = true, heaterRule = HeaterRule(on = false)).root
        assertTrue(off.items.flatMap { (it as Block).items }.none { it is SetSwitch }, "neither on at the start nor off at the end")

        // A heater that takes a value goes to that share of its range, on its steps.
        val strip = SwitchInfo(1, "Dew heater", "", canWrite = true, min = 0.0, max = 100.0, step = 10.0)
        val half = tonight(listOf(m33), strip, focuser = true, heaterRule = HeaterRule(power = 0.47)).root
        assertEquals(SetSwitch("Dew heater", 50.0), (half.items.first() as Block).items[1])
        assertEquals(SetSwitch("Dew heater", 0.0), (half.items.last() as Block).items.last())
        // On and off only, it is on whatever the power.
        val seestar = tonight(listOf(m33), heater, focuser = true, heaterRule = HeaterRule(power = 0.25)).root
        assertEquals(SetSwitch("Dew heater", 1.0), (seestar.items.first() as Block).items[1])
    }

    @Test
    fun theDarksAndBiasKeepEachSettingOfTheLights() {
        val end = tonight(listOf(m31, m33), null, focuser = false).root.items.last() as Block

        val calibration = end.items.filterIsInstance<Exposures>()
        // 60 s and 120 s darks; one set of bias, as both have gain 80.
        assertEquals(listOf(60.0, 120.0), calibration.filter { it.type == FrameType.Dark }.map { it.exposureSeconds })
        assertEquals(1, calibration.count { it.type == FrameType.Bias })
        calibration.forEach {
            assertEquals(80, it.gain)
            assertEquals(TONIGHT_CALIBRATION, it.count)
        }
    }

    @Test
    fun theDarkRulesPutDarksAtTheStartTooAndTheirTriggerStaysWithoutAFocuser() {
        val darksTrigger = Trigger.DarksAfterTemperature(3.0, 5)
        val sequence =
            tonight(
                listOf(m31),
                null,
                focuser = false,
                DEFAULT_TRIGGERS + darksTrigger,
                DarkRules(atStart = true, atEnd = false, count = 5),
            )
        val root = sequence.root

        val start = (root.items.first() as Block).items
        assertEquals(listOf(60.0, 120.0), start.filterIsInstance<Exposures>().map { it.exposureSeconds })
        assertTrue(start.filterIsInstance<Exposures>().all { it.type == FrameType.Dark && it.count == 5 })
        assertEquals(listOf(Park), (root.items.last() as Block).items, "no darks or bias at the end")
        assertEquals(listOf<Trigger>(darksTrigger), root.triggers, "the autofocus ones need a focuser; the darks do not")
        assertEquals(DarkRules(atStart = true, atEnd = false, count = 5), sequence.darkRules())
        assertEquals(DarkRules(), tonight(listOf(m31), null, focuser = false).darkRules())
    }

    @Test
    fun withoutAHeaterOrAFocuserThereIsNoneOfThem() {
        val root = tonight(listOf(m33), null, focuser = false).root

        assertEquals(listOf(Unpark), (root.items.first() as Block).items)
        assertEquals(Park, (root.items.last() as Block).items.last())
        assertEquals(emptyList(), root.triggers)
    }

    @Test
    fun theDewHeaterIsTheSwitchThatSaysSo() {
        assertEquals(heater, dewHeater(listOf(SwitchInfo(1, "Light box", "", true, 0.0, 100.0, 1.0), heater)))
        assertNull(dewHeater(listOf(heater.copy(canWrite = false))))
    }

    @Test
    fun aNewTargetTakesAnHourThroughTheFirstFilterThatLetsTheLightIn() {
        assertEquals(
            listOf(Exposures(NEW_TARGET_FRAMES, NEW_TARGET_SECONDS, "IR", ditherEvery = DITHER_EVERY)),
            newTargetExposures(listOf("Dark", "IR", "LP")),
        )
        assertNull(newTargetExposures(emptyList()).single().filter)
    }

    @Test
    fun theTimelineHasEachTargetAfterTheOtherFromDusk() {
        val dusk = Instant.parse("2026-10-06T19:47:00Z")
        val dawn = Instant.parse("2026-10-07T03:12:00Z")
        val dither = DitherOptions(settleSeconds = 10.0)

        val plan = NightPlan.of(listOf(m33), from = Instant.parse("2026-10-06T15:00:00Z"), dusk, dawn, dither, Pace(frameSeconds = 3.0))

        // 45 frames of 63 s, 14 dithers of 12 s, and a minute to centre.
        assertEquals(dusk, plan.start)
        assertEquals(dusk + 63.seconds * 45 + 12.seconds * 14 + 1.minutes, plan.targets.single().end)
        assertNull(plan.late)
    }

    @Test
    fun thePlanTakesTheEquipmentsMeasuredPaceAndTheAutofocusItCanForesee() {
        val dusk = Instant.parse("2026-10-06T19:47:00Z")
        val target = m33.copy(exposures = listOf(Exposures(count = 30, exposureSeconds = 50.0, filter = "IR", ditherEvery = 3)))
        val pace = Pace(frameSeconds = 10.0, ditherSeconds = 15.0, autofocusSeconds = 200.0, centringSeconds = 40.0)

        val plan = NightPlan.of(listOf(target), dusk, dusk, null, DitherOptions(), pace, listOf(Trigger.AutofocusAfterTime(15.0)))

        // 30 frames of 60 s and 9 dithers of 15 s are 32.25 minutes: two autofocus runs; and 40 s to centre.
        assertEquals(dusk + 60.seconds * 30 + 15.seconds * 9 + 200.seconds * 2 + 40.seconds, plan.targets.single().end)
    }

    @Test
    fun aPlanThatDoesNotFitSaysWhichTargetEndsAfterDawn() {
        val now = Instant.parse("2026-10-06T23:00:00Z")
        val dawn = now + 3.hours
        // M 31's two panels take about 3 h 30 min.
        val plan = NightPlan.of(listOf(m33, m31), from = now, dusk = now - 3.hours, dawn, DitherOptions())

        assertEquals(now, plan.start)
        assertEquals("M 31", plan.late?.name)
        assertTrue(plan.targets[0].end < dawn)
        assertFalse(plan.targets[1].end < dawn)
    }
}
