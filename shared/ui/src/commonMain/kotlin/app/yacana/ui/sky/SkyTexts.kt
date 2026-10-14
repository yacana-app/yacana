// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.runtime.Composable
import app.yacana.domain.profile.Location
import app.yacana.domain.sky.Ephemerides
import app.yacana.domain.sky.Night
import app.yacana.domain.sky.ObjectType
import app.yacana.domain.sky.SkyObject
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.catalogue_language
import app.yacana.ui.resources.object_type_association
import app.yacana.ui.resources.object_type_cluster_nebula
import app.yacana.ui.resources.object_type_dark
import app.yacana.ui.resources.object_type_double_star
import app.yacana.ui.resources.object_type_emission
import app.yacana.ui.resources.object_type_galaxy
import app.yacana.ui.resources.object_type_galaxy_barred
import app.yacana.ui.resources.object_type_galaxy_elliptical
import app.yacana.ui.resources.object_type_galaxy_group
import app.yacana.ui.resources.object_type_galaxy_irregular
import app.yacana.ui.resources.object_type_galaxy_lenticular
import app.yacana.ui.resources.object_type_galaxy_pair
import app.yacana.ui.resources.object_type_galaxy_spiral
import app.yacana.ui.resources.object_type_galaxy_triplet
import app.yacana.ui.resources.object_type_globular_cluster
import app.yacana.ui.resources.object_type_hii
import app.yacana.ui.resources.object_type_nebula
import app.yacana.ui.resources.object_type_nova
import app.yacana.ui.resources.object_type_open_cluster
import app.yacana.ui.resources.object_type_other
import app.yacana.ui.resources.object_type_planetary
import app.yacana.ui.resources.object_type_quasar
import app.yacana.ui.resources.object_type_reflection
import app.yacana.ui.resources.object_type_snr
import app.yacana.ui.resources.object_type_star
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The object's common name in the app's language, else in the other, else its designation. */
@Composable
internal fun SkyObject.name(): String {
    val english = englishNames.firstOrNull()
    val spanish = stringResource(Res.string.catalogue_language) == "es"
    return (if (spanish) spanishName ?: english else english ?: spanishName) ?: designation
}

/**
 * Its other names, as known in English, and its other designations, beyond those of its [title]:
 * none if it has no others. The Spanish name is its name in Spanish, and only there.
 */
@Composable
internal fun SkyObject.others(): List<String> {
    val shown = name()
    return (englishNames + designations.drop(1)).filter { it != shown }.distinct()
}

/** "M 33 · Triangulum Galaxy", or the designation alone if that is its name. */
@Composable
internal fun SkyObject.title(): String = name().let { if (it == designation) it else "$designation · $it" }

/** What it is: a galaxy by its Hubble type (spiral, barred, elliptical…), or its kind. */
@Composable
internal fun SkyObject.kind(): String = stringResource(
    if (type == ObjectType.Galaxy) galaxyKind(hubble) else TYPES.getValue(type),
)

/**
 * Its size: "70′ × 42′", or in [degrees] from a degree on, "1.2°", as the list shows it; null when
 * the catalogue does not know it.
 */
@Composable
internal fun SkyObject.size(degrees: Boolean = true): String? {
    val major = majorArcmin ?: return null
    val comma = decimalComma()
    return when {
        degrees && major >= ARCMIN_PER_DEGREE -> "${formatDecimal(major / ARCMIN_PER_DEGREE, 1, comma)}°"
        minorArcmin != null && minorArcmin != major -> "${formatArcmin(major, comma)}′ × ${formatArcmin(minorArcmin!!, comma)}′"
        else -> "${formatArcmin(major, comma)}′"
    }
}

private fun formatArcmin(value: Double, comma: Boolean) = formatDecimal(value, if (value < TENTHS_BELOW) 1 else 0, comma)

private fun galaxyKind(hubble: String?): StringResource {
    val code = hubble.orEmpty()
    return when {
        code.startsWith(
            "S0",
        ) || code.startsWith("SA0") || code.startsWith("SB0") || code.startsWith("SAB0") -> Res.string.object_type_galaxy_lenticular

        code.startsWith("SB") -> Res.string.object_type_galaxy_barred

        code.startsWith("S") -> Res.string.object_type_galaxy_spiral

        code.startsWith("E") || code.startsWith("cD") -> Res.string.object_type_galaxy_elliptical

        code.startsWith("I") -> Res.string.object_type_galaxy_irregular

        else -> Res.string.object_type_galaxy
    }
}

private val TYPES =
    mapOf(
        ObjectType.Galaxy to Res.string.object_type_galaxy,
        ObjectType.Quasar to Res.string.object_type_quasar,
        ObjectType.GalaxyPair to Res.string.object_type_galaxy_pair,
        ObjectType.GalaxyTriplet to Res.string.object_type_galaxy_triplet,
        ObjectType.GalaxyGroup to Res.string.object_type_galaxy_group,
        ObjectType.OpenCluster to Res.string.object_type_open_cluster,
        ObjectType.GlobularCluster to Res.string.object_type_globular_cluster,
        ObjectType.ClusterAndNebula to Res.string.object_type_cluster_nebula,
        ObjectType.StarAssociation to Res.string.object_type_association,
        ObjectType.PlanetaryNebula to Res.string.object_type_planetary,
        ObjectType.HiiRegion to Res.string.object_type_hii,
        ObjectType.EmissionNebula to Res.string.object_type_emission,
        ObjectType.ReflectionNebula to Res.string.object_type_reflection,
        ObjectType.Nebula to Res.string.object_type_nebula,
        ObjectType.DarkNebula to Res.string.object_type_dark,
        ObjectType.SupernovaRemnant to Res.string.object_type_snr,
        ObjectType.Star to Res.string.object_type_star,
        ObjectType.DoubleStar to Res.string.object_type_double_star,
        ObjectType.Nova to Res.string.object_type_nova,
        ObjectType.Other to Res.string.object_type_other,
    )

private const val ARCMIN_PER_DEGREE = 60.0

/** Sizes under this many arcminutes keep a decimal. */
private const val TENTHS_BELOW = 10.0

/** The evening whose night it is at [now]: before noon, still last night's. */
internal fun evening(now: Instant, zone: TimeZone): LocalDate {
    val local = now.toLocalDateTime(zone)
    return if (local.hour < NOON) local.date.minus(1, DateTimeUnit.DAY) else local.date
}

/**
 * The night to plan at [site] [now]: the one going on or still to come; in the morning, once the
 * night before is over, the next.
 */
internal fun planNight(now: Instant, zone: TimeZone, site: Location): Night {
    val evening = evening(now, zone)
    val night = Ephemerides.night(evening, site)
    val over = night.astronomicalDawn?.let { it < now } == true
    return if (over) Ephemerides.night(evening.plus(1, DateTimeUnit.DAY), site) else night
}

private const val NOON = 12
