// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The kinds the Sky Atlas filters by, each of several types of the catalogue. */
enum class ObjectGroup { Galaxies, Nebulae, Clusters, Other }

fun ObjectType.group(): ObjectGroup = when (this) {
    ObjectType.Galaxy, ObjectType.Quasar, ObjectType.GalaxyPair, ObjectType.GalaxyTriplet, ObjectType.GalaxyGroup -> ObjectGroup.Galaxies

    ObjectType.PlanetaryNebula, ObjectType.HiiRegion, ObjectType.EmissionNebula, ObjectType.ReflectionNebula, ObjectType.Nebula,
    ObjectType.DarkNebula, ObjectType.SupernovaRemnant, ObjectType.ClusterAndNebula,
    -> ObjectGroup.Nebulae

    ObjectType.OpenCluster, ObjectType.GlobularCluster, ObjectType.StarAssociation -> ObjectGroup.Clusters

    else -> ObjectGroup.Other
}

/**
 * What the Sky Atlas shows: the kinds chosen, all if none; objects from [minSizeArcmin]
 * across, or a tenth of the field's short side if null; down to [maxMagnitude]; and [tonight],
 * above [minAltitudeDeg] for [minHours] of the dark night and [minMoonDeg] from the Moon. Those
 * that [fit] the field, and the [favourites] only, if asked; in [order].
 */
@Serializable
data class AtlasFilters(
    val tonight: Boolean = true,
    val groups: Set<ObjectGroup> = emptySet(),
    val minSizeArcmin: Double? = null,
    val maxMagnitude: Double? = null,
    val minAltitudeDeg: Double = 30.0,
    val minHours: Double = 2.0,
    val minMoonDeg: Double = 0.0,
    val fit: Boolean = false,
    val favourites: Boolean = false,
    val order: AtlasOrder = AtlasOrder.BestTime,
)

/**
 * How the Sky Atlas is sorted, each its natural way: the earliest best time tonight first, the
 * highest tonight, the longest above the altitude asked for, the largest, or the brightest. Those
 * of tonight sort by size without the profile's site.
 */
enum class AtlasOrder { BestTime, Highest, LongestUp, Largest, Brightest }

/**
 * An object of the Sky Atlas: the sine of its altitude at each time of the [AtlasNight], and the
 * time it is highest, [best], an index of those times; -1 without a night.
 */
class AtlasEntry(val item: SkyObject, val sines: FloatArray, val best: Int) {
    /** Its altitude at each time, in degrees. */
    fun altitudes(): List<Double> = sines.map { asin(it.toDouble()) * DEGREES_PER_RADIAN }
}

/**
 * Tonight's dark hours at [site] for the Sky Atlas: the astronomical night, or the nautical one
 * where the Sun does not go so low, or sunset to sunrise, every [step]; the local sidereal time at
 * each, and the Moon in the middle. Each object's altitude comes from them with a sine and a
 * cosine, without precession: a few tenths of a degree, nothing for choosing what to image, and
 * the whole catalogue in milliseconds.
 */
class AtlasNight(val times: List<Instant>, val step: Duration, private val site: Location, val moon: SkyPosition?) {
    private val sinLatitude = sin(site.latitude * RADIANS_PER_DEGREE)
    private val cosLatitude = cos(site.latitude * RADIANS_PER_DEGREE)
    private val sidereal = times.map { Ephemerides.localSiderealDeg(it, site) * RADIANS_PER_DEGREE }
    private val cosSidereal = sidereal.map { cos(it) }
    private val sinSidereal = sidereal.map { sin(it) }

    fun entry(item: SkyObject): AtlasEntry {
        val ra = item.position.raHours * DEGREES_PER_HOUR * RADIANS_PER_DEGREE
        val dec = item.position.decDeg * RADIANS_PER_DEGREE
        val above = sin(dec) * sinLatitude
        val across = cos(dec) * cosLatitude
        val cosRa = cos(ra)
        val sinRa = sin(ra)
        val sines = FloatArray(times.size) { i -> (above + across * (cosSidereal[i] * cosRa + sinSidereal[i] * sinRa)).toFloat() }
        var best = -1
        sines.forEachIndexed { i, value -> if (best < 0 || value > sines[best]) best = i }
        return AtlasEntry(item, sines, best)
    }

