// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class SkyAtlasTest {
    /** Calar Alto, on an October night. */
    private val site = Location(37.2236, -2.5463, 2168.0)
    private val night = assertNotNull(AtlasNight.of(Ephemerides.night(LocalDate(2026, 10, 7), site), site))
    private val seestar = 1.35 to 2.40

    private fun item(
        name: String,
        raHours: Double,
        decDeg: Double,
        type: ObjectType = ObjectType.Galaxy,
        size: Double? = 60.0,
        mag: Double? = 8.0,
    ) = SkyObject(listOf(name), type, SkyPosition(raHours, decDeg), "And", size, size, null, mag, null, emptyList(), null)

    private val m31 = item("M 31", 0.7123, 41.27, size = 178.0, mag = 3.4)
    private val m33 = item("M 33", 1.5641, 30.66, size = 62.0, mag = 5.8)
    private val m42 = item("M 42", 5.588, -5.39, ObjectType.HiiRegion, 85.0, 4.0)
    private val m57 = item("M 57", 18.893, 33.03, ObjectType.PlanetaryNebula, 1.4, 8.8)
    private val omegaCen = item("NGC 5139", 13.447, -47.48, ObjectType.GlobularCluster, 36.0, 3.9)
    private val all = listOf(m31, m33, m42, m57, omegaCen).map(night::entry)

    @Test
    fun theQuickAltitudeIsTheEphemeridesOneWithinHalfADegree() {
        all.forEach { entry ->
            entry.altitudes().zip(night.times).forEach { (quick, at) ->
                val exact = Ephemerides.altitude(entry.item.position, at, site)
                assertTrue(abs(quick - exact) < 0.5, "${entry.item.designation} at $at: $quick against $exact")
            }
        }
    }

    @Test
    fun tonightForTheSeestarFromCalarAlto() {
        val list = SkyAtlas.list(all, night, AtlasFilters(), seestar, emptySet())

        // M 57 is too small for the field and ω Centauri never gets above 30°; M 42 is best at dawn, last.
        assertEquals(listOf("M 31", "M 33", "M 42"), list.map { it.item.designation })
        assertTrue(list[0].best <= list[1].best && list[1].best <= list[2].best)
    }

    @Test
    fun eachOrderItsNaturalWay() {
        fun names(order: AtlasOrder, on: AtlasNight? = night) =
            SkyAtlas.list(all, on, AtlasFilters(order = order), seestar, emptySet()).map { it.item.designation }

        // From 37° north, M 31 culminates near the zenith, M 33 a little lower and M 42 at under 50°.
        assertEquals(listOf("M 31", "M 33", "M 42"), names(AtlasOrder.Highest))
        assertEquals("M 42", names(AtlasOrder.LongestUp).last(), "the shortest above 30° tonight")
        assertEquals(listOf("M 31", "M 42", "M 33"), names(AtlasOrder.Largest))
        assertEquals(listOf("M 31", "M 42", "M 33"), names(AtlasOrder.Brightest))
        // Without the site, tonight's orders go by size, and ω Centauri is no longer left out as low.
        assertEquals(listOf("M 31", "M 42", "M 33", "NGC 5139"), names(AtlasOrder.Highest, on = null))
    }

    @Test
    fun theFiltersAsTheSheet() {
        fun names(filters: AtlasFilters, favourites: Set<String> = emptySet()) =
            SkyAtlas.list(all, night, filters, seestar, favourites).map { it.item.designation }.toSet()

        assertEquals(setOf("M 31", "M 33", "M 42", "M 57", "NGC 5139"), names(AtlasFilters(tonight = false, minSizeArcmin = 0.0)))
        assertEquals(setOf("M 42", "M 57"), names(AtlasFilters(tonight = false, minSizeArcmin = 0.0, groups = setOf(ObjectGroup.Nebulae))))
        assertEquals(setOf("M 31", "M 42", "NGC 5139"), names(AtlasFilters(tonight = false, minSizeArcmin = 0.0, maxMagnitude = 5.0)))
        assertEquals(setOf("M 33"), names(AtlasFilters(fit = true)))
        assertEquals(setOf("M 31", "M 42"), names(AtlasFilters(favourites = true), setOf("M 31", "M 42", "M 57")))
        assertEquals(setOf("M 31", "M 33"), names(AtlasFilters(minHours = 6.0)))
        assertEquals(setOf("M 31", "M 33", "M 42"), names(AtlasFilters(minHours = 1.0, minAltitudeDeg = 20.0)))
    }

    @Test
    fun aFilteredListOfTheWholeCatalogueInLessThan300Ms() {
        val random = Random(7)
        val catalogue =
            List(14_000) { item("X $it", random.nextDouble(24.0), random.nextDouble(-90.0, 90.0), size = random.nextDouble(0.5, 60.0)) }
        val start = TimeSource.Monotonic.markNow()
        val entries = catalogue.map(night::entry)
        val list = SkyAtlas.list(entries, night, AtlasFilters(groups = setOf(ObjectGroup.Galaxies)), seestar, emptySet())
        val took = start.elapsedNow()

        assertTrue(list.isNotEmpty())
        assertTrue(took.inWholeMilliseconds < 300, "It took $took")
    }
}
