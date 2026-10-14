// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorRulesTest {
    private val m31 = SkyTarget("M 31", 0.712, 41.27)

    @Test
    fun whatPointsAtATargetGoesOnlyInsideOne() {
        assertEquals(Blocked.OnlyInATarget, Entry.Centre.blocked(Place.Start, inTarget = false, cooler = null))
        assertEquals(Blocked.OnlyInATarget, Entry.RepeatWhileHigh.blocked(Place.Block, inTarget = false, cooler = null))
        assertEquals(Blocked.OnlyInATarget, Entry.Recentre.blocked(Place.Block, inTarget = false, cooler = null))
        assertNull(Entry.Centre.blocked(Place.Block, inTarget = true, cooler = null))
        assertNull(Entry.Target.blocked(Place.Start, inTarget = false, cooler = null))
        assertEquals(Blocked.AlreadyInATarget, Entry.Target.blocked(Place.Block, inTarget = true, cooler = null))
    }

    @Test
    fun eachPlaceTakesItsOwnKindsAndTheCatalogueSaysWhy() {
        assertEquals(Blocked.EnterAnArea, Entry.UnparkMount.blocked(Place.Night, inTarget = false, cooler = null))
        assertNull(Entry.AutofocusTemperature.blocked(Place.Night, inTarget = false, cooler = null))
        assertEquals(Blocked.NoConditions, Entry.Repeat.blocked(Place.Start, inTarget = false, cooler = null))
        assertNull(Entry.Repeat.blocked(Place.Targets, inTarget = false, cooler = null))
        // Darks in the middle of the night need a filter that blocks the light.
        assertEquals(Blocked.NoDarkFilter, Entry.DarksTemperature.blocked(Place.Night, inTarget = false, cooler = null, darkFilter = false))
        assertNull(Entry.DarksTemperature.blocked(Place.Night, inTarget = false, cooler = null, darkFilter = true))
        assertEquals(Blocked.TriggersForTheNight, Entry.AutofocusFilter.blocked(Place.Targets, inTarget = false, cooler = null))
        assertEquals(Blocked.NoCooler, Entry.Cool.blocked(Place.Start, inTarget = false, cooler = false))
        assertNull(Entry.Cool.blocked(Place.Start, inTarget = false, cooler = null))
    }

    @Test
    fun aMoveSaysWhyItCantGoThere() {
        val centre = GoToTarget()
        val box = Block("Box", listOf(WaitUntilAbove(30.0)))
        val target = Block("M 31", listOf(centre), target = m31)

        assertEquals(Blocked.OnlyInATarget, centre.blockedIn(Place.Start, inTarget = false))
        assertEquals(Blocked.OnlyInATarget, box.blockedIn(Place.Targets, inTarget = false))
        assertNull(box.blockedIn(Place.Block, inTarget = true))
        assertEquals(Blocked.AlreadyInATarget, target.blockedIn(Place.Block, inTarget = true))
        assertNull(target.blockedIn(Place.Targets, inTarget = false))
        assertEquals(Blocked.EnterAnArea, Note("hola").blockedIn(Place.Night, inTarget = false))
    }
}
