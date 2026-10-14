// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

/** A safety monitor: ISafetyMonitorV1 to V3. It says whether the conditions let the night go on. */
class SafetyMonitor(client: AlpacaClient, number: Int) : AlpacaDevice(client, DeviceType.SafetyMonitor, number) {
    suspend fun isSafe(): Boolean = bool("issafe")
}
