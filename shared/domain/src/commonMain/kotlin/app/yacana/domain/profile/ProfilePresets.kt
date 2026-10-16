// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.profile

import app.yacana.alpaca.AlpacaServer
import app.yacana.alpaca.ConfiguredDevice
import app.yacana.alpaca.DeviceType
import kotlin.uuid.Uuid

/** Ready-made profiles for a server that was just found or added (docs/design/STYLE.md). */
object ProfilePresets {
    /** A new profile ID: letters, digits and dashes, so it is also a file name. */
    fun newId(): String = Uuid.random().toString()

    /** The Seestar's devices start their names with the model, such as `Seestar S50 Pro_… Telescope`. */
    fun isSeestarS50Pro(server: AlpacaServer): Boolean = server.devices.any { it.name.startsWith(SEESTAR_S50_PRO) }

    /** Whether [device] is a Seestar's, of any model, by its name. */
    fun isSeestar(device: DeviceRef): Boolean = device.name.startsWith(SEESTAR)

    /**
     * A profile for [server]: the Seestar S50 Pro profile if it is one, or else the device
     * with the lowest number in each role. The site is left for the phone to fill in.
     */
    fun forServer(server: AlpacaServer, id: String = newId()): Profile =
        if (isSeestarS50Pro(server)) seestarS50Pro(server, id) else generic(server, id)

    /**
     * The Seestar S50 Pro: 50 mm at f/5.2, 2.248″ per pixel as measured
     * with ASTAP in spike S1, and D50 as the star database. Its telephoto camera and
     * focuser are number 0; the wide angle ones, number 1, have their own profile,
     * [seestarS50ProWideAngle]. Its focuser runs from 0 to
     * 2,600 steps (spike S3): the autofocus takes 15 points 15 steps apart with 3 s frames, as it
     * fitted best on the first night under the sky (steps of 30 failed twice in five runs).
     */
    fun seestarS50Pro(server: AlpacaServer, id: String = newId()): Profile = generic(server, id).copy(
        name = SEESTAR_S50_PRO,
        optics =
        Optics(
            focalLengthMm = 260.0,
            apertureMm = 50.0,
            measuredPixelScaleArcsec = 2.248,
            // An S50 Pro in equatorial mode, its long side nearly north to south, until a plate solve measures this one.
            measuredRotationDeg = 93.2,
            sensorWidthPx = 2160,
            sensorHeightPx = 3840,
            pixelSizeMicrons = 2.9,
        ),
        options = ProfileOptions(starDatabase = "D50", autofocus = AutofocusOptions(stepSize = 15, offsetSteps = 7, exposureSeconds = 3.0)),
    )

    /** Whether the Seestar at [server] has its wide-angle camera, number 1 (spike S3). */
    fun hasWideAngle(server: AlpacaServer): Boolean =
        isSeestarS50Pro(server) && server.devices.any { it.type == DeviceType.Camera && it.number == WIDE_ANGLE }

    /**
     * The Seestar S50 Pro's wide-angle camera as a profile of its own, named [name] in the app's
     * language: the mount, the wide-angle camera and focuser (number 1) and the switches, but not
     * the filter wheel, which sits in front of the telephoto lens only. The lens has 3.4 mm of
     * aperture at f/1.75, so 5.95 mm of focal length, the published 63° across the diagonal of its
     * 2160 × 3840 sensor of 1.6 µm; W08 is ASTAP's database for such fields. Its focuser runs from
     * 0 to 1,023 steps (spike S3): the autofocus starts with 15 points 10 steps apart, to be
     * adjusted under the sky.
     */
    fun seestarS50ProWideAngle(server: AlpacaServer, name: String, id: String = newId()): Profile {
        fun wide(type: DeviceType) = server.devices.firstOrNull { it.type == type && it.number == WIDE_ANGLE }?.ref(server.url)
        val profile = generic(server, id)
        return profile.copy(
            name = name,
            devices = profile.devices.copy(camera = wide(DeviceType.Camera), focuser = wide(DeviceType.Focuser), filterWheel = null),
            optics = Optics(focalLengthMm = 5.95, apertureMm = 3.4, sensorWidthPx = 2160, sensorHeightPx = 3840, pixelSizeMicrons = 1.6),
            options = ProfileOptions(
                starDatabase = "W08",
                autofocus = AutofocusOptions(stepSize = 10, offsetSteps = 7, exposureSeconds = 3.0),
            ),
        )
    }

    private fun generic(server: AlpacaServer, id: String): Profile {
        fun first(type: DeviceType): DeviceRef? = server.devices.filter { it.type == type }.minByOrNull { it.number }?.ref(server.url)
        return Profile(
            id = id,
            name = server.description.serverName.ifBlank { server.url },
            devices =
            ProfileDevices(
                mount = first(DeviceType.Telescope),
                camera = first(DeviceType.Camera),
                focuser = first(DeviceType.Focuser),
                filterWheel = first(DeviceType.FilterWheel),
                switches = first(DeviceType.Switch),
                flatPanel = first(DeviceType.CoverCalibrator),
                safetyMonitor = first(DeviceType.SafetyMonitor),
            ),
        )
    }

    private fun ConfiguredDevice.ref(serverUrl: String) = DeviceRef(serverUrl, number, uniqueId, name)

    private const val SEESTAR = "Seestar"
    private const val SEESTAR_S50_PRO = "$SEESTAR S50 Pro"
    private const val WIDE_ANGLE = 1
}
