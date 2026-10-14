// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A mount: ITelescopeV3 and V4.
 *
 * Right ascension is in hours and every other angle in degrees, in the mount's
 * [TelescopeCapabilities.equatorialSystem]. Rates are in degrees per second.
 */
@Suppress("TooManyFunctions") // One function per member of the ASCOM interface.
class Telescope(client: AlpacaClient, number: Int) : AlpacaDevice(client, DeviceType.Telescope, number) {
    /** What the mount can do. It does not change while connected, so read it once. */
    suspend fun capabilities(): TelescopeCapabilities = TelescopeCapabilities(
        alignmentMode = optional { ascomEnum<AlignmentMode>(int("alignmentmode")) },
        equatorialSystem = optional { ascomEnum<EquatorialSystem>(int("equatorialsystem")) } ?: EquatorialSystem.Other,
        canFindHome = bool("canfindhome"),
        canPark = bool("canpark"),
        canUnpark = bool("canunpark"),
        canSetPark = bool("cansetpark"),
        canPulseGuide = bool("canpulseguide"),
        canSetTracking = bool("cansettracking"),
        canSlewAsync = bool("canslewasync"),
        canSync = bool("cansync"),
        movableAxes = TelescopeAxis.entries.filter { bool("canmoveaxis", "Axis" to it.ordinal) }.toSet(),
        trackingRates = array("trackingrates").map { ascomEnum<DriveRate>(it.jsonPrimitive.asInt()) },
    )

    suspend fun rightAscension(): Double = double("rightascension")

    suspend fun declination(): Double = double("declination")

    suspend fun altitude(): Double = double("altitude")

    suspend fun azimuth(): Double = double("azimuth")

    /** Local apparent sidereal time, in hours. */
    suspend fun siderealTime(): Double = double("siderealtime")

    suspend fun slewing(): Boolean = bool("slewing")

    suspend fun atHome(): Boolean = bool("athome")

    suspend fun atPark(): Boolean = bool("atpark")

    suspend fun isPulseGuiding(): Boolean = bool("ispulseguiding")

    /** Null if the mount does not know or does not implement it (the Seestar does not). */
    suspend fun sideOfPier(): PierSide? = optional { int("sideofpier") }?.let { PierSide.entries.getOrNull(it) }

    suspend fun tracking(): Boolean = bool("tracking")

    suspend fun setTracking(on: Boolean) = put("tracking", "Tracking" to on)

    suspend fun trackingRate(): DriveRate = ascomEnum(int("trackingrate"))

    suspend fun setTrackingRate(rate: DriveRate) = put("trackingrate", "TrackingRate" to rate.ordinal)

    suspend fun siteLatitude(): Double = double("sitelatitude")

    suspend fun siteLongitude(): Double = double("sitelongitude")

    /** Metres above sea level, or null if the mount does not implement it (the Seestar does not). */
    suspend fun siteElevation(): Double? = optional { double("siteelevation") }

    suspend fun setSiteLatitude(degrees: Double) = put("sitelatitude", "SiteLatitude" to degrees)

    suspend fun setSiteLongitude(degrees: Double) = put("sitelongitude", "SiteLongitude" to degrees)

    suspend fun setSiteElevation(metres: Double) = put("siteelevation", "SiteElevation" to metres)

    suspend fun utcDate(): Instant = parseUtc(string("utcdate"))

    suspend fun setUtcDate(time: Instant) = put("utcdate", "UTCDate" to formatUtc(time))

    /** Starts a GoTo and returns at once; [slewing] says when it ends. Check the horizon first. */
    suspend fun slewToCoordinatesAsync(rightAscension: Double, declination: Double) =
        put("slewtocoordinatesasync", "RightAscension" to rightAscension, "Declination" to declination)

    suspend fun syncToCoordinates(rightAscension: Double, declination: Double) =
        put("synctocoordinates", "RightAscension" to rightAscension, "Declination" to declination)

    suspend fun abortSlew() = put("abortslew")

    suspend fun park() = put("park")

    suspend fun unpark() = put("unpark")

    suspend fun findHome() = put("findhome")

    suspend fun setPark() = put("setpark")

    /** Moves [axis] at [rate] degrees per second until called again with 0. The rate must be within [axisRates]. */
    suspend fun moveAxis(axis: TelescopeAxis, rate: Double) = put("moveaxis", "Axis" to axis.ordinal, "Rate" to rate)

    /** The rate ranges that [moveAxis] accepts for [axis], in degrees per second. */
    suspend fun axisRates(axis: TelescopeAxis): List<ClosedFloatingPointRange<Double>> =
        array("axisrates", "Axis" to axis.ordinal).map { element ->
            val range = element.jsonObject
            val minimum = range.getValue("Minimum").jsonPrimitive.double
            val maximum = range.getValue("Maximum").jsonPrimitive.double
            minimum..maximum
        }

    suspend fun pulseGuide(direction: GuideDirection, duration: Duration) =
        put("pulseguide", "Direction" to direction.ordinal, "Duration" to duration.inWholeMilliseconds)
}

/** What a mount can do, from its `Can…` properties. Null [alignmentMode] means the mount does not say. */
data class TelescopeCapabilities(
    val alignmentMode: AlignmentMode?,
    val equatorialSystem: EquatorialSystem,
    val canFindHome: Boolean,
    val canPark: Boolean,
    val canUnpark: Boolean,
    val canSetPark: Boolean,
    val canPulseGuide: Boolean,
    val canSetTracking: Boolean,
    val canSlewAsync: Boolean,
    val canSync: Boolean,
    val movableAxes: Set<TelescopeAxis>,
    val trackingRates: List<DriveRate>,
)

/** ASCOM dates are ISO 8601 in UTC, such as `2026-10-04T21:30:00.1234567Z`; some servers leave out the `Z`. */
internal fun parseUtc(text: String): Instant = try {
    Instant.parse(if (text.endsWith("Z") || text.endsWith("z")) text else "${text}Z")
} catch (e: IllegalArgumentException) {
    throw AlpacaProtocolException("Not an ISO 8601 date: $text", e)
}

/** Millisecond precision, which every server parses. */
internal fun formatUtc(time: Instant): String = Instant.fromEpochMilliseconds(time.toEpochMilliseconds()).toString()
