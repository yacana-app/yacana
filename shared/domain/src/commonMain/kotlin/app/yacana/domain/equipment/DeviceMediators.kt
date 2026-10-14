// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.CalibratorStatus
import app.yacana.alpaca.Camera
import app.yacana.alpaca.CameraInfo
import app.yacana.alpaca.CameraState
import app.yacana.alpaca.CoverCalibrator
import app.yacana.alpaca.CoverStatus
import app.yacana.alpaca.FilterWheel
import app.yacana.alpaca.Focuser
import app.yacana.alpaca.FocuserInfo
import app.yacana.alpaca.SafetyMonitor
import app.yacana.alpaca.SwitchDevice
import app.yacana.alpaca.SwitchInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** The connected camera's live state. Capture itself comes with the imaging screen. */
class CameraMediator internal constructor(val camera: Camera, val info: CameraInfo, scope: CoroutineScope, clock: Clock) {
    private val poller =
        Poller(scope, clock) {
            CameraStatus(
                state = camera.state(),
                ccdTemperature = if (info.hasCcdTemperature) camera.ccdTemperature() else null,
                coolerOn = if (info.canSetCcdTemperature) camera.coolerOn() else null,
                coolerPower = if (info.canGetCoolerPower) camera.coolerPower() else null,
            )
        }

    val state: StateFlow<Live<CameraStatus>> = poller.state

    /** Reads the state again at once, after a command. */
    fun refresh() = poller.refresh()
}

/** What the camera reports. Values the camera does not have are null. */
data class CameraStatus(val state: CameraState, val ccdTemperature: Double?, val coolerOn: Boolean?, val coolerPower: Double?)

/** The connected focuser. */
class FocuserMediator internal constructor(val focuser: Focuser, val info: FocuserInfo, scope: CoroutineScope, clock: Clock) {
    private val poller =
        Poller(scope, clock) {
            FocuserStatus(
                position = if (info.absolute) focuser.position() else null,
                moving = focuser.isMoving(),
                temperature = if (info.hasTemperature) focuser.temperature() else null,
            )
        }

    val state: StateFlow<Live<FocuserStatus>> = poller.state

    /** Moves an absolute focuser to [position], kept within 0 and its last step. */
    suspend fun moveTo(position: Int) {
        focuser.move(position.coerceIn(0, info.maxStep))
        poller.refresh()
    }

    suspend fun halt() {
        focuser.halt()
        poller.refresh()
    }

    /** Moves to [position] and returns its position once it has stopped. */
    suspend fun moveAndWait(position: Int): Int {
        focuser.move(position.coerceIn(0, info.maxStep))
        awaitStop()
        return focuser.position()
    }

    /** Moves by [steps] from where it is, and waits until it has stopped; a relative focuser takes them as they are. */
    suspend fun moveBy(steps: Int) {
        if (info.absolute) {
            moveAndWait(focuser.position() + steps)
        } else {
            focuser.move(steps)
            awaitStop()
        }
    }

    private suspend fun awaitStop() {
        withTimeout(MOVE_TIMEOUT) {
            while (focuser.isMoving()) delay(MOVE_POLL)
        }
        poller.refresh()
    }

    private companion object {
        val MOVE_POLL = 200.milliseconds
        val MOVE_TIMEOUT = 2.minutes
    }
}

/** What the focuser reports. [position] is null for a relative focuser, [temperature] without a sensor. */
data class FocuserStatus(val position: Int?, val moving: Boolean, val temperature: Double?)

/**
 * The connected filter wheel. [names] has one filter per position. With a [focuser], each
 * filter's offset in [focusOffsets] applies on a change.
 */
