// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.profile

import app.yacana.alpaca.AlpacaServer
import app.yacana.alpaca.ConfiguredDevice
import app.yacana.alpaca.DeviceType
import app.yacana.alpaca.ServerDescription
import app.yacana.domain.solver.recommendedDatabase
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileTest {
    private val seestar =
        AlpacaServer(
            url = "http://10.0.0.1:32323",
            description = ServerDescription("ASCOM Alpaca", "ASCOM Initiative", "1.3.1-1", ""),
            devices =
            listOf(
                device("Seestar S50 Pro_0000 Wide Angle Camera", DeviceType.Camera, 1),
                device("Seestar S50 Pro_0000 Wide Angle Focuser", DeviceType.Focuser, 1),
                device("Seestar S50 Pro_0000 Telephoto Camera", DeviceType.Camera, 0),
                device("Seestar S50 Pro_0000 Telephoto Focuser", DeviceType.Focuser, 0),
                device("Seestar S50 Pro_0000 Filter Wheel", DeviceType.FilterWheel, 0),
                device("Seestar S50 Pro_0000 Telescope", DeviceType.Telescope, 0),
                device("Seestar S50 Pro_0000 Switch", DeviceType.Switch, 0),
            ),
        )

    private val directory = Path(SystemTemporaryDirectory, "yacana-profiles-${ProfilePresets.newId()}")

    private fun device(name: String, type: DeviceType, number: Int) = ConfiguredDevice(name, type.name, type, number, "$type-$number")

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun theSeestarGetsItsReadyMadeProfile() {
        val profile = ProfilePresets.forServer(seestar, id = "s50")

        assertEquals("Seestar S50 Pro", profile.name)
        assertEquals(DeviceRef("http://10.0.0.1:32323", 0, "Camera-0", "Seestar S50 Pro_0000 Telephoto Camera"), profile.devices.camera)
        assertEquals(0, profile.devices.focuser?.number)
        assertEquals("Telescope-0", profile.devices.mount?.uniqueId)
        assertEquals("D50", profile.options.starDatabase)
        assertEquals(2.248, profile.optics.pixelScaleArcsec(pixelSizeMicrons = 2.9))
        assertNull(profile.location)
    }

    @Test
    fun theSeestarsWideAngleCameraHasAProfileOfItsOwn() {
        assertTrue(ProfilePresets.hasWideAngle(seestar))

        val profile = ProfilePresets.seestarS50ProWideAngle(seestar, "Seestar S50 Pro gran angular", id = "wide")

        assertEquals("Seestar S50 Pro gran angular", profile.name)
        assertEquals(DeviceRef("http://10.0.0.1:32323", 1, "Camera-1", "Seestar S50 Pro_0000 Wide Angle Camera"), profile.devices.camera)
        assertEquals("Focuser-1", profile.devices.focuser?.uniqueId)
        assertEquals("Telescope-0", profile.devices.mount?.uniqueId)
        assertEquals("Switch-0", profile.devices.switches?.uniqueId)
        // The filter wheel is in front of the telephoto lens only.
        assertNull(profile.devices.filterWheel)
        // About 33° × 59°, a field for ASTAP's W08.
        val (width, height) = profile.optics.fieldDeg()!!
        assertEquals(33.3, width, 0.1)
        assertEquals(59.2, height, 0.1)
        assertEquals("W08", profile.options.starDatabase)
        assertEquals(recommendedDatabase(height).name, profile.options.starDatabase)
    }

    @Test
    fun otherServersGetTheirFirstDeviceOfEachKind() {
        val omniSim =
            AlpacaServer(
                url = "http://localhost:32323",
                description = ServerDescription("OmniSim", "ASCOM Initiative", "0.5.0", ""),
                devices = listOf(device("Camera 2", DeviceType.Camera, 2), device("Camera 1", DeviceType.Camera, 1)),
            )

        val profile = ProfilePresets.forServer(omniSim)

        assertEquals("OmniSim", profile.name)
        assertEquals(1, profile.devices.camera?.number)
        assertNull(profile.devices.mount)
        assertEquals(Optics(), profile.optics)
    }

    @Test
    fun theScaleComesFromTheFocalLengthUnlessMeasured() {
        assertEquals(2.30, Optics(focalLengthMm = 260.0).pixelScaleArcsec(2.9)!!, 0.005)
        assertEquals(2.248, Optics(focalLengthMm = 260.0, measuredPixelScaleArcsec = 2.248).pixelScaleArcsec(2.9))
        assertNull(Optics().pixelScaleArcsec(2.9))
    }

    @Test
    fun aProfileSurvivesTheRoundTrip() {
        val profile =
            ProfilePresets.forServer(seestar, id = "s50").copy(
                location = Location(latitude = 40.0, longitude = -3.5, elevationM = 650.0),
                options = ProfileOptions(minAltitudeDeg = 15.0, horizon = listOf(HorizonPoint(90.0, 25.0))),
            )

        val text = ProfileFile.encode(profile)

        assertTrue(text.startsWith("{\n    \"kind\": \"profile\",\n    \"schemaVersion\": 1,"), text)
        assertEquals(ProfileFile.Decoded(profile, migrated = false), ProfileFile.decode(text))
    }

    @Test
    fun readsTheVersion1Sample() {
        val decoded = ProfileFile.decode(VERSION_1_SAMPLE)

        assertFalse(decoded.migrated)
        assertEquals("Seestar S50 Pro", decoded.profile.name)
        assertEquals(DeviceRef("http://10.0.0.1:32323", 0, "abc", "Telescope"), decoded.profile.devices.mount)
        assertEquals(listOf(HorizonPoint(180.0, 12.5)), decoded.profile.options.horizon)
        assertEquals(DEFAULT_GAIN, decoded.profile.options.gain, "a profile from before the gain was kept takes 200")
    }

    @Test
    fun aFileFromANewerVersionStillOpens() {
        val newer = VERSION_1_SAMPLE.replace(
            "\"schemaVersion\": 1",
            "\"schemaVersion\": 7",
        ).replace("\"options\": {", "\"options\": {\"future\": true,")

        assertEquals(ProfileFile.decode(VERSION_1_SAMPLE).profile, ProfileFile.decode(newer).profile)
    }

    @Test
    fun otherFilesAreRejected() {
        assertFailsWith<ProfileFormatException> { ProfileFile.decode("not json") }
        assertFailsWith<ProfileFormatException> { ProfileFile.decode("""{"kind": "sequence", "schemaVersion": 1}""") }
        assertFailsWith<ProfileFormatException> { ProfileFile.decode("""{"kind": "profile"}""") }
        assertFailsWith<ProfileFormatException> { ProfileFile.decode("""{"kind": "profile", "schemaVersion": 1, "name": "No id"}""") }
    }

    @Test
    fun theStoreSavesListsAndDeletes() {
        val store = ProfileStore(directory)
        val first = ProfilePresets.forServer(seestar, id = "b")
        val second = first.copy(id = "a", name = "Another setup")

        store.save(first)
        store.save(second)
        store.save(first.copy(options = ProfileOptions(starDatabase = "D05")))

        assertEquals(listOf("Another setup", "Seestar S50 Pro"), store.list().map { it.name })
        assertEquals("D05", store.load("b")?.options?.starDatabase)
        assertEquals(listOf("a.json", "b.json"), SystemFileSystem.list(directory).map { it.name }.sorted())

        store.delete("a")
        assertNull(store.load("a"))
        assertEquals(listOf("b"), store.list().map { it.id })
    }

    @Test
    fun theStoreSkipsFilesItCannotRead() {
        val store = ProfileStore(directory)
        store.save(ProfilePresets.forServer(seestar, id = "good"))
        SystemFileSystem.sink(Path(directory, "broken.json")).buffered().use { it.writeString("{\"kind\": \"prof") }

        assertEquals(listOf("good"), store.list().map { it.id })
    }

    @Test
    fun profileIdsCannotLeaveTheFolder() {
        assertFailsWith<IllegalArgumentException> { ProfileStore(directory).load("../secrets") }
    }

    @Test
    fun readsNinaHorizonFiles() {
        val text = "# Trees to the east\n0 10\n90\t25.5\n\n180 5\n270 12,5\n"

        assertEquals(
            listOf(HorizonPoint(0.0, 10.0), HorizonPoint(90.0, 25.5), HorizonPoint(180.0, 5.0), HorizonPoint(270.0, 12.5)),
            parseHorizon(text),
        )
        assertFailsWith<IllegalArgumentException> { parseHorizon("0 10\nnorth 12\n") }
        assertFailsWith<IllegalArgumentException> { parseHorizon("# empty\n") }
    }

    private companion object {
        /** A profile saved by schema version 1. Keep it as it is: it guards the migrations. */
        val VERSION_1_SAMPLE =
            """
            {
                "kind": "profile",
                "schemaVersion": 1,
                "id": "sample",
                "name": "Seestar S50 Pro",
                "devices": {
                    "mount": {"serverUrl": "http://10.0.0.1:32323", "number": 0, "uniqueId": "abc", "name": "Telescope"},
                    "camera": null,
                    "focuser": null,
                    "filterWheel": null,
                    "switches": null
                },
                "optics": {"focalLengthMm": 260.0, "apertureMm": 50.0, "measuredPixelScaleArcsec": 2.248},
                "location": null,
                "options": {
                    "sendTimeAndLocation": true,
                    "locationFromPhone": true,
                    "starDatabase": "D50",
                    "minAltitudeDeg": 0.0,
                    "horizon": [{"azimuthDeg": 180.0, "altitudeDeg": 12.5}]
                }
            }
            """.trimIndent()
    }
}
