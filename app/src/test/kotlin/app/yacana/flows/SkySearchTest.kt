// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.sky.NameResolver
import app.yacana.domain.sky.Sesame
import app.yacana.domain.sky.SkyObject
import app.yacana.ui.Section
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Sky · list" as it is touched: a name searched in SIMBAD, known as typed, or only like other names. */
class SkySearchTest : FlowTest() {
    /** SIMBAD as it answered: M 31 as typed, and two stars of Reticulum for names like "reticuli". */
    private val simbad =
        object : NameResolver {
            override suspend fun resolve(name: String): SkyObject? = if (name.trim() == "M31") Sesame.parse(M31, name) else null

            override suspend fun like(name: String) = Sesame.parseTap(RETICULUM)
        }

    private fun search(query: String) {
        tap("List")
        type(query)
        tap("Search «$query» in SIMBAD")
    }

    @Test
    fun aNameSimbadDoesNotKnowShowsTheOnesLikeItAndKeepsTheOneChosen() {
        val services = show(Section.Sky, resolver = simbad)

        search("reticuli")
        assertTrue(appears("Without that exact name · 2 in SIMBAD with names like it, the brightest first"))
        assertTrue(appears("Double star · mag 5.2"))
        tap("alf Ret")

        // Kept on the phone off the screen's clock, then its page opens, with what is done with an object.
        assertTrue(eventually { services.objects.find("alf Ret") != null })
        assertTrue(appears("Frame"))
        settle()
        assertFalse(shows("Search «reticuli» in SIMBAD"), "the list is left once its transition ends")
    }

    @Test
    fun anObjectTheCatalogueHasOpensItsOwnPageOnce() {
        val services = show(Section.Sky, resolver = simbad)

        search("M31")

        // The catalogue's M 31, never a second one from SIMBAD.
        assertTrue(appears("Frame"))
        assertFalse(shows("Search «M31» in SIMBAD"))
        assertEquals(1, services.objects.search("m31").count { it.designation == "M 31" })
    }

    private companion object {
        const val M31 = "%C.0 AGN\n%J 10.68470833 +41.26875000 = 00 42 44.330  +41 16 07.50\n%I.0 M  31\n"
        const val RETICULUM =
            "main_id\tra\tdec\totype\tv\n" +
                "\"* alf Ret\"\t63.60612501835416\t-62.47380348899389\t\"PM*\"\t3.36\n" +
                "\"* zet02 Ret\"\t49.5534120100488\t-62.5063624197311\t\"**\"\t5.228\n"
    }
}
