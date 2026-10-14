// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.equipment.Connection
import app.yacana.domain.focus.AutofocusState
import app.yacana.domain.profile.DeviceRole
import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertTrue

/** "Equipment" as it is touched: connecting and disconnecting, the mount, the focuser, the wheel and the switches. */
class EquipmentTest : FlowTest() {
    @Test
    fun theProfileConnectsAndDisconnects() {
        val services = show(Section.Equipment)

        tap("Connect")
        assertTrue(eventually { services.equipment.state.value.connection is Connection.Connected })
        assertTrue(appears("connected", substring = true))

        tap("Disconnect all")
        assertTrue(eventually { services.equipment.state.value.connection !is Connection.Connected })
    }

    @Test
    fun theMountParksWhenHeldAndItsTrackingSwitches() {
        show(Section.Equipment, Screen.Device(DeviceRole.Mount), connected = true)

        hold("Park")
        assertTrue(sent("telescope/0/park"))

        tap("Tracking")
        assertTrue(sent("telescope/0/tracking"))
    }

    @Test
    fun theFocuserGoesToAPositionTypedOnTheKeypad() {
        show(Section.Equipment, Screen.Device(DeviceRole.Focuser), connected = true)

        tap("Go to position…")
        listOf("1", "5", "0", "0").forEach { tap(it) }
        tap("Save")

        assertTrue(sent("focuser/0/move", "Position", "1500"))
    }

    @Test
    fun theCameraSaysWhatItIsAndSendsToImaging() {
        show(Section.Equipment, Screen.Device(DeviceRole.Camera), connected = true)
        // Each reading is its name and its value.
        assertTrue(appears("Sensor ", substring = true))

        tap("Go to Imaging")

        // Imaging, with its shutter.
        assertTrue(showsLabelled("Capture"))
    }

    @Test
    fun theFocusersAutofocusRunsAndSaysWhyItFailed() {
        val services = show(Section.Equipment, Screen.Device(DeviceRole.Focuser), connected = true)

        tap("Autofocus")
        // The fake Seestar's stars are as sharp at any position: there is no curve to fit.
        assertTrue(eventually { services.autofocus.state.value is AutofocusState.Failed })
        assertTrue(appears("Try again"))

        tap("Try again")
        assertTrue(services.autofocus.state.value !is AutofocusState.Idle)
    }

    @Test
    fun aFilterTappedIsTheWheels() {
        show(Section.Equipment, Screen.Device(DeviceRole.FilterWheel), connected = true)

        tap("LP")

        assertTrue(sent("filterwheel/0/position", "Position", "2"))
    }

    @Test
    fun theDewHeaterSwitchesOff() {
        show(Section.Equipment, Screen.Device(DeviceRole.Switches), connected = true)
        assertTrue(appears("Dew heater", substring = true))

        toggle()

        // The fake Seestar's heater is on.
        assertTrue(sent("switch/0/setswitch", "State", "false"))
    }
}
