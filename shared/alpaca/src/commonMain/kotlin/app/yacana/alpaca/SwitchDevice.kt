// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

/**
 * A switch device: ISwitchV2 and V3. One device holds several switches,
 * numbered from 0, such as the Seestar's dew heater.
 */
class SwitchDevice(client: AlpacaClient, number: Int) : AlpacaDevice(client, DeviceType.Switch, number) {
    /** How many switches the device has. */
    suspend fun count(): Int = int("maxswitch")

    /** What switch [id] is and how it can be set. It does not change while connected, so read it once. */
    suspend fun info(id: Int): SwitchInfo = SwitchInfo(
        id = id,
        name = string("getswitchname", "Id" to id),
        description = string("getswitchdescription", "Id" to id),
        canWrite = bool("canwrite", "Id" to id),
        min = double("minswitchvalue", "Id" to id),
        max = double("maxswitchvalue", "Id" to id),
        step = double("switchstep", "Id" to id),
    )

    suspend fun isOn(id: Int): Boolean = bool("getswitch", "Id" to id)

    suspend fun value(id: Int): Double = double("getswitchvalue", "Id" to id)

    suspend fun setOn(id: Int, on: Boolean) = put("setswitch", "Id" to id, "State" to on)

    suspend fun setValue(id: Int, value: Double) = put("setswitchvalue", "Id" to id, "Value" to value)
}

/** One switch: its name, whether it can be written, and its range of values. */
data class SwitchInfo(
    val id: Int,
    val name: String,
    val description: String,
    val canWrite: Boolean,
    val min: Double,
    val max: Double,
    val step: Double,
) {
    /** On and off only, like the Seestar's dew heater (0–1 in steps of 1). Otherwise the switch takes a value. */
    val isOnOff: Boolean get() = min == 0.0 && max == 1.0 && step == 1.0
}
