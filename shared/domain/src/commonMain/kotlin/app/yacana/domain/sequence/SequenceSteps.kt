// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.equipment.ConnectedEquipment
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.Dither
import app.yacana.domain.equipment.DitherOffset
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.FilterWheelMediator
import app.yacana.domain.equipment.MountMediator
import app.yacana.domain.imaging.DARK_FILTER
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.Location
import app.yacana.domain.session.Journal
import app.yacana.domain.session.log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The sequence's instructions that only ask the devices: the mount's park
 * and dither, the camera's cooler, the switches, the filter wheel and the focuser.
 */
internal class SequenceSteps(private val equipment: EquipmentController, private val journal: Journal, private val random: Random) {
    fun connected(): ConnectedEquipment =
        (equipment.state.value.connection as? Connection.Connected)?.equipment ?: error("Connect the equipment in Equipment first")

    fun mount(): MountMediator = connected().mount ?: error("The profile has no mount connected")

    /** The instructions that only ask a device: the mount's park and tracking, the cooler, a switch, the wheel and the focuser. */
    suspend fun device(item: SequenceItem) {
        when (item) {
            Unpark -> mount().unparkAndWait()
            Park -> mount().parkAndWait()
            StopTracking -> mount().setTracking(false)
            is CoolCamera -> cool(item)
            WarmCamera -> warm()
            is SetSwitch -> switch(item)
            is SwitchFilter -> selectFilter(item.filter)
            is MoveFocuser -> moveFocuser(item.position)
            else -> Unit
        }
    }

    /** Where the profile's equipment is, for a target's altitude. */
    fun site(): Location = connected().profile.location ?: error("The profile has no location")

    /** The wheel at the filter called [name]. */
    private suspend fun selectFilter(name: String) {
        val wheel = connected().filterWheel ?: error("The profile has no filter wheel connected")
        val position =
            wheel.names.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it >= 0 } ?: error("The filter wheel has no $name")
        // A wheel still turning has no position, and takes no other until it stops.
        wheel.awaitStill()
        if (wheel.wheel.position() != position) {
            wheel.select(position)
            wheel.awaitStill()
        }
    }

    /** The focuser at [position], once it has stopped. */
    private suspend fun moveFocuser(position: Int) {
        val focuser = connected().focuser ?: error("The profile has no focuser connected")
        focuser.moveAndWait(position)
    }

    /** The cooler on at the temperature, waiting until the sensor is within a degree of it; a camera without a cooler is skipped. */
    suspend fun cool(step: CoolCamera) {
        val camera = connected().camera ?: error("The profile has no camera connected")
        if (!camera.info.canSetCcdTemperature) {
            journal.log("sequence_skipped", "item" to step.toString(), "reason" to "the camera has no cooler")
            return
        }
        camera.camera.setTargetCcdTemperature(step.temperatureC)
        camera.camera.setCoolerOn(true)
        val reached =
            withTimeoutOrNull(step.minutes.minutes) {
                while (abs((camera.camera.ccdTemperature() ?: step.temperatureC) - step.temperatureC) > COOLED_WITHIN) delay(COOLER_POLL)
            }
        if (reached == null) journal.log("sequence_cooling_timeout", "temperature" to camera.camera.ccdTemperature())
    }

    /** The cooler off; a camera without a cooler is skipped. */
    suspend fun warm() {
        val camera = connected().camera ?: error("The profile has no camera connected")
        if (camera.info.canSetCcdTemperature) camera.camera.setCoolerOn(false)
    }

    /** The switch with the step's name, such as the Seestar's "Dew heater", set to its value. */
    suspend fun switch(step: SetSwitch) {
        val switches = connected().switches ?: error("The profile has no switches connected")
        val switch =
            switches.switches.firstOrNull { it.name.equals(step.name, ignoreCase = true) }
                ?: error("There is no switch called ${step.name}")
        switches.setValue(switch.id, step.value)
    }

    /**
     * The wheel at the step's filter; darks and bias at the filter that blocks the light, if the
     * wheel has one as the Seestar's; lights without a filter leave that one for the first other.
     * Returns the filter it is at, which the wheel's polled position only tells a moment later.
     */
    suspend fun filter(wheel: FilterWheelMediator, step: Exposures): String? {
        val dark = wheel.names.indexOfFirst { it.equals(DARK_FILTER, ignoreCase = true) }.takeIf { it >= 0 }
        val current = wheel.wheel.position()
        val wanted =
            when {
                !step.type.needsLight -> dark

                step.filter != null ->
                    wheel.names.indexOfFirst { it.equals(step.filter, ignoreCase = true) }.takeIf { it >= 0 }
                        ?: error("The filter wheel has no ${step.filter}")

                current == dark -> wheel.names.indices.first { it != dark }

                else -> null
            }
        if (wanted != null && wanted != current) {
            wheel.select(wanted)
            wheel.awaitStill()
        }
        return (wanted ?: current)?.let { wheel.names.getOrNull(it) }
    }

    /** The sequence's dithers, around where each target was framed: reset as it goes to one. */
    val dithers = Dither(random)

    /** The camera's scale in arcseconds per pixel, from the profile's optics, or 2″ without them. */
    val pixelScale: Double get() = connected().pixelScale()

    /** Dithers without a guider, as N.I.N.A.'s direct guider; returns where the field is now, around where it began. */
    suspend fun dither(options: DitherOptions): DitherOffset = dithers.move(mount(), options, connected().pixelScale())

    private companion object {
        const val COOLED_WITHIN = 1.0
        val COOLER_POLL = 5.seconds
    }
}

/** The camera's scale in arcseconds per pixel, from the profile's optics, or 2″ without them. */
private fun ConnectedEquipment.pixelScale(): Double = camera?.info?.let { profile.optics.pixelScaleArcsec(it.pixelSizeX) } ?: DEFAULT_SCALE

/** Arcseconds per pixel for a camera whose scale the profile does not know. */
private const val DEFAULT_SCALE = 2.0
