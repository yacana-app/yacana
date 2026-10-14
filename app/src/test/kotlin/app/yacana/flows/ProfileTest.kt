// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.domain.profile.DeviceRole
import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The profile as it is touched: its site typed in, equipment found by its address and a device added. */
class ProfileTest : FlowTest() {
    @Test
    fun theSiteIsTypedInWhenItDoesNotComeFromThePhone() {
        val services = show(Section.Equipment, Screen.Profile)
        val profile = checkNotNull(services.equipment.state.value.active)
        services.equipment.save(profile.copy(options = profile.options.copy(locationFromPhone = false)))
        assertTrue(appears("Off: you type it in"))

        tap("Latitude")
        tap("South")
        listOf("3", "3", ".", "5").forEach { tap(it) }
        tap("Save")
        tap("Altitude")
        listOf("1", "2", "0", "0").forEach { tap(it) }
        tap("Save")

        val site = checkNotNull(services.equipment.state.value.active?.location)
        assertEquals(-33.5, site.latitude)
        assertEquals(1200.0, site.elevationM)
    }

    @Test
    fun theCamerasSensorIsTypedSoTheNightIsFramedWithoutConnecting() {
        val services = show(Section.Equipment, Screen.Profile)
        val profile = checkNotNull(services.equipment.state.value.active)
        services.equipment.save(
            profile.copy(optics = profile.optics.copy(sensorWidthPx = null, sensorHeightPx = null, pixelSizeMicrons = null)),
        )
        assertTrue(appears("The field comes with the camera's sensor: type it above or connect the camera once.", substring = true))

        tap("Camera sensor")
        listOf("2", "1", "6", "0").forEach { tap(it) }
        tap("Save")
        // The height follows the width.
        listOf("3", "8", "4", "0").forEach { tap(it) }
        tap("Save")
        tap("Pixel size")
        listOf("2", ".", "9").forEach { tap(it) }
        tap("Save")

        val optics = checkNotNull(services.equipment.state.value.active).optics
        assertEquals(2160, optics.sensorWidthPx)
        assertEquals(3840, optics.sensorHeightPx)
        assertEquals(2.9, optics.pixelSizeMicrons)
        assertTrue(appears("2160 × 3840 px"))
        assertTrue(optics.fieldDeg() != null, "the framing has its field, with nothing connected")
    }

    @Test
    fun equipmentIsFoundByItsAddress() {
        show(Section.Equipment, Screen.AddEquipment)
        assertTrue(appears("Equipment found: 1"))

        tap("Add by IP and port")
        type("192.168.1.20:32323")
        // The address's "Add", below the equipment found.
        tap("Add")

        assertTrue(appears("Equipment found: 2"))
    }

    @Test
    fun theSeestarsWideAngleCameraGetsAProfileOfItsOwn() {
        val services = show(Section.Equipment, Screen.NewProfile)
        assertTrue(appears("Wide angle"))

        tap("Wide angle")

        val profile = checkNotNull(services.equipment.state.value.active)
        assertEquals("Seestar S50 Pro wide angle", profile.name)
        assertEquals(1, profile.devices.camera?.number)
        assertEquals(1, profile.devices.focuser?.number)
        assertNull(profile.devices.filterWheel)
        assertEquals(2, services.equipment.state.value.profiles.size)
    }

    @Test
    fun aDeviceFoundIsAddedInItsRole() {
        val services = show(Section.Equipment, Screen.AddEquipment)
        // The Seestar's second focuser is the last device the profile does not have.
        assertTrue(appears("Seestar S50 Pro_0000 Focuser 1"))

        tap("Add")

        assertEquals(1, checkNotNull(services.equipment.state.value.active).devices.byRole()[DeviceRole.Focuser]?.number)
        assertFalse(shows("Seestar S50 Pro_0000 Focuser 1"))
    }
}
