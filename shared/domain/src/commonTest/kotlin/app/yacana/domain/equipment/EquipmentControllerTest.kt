// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.alpaca.AlpacaServer
import app.yacana.alpaca.ConfiguredDevice
import app.yacana.alpaca.DeviceType
import app.yacana.alpaca.ServerDescription
import app.yacana.domain.profile.DeviceRef
import app.yacana.domain.profile.DeviceRole
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.profile.ProfileStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EquipmentControllerTest {
    private val seestar = FakeAlpaca("seestar").apply { seestarMount() }
    private val directory = Path(SystemTemporaryDirectory, "yacana-equipment-${ProfilePresets.newId()}")
    private val phone = Location(latitude = 12.34, longitude = 56.78, elevationM = 100.0)

    private val server =
        AlpacaServer(
            url = seestar.url,
            description = ServerDescription("ASCOM Alpaca", "ASCOM Initiative", "1.3.1-1", ""),
            devices = listOf(ConfiguredDevice("Seestar S50 Pro_0000 Telescope", "Telescope", DeviceType.Telescope, 0, "mount-1")),
        )

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private var fixes = 0

    private fun TestScope.controller(wifi: Boolean = true, location: Location? = phone): EquipmentController {
        val http = fakeHttp(StandardTestDispatcher(testScheduler), seestar)
        val platform =
            object : EquipmentPlatform {
                override fun httpClient(): HttpClient? = http.takeIf { wifi }

                override fun networkHandle(): Long? = null

                override val discovery = AlpacaDiscovery { emptyList() }

                override suspend fun location() = location.also { fixes++ }
            }
        return EquipmentController(ProfileStore(directory), platform, backgroundScope)
    }

    @Test
    fun aServerBecomesTheActiveProfile() = runTest {
        val controller = controller()

        val profile = controller.use(server)

        assertEquals("Seestar S50 Pro", profile.name)
        assertEquals(profile, controller.state.value.active)
        assertEquals(listOf(profile), controller.state.value.profiles)
        // Remembered between launches.
        assertEquals(profile, controller().state.value.active)
    }

    @Test
    fun aDeviceOfAnotherServerJoinsTheProfileInItsRole() = runTest {
        val controller = controller()
        controller.use(server)
        val other =
            AlpacaServer(
                url = "http://10.0.0.2:32323",
                description = ServerDescription("ASCOM Alpaca Simulators", "ASCOM Initiative", "0.5.0", ""),
                devices =
                listOf(
                    ConfiguredDevice("Alpaca Focuser Simulator - 0", "Focuser", DeviceType.Focuser, 0, "focuser-0"),
                    ConfiguredDevice("Alpaca Dome Simulator", "Dome", DeviceType.Dome, 0, "dome-0"),
                ),
            )

        controller.add(other, other.devices[0])
        controller.add(other, other.devices[1])

        val devices = controller.state.value.active!!.devices
        assertEquals(DeviceRef("http://10.0.0.2:32323", 0, "focuser-0", "Alpaca Focuser Simulator - 0"), devices.focuser)
        assertEquals("mount-1", devices.mount?.uniqueId, "the mount stays")
        assertEquals(setOf(DeviceRole.Mount, DeviceRole.Focuser), devices.byRole().keys, "a dome has no role yet")
    }

    @Test
    fun connectsWithThePhoneLocation() = runTest {
        val controller = controller()
        controller.use(server)

        controller.connect()
        val connected = assertIs<Connection.Connected>(controller.state.first { it.connection !is Connection.Connecting }.connection)

        assertEquals(phone, controller.state.value.active?.location)
        assertEquals("12.34", seestar.values["telescope/0/sitelatitude"])
        assertNotNull(connected.equipment.mount)

        controller.disconnect()
        assertEquals(Connection.Disconnected, controller.state.value.connection)
    }

    @Test
    fun aReconnectionDoesNotReadThePhoneLocationAgain() = runTest {
        val controller = controller()
        controller.use(server)
        controller.connect()
        controller.state.first { it.connection is Connection.Connected }
        assertEquals(1, fixes)

        assertEquals(true, controller.reconnect())

        assertEquals(1, fixes, "the phone's location dot stays off")
        assertEquals(phone, controller.state.value.active?.location)
    }

    @Test
    fun aTypedLocationIsKept() = runTest {
        val controller = controller()
        val typed = Location(latitude = -33.0, longitude = -70.0)
        controller.save(controller.use(server).copy(location = typed, options = ProfileOptions(locationFromPhone = false)))

        controller.connect()
        controller.state.first { it.connection is Connection.Connected }

        assertEquals(typed, controller.state.value.active?.location)
        assertEquals("-33.0", seestar.values["telescope/0/sitelatitude"])
    }

    @Test
    fun thePhoneLocationIsReadWithoutConnecting() = runTest {
        val controller = controller()
        controller.use(server)

        assertEquals(true, controller.refreshPhoneLocation())

        assertEquals(phone, controller.state.value.active?.location)
        assertEquals(Connection.Disconnected, controller.state.value.connection)
    }

    @Test
    fun withoutAFixOrTypedInTheLocationStays() = runTest {
        val typed = Location(latitude = -33.0, longitude = -70.0)
        val noFix = controller(location = null)
        noFix.save(noFix.use(server).copy(location = typed))

        assertEquals(false, noFix.refreshPhoneLocation())
        assertEquals(typed, noFix.state.value.active?.location)

        val byHand = controller()
        byHand.save(byHand.state.value.active!!.copy(options = ProfileOptions(locationFromPhone = false)))

        assertEquals(false, byHand.refreshPhoneLocation())
        assertEquals(typed, byHand.state.value.active?.location)
    }

    @Test
    fun withoutWifiItSaysSo() = runTest {
        val controller = controller(wifi = false)
        controller.use(server)

        controller.connect()

        val failed = assertIs<Connection.Failed>(controller.state.first { it.connection !is Connection.Connecting }.connection)
        assertEquals("The phone is not on a WiFi network", failed.reason)
    }

    @Test
    fun whenEveryDeviceFailsTheConnectionFails() = runTest {
        val controller = controller()
        controller.use(server)
        seestar.notImplemented += "telescope/0/canfindhome"

        controller.connect()

        val failed = assertIs<Connection.Failed>(controller.state.first { it.connection !is Connection.Connecting }.connection)
        assertEquals(
            "false",
            seestar.values["telescope/0/connected"],
            "the device that did connect is disconnected again: ${failed.reason}",
        )
    }

    @Test
    fun aProfileTravelsToAnotherPhone() = runTest {
        val first = controller()
        val profile = first.use(server)
        first.save(profile.copy(options = ProfileOptions(starDatabase = "D20", minAltitudeDeg = 12.0)))
        val file = assertNotNull(first.exportActive())
        cleanUp()

        val other = controller()
        val imported = other.import(file)

        assertEquals(first.state.value.active, imported)
        assertEquals(imported, other.state.value.active)
    }

    @Test
    fun deletingTheActiveProfileLeavesNone() = runTest {
        val controller = controller()
        val profile = controller.use(server)
        controller.save(profile.copy(id = "another", name = "Another"))

        controller.delete(profile.id)

        // One profile left: it is the one in use.
        assertEquals("another", controller.state.value.active?.id)
        controller.delete("another")
        assertNull(controller.state.value.active)
    }
}
