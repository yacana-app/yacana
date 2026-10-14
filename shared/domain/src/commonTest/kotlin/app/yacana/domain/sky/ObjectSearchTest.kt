// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Names outside the catalogue, found on the internet with Sesame and kept on the phone. */
class ObjectSearchTest {
    private val saved = Path(SystemFileSystem.resolve(Path(".")), "build", "found-${Random.nextLong()}.tsv")

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(saved)) SystemFileSystem.delete(saved)
    }

    /** Sesame's own answer for TON 618, as it gave it. */
    private val ton618 =
        """
        # TON 618	#Q9372410
        #=Sc=Simbad (CDS, via client/server):    1     0ms (from cache)
        %@ @1928128
        %C.0 Bla
        %J 187.10402489 +31.47711916 = 12 28 24.966  +31 28 37.63
        %J.E [0.0333 0.0216 90] A 2020yCat.1350....0G
        %M.B 16.15 [~]  D 2010A&A...518A..10V
        %M.V 15.87 [~]  D 2010A&A...518A..10V
        %I.0 Ton  618
        %I 7C 122556.3+314516
        """.trimIndent()

    private val nothing = "# zzqq\t#Q9372417\n#! *** NNNothing found *** \n"

    private val catalogue =
        (
            listOf("M 31,NGC 224", "G", "0.712319", "41.26906", "And", "177.83", "69.66", "35", "3.44", "23.63", "Sb") +
                listOf("Andromeda Galaxy", "Galaxia de Andrómeda")
            ).joinToString("\t")

    @Test
    fun sesamesAnswer() {
        val ton = Sesame.parse(ton618, "TON618")!!

        assertEquals(listOf("Ton 618"), ton.designations, "the name as typed is the same one")
        assertEquals(ObjectType.Quasar, ton.type)
        assertEquals(187.10402489 / 15, ton.position.raHours, 1e-9)
        assertEquals(31.47711916, ton.position.decDeg, 1e-9)
        assertEquals(15.87, ton.magnitude)
        assertNull(Sesame.parse(nothing, "zzqq"))
    }

    @Test
    fun whatIsFoundIsKeptAndSearchedWithoutInternet() = runTest {
        val online = ObjectSearch({ catalogue }, saved, { Sesame.parse(ton618, it) }, backgroundScope)
        online.objects.first { it != null }

        assertTrue(online.search("ton 618").isEmpty())
        assertIs<LookUp.Found>(online.lookUp("TON 618"))
        assertEquals("Ton 618", online.search("ton618").single().designation)

        val offline = ObjectSearch({ catalogue }, saved, null, backgroundScope)
        offline.objects.first { it != null }
        assertEquals("Ton 618", offline.find("TON 618")?.designation)
        assertEquals("M 31", offline.find("M 31")?.designation)
    }

    /** Sesame's answer for M 31, which the catalogue has. */
    private val m31 = "%C.0 AGN\n%J 10.68470833 +41.26875000 = 00 42 44.330  +41 16 07.50\n%I.0 M  31\n"

    /** Sesame's answer for a star, which SIMBAD marks with "* ". */
    private val zeta1Reticuli = "%C.0 PM*\n%J 49.44234773 -62.57532013 = 03 17 46.163  -62 34 31.15\n%M.V 5.52 [~]\n%I.0 * zet01 Ret\n"

    @Test
    fun anObjectTheCatalogueHasIsThatOneAndNotASecond() = runTest {
        val search = ObjectSearch({ catalogue }, saved, { Sesame.parse(m31, it) }, backgroundScope)
        search.objects.first { it != null }

        val found = assertIs<LookUp.Found>(search.lookUp("M31"))

        assertEquals(listOf("M 31", "NGC 224"), found.item.designations)
        assertEquals(1, search.search("m31").size)
        assertFalse(SystemFileSystem.exists(saved), "nothing to keep")
    }

    @Test
    fun objectsKeptTwiceBefore020AreShownOnce() = runTest {
        val twice = listOf(Sesame.parse(m31, "M31")!!, Sesame.parse(ton618, "TON 618")!!)
        SystemFileSystem.sink(saved).buffered().use { sink -> twice.forEach { sink.writeString(Catalogue.line(it) + "\n") } }

        val search = ObjectSearch({ catalogue }, saved, null, backgroundScope)
        val objects = search.objects.first { it != null }!!.objects

        assertEquals(listOf("Ton 618", "M 31"), objects.map { it.designation })
        assertEquals(listOf("M 31", "NGC 224"), search.find("M 31")?.designations)
    }

    @Test
    fun aStarIsNamedWithoutSimbadsMark() {
        val star = Sesame.parse(zeta1Reticuli, "Zeta01 Reticuli")!!

        assertEquals(listOf("zet01 Ret", "Zeta01 Reticuli"), star.designations)
        assertEquals(ObjectType.Star, star.type)
        assertEquals(5.52, star.magnitude)
    }

    /** SIMBAD's answer to `*Ret`, with its error when nothing matches. */
    private val reticulum =
        """
        * alf Ret|063.6061250183542|-62.4738034889939|PM*|3.36
        * zet02 Ret|049.5534120100488|-62.5063624197311|**|5.228
        * tet Ret|064.41779988|-63.25540609|**|
        NAME Horologium-Reticulum Complex|No Coord.|No Coord.|Region|
        """.trimIndent()
    private val nothingLike = "::error::::::::\n[4] Identifier not found in the database : %xyzzyq%\n"

    @Test
    fun simbadsWildcardAnswer() {
        val stars = Sesame.parseLike(reticulum)

        assertEquals(listOf("alf Ret", "zet02 Ret", "tet Ret"), stars.map { it.designation })
        assertEquals(listOf(ObjectType.Star, ObjectType.DoubleStar, ObjectType.DoubleStar), stars.map { it.type })
        assertEquals(63.6061250183542 / 15, stars.first().position.raHours, 1e-9)
        assertEquals(listOf(3.36, 5.228, null), stars.map { it.magnitude })
        assertTrue(Sesame.parseLike(nothingLike).isEmpty())
    }

    @Test
    fun simbadsTableOfNamesAnswer() {
        val tap =
            "main_id\tra\tdec\totype\tv\n" +
                "\"* alf And\"\t2.0969161856756404\t29.090431118059698\t\"a2*\"\t2.06\n" +
                "\"* tet Ret\"\t64.41779988\t-63.25540609\t\"**\"\t\n"

        val stars = Sesame.parseTap(tap)

        assertEquals(listOf("alf And", "tet Ret"), stars.map { it.designation })
        assertEquals(listOf(ObjectType.Star, ObjectType.DoubleStar), stars.map { it.type })
        assertEquals(listOf(2.06, null), stars.map { it.magnitude })
        assertTrue(Sesame.parseTap("main_id\tra\tdec\totype\tv\n").isEmpty())
    }

    @Test
    fun withoutTheExactNameTheOnesLikeItAreShownAndTheOneChosenIsKept() = runTest {
        val like =
            object : NameResolver {
                override suspend fun resolve(name: String) = Sesame.parse(nothing, name)

                override suspend fun like(name: String) = Sesame.parseLike(reticulum) + Sesame.parse(m31, "M31")!!
            }
        val search = ObjectSearch({ catalogue }, saved, like, backgroundScope)
        search.objects.first { it != null }

        val many = assertIs<LookUp.Many>(search.lookUp("reticuli"))

        assertEquals(listOf("alf Ret", "zet02 Ret", "tet Ret", "M 31"), many.items.map { it.designation })
        assertEquals(listOf("M 31", "NGC 224"), many.items.last().designations, "the catalogue's own M 31")
        assertFalse(SystemFileSystem.exists(saved), "none kept until chosen")
        assertEquals("zet02 Ret", search.choose(many.items[1]).designation)
        assertEquals("zet02 Ret", search.find("zet02 Ret")?.designation)
    }

    @Test
    fun notFoundAndNoInternetSaySo() = runTest {
        val search = ObjectSearch({ catalogue }, saved, { Sesame.parse(nothing, it) }, backgroundScope)
        assertEquals(LookUp.NotFound, search.lookUp("zzqq"))

        val offline = ObjectSearch({ catalogue }, saved, { error("Unable to resolve host cds.unistra.fr") }, backgroundScope)
        assertEquals(LookUp.Failed("Unable to resolve host cds.unistra.fr"), offline.lookUp("TON 618"))
    }
}
