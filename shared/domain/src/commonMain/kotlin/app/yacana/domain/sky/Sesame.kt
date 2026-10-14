// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart

/** Finds an object outside the catalogue by its name, on the internet; null if nobody knows it. */
fun interface NameResolver {
    suspend fun resolve(name: String): SkyObject?

    /** The objects whose names are like [name], when nobody knows it as typed; none by default. */
    suspend fun like(name: String): List<SkyObject> = emptyList()
}

/**
 * CDS's Sesame, which asks SIMBAD, NED and VizieR for a name, such as a quasar (TON 618), a star
 * or an object of a catalogue the app does not carry. It needs the internet: [http] must not be
 * bound to the equipment's network, which may have none.
 */
class Sesame(private val http: HttpClient) : NameResolver {
    override suspend fun resolve(name: String): SkyObject? = parse(http.get(URL + name.trim().encodeURLPathPart()).bodyAsText(), name)

    /**
     * SIMBAD's objects with a name like [name], the brightest first, [MOST] at most: a
     * constellation's stars from its table of names, which sorts them, and the name's words with
     * its wildcards, which take any case (SimbadNames).
     */
    override suspend fun like(name: String): List<SkyObject> {
        val queries = SimbadNames.queries(name) ?: return emptyList()
        val stars =
            queries.stars?.let { pattern ->
                parseTap(
                    http.get(TAP) {
                        parameter("REQUEST", "doQuery")
                        parameter("LANG", "ADQL")
                        parameter("FORMAT", "tsv")
                        parameter("QUERY", adql(pattern))
                    }.bodyAsText(),
                )
            }.orEmpty()
        val named = parseLike(http.get(SCRIPT) { parameter("script", script(queries.wildcard)) }.bodyAsText())
        return (stars + named).distinctBy { it.designation }.sortedBy { it.magnitude ?: Double.MAX_VALUE }.take(MOST)
    }

