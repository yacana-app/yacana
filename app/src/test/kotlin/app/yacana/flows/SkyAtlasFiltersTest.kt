// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.sky.AtlasFilters
import app.yacana.domain.sky.ObjectGroup
import app.yacana.ui.Section
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Sky · Sky Atlas" and its filters as they are touched: chosen in the sheet, kept, and taken off from the list. */
class SkyAtlasFiltersTest : FlowTest() {
    @Test
    fun aFilterChosenInTheSheetStaysOnTheListAfterLeavingTheSection() {
        val services = show(Section.Sky)
        tap("List")

        tapIcon("Filters")
        tap("Up to 10")
        assertEquals(10.0, services.skyBrowsing.filters.value.maxMagnitude)
        tap("Done")
        assertFalse(shows("Reset"), "Done closes the sheet")
        assertTrue(appears("Up to mag 10"), "the list shows the filter that is on")

        // Away to another section and back, the filter is still on.
        tap("Night")
        tap("Sky")
        assertTrue(appears("Up to mag 10"))

        tapIcon("Remove Up to mag 10")
        assertNull(services.skyBrowsing.filters.value.maxMagnitude)
        assertFalse(shows("Up to mag 10"))
    }

    @Test
    fun theFiltersStayAfterAnObjectIsOpenedAndLeftWithBack() {
        val services = show(Section.Sky)
        tap("List")
        tapIcon("Filters")
        tap("Up to 10")
        tap("Done")

        tap("Large Magellanic Cloud")
        assertTrue(appears("Frame"), "the object's page")
        tapIcon("Back")

        assertTrue(appears("Up to mag 10"), "the list still shows the filter")
        assertEquals(10.0, services.skyBrowsing.filters.value.maxMagnitude)
    }

    @Test
    fun resetPutsTheFiltersBackAsTheyWereAtFirst() {
        val services = show(Section.Sky)
        tap("List")
        tapIcon("Filters")
        tap("Galaxies")
        tap("45°")
        assertEquals(AtlasFilters(groups = setOf(ObjectGroup.Galaxies), minAltitudeDeg = 45.0), services.skyBrowsing.filters.value)

        tap("Reset")

        assertEquals(AtlasFilters(), services.skyBrowsing.filters.value)
    }
}
