// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.alpaca.CalibratorStatus
import app.yacana.alpaca.CoverStatus
import app.yacana.alpaca.DeviceType
import app.yacana.alpaca.DiscoveryResponse
import app.yacana.alpaca.TelescopeAxis
import app.yacana.domain.profile.DeviceRef
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.HorizonPoint
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileDevices
import app.yacana.domain.profile.ProfileOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EquipmentTest {
    private val seestar = FakeAlpaca("seestar").apply { seestarMount() }
    private val now = Instant.parse("2026-10-04T22:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }
    private val noDiscovery = AlpacaDiscovery { emptyList() }

    private fun profile(options: ProfileOptions = ProfileOptions(), url: String = seestar.url) = Profile(
        id = "p",
        name = "Seestar",
        devices = ProfileDevices(mount = DeviceRef(url, 0, "mount-1")),
        location = Location(latitude = 12.34, longitude = 56.78, elevationM = 100.0),
        options = options,
    )

    private suspend fun TestScope.connect(
        profile: Profile,
        discovery: AlpacaDiscovery = noDiscovery,
        vararg servers: FakeAlpaca = arrayOf(seestar),
    ) = EquipmentConnector(fakeHttp(StandardTestDispatcher(testScheduler), *servers), discovery, clock).connect(profile, backgroundScope)

    @Test
    fun connectsAndSendsTheTimeAndTheSite() = runTest {
        val equipment = connect(profile())

        assertEquals("true", seestar.values["telescope/0/connected"])
        assertEquals("\"2026-10-04T22:00:00Z\"", seestar.values["telescope/0/utcdate"])
        assertEquals("12.34", seestar.values["telescope/0/sitelatitude"])
        assertEquals("56.78", seestar.values["telescope/0/sitelongitude"])
        // The Seestar does not implement the elevation: skipped, not an error.
        val report = assertNotNull(equipment.timeAndSite)
        assertEquals(0.seconds, report.mountClockOffset)
        assertEquals(12.34, report.latitude)
        assertEquals(emptyList(), report.errors)
        assertTrue(equipment.failures.isEmpty())
    }

    @Test
    fun theTimeIsNotSentIfTheProfileSaysSo() = runTest {
        val equipment = connect(profile(ProfileOptions(sendTimeAndLocation = false)))

        assertNull(equipment.timeAndSite)
        assertEquals("\"2026-10-04T21:00:00Z\"", seestar.values["telescope/0/utcdate"])
    }

    @Test
    fun theMountStatePublishesEverySecond() = runTest {
        val mount = assertNotNull(connect(profile()).mount)
        mount.state.first { it.value?.altitudeDeg == 89.0 }
        val start = testScheduler.currentTime

        seestar.values["telescope/0/altitude"] = "45.0"
        val live = mount.state.first { it.value?.altitudeDeg == 45.0 }

        assertTrue(testScheduler.currentTime - start <= 1_000, "the next read came after ${testScheduler.currentTime - start} ms")
        assertEquals(now, live.updatedAt)
    }

    @Test
    fun aFailedReadKeepsTheLastValuesAndSaysWhy() = runTest {
        val mount = assertNotNull(connect(profile()).mount)
        mount.state.first { it.value != null }

        seestar.notImplemented += "telescope/0/altitude"
        val live = mount.state.first { it.error != null }

        assertEquals(89.0, live.value?.altitudeDeg)
        assertTrue(live.error.orEmpty().contains("altitude"), live.error)
    }

    @Test
    fun noGoToBelowTheHorizon() = runTest {
        val horizon =
            ProfileOptions(
                sendTimeAndLocation = false,
                minAltitudeDeg = 10.0,
                horizon = listOf(HorizonPoint(0.0, 30.0), HorizonPoint(180.0, 30.0)),
            )
        val mount = assertNotNull(connect(profile(horizon)).mount)

        // At sidereal time 2 h and latitude 40°: on the meridian, 20° high in the south, under the 30° horizon.
        val error = assertFailsWith<BelowHorizonException> { mount.goTo(2.0, -30.0) }
        assertEquals(20.0, error.target.altitudeDeg, 1e-9)
        assertEquals(30.0, error.limitDeg, 1e-9)
        assertTrue(seestar.puts.none { it.first.endsWith("slewtocoordinatesasync") })

        mount.goTo(2.0, 0.0)
        assertEquals(
            mapOf("RightAscension" to "2.0", "Declination" to "0.0"),
            seestar.puts.last {
                it.first.endsWith("slewtocoordinatesasync")
            }.second,
        )
    }

    @Test
    fun aManualMoveStopsAtTheHorizon() = runTest {
        val mount = assertNotNull(connect(profile(ProfileOptions(minAltitudeDeg = 20.0))).mount)
        mount.state.first { it.value != null }

        mount.move(TelescopeAxis.Secondary, -2.0)
        seestar.values["telescope/0/altitude"] = "19.0"
        // The stop goes out before the state that crossed the limit is published.
        mount.state.first { it.value?.altitudeDeg == 19.0 }

        assertEquals("telescope/0/moveaxis" to mapOf("Axis" to "1", "Rate" to "0.0"), seestar.puts.last())
    }

    @Test
    fun aManualMoveMayStartBelowTheHorizon() = runTest {
        seestar.values["telescope/0/altitude"] = "5.0"
        val mount = assertNotNull(connect(profile(ProfileOptions(minAltitudeDeg = 20.0))).mount)
        mount.state.first { it.value != null }

        mount.move(TelescopeAxis.Secondary, 2.0)
        seestar.values["telescope/0/azimuth"] = "181.0"
        mount.state.first { it.value?.azimuthDeg == 181.0 }

        assertEquals("telescope/0/moveaxis" to mapOf("Axis" to "1", "Rate" to "2.0"), seestar.puts.last())
    }

    @Test
    fun aDeviceThatMovedIsFoundByItsUniqueId() = runTest {
        // The profile remembers the Seestar on the home WiFi; now it is on its own WiFi.
        val ownWifi = FakeAlpaca("10.0.0.1").apply { seestarMount() }
        val discovery = AlpacaDiscovery { listOf(DiscoveryResponse("10.0.0.1", 32323)) }

        val equipment = connect(profile(url = "http://192.168.0.87:32323"), discovery, ownWifi)

        assertEquals("http://10.0.0.1:32323", equipment.profile.devices.mount?.serverUrl)
        assertNotNull(equipment.mount)
        assertEquals("true", ownWifi.values["telescope/0/connected"])
    }

    @Test
    fun aDeviceThatFailsIsReportedAndTheOthersConnect() = runTest {
        seestar.devices += Triple("Switch", 0, "switch-1")
        val withSwitch = profile().let { it.copy(devices = it.devices.copy(switches = DeviceRef(seestar.url, 0, "switch-1"))) }

        val equipment = connect(withSwitch)

        assertNotNull(equipment.mount)
        assertNull(equipment.switches)
        assertTrue(equipment.failures.getValue(DeviceRole.Switches).contains("maxswitch"))
    }

    @Test
    fun theFlatPanelAndTheSafetyMonitorShowTheirStateAndTakeCommands() = runTest {
        seestar.devices += Triple("CoverCalibrator", 0, "panel-1")
        seestar.devices += Triple("SafetyMonitor", 0, "monitor-1")
        mapOf("coverstate" to "1", "calibratorstate" to "1", "brightness" to "0", "maxbrightness" to "255")
            .forEach { (member, value) -> seestar.values["covercalibrator/0/$member"] = value }
        seestar.values["safetymonitor/0/issafe"] = "false"
        val devices =
            ProfileDevices(flatPanel = DeviceRef(seestar.url, 0, "panel-1"), safetyMonitor = DeviceRef(seestar.url, 0, "monitor-1"))
        val equipment = connect(Profile(id = "f", name = "f", devices = devices))
        val panel = assertNotNull(equipment.flatPanel)

        assertEquals(FlatPanelState(CoverStatus.Closed, CalibratorStatus.Off, 0), panel.state.first { it.value != null }.value)
        assertEquals(false, assertNotNull(equipment.safetyMonitor).safe.first { it.value != null }.value)
        panel.open()
        panel.lightOn(400)
        panel.lightOff()

        assertEquals(255, panel.maxBrightness)
        assertEquals(
            listOf("covercalibrator/0/opencover", "covercalibrator/0/calibratoron", "covercalibrator/0/calibratoroff"),
            seestar.puts.map { it.first }.filter { it.startsWith("covercalibrator/0/") && it != "covercalibrator/0/connected" },
        )
        // Within the panel's range.
        assertEquals("255", seestar.puts.first { it.first == "covercalibrator/0/calibratoron" }.second["Brightness"])
        assertEquals(DeviceRole.FlatPanel, DeviceRole.of(DeviceType.CoverCalibrator))
    }

    @Test
    fun switchesAreSetWithinTheirStep() = runTest {
        seestar.devices += Triple("Switch", 0, "switch-1")
        mapOf(
            "maxswitch" to "1",
            "getswitchname" to "\"Light box\"",
            "getswitchdescription" to "\"\"",
            "canwrite" to "true",
            "minswitchvalue" to "0",
            "maxswitchvalue" to "100",
            "switchstep" to "10",
            "getswitchvalue" to "0",
        ).forEach { (member, value) -> seestar.values["switch/0/$member"] = value }
        val equipment = connect(Profile(id = "s", name = "s", devices = ProfileDevices(switches = DeviceRef(seestar.url, 0, "switch-1"))))
        val switches = assertNotNull(equipment.switches)

        switches.setValue(0, 47.0)
        switches.setValue(0, 140.0)

        assertEquals(listOf("50.0", "100.0"), seestar.puts.filter { it.first == "switch/0/setswitchvalue" }.map { it.second["Value"] })
        assertEquals(listOf(0.0), switches.values.first { it.value != null }.value)
    }

    @Test
    fun disconnectingStopsThePollingAndDisconnects() = runTest {
        val equipment = connect(profile())
        assertNotNull(equipment.mount).state.first { it.value != null }

        equipment.disconnect()
        val calls = seestar.puts.size
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals("false", seestar.values["telescope/0/connected"])
        assertEquals(calls, seestar.puts.size)
    }
}
