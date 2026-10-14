// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.DriveRate
import app.yacana.alpaca.GuideDirection
import app.yacana.alpaca.Telescope
import app.yacana.alpaca.TelescopeAxis
import app.yacana.alpaca.TelescopeCapabilities
import app.yacana.ports.SkyPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The connected mount: its live state, and commands that respect the horizon limit.
 *
 * Coordinates are in the mount's own system ([TelescopeCapabilities.equatorialSystem]); the
 * Seestar uses JNow.
 */
@Suppress("TooManyFunctions") // One function per mount command.
class MountMediator internal constructor(
    val telescope: Telescope,
    val capabilities: TelescopeCapabilities,
    private val limit: HorizonLimit,
    scope: CoroutineScope,
    clock: Clock,
) {
    private val moving = MutableStateFlow(emptySet<TelescopeAxis>())
    private var wasAllowed = true
    private val poller = Poller(scope, clock) { read().also { stopAtTheHorizon(it) } }

    val state: StateFlow<Live<MountState>> = poller.state

    /**
     * Starts a GoTo, unless the target is below the horizon limit: then it throws
     * [BelowHorizonException] and the mount does not move. The target is checked with the
     * mount's own sidereal time and site.
     */
    suspend fun goTo(rightAscensionHours: Double, declinationDeg: Double) {
        val target = altAz(rightAscensionHours, declinationDeg, telescope.siderealTime(), telescope.siteLatitude())
        if (!limit.allows(target)) throw BelowHorizonException(target, limit.altitudeAt(target.azimuthDeg))
        command { telescope.slewToCoordinatesAsync(rightAscensionHours, declinationDeg) }
    }

    suspend fun sync(rightAscensionHours: Double, declinationDeg: Double) =
        command { telescope.syncToCoordinates(rightAscensionHours, declinationDeg) }

    /** Goes to [position], in the mount's system, and returns once the mount has stopped slewing. */
    suspend fun goToAndWait(position: SkyPosition, timeout: Duration = SLEW_TIMEOUT) {
        goTo(position.raHours, position.decDeg)
        withTimeout(timeout) {
            while (telescope.slewing()) delay(SLEW_POLL)
        }
        poller.refresh()
    }

    /** Where the mount says it points, now, in its own system. */
    suspend fun position(): SkyPosition = SkyPosition(telescope.rightAscension(), telescope.declination())

    /** [j2000] in the mount's coordinate system at [at]. */
    fun toMount(j2000: SkyPosition, at: Instant): SkyPosition = toMountSystem(j2000, capabilities.equatorialSystem, at)

    /** A [position] in the mount's coordinate system, in J2000. */
    fun fromMount(position: SkyPosition, at: Instant): SkyPosition = fromMountSystem(position, capabilities.equatorialSystem, at)

    suspend fun setTracking(on: Boolean) = command { telescope.setTracking(on) }

    suspend fun setTrackingRate(rate: DriveRate) = command { telescope.setTrackingRate(rate) }

    /** Stops any GoTo and any manual move. */
    suspend fun stop() = command {
        moving.value = emptySet()
        telescope.abortSlew()
    }

    suspend fun park() = command { telescope.park() }

    suspend fun unpark() = command { telescope.unpark() }

    /** Parks, and returns once the mount says it is parked. */
    suspend fun parkAndWait(timeout: Duration = SLEW_TIMEOUT) {
        park()
        withTimeout(timeout) {
            while (!telescope.atPark()) delay(SLEW_POLL)
        }
        poller.refresh()
    }

    /** Unparks, and returns once the mount says it is no longer parked. */
    suspend fun unparkAndWait(timeout: Duration = SLEW_TIMEOUT) {
        unpark()
        withTimeout(timeout) {
            while (telescope.atPark()) delay(SLEW_POLL)
        }
        poller.refresh()
    }

    /** Pulse guides towards [direction] for [duration], and returns once the pulse is over. */
    suspend fun pulseGuide(direction: GuideDirection, duration: Duration) {
        telescope.pulseGuide(direction, duration)
        delay(duration)
        withTimeout(duration + SLEW_TIMEOUT) {
            while (telescope.isPulseGuiding()) delay(PULSE_POLL)
        }
    }

    suspend fun findHome() = command { telescope.findHome() }

    /**
     * Moves [axis] at [rate] degrees per second; a rate of 0 stops it. A manual move stops by itself
     * when it takes the mount below the horizon limit. Starting below the limit is allowed, so the
     * mount can always be brought back up.
     */
    suspend fun move(axis: TelescopeAxis, rate: Double) = command {
        telescope.moveAxis(axis, rate)
        moving.update { if (rate == 0.0) it - axis else it + axis }
    }

    private suspend fun read() = MountState(
        rightAscensionHours = telescope.rightAscension(),
        declinationDeg = telescope.declination(),
        altitudeDeg = telescope.altitude(),
        azimuthDeg = telescope.azimuth(),
        siderealTimeHours = telescope.siderealTime(),
        slewing = telescope.slewing(),
        tracking = telescope.tracking(),
        trackingRate = telescope.trackingRate(),
        atPark = telescope.atPark(),
        atHome = telescope.atHome(),
    )

    private suspend fun stopAtTheHorizon(state: MountState) {
        val allowed = limit.allows(AltAz(state.altitudeDeg, state.azimuthDeg))
        val axes = moving.value
        if (wasAllowed && !allowed && axes.isNotEmpty()) {
            axes.forEach { telescope.moveAxis(it, 0.0) }
            moving.update { it - axes }
        }
        wasAllowed = allowed
    }

    private suspend fun command(block: suspend () -> Unit) {
        block()
        poller.refresh()
    }

    private companion object {
        val SLEW_POLL = 500.milliseconds
        val PULSE_POLL = 100.milliseconds

        /** Longer than any GoTo across the sky. */
        val SLEW_TIMEOUT = 5.minutes
    }
}

/** What the mount reports, read every second. Right ascension and sidereal time are in hours, the rest in degrees. */
data class MountState(
    val rightAscensionHours: Double,
    val declinationDeg: Double,
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val siderealTimeHours: Double,
    val slewing: Boolean,
    val tracking: Boolean,
    val trackingRate: DriveRate,
    val atPark: Boolean,
    val atHome: Boolean,
)
