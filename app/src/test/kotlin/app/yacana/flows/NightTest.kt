// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.profile.Location
import app.yacana.domain.sequence.RunStatus
import app.yacana.ui.Section
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Night" as it is touched: tonight started, paused, resumed and stopped with its buttons. */
class NightTest : FlowTest() {
    @Test
    fun anotherNightShowsOnTheCardFromItsCalendarAndTonightComesBack() {
        val services = show(Section.Night)
        val profile = services.equipment.state.value.active!!
        services.equipment.save(profile.copy(location = Location(40.4, -3.7, 650.0)))
        assertTrue(appears("Sunday, October 4"))

        tapIcon("See another night")
        tap("17")

        assertTrue(appears("Saturday, October 17"))
        tapIcon("See another night")
        tap("Back to tonight")
        assertTrue(appears("Sunday, October 4"))
    }

    @Test
    fun theNightStartsIsPausedResumedAndStopped() {
        val services = show(Section.Night, connected = true)
        val runner = services.sequence

        tap("Start the night")
        assertEquals(RunStatus.Running, runner.state.value?.status)

        tap("Pause")
        assertEquals(RunStatus.Paused, runner.state.value?.status)
        tap("Resume")
        assertEquals(RunStatus.Running, runner.state.value?.status)

        hold("Hold to stop")
        assertTrue(runner.state.value?.status != RunStatus.Running)
    }
}
