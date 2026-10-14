// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

/**
 * A flat panel: ICoverCalibratorV1 and V2. It may have a cover that opens over
 * the telescope, a light for flats whose brightness goes from 0 to [maxBrightness], or both.
 */
class CoverCalibrator(client: AlpacaClient, number: Int) : AlpacaDevice(client, DeviceType.CoverCalibrator, number) {
    suspend fun coverState(): CoverStatus = ascomEnum(int("coverstate"))

    suspend fun calibratorState(): CalibratorStatus = ascomEnum(int("calibratorstate"))

    /** The light's brightness now, from 0 to [maxBrightness]. */
    suspend fun brightness(): Int = int("brightness")

    /** The brightest the light goes; it does not change while connected, so read it once. */
    suspend fun maxBrightness(): Int = int("maxbrightness")

    suspend fun openCover() = put("opencover")

    suspend fun closeCover() = put("closecover")

    suspend fun haltCover() = put("haltcover")

    suspend fun calibratorOn(brightness: Int) = put("calibratoron", "Brightness" to brightness)

    suspend fun calibratorOff() = put("calibratoroff")
}