class FilterWheelMediator internal constructor(
    val wheel: FilterWheel,
    val names: List<String>,
    scope: CoroutineScope,
    clock: Clock,
    private val focuser: FocuserMediator? = null,
    private val focusOffsets: () -> Map<String, Int> = { emptyMap() },
) {
    private val poller = Poller(scope, clock) { wheel.position() }

    /** The current position, or null while the wheel moves. The Seestar's wheel may take a while to report it. */
    val position: StateFlow<Live<Int?>> = poller.state

    /**
     * Turns the wheel to [position]. As N.I.N.A., the focuser meanwhile moves by the new filter's
     * focus offset minus the old one's, when the old one is known.
     */
    suspend fun select(position: Int) = coroutineScope {
        val from = wheel.position()
        val offsets = focusOffsets()
        fun offset(at: Int) = offsets[names.getOrNull(at)] ?: 0
        val steps = if (from == null || from == position) 0 else offset(position) - offset(from)
        val focus = if (steps != 0 && focuser != null) launch { focuser.moveBy(steps) } else null
        wheel.setPosition(position)
        focus?.join()
        poller.refresh()
    }

    /** Waits until the wheel stops turning, as N.I.N.A. does before a frame, a minute at most. */
    suspend fun awaitStill() {
        withTimeoutOrNull(WHEEL_TIMEOUT) { while (wheel.position() == null) delay(WHEEL_POLL) }
    }

    private companion object {
        val WHEEL_POLL = 500.milliseconds
        val WHEEL_TIMEOUT = 1.minutes
    }
}

/**
 * The connected switch device, such as the Seestar's dew heater. Each switch is shown
 * as it declares itself: on and off, a value within its range, or read only.
 */
class SwitchesMediator internal constructor(val device: SwitchDevice, val switches: List<SwitchInfo>, scope: CoroutineScope, clock: Clock) {
    private val poller = Poller(scope, clock) { switches.map { device.value(it.id) } }

    /** The value of each switch, in the order of [switches]. On and off read as 1 and 0. */
    val values: StateFlow<Live<List<Double>>> = poller.state

    suspend fun setOn(id: Int, on: Boolean) {
        device.setOn(id, on)
        poller.refresh()
    }

    /** Sets switch [id] to [value], rounded to its step and kept within its range. */
    suspend fun setValue(id: Int, value: Double) {
        val info = switches.first { it.id == id }
        val steps = if (info.step > 0) kotlin.math.round((value - info.min) / info.step) else 0.0
        device.setValue(id, (info.min + steps * info.step).coerceIn(info.min, info.max))
        poller.refresh()
    }
}

/** The flat panel's cover and light as last read; [brightness] is null without a light. */
data class FlatPanelState(val cover: CoverStatus, val light: CalibratorStatus, val brightness: Int?)

/**
 * The connected flat panel: its cover opens and closes, and its light turns on at a
 * brightness from 0 to [maxBrightness], or off. What the panel does not have reads as not present;
 * a panel with only a cover has a [maxBrightness] of 0.
 */
class FlatPanelMediator internal constructor(val device: CoverCalibrator, val maxBrightness: Int, scope: CoroutineScope, clock: Clock) {
    private val poller =
        Poller(scope, clock) {
            val light = device.calibratorState()
            FlatPanelState(device.coverState(), light, if (light == CalibratorStatus.NotPresent) null else device.brightness())
        }

    val state: StateFlow<Live<FlatPanelState>> = poller.state

    suspend fun open() = command { device.openCover() }

    suspend fun close() = command { device.closeCover() }

    suspend fun halt() = command { device.haltCover() }

    /** Turns the light on at [brightness], kept within 0 and [maxBrightness]. */
    suspend fun lightOn(brightness: Int) = command { device.calibratorOn(brightness.coerceIn(0, maxBrightness)) }

    suspend fun lightOff() = command { device.calibratorOff() }

    private suspend fun command(send: suspend () -> Unit) {
        send()
        poller.refresh()
    }
}

/** The connected safety monitor: whether the conditions let the night go on. */
class SafetyMonitorMediator internal constructor(val device: SafetyMonitor, scope: CoroutineScope, clock: Clock) {
    private val poller = Poller(scope, clock) { device.isSafe() }

    val safe: StateFlow<Live<Boolean>> = poller.state
}