    companion object {
        private const val URL = "https://cds.unistra.fr/cgi-bin/nph-sesame/-oI/SNV?"
        private const val SCRIPT = "https://simbad.cds.unistra.fr/simbad/sim-script"
        private const val TAP = "https://simbad.cds.unistra.fr/simbad/sim-tap/sync"

        /** How many objects a search by a name like it shows. */
        const val MOST = 100

        /** The [MOST] brightest whose names are like [pattern], as SQL's `LIKE`; the pattern holds only SimbadNames' own words. */
        private fun adql(pattern: String) = "SELECT DISTINCT TOP $MOST b.main_id, b.ra, b.dec, b.otype, f.flux AS v " +
            "FROM ident AS i JOIN basic AS b ON i.oidref = b.oid " +
            "LEFT JOIN flux AS f ON f.oidref = b.oid AND f.\"filter\" = 'V' " +
            "WHERE i.id LIKE '${pattern.replace("'", "''")}' ORDER BY v"

        /** SIMBAD's script for [pattern]: each object on a line, its name, place in degrees, type and V magnitude. */
        private fun script(pattern: String) = listOf(
            "output console=off script=off",
            "format object \"%MAIN_ID|%COO(d;A)|%COO(d;D)|%OTYPE(3)|%FLUXLIST(V;F)\"",
            "set limit $MOST",
            "query id wildcard $pattern",
        ).joinToString("\n")

        /** The objects of a wildcard search's answer; none when SIMBAD found nothing, which it answers with an error. */
        fun parseLike(text: String): List<SkyObject> = text.lines().mapNotNull { row(it.split('|')) }

        /** The objects of the table of names' answer, tab-separated after its header, with its text quoted. */
        fun parseTap(text: String): List<SkyObject> = text.lines().drop(1).mapNotNull { line -> row(line.split('\t').map { it.trim('"') }) }

        /** An object from its name, right ascension and declination in degrees, type and V magnitude; null without a place. */
        private fun row(fields: List<String>): SkyObject? {
            if (fields.size < FIELDS) return null
            val ra = fields[RA].trim().toDoubleOrNull()
            val dec = fields[DEC].trim().toDoubleOrNull()
            return if (ra == null || dec == null) {
                null
            } else {
                SkyObject(
                    designations = listOf(withoutPrefix(fields[ID].trim().replace(SPACES, " "))),
                    type = simbadType(fields[TYPE].trim().ifEmpty { null }),
                    position = SkyPosition(ra / DEGREES_PER_HOUR, dec),
                    constellation = "",
                    majorArcmin = null,
                    minorArcmin = null,
                    angleDeg = null,
                    magnitude = fields[MAGNITUDE].trim().toDoubleOrNull(),
                    hubble = null,
                    englishNames = emptyList(),
                    spanishName = null,
                )
            }
        }

        /**
         * Sesame's plain answer: its place in J2000 degrees (`%J`), SIMBAD's type (`%C.0`), the
         * name SIMBAD knows it by (`%I.0`) and its V magnitude (`%M.V`); null if nothing was found.
         */
        fun parse(text: String, query: String): SkyObject? {
            val lines = text.lines()
            fun field(tag: String) = lines.firstOrNull { it.startsWith("$tag ") }?.removePrefix("$tag ")?.trim()
            val place = field("%J")?.split(' ')?.filter { it.isNotEmpty() } ?: return null
            val main = field("%I.0")?.replace(SPACES, " ")?.let(::withoutPrefix)
            val designations = listOfNotNull(main, query.trim().replace(SPACES, " ")).distinctBy(::compact)
            return SkyObject(
                designations = designations,
                type = simbadType(field("%C.0")),
                position = SkyPosition(place[0].toDouble() / DEGREES_PER_HOUR, place[1].toDouble()),
                constellation = "",
                majorArcmin = null,
                minorArcmin = null,
                angleDeg = null,
                magnitude = field("%M.V")?.split(' ')?.firstOrNull()?.toDoubleOrNull(),
                hubble = null,
                englishNames = emptyList(),
                spanishName = null,
            )
        }

        /** SIMBAD's name without the mark it gives stars: "* zet01 Ret" is zet01 Ret, "NAME Betelgeuse" Betelgeuse. */
        private fun withoutPrefix(name: String): String = PREFIXES.firstOrNull { name.startsWith(it) }?.let(name::removePrefix) ?: name

        /** SIMBAD's object type, grouped as the catalogue's: any other kind of star is a star. */
        private fun simbadType(code: String?): ObjectType = when {
            code == null -> ObjectType.Other
            code in SIMBAD_TYPES -> SIMBAD_TYPES.getValue(code)
            code.endsWith("*") || code.endsWith("*?") -> ObjectType.Star
            else -> ObjectType.Other
        }

        private val SIMBAD_TYPES =
            mapOf(
                ObjectType.Quasar to listOf("QSO", "Bla", "BLL", "Q?", "Bz?", "BL?"),
                ObjectType.Galaxy to
                    listOf("G", "GiC", "GiG", "GiP", "BiC", "SBG", "H2G", "EmG", "AGN") +
                    listOf("LIN", "SyG", "Sy1", "Sy2", "rG", "LSB", "bCG", "AG?", "G?"),
                ObjectType.GalaxyPair to listOf("PaG", "IG"),
                ObjectType.GalaxyGroup to listOf("GrG", "CGG", "ClG", "SCG"),
                ObjectType.OpenCluster to listOf("OpC", "Cl*", "C?*"),
                ObjectType.GlobularCluster to listOf("GlC", "Gl?"),
                ObjectType.StarAssociation to listOf("As*"),
                ObjectType.PlanetaryNebula to listOf("PN", "PN?"),
                ObjectType.HiiRegion to listOf("HII"),
                ObjectType.ReflectionNebula to listOf("RNe"),
                ObjectType.DarkNebula to listOf("DNe", "MoC", "cor"),
                ObjectType.SupernovaRemnant to listOf("SNR", "SR?"),
                ObjectType.Nebula to listOf("ISM", "Cld", "GNe", "EmO", "bub"),
                ObjectType.DoubleStar to listOf("**", "SB*", "EB*"),
                ObjectType.Nova to listOf("No*", "NL*"),
            ).flatMap { (type, codes) -> codes.map { it to type } }.toMap()

        private val SPACES = Regex("\\s+")
        private const val DEGREES_PER_HOUR = 15.0
        private const val FIELDS = 5

        /** Where each field is in a row of SIMBAD's answers. */
        private const val ID = 0
        private const val RA = 1
        private const val DEC = 2
        private const val TYPE = 3
        private const val MAGNITUDE = 4
        private val PREFIXES = listOf("** ", "* ", "V* ", "NAME ")
    }
}
