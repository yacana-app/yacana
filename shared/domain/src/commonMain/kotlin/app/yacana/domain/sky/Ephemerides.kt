// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.equipment.apparentPlace
import app.yacana.domain.equipment.separationArcsec
import app.yacana.domain.profile.Location
import app.yacana.ports.SkyPosition
import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.searchAltitude
import io.github.cosinekitty.astronomy.searchRiseSet
import io.github.cosinekitty.astronomy.siderealTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Where an object is at [at]: degrees above the horizon, and of azimuth from north through east. */
data class TrackPoint(val at: Instant, val altitudeDeg: Double, val azimuthDeg: Double)

/**
 * Where the Sun or the Moon is for an observer: its apparent place of date as seen from the site,
 * and its altitude and azimuth (from north through east) without refraction, in degrees.
 */
data class BodyPlace(val position: SkyPosition, val altitudeDeg: Double, val azimuthDeg: Double)

/**
 * The night that starts on the evening of [evening] at a site, from local noon to the next local
 * noon: sunset, the end of each twilight, their starts in the morning and sunrise; when
 * the Moon is up, and how much of it is lit at the middle of the night (0 to 1). An event that
 * does not happen that night, such as the end of astronomical twilight in a northern summer, is null.
 */
data class Night(
    val evening: LocalDate,
    val sunset: Instant?,
    val civilDusk: Instant?,
    val nauticalDusk: Instant?,
    val astronomicalDusk: Instant?,
    val astronomicalDawn: Instant?,
    val nauticalDawn: Instant?,
    val civilDawn: Instant?,
    val sunrise: Instant?,
    /** The first moonrise and moonset in the night's day, from noon to noon. */
    val moonrise: Instant?,
    val moonset: Instant?,
    /** When the Moon is above the horizon, from noon to noon. */
    val moonUp: List<OpenEndRange<Instant>>,
    val moonIllumination: Double,
) {
    /** The astronomical night, with the Sun more than 18° below the horizon; null if it never gets so low. */
    val astronomical: OpenEndRange<Instant>?
        get() = if (astronomicalDusk != null && astronomicalDawn != null) astronomicalDusk..<astronomicalDawn else null

    /** The astronomical night without the Moon above the horizon. */
    val moonlessDark: List<OpenEndRange<Instant>>
        get() {
            val night = astronomical ?: return emptyList()
            var free = listOf(night)
            for (moon in moonUp) {
                free =
                    free.flatMap { part ->
                        listOfNotNull(
                            (part.start..<minOf(part.endExclusive, moon.start)).takeIf { it.start < it.endExclusive },
                            (maxOf(part.start, moon.endExclusive)..<part.endExclusive).takeIf { it.start < it.endExclusive },
                        )
                    }
            }
            return free
        }
}

/** The Sun and the Moon, computed on the phone with Astronomy Engine. */
@Suppress("TooManyFunctions") // The Sun's, the Moon's and the objects' places for the site, in one place.
object Ephemerides {
    fun sun(at: Instant, site: Location) = place(Body.Sun, at, site)

    fun moon(at: Instant, site: Location) = place(Body.Moon, at, site)

    /** How much of the Moon's disc is lit, from 0 (new) to 1 (full). */
    fun moonIllumination(at: Instant): Double = illumination(Body.Moon, at.time).phaseFraction

    /** The angle between the Moon and [target], in J2000, as seen from the site, in degrees. */
    fun moonDistance(target: SkyPosition, at: Instant, site: Location): Double =
        separationArcsec(moonJ2000(at, site), target) / ARCSEC_PER_DEGREE

    /** The Moon's place in J2000, as the catalogue's objects, seen from the site. */
    fun moonJ2000(at: Instant, site: Location): SkyPosition {
        val moon = equator(Body.Moon, at.time, site.observer, EquatorEpoch.J2000, Aberration.Corrected)
        return SkyPosition(moon.ra, moon.dec)
    }

    /** The local sidereal time at the site, as an angle in degrees: the right ascension on the meridian. */
    fun localSiderealDeg(at: Instant, site: Location): Double = (siderealTime(at.time) * DEGREES_PER_HOUR + site.longitude).mod(FULL_TURN)

    /** The altitude of [target], in J2000, above the site's horizon without refraction, in degrees. */
    fun altitude(target: SkyPosition, at: Instant, site: Location): Double = point(target, at, site).altitudeDeg

    /**
     * [target]'s altitude through the night, from sunset to sunrise every [step]; the whole day
     * from noon to noon if the Sun does not set or rise.
     */
    fun altitudes(target: SkyPosition, night: Night, site: Location, step: Duration = 10.minutes): List<Pair<Instant, Double>> =
        track(target, night, site, step).map { it.at to it.altitudeDeg }

