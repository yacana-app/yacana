// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The typed devices against OmniSim when OMNISIM_URL is set.
 * Each test connects its device, drives it, and disconnects it again.
 */
class TypedDevicesOmniSimTest {
    private val url = System.getenv("OMNISIM_URL").orEmpty()

    private fun <D : AlpacaDevice> withDevice(create: (AlpacaClient) -> D, block: suspend (D) -> Unit) {
        if (url.isBlank()) {
            println("OMNISIM_URL is not set: skipping the OmniSim integration test")
            return
        }
        HttpClient(OkHttp) { alpacaDefaults() }.use { http ->
            runBlocking {
                val device = create(AlpacaClient(http, url))
                device.connect()
                try {
                    assertTrue(device.connected())
                    block(device)
                } finally {
                    device.disconnect()
                }
            }
        }
    }

    /** Polls [done] every 100 ms for up to 60 s. */
    private suspend fun waitUntil(what: String, done: suspend () -> Boolean) = withTimeout(60.seconds) {
        while (!done()) delay(100)
    }.also { println("$what: done") }

    @Test
    fun telescopeSlewsSyncsAndTracks() = withDevice({ Telescope(it, 0) }) { mount ->
        val capabilities = mount.capabilities()
        assertTrue(capabilities.canSlewAsync && capabilities.canSync && capabilities.canSetTracking)
        if (mount.atPark()) mount.unpark()

        mount.setTracking(true)
        assertTrue(mount.tracking())
        // Near the zenith, so the target is always above the horizon.
        val ra = mount.siderealTime()
        val dec = mount.siteLatitude() - 1.0
        mount.slewToCoordinatesAsync(ra, dec)
        waitUntil("slew") { !mount.slewing() }
        assertTrue(abs(mount.declination() - dec) < 0.01, "declination ${mount.declination()} should be $dec")

        mount.syncToCoordinates(mount.rightAscension(), mount.declination())
        assertTrue(mount.axisRates(TelescopeAxis.Primary).isNotEmpty())
        assertContains(capabilities.trackingRates, mount.trackingRate())
    }

    @Test
    fun telescopeTakesTheTimeAndTheSite() = withDevice({ Telescope(it, 0) }) { mount ->
        val now = Clock.System.now()
        mount.setUtcDate(now)
        val reported = mount.utcDate()
        assertTrue(abs((reported - now).inWholeMilliseconds) < 1_000, "the mount reports $reported, sent $now")

        val latitude = mount.siteLatitude()
        val longitude = mount.siteLongitude()
        try {
            mount.setSiteLatitude(12.34)
            mount.setSiteLongitude(56.78)
            assertEquals(12.34, mount.siteLatitude(), 1e-6)
            assertEquals(56.78, mount.siteLongitude(), 1e-6)
        } finally {
            mount.setSiteLatitude(latitude)
            mount.setSiteLongitude(longitude)
        }
    }

    @Test
    fun cameraTakesAnExposureWithItsSettings() = withDevice({ Camera(it, 0) }) { camera ->
        val info = camera.info()
        assertTrue(info.width > 0 && info.height > 0)

        val gain = info.gain
        if (gain is GainControl.Range) {
            val value = (gain.min + gain.max) / 2
            camera.setGain(value)
            assertEquals(value, camera.gain())
        }
        val binning = Binning(minOf(2, info.maxBinX), minOf(2, info.maxBinY))
        camera.setBinning(binning)
        assertEquals(binning, camera.binning())
        val subframe = Subframe(0, 0, info.width / binning.x / 2, info.height / binning.y / 2)
        camera.setSubframe(subframe)
        assertEquals(subframe, camera.subframe())
        if (info.hasCcdTemperature) assertNotNull(camera.ccdTemperature())

        camera.startExposure(maxOf(0.1, info.exposureMin))
        waitUntil("exposure") { camera.imageReady() }
        assertEquals(CameraState.Idle, camera.state())

        camera.setBinning(Binning(1, 1))
        camera.setSubframe(Subframe(0, 0, info.width, info.height))
    }

    @Test
    fun focuserMovesToAPosition() = withDevice({ Focuser(it, 0) }) { focuser ->
        val info = focuser.info()
        assertTrue(info.absolute)
        val target = (focuser.position() + 100).coerceAtMost(info.maxStep)

        focuser.move(target)
        waitUntil("focuser move") { !focuser.isMoving() }

        assertEquals(target, focuser.position())
    }

    @Test
    fun filterWheelChangesFilter() = withDevice({ FilterWheel(it, 0) }) { wheel ->
        val names = wheel.names()
        assertTrue(names.size > 1)
        assertEquals(names.size, wheel.focusOffsets().size)
        val target = ((wheel.position() ?: 0) + 1) % names.size

        wheel.setPosition(target)
        waitUntil("filter change") { wheel.position() == target }
    }

    @Test
    fun everyKindOfSwitchReadsAndWrites() = withDevice({ SwitchDevice(it, 0) }) { switches ->
        val all = (0 until switches.count()).map { switches.info(it) }
        val onOff = all.first { it.canWrite && it.isOnOff }
        val ranged = all.first { it.canWrite && !it.isOnOff }
        val readOnly = all.first { !it.canWrite }

        switches.setOn(onOff.id, true)
        assertTrue(switches.isOn(onOff.id))
        switches.setOn(onOff.id, false)
        assertTrue(!switches.isOn(onOff.id))

        val value = ranged.min + ranged.step
        switches.setValue(ranged.id, value)
        assertEquals(value, switches.value(ranged.id), ranged.step / 2)

        assertTrue(switches.value(readOnly.id) in readOnly.min..readOnly.max)
    }

    @Test
    fun theFlatPanelOpensClosesAndSetsItsBrightness() = withDevice({ CoverCalibrator(it, 0) }) { panel ->
        panel.openCover()
        waitUntil("cover open") { panel.coverState() == CoverStatus.Open }
        panel.closeCover()
        waitUntil("cover closed") { panel.coverState() == CoverStatus.Closed }

        val brightness = panel.maxBrightness() / 2
        panel.calibratorOn(brightness)
        waitUntil("light ready") { panel.calibratorState() == CalibratorStatus.Ready }
        assertEquals(brightness, panel.brightness())
        panel.calibratorOff()
        waitUntil("light off") { panel.calibratorState() == CalibratorStatus.Off }
    }

    @Test
    fun theSafetyMonitorSaysWhetherItIsSafe() = withDevice({ SafetyMonitor(it, 0) }) { monitor ->
        // OmniSim's says safe or not as it is set up; reading it is what counts.
        monitor.isSafe()
    }
}
