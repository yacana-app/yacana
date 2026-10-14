// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.TimeSource

/** The app's catalogue as `catalog/build.py` makes it, and the search on it. */
class CatalogueTest {
    private val catalogue = Catalogue.parse(SystemFileSystem.source(Path(CATALOGUE)).buffered().use { it.readString() })

    private fun first(query: String) = catalogue.search(query).firstOrNull()?.designation

    @Test
    fun allOfMessierNgcAndIc() {
        assertTrue(catalogue.objects.size > 13_000)
        (1..110).forEach { assertNotNull(catalogue.find("M $it"), "M $it") }
        assertEquals("Galaxia de Andrómeda", catalogue.find("M 31")?.spanishName)
        assertEquals(listOf("Andromeda Galaxy"), catalogue.find("M 31")?.englishNames)
    }

    @Test
    fun designationsAsTheyAreTyped() {
        listOf("M 31", "m31", "Messier 31", "NGC 224", "ngc0224", "UGC 454").forEach { assertEquals("M 31", first(it), it) }
        assertEquals("NGC 7000", first("C 20"), "Caldwell")
        assertEquals("C 9", first("sh2-155"), "Sharpless")
        assertEquals("NGC 281", first("IC 11"), "a duplicate is another name of the object it repeats")
        assertEquals("M 101", first("M 102"))
    }

    @Test
    fun theExactOneFirstThenTheBrightestThatStartLikeIt() {
        val found = catalogue.search("M3").map { it.designation }

        assertEquals("M 3", found.first())
        assertEquals("M 31", found[1], "the brightest of M 30 to M 39")
        assertTrue(found.none { it == "M 300" })
        assertTrue("M 13" !in found, "M3 does not find M 13")
    }

    @Test
    fun namesInSpanishAndEnglish() {
        assertEquals("M 31", first("andromeda"))
        assertEquals("M 31", first("Andrómeda"))
        assertEquals("M 45", first("pléyades"))
        assertEquals("M 45", first("pleiades"))
        assertEquals(setOf("NGC 6960", "NGC 6992"), catalogue.search("velo").take(2).map { it.designation }.toSet())
        assertEquals("NGC 6960", first("velo occ"))
        assertEquals("NGC 7000", first("norteamérica"))
        assertEquals(
            listOf("IC 434", "B 33"),
            catalogue.search("cabeza de caballo").take(2).map {
                it.designation
            },
            "the Horsehead before the Blue one",
        )
    }

    @Test
    fun wellKnownObjectsHaveTheirNames() {
        val rosette = assertNotNull(catalogue.find("NGC 2239"))
        assertEquals("Cúmulo de la Roseta", rosette.spanishName, "a part of a named object takes its name")
        assertEquals(listOf("Satellite Cluster", "Rosette Nebula"), rosette.englishNames)
        assertEquals("Nebulosa de la Gaviota", catalogue.find("IC 2177")?.spanishName)
        assertEquals("Horsehead Nebula", catalogue.find("IC 434")?.englishNames?.first())
        assertEquals("Running Man Nebula", catalogue.find("NGC 1977")?.englishNames?.first(), "without OpenNGC's \"the\"")
        assertEquals("IC 2177", first("gaviota"))
        assertTrue("NGC 2239" in catalogue.search("roseta").map { it.designation })
    }

    @Test
    fun aSearchTakesMilliseconds() {
        val queries = listOf("M 31", "velo", "ngc 7", "andromeda", "ic 4", "zzz")
        catalogue.search("warm up")
        val started = TimeSource.Monotonic.markNow()
        repeat(100) { queries.forEach(catalogue::search) }
        val each = started.elapsedNow() / (100 * queries.size)
        assertTrue(each.inWholeMilliseconds < 50, "$each a search")
    }

    @Test
    fun polarisStandsAtTheSitesLatitude() {
        val site = Location(28.7606, -17.8816, 2396.0)
        val polaris = SkyPosition(2.530301, 89.264109)

        assertEquals(site.latitude, Ephemerides.altitude(polaris, Instant.parse("2026-10-03T01:11:00Z"), site), 1.0)
    }

    private companion object {
        /** The catalogue the app carries, from the domain's folder, where the tests run. */
        const val CATALOGUE = "../ui/src/commonMain/composeResources/files/catalogue.tsv"
    }
}