    companion object {
        fun of(night: Night, site: Location, step: Duration = 10.minutes): AtlasNight? {
            val dark =
                night.astronomical
                    ?: night.nauticalDusk?.let { dusk -> night.nauticalDawn?.let { dawn -> dusk..<dawn } }
                    ?: night.sunset?.let { set -> night.sunrise?.let { rise -> set..<rise } }
                    ?: return null
            val times = generateSequence(dark.start) { it + step }.takeWhile { it < dark.endExclusive }.toList()
            val middle = dark.start + (dark.endExclusive - dark.start) / 2
            return AtlasNight(times, step, site, Ephemerides.moonJ2000(middle, site))
        }
    }
}

/** The Sky Atlas: the catalogue as the filters leave it, by the time each object is highest tonight. */
object SkyAtlas {
    /**
     * The [objects] the [filters] leave for the profile's [field], with [favourites] and tonight's
     * [night], in the filters' order, and the largest first where they are even; without a night,
     * those of tonight's orders the largest first.
     */
    fun list(
        objects: List<AtlasEntry>,
        night: AtlasNight?,
        filters: AtlasFilters,
        field: Pair<Double, Double>?,
        favourites: Set<String>,
    ): List<AtlasEntry> {
        val minSize = filters.minSizeArcmin ?: field?.let { minOf(it.first, it.second) * ARCMIN_PER_DEGREE / FIELD_SHARE } ?: DEFAULT_SIZE
        val minSine = sin(filters.minAltitudeDeg * RADIANS_PER_DEGREE).toFloat()
        val samples = night?.let { (filters.minHours * MINUTES_PER_HOUR / it.step.inWholeMinutes).toInt() } ?: 0
        val kept =
            objects.filter { entry ->
                looks(entry.item, filters, minSize, field, favourites) &&
                    (!filters.tonight || night == null || tonight(entry, night, filters, minSine, samples))
            }
        val ofTonight = filters.order in setOf(AtlasOrder.BestTime, AtlasOrder.Highest, AtlasOrder.LongestUp)
        return kept.sortedWith(if (night == null && ofTonight) LARGEST else comparator(filters.order, minSine).then(LARGEST))
    }

    /** [order] as a comparator; tonight's from the altitudes, above [minSine] for the hours up. */
    private fun comparator(order: AtlasOrder, minSine: Float): Comparator<AtlasEntry> = when (order) {
        AtlasOrder.BestTime -> compareBy { it.best }

        AtlasOrder.Highest -> compareByDescending { entry -> entry.sines.maxOrNull() ?: -1f }

        AtlasOrder.LongestUp -> compareByDescending { entry -> entry.sines.count { it >= minSine } }

        AtlasOrder.Largest -> LARGEST

        // Those without a magnitude last.
        AtlasOrder.Brightest -> compareBy { it.item.magnitude ?: Double.MAX_VALUE }
    }

    /** Even, the largest first: many are highest as the night starts, setting in the west. */
    private val LARGEST = compareByDescending<AtlasEntry> { it.item.majorArcmin ?: 0.0 }

    /** Whether [item] is of the kinds, size, brightness and fit asked for, and among the favourites if asked. */
    private fun looks(
        item: SkyObject,
        filters: AtlasFilters,
        minSize: Double,
        field: Pair<Double, Double>?,
        favourites: Set<String>,
    ): Boolean {
        val kind = filters.groups.isEmpty() || item.type.group() in filters.groups
        val large = minSize <= 0 || (item.majorArcmin ?: 0.0) >= minSize
        val bright = filters.maxMagnitude == null || item.magnitude == null || item.magnitude <= filters.maxMagnitude
        val fits = !filters.fit || item.panels(field) == 1
        val favourite = !filters.favourites || item.designation in favourites
        return kind && large && bright && fits && favourite
    }

    private fun tonight(entry: AtlasEntry, night: AtlasNight, filters: AtlasFilters, minSine: Float, samples: Int): Boolean {
        val above = entry.sines.count { it >= minSine }
        val moon = night.moon
        val farFromMoon = moon == null || filters.minMoonDeg <= 0 || SkyView.separation(moon, entry.item.position) >= filters.minMoonDeg
        return above > 0 && above >= samples && farFromMoon
    }

    /** The default smallest object: a tenth of the field's short side, 8′ for the Seestar. */
    private const val FIELD_SHARE = 10.0

    /** Without a field, 10′. */
    private const val DEFAULT_SIZE = 10.0
    private const val MINUTES_PER_HOUR = 60.0
}

private const val ARCMIN_PER_DEGREE = 60.0
private const val DEGREES_PER_HOUR = 15.0
private const val RADIANS_PER_DEGREE = PI / 180.0
private const val DEGREES_PER_RADIAN = 180.0 / PI
