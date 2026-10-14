// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class NightAreasTest {
    private val m31 = Target("M 31", 0.712, 41.27, 0.0, listOf(TargetPanel(1, 0.712, 41.27, 0.0)), newTargetExposures(listOf("IR")))
    private val m33 = Target("M 33", 1.564, 30.66, 0.0, listOf(TargetPanel(1, 1.564, 30.66, 0.0)), newTargetExposures(listOf("IR")))
    private val night = tonight(listOf(m31, m33), null, focuser = true)

    @Test
    fun tonightsTargetsAreTheAreaBetweenTheStartAndTheEnd() {
        val areas = night.areas()

        assertEquals(night.root.items.first(), areas.start)
        assertEquals(night.root.items.last(), areas.end)
        assertEquals(listOf("M 31", "M 33"), areas.targets.items.map { (it as Block).name })
        assertEquals(night.root.triggers, areas.triggers)
        // Without conditions, back as the simple editor made it.
        assertEquals(night, areas.toSequence(night.name))
    }

    @Test
    fun targetsThatRepeatKeepABlockOfTheirOwnAndAreNoLongerTheSimpleEditors() {
        val areas = night.areas().let { it.copy(targets = it.targets.copy(conditions = listOf(Condition.Loop(2)))) }

        val sequence = areas.toSequence(night.name)

        assertEquals(3, sequence.root.items.size)
        assertEquals(areas, sequence.areas())
        assertNull(sequence.simpleTargets())
    }

    @Test
    fun aRootWithoutAreasIsAllTargets() {
        val alone = Sequence("M 31", Block("M 31", listOf(GoToTarget())))

        val areas = alone.areas()

        assertEquals(listOf<SequenceItem>(GoToTarget()), areas.targets.items)
        assertEquals(emptyList(), areas.start.items)
    }

    @Test
    fun tonightsHoursAreTheEveningsBeforeMidnightAndTheNextMorningsAfter() {
        val evening = Instant.parse("2026-10-07T21:00:00Z")
        val morning = Instant.parse("2026-10-08T03:00:00Z")

        assertEquals(Instant.parse("2026-10-08T02:00:00Z"), tonightAt(120, evening, TimeZone.UTC))
        assertEquals(Instant.parse("2026-10-07T21:30:00Z"), tonightAt(1290, evening, TimeZone.UTC))
        // After midnight it is still the night that began on the 7th.
        assertEquals(Instant.parse("2026-10-08T02:00:00Z"), tonightAt(120, morning, TimeZone.UTC))
        assertEquals(Instant.parse("2026-10-07T21:30:00Z"), tonightAt(1290, morning, TimeZone.UTC))
        // In Madrid's summer time, two hours ahead.
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), tonightAt(120, evening, TimeZone.of("Europe/Madrid")))
    }
}
