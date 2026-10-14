// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

/** The ten Alpaca device types, with the name each one has in API paths. */
enum class DeviceType(val path: String) {
    Telescope("telescope"),
    Camera("camera"),
    Focuser("focuser"),
    FilterWheel("filterwheel"),
    Rotator("rotator"),
    CoverCalibrator("covercalibrator"),
    Dome("dome"),
    Switch("switch"),
    ObservingConditions("observingconditions"),
    SafetyMonitor("safetymonitor"),
    ;

    companion object {
        /** Matches the `DeviceType` of the management API, ignoring case; null if unknown. */
        fun fromName(name: String): DeviceType? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** What `/management/v1/description` returns. */
data class ServerDescription(val serverName: String, val manufacturer: String, val manufacturerVersion: String, val location: String)

/** One entry of `/management/v1/configureddevices`. [type] is null for device types this app does not know. */
data class ConfiguredDevice(val name: String, val typeName: String, val type: DeviceType?, val number: Int, val uniqueId: String)

/** The device answered with a non-zero `ErrorNumber`, such as 0x407 (not connected). */
class AlpacaException(val errorNumber: Int, message: String) :
    Exception("Alpaca error 0x${errorNumber.toString(HEX).uppercase()}: $message") {
    companion object {
        private const val HEX = 16

        const val NOT_IMPLEMENTED = 0x400
        const val INVALID_VALUE = 0x401
        const val VALUE_NOT_SET = 0x402
        const val NOT_CONNECTED = 0x407
        const val INVALID_WHILE_PARKED = 0x408
        const val INVALID_OPERATION = 0x40B
    }
}

/** The server broke the protocol: an HTTP error status or a body that is not an Alpaca response. */
class AlpacaProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
