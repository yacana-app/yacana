// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition

/** What kind of object it is, as OpenNGC classes them. */
enum class ObjectType(val code: String) {
    Galaxy("G"),
    Quasar("QSO"),
    GalaxyPair("GPair"),
    GalaxyTriplet("GTrpl"),
    GalaxyGroup("GGroup"),
    OpenCluster("OCl"),
    GlobularCluster("GCl"),
    ClusterAndNebula("Cl+N"),
    StarAssociation("*Ass"),
    PlanetaryNebula("PN"),
    HiiRegion("HII"),
    EmissionNebula("EmN"),
    ReflectionNebula("RfN"),
    Nebula("Neb"),
    DarkNebula("DrkN"),
    SupernovaRemnant("SNR"),
    Star("*"),
    DoubleStar("**"),
    Nova("Nova"),
    Other("Other"),
}

/**
 * A deep-sky object of the catalogue: its [designations], the first the one it is known
 * by (M 31, NGC 7000…); its J2000 place; its size in arcminutes, magnitude and Hubble type when
 * known; and its common names, in English and in Spanish.
 */
data class SkyObject(
    val designations: List<String>,
    val type: ObjectType,
    val position: SkyPosition,
    val constellation: String,
    val majorArcmin: Double?,
    val minorArcmin: Double?,
    val angleDeg: Double?,
    val magnitude: Double?,
    val hubble: String?,
    val englishNames: List<String>,
    val spanishName: String?,
) {
    val designation get() = designations.first()
}

/**
 * The app's own catalogue of deep-sky objects: OpenNGC's NGC and IC objects, the
 * Messier ones and its addendum, read once into memory, and searched by designation and by name
 * without internet.
 */
class Catalogue(val objects: List<SkyObject>) {
    private val keys =
        objects.map { item ->
            Keys(item.designations.map(::compact), (item.englishNames + listOfNotNull(item.spanishName)).map(::words))
        }

    /** The object known by [designation], such as "M 31" or "ngc224". */
    fun find(designation: String): SkyObject? {
        val wanted = compact(designation)
        return objects.indices.firstOrNull { wanted in keys[it].designations }?.let(objects::get)
    }

    /**
     * The objects that [query] names, best first: the one whose designation it is, then those whose
     * designation starts with it (M 3 brings M 30 to M 39), then those with a name whose words start
     * with its words ("velo" finds both Veils, "andromeda" the galaxy), those it names whole first
     * ("cabeza de caballo" the Horsehead before the Blue Horsehead); the brighter first in each, and
     * the larger where they are even.
     */
    fun search(query: String, limit: Int = LIMIT): List<SkyObject> {
        val wanted = compact(query)
        val queryWords = words(query)
        if (wanted.isEmpty()) return emptyList()
        val found = mutableListOf<Pair<Int, Int>>()
        keys.forEachIndexed { index, key ->
            val rank =
                when {
                    wanted in key.designations -> EXACT
                    key.designations.any { it.startsWith(wanted) && it.getOrNull(wanted.length)?.isDigit() != false } -> PREFIX
                    key.names.any { name -> named(name, queryWords) && whole(name, queryWords) } -> WHOLE_NAME
                    key.names.any { name -> named(name, queryWords) } -> NAME
                    else -> null
                }
            if (rank != null) found += rank to index
        }
        return found
            .sortedWith(
                compareBy({ it.first }, { objects[it.second].magnitude ?: Double.MAX_VALUE }, { -(objects[it.second].majorArcmin ?: 0.0) }),
            )
            .take(limit)
            .map { objects[it.second] }
    }

    private class Keys(val designations: List<String>, val names: List<List<String>>)

    /** Whether each of the [query]'s words starts one of the [name]'s. */
    private fun named(name: List<String>, query: List<String>) = query.all { word -> name.any { it.startsWith(word) } }

    /** Whether the [query] says all of the [name] but its common words, as "nebulosa" or "de". */
    private fun whole(name: List<String>, query: List<String>) =
        name.filter { it !in COMMON_WORDS }.all { part -> query.any { part.startsWith(it) } }

    companion object {
        /** The catalogue as `catalog/build.py` writes it: a line per object, tab-separated, after the comments. */
        fun parse(text: String): Catalogue = Catalogue(
            text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map(::row)
                .toList(),
        )

        /** [item] as a line of the catalogue's file, the inverse of [parse]. */
        fun line(item: SkyObject): String = listOf(
            item.designations.joinToString(","),
            item.type.code,
            item.position.raHours.toString(),
            item.position.decDeg.toString(),
            item.constellation,
            item.majorArcmin?.toString().orEmpty(),
            item.minorArcmin?.toString().orEmpty(),
            item.angleDeg?.toString().orEmpty(),
            item.magnitude?.toString().orEmpty(),
            "",
            item.hubble.orEmpty(),
            item.englishNames.joinToString(","),
            item.spanishName.orEmpty(),
        ).joinToString("	")

        private fun row(line: String): SkyObject {
            val cells = line.split('\t')
            fun cell(column: Int) = cells.getOrNull(column)?.ifBlank { null }
            return SkyObject(
                designations = cells[0].split(','),
                type = TYPES[cells[1]] ?: ObjectType.Other,
                position = SkyPosition(cells[RA].toDouble(), cells[DEC].toDouble()),
                constellation = cells[CONSTELLATION],
                majorArcmin = cell(MAJOR)?.toDoubleOrNull(),
                minorArcmin = cell(MINOR)?.toDoubleOrNull(),
                angleDeg = cell(ANGLE)?.toDoubleOrNull(),
                magnitude = cell(MAGNITUDE)?.toDoubleOrNull(),
                hubble = cell(HUBBLE),
                englishNames = cell(NAMES)?.split(',').orEmpty(),
                spanishName = cell(SPANISH),
            )
        }

        private val TYPES = ObjectType.entries.associateBy { it.code }
        private const val RA = 2
        private const val DEC = 3
        private const val CONSTELLATION = 4
        private const val MAJOR = 5
        private const val MINOR = 6
        private const val ANGLE = 7
        private const val MAGNITUDE = 8
        private const val HUBBLE = 10
        private const val NAMES = 11
        private const val SPANISH = 12
        private const val LIMIT = 30
        private const val EXACT = 0
        private const val PREFIX = 1
        private const val WHOLE_NAME = 2
        private const val NAME = 3

        /** The words of names that say what an object is rather than which. */
        private val COMMON_WORDS =
            setOf("nebula", "nebulosa", "galaxy", "galaxia", "cluster", "cumulo", "the", "of", "de", "del", "la", "el", "los", "las")
    }
}

/**
 * A designation as it is compared: lower case, without spaces, dashes or dots, "messier" as "m",
 * and numbers without leading zeros, so "M 31", "m31", "Messier 31" and "NGC 0224" match.
 */
internal fun compact(text: String): String = fold(text).replace("messier", "m").filter { it.isLetterOrDigit() }.replace(LEADING_ZEROS, "$1")

/** A name's words, folded: "Galaxia de Andrómeda" is galaxia, de, andromeda. */
internal fun words(text: String): List<String> = fold(text).split(' ', '-', ',', '\'', '.').filter { it.isNotEmpty() }

/** Lower case without accents: what one types matches what is written. */
internal fun fold(text: String): String = text.lowercase().map { ACCENTS[it] ?: it }.joinToString("")

private val LEADING_ZEROS = Regex("(?<!\\d)0+(\\d)")

private val ACCENTS =
    mapOf(
        'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e', 'í' to 'i', 'ì' to 'i',
        'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
        'ñ' to 'n', 'ç' to 'c',
    )