    /** As [altitudes], with the azimuth too: where it is over the horizon of the site, as trees and buildings hide it. */
    fun track(target: SkyPosition, night: Night, site: Location, step: Duration = 10.minutes): List<TrackPoint> {
        val start = night.sunset ?: night.evening.atTime(LocalTime(NOON, 0)).toInstant(TimeZone.UTC)
        val end = night.sunrise?.takeIf { it > start } ?: (start + 1.days)
        return generateSequence(start) { it + step }.takeWhile { it <= end }.map { point(target, it, site) }.toList()
    }

    private fun point(target: SkyPosition, at: Instant, site: Location): TrackPoint {
        val place = apparentPlace(target, at)
        val local = horizon(at.time, site.observer, place.raHours, place.decDeg, Refraction.None)
        return TrackPoint(at, local.altitude, local.azimuth)
    }

    fun night(evening: LocalDate, site: Location): Night {
        // Local mean noon, from the longitude: the night lies between it and the next one.
        val start = evening.atTime(LocalTime(NOON, 0)).toInstant(TimeZone.UTC) - (site.longitude / DEGREES_PER_HOUR).hours
        val end = start + 1.days
        val observer = site.observer
        fun sun(direction: Direction, altitude: Double?) = when (altitude) {
            null -> searchRiseSet(Body.Sun, observer, direction, start.time, 1.0)
            else -> searchAltitude(Body.Sun, observer, direction, start.time, 1.0, altitude)
        }?.instant
        val moonUp = moonUp(observer, start, end)
        return Night(
            evening = evening,
            sunset = sun(Direction.Set, null),
            civilDusk = sun(Direction.Set, CIVIL),
            nauticalDusk = sun(Direction.Set, NAUTICAL),
            astronomicalDusk = sun(Direction.Set, ASTRONOMICAL),
            astronomicalDawn = sun(Direction.Rise, ASTRONOMICAL),
            nauticalDawn = sun(Direction.Rise, NAUTICAL),
            civilDawn = sun(Direction.Rise, CIVIL),
            sunrise = sun(Direction.Rise, null),
            moonrise = searchRiseSet(Body.Moon, observer, Direction.Rise, start.time, 1.0)?.instant,
            moonset = searchRiseSet(Body.Moon, observer, Direction.Set, start.time, 1.0)?.instant,
            moonUp = moonUp,
            moonIllumination = moonIllumination(start + 1.days / 2),
        )
    }

    /** When the Moon is up between [start] and [end]: from each rise to the set after it. */
    private fun moonUp(observer: Observer, start: Instant, end: Instant): List<OpenEndRange<Instant>> {
        fun next(direction: Direction, from: Instant): Instant? =
            searchRiseSet(Body.Moon, observer, direction, from.time, (end - from).days)?.instant?.takeIf { it < end }
        // Up at the start if it sets before it rises, or stays up all day.
        val firstRise = next(Direction.Rise, start)
        val firstSet = next(Direction.Set, start)
        val upAtStart =
            when {
                firstSet != null -> firstRise == null || firstSet < firstRise
                firstRise != null -> false
                else -> place(Body.Moon, start, observer).altitudeDeg > 0
            }
        var upSince = if (upAtStart) start else null
        var from = start
        val up = mutableListOf<OpenEndRange<Instant>>()
        while (true) {
            val since = upSince
            val event = next(if (since == null) Direction.Rise else Direction.Set, from) ?: break
            if (since == null) upSince = event else up += since..<event.also { upSince = null }
            from = event + STEP_PAST
        }
        upSince?.let { up += it..<end }
        return up
    }

    private fun place(body: Body, at: Instant, site: Location) = place(body, at, site.observer)

    private fun place(body: Body, at: Instant, observer: Observer): BodyPlace {
        val time = at.time
        val equatorial = equator(body, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        val horizontal = horizon(time, observer, equatorial.ra, equatorial.dec, Refraction.None)
        return BodyPlace(SkyPosition(equatorial.ra, equatorial.dec), horizontal.altitude, horizontal.azimuth)
    }

    private val Location.observer get() = Observer(latitude, longitude, elevationM)

    private val Instant.time get() = Time.fromMillisecondsSince1970(toEpochMilliseconds())

    private val Time.instant get() = Instant.fromEpochMilliseconds(toMillisecondsSince1970())

    private val Duration.days get() = inWholeMilliseconds / MILLISECONDS_PER_DAY

    private const val CIVIL = -6.0
    private const val NAUTICAL = -12.0
    private const val ASTRONOMICAL = -18.0
    private const val NOON = 12
    private const val DEGREES_PER_HOUR = 15.0
    private const val FULL_TURN = 360.0
    private const val ARCSEC_PER_DEGREE = 3600.0
    private const val MILLISECONDS_PER_DAY = 86_400_000.0

    /** Past an event, so that the next search does not find it again. */
    private val STEP_PAST = 1.seconds
}
