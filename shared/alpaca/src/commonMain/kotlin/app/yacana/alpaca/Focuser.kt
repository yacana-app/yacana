// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

/** A focuser: IFocuserV3 and V4. Positions are in steps. */
class Focuser(client: AlpacaClient, number: Int) : AlpacaDevice(client, DeviceType.Focuser, number) {
    /** What the focuser is and can do. It does not change while connected, so read it once. */
    suspend fun info(): FocuserInfo = FocuserInfo(
        absolute = bool("absolute"),
        maxStep = int("maxstep"),
        maxIncrement = int("maxincrement"),
        stepSizeMicrons = optional { double("stepsize") },
        tempCompAvailable = bool("tempcompavailable"),
        hasTemperature = optional { double("temperature") } != null,
    )

    /** The current position of an absolute focuser. */
    suspend fun position(): Int = int("position")

    suspend fun isMoving(): Boolean = bool("ismoving")

    /** Temperature in °C, or null without a sensor. */
    suspend fun temperature(): Double? = optional { double("temperature") }

    suspend fun tempComp(): Boolean = bool("tempcomp")

    suspend fun setTempComp(on: Boolean) = put("tempcomp", "TempComp" to on)

    /** Moves to [position] (absolute focuser) or by [position] steps (relative focuser), and returns at once. */
    suspend fun move(position: Int) = put("move", "Position" to position)

    suspend fun halt() = put("halt")
}

/** What a focuser is and can do. [stepSizeMicrons] is null if the focuser does not say (the Seestar does not). */
data class FocuserInfo(
    val absolute: Boolean,
    val maxStep: Int,
    val maxIncrement: Int,
    val stepSizeMicrons: Double?,
    val tempCompAvailable: Boolean,
    val hasTemperature: Boolean,
)
