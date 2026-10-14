// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import kotlinx.serialization.json.jsonPrimitive

/** A filter wheel: IFilterWheelV2 and V3. Positions start at 0. */
class FilterWheel(client: AlpacaClient, number: Int) : AlpacaDevice(client, DeviceType.FilterWheel, number) {
    /** The filter names, one per position, such as Dark, IR and LP on the Seestar. */
    suspend fun names(): List<String> = array("names").map { it.jsonPrimitive.content }

    /** The focus offset of each filter, in focuser steps. */
    suspend fun focusOffsets(): List<Int> = array("focusoffsets").map { it.jsonPrimitive.asInt() }

    /** The current position, or null while the wheel is moving. */
    suspend fun position(): Int? = int("position").takeIf { it >= 0 }

    /** Starts moving to [position] and returns at once; [position] is null until it arrives. */
    suspend fun setPosition(position: Int) = put("position", "Position" to position)
}
