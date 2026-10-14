// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import app.yacana.alpaca.AlpacaClient.Companion.alpacaDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The typed devices against canned answers. Values are keyed by member and are
 * JSON literals; [NOT_IMPLEMENTED] answers with error 0x400, as the Seestar does.
 */
class AlpacaDevicesTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(values: Map<String, String> = emptyMap()): AlpacaClient {
        val engine =
            MockEngine { request ->
                requests += request
                val member = request.url.encodedPath.substringAfterLast('/')
                val value = values[member]
                val body =
                    when (value) {
                        null -> """{"ErrorNumber":0,"ErrorMessage":""}"""
                        NOT_IMPLEMENTED -> """{"ErrorNumber":1024,"ErrorMessage":"Not implemented"}"""
                        else -> """{"Value":$value,"ErrorNumber":0,"ErrorMessage":""}"""
                    }
                respond(body, headers = headersOf("Content-Type", "application/json"))
            }
        return AlpacaClient(HttpClient(engine) { alpacaDefaults() }, "http://seestar.local:32323", clientId = 7u)
    }

    private fun HttpRequestData.form(): Map<String, String> = (body as OutgoingContent.ByteArrayContent)
        .bytes()
        .decodeToString()
        .split('&')
        .associate { it.substringBefore('=') to it.substringAfter('=') }
        .filterKeys { it != "ClientID" && it != "ClientTransactionID" }

    @Test
    fun goToSendsTheCaseSensitiveParameters() = runTest {
        Telescope(client(), 0).slewToCoordinatesAsync(rightAscension = 0.7123, declination = 41.27)

        val put = requests.single()
        assertEquals(HttpMethod.Put, put.method)
        assertEquals("/api/v1/telescope/0/slewtocoordinatesasync", put.url.encodedPath)
        assertEquals(mapOf("RightAscension" to "0.7123", "Declination" to "41.27"), put.form())
    }

    @Test
    fun readsTheSeestarMountCapabilities() = runTest {
        val telescope =
            Telescope(
                client(
                    mapOf(
                        "alignmentmode" to "1",
                        "equatorialsystem" to "1",
                        "canfindhome" to "true",
                        "canpark" to "true",
                        "canunpark" to "true",
                        "cansetpark" to "false",
                        "canpulseguide" to "true",
                        "cansettracking" to "true",
                        "canslewasync" to "true",
                        "cansync" to "true",
                        "canmoveaxis" to "true",
                        "trackingrates" to "[0,1,2]",
                    ),
                ),
                0,
            )

        val capabilities = telescope.capabilities()

        assertEquals(AlignmentMode.Polar, capabilities.alignmentMode)
        assertEquals(EquatorialSystem.Topocentric, capabilities.equatorialSystem)
        assertEquals(TelescopeAxis.entries.toSet(), capabilities.movableAxes)
        assertEquals(listOf(DriveRate.Sidereal, DriveRate.Lunar, DriveRate.Solar), capabilities.trackingRates)
        val axes = requests.filter { it.url.encodedPath.endsWith("/canmoveaxis") }.map { it.url.parameters["Axis"] }
        assertEquals(listOf("0", "1", "2"), axes)
    }

    @Test
    fun membersTheMountDoesNotImplementAreNull() = runTest {
        val seestar = Telescope(client(mapOf("sideofpier" to NOT_IMPLEMENTED, "siteelevation" to NOT_IMPLEMENTED)), 0)
        assertNull(seestar.sideOfPier())
        assertNull(seestar.siteElevation())

        assertNull(Telescope(client(mapOf("sideofpier" to "-1")), 0).sideOfPier())
        assertEquals(PierSide.West, Telescope(client(mapOf("sideofpier" to "1")), 0).sideOfPier())
    }

    @Test
    fun otherDeviceErrorsAreNotHidden() = runTest {
        val engine = MockEngine { respond("""{"ErrorNumber":1031,"ErrorMessage":"Not connected"}""", headers = JSON) }
        val telescope = Telescope(AlpacaClient(HttpClient(engine) { alpacaDefaults() }, "http://mount"), 0)

        val error = assertFailsWith<AlpacaException> { telescope.sideOfPier() }
        assertEquals(AlpacaException.NOT_CONNECTED, error.errorNumber)
    }

    @Test
    fun readsAndWritesUtcDates() = runTest {
        val expected = Instant.parse("2026-10-04T21:30:00.123456700Z")
        assertEquals(expected, Telescope(client(mapOf("utcdate" to "\"2026-10-04T21:30:00.1234567Z\"")), 0).utcDate())
        assertEquals(expected, Telescope(client(mapOf("utcdate" to "\"2026-10-04T21:30:00.1234567\"")), 0).utcDate())

        Telescope(client(), 0).setUtcDate(expected)
        assertEquals("2026-10-04T21%3A30%3A00.123Z", requests.last().form()["UTCDate"])
    }

    @Test
    fun pulseGuideSendsMilliseconds() = runTest {
        Telescope(client(), 0).pulseGuide(GuideDirection.East, 1500.milliseconds)

        assertEquals(mapOf("Direction" to "2", "Duration" to "1500"), requests.single().form())
    }

    @Test
    fun readsTheSeestarCamera() = runTest {
        val info = Camera(client(SEESTAR_CAMERA), 0).info()

        assertEquals(SensorType.Rggb, info.sensorType)
        assertEquals("GRBG", info.bayerPattern)
        assertEquals(GainControl.Range(0, 600), info.gain)
        assertEquals(GainControl.None, info.offset)
        assertTrue(info.hasCcdTemperature)
        assertEquals(listOf("0"), info.readoutModes)
    }

    @Test
    fun namedGainsWinOverTheRange() = runTest {
        val values = SEESTAR_CAMERA + mapOf("gains" to """["Low","High"]""", "sensortype" to "0")

        val info = Camera(client(values), 0).info()

        assertEquals(GainControl.Named(listOf("Low", "High")), info.gain)
        assertNull(info.bayerPattern)
        assertNull(info.bayerOffsetX)
    }

    @Test
    fun bayerPatternFollowsTheOffsets() {
        val rggb = SEESTAR_INFO.copy(bayerOffsetX = 0, bayerOffsetY = 0)
        assertEquals("RGGB", rggb.bayerPattern)
        assertEquals("GBRG", rggb.copy(bayerOffsetY = 1).bayerPattern)
        assertEquals("BGGR", rggb.copy(bayerOffsetX = 1, bayerOffsetY = 1).bayerPattern)
    }

    @Test
    fun exposureAndSubframeParameters() = runTest {
        val camera = Camera(client(), 0)
        camera.startExposure(20.0)
        camera.setBinning(Binning(2, 2))
        camera.setSubframe(Subframe(10, 20, 640, 480))

        val forms = requests.map { it.url.encodedPath.substringAfterLast('/') to it.form() }
        assertEquals(
            listOf(
                "startexposure" to mapOf("Duration" to "20.0", "Light" to "true"),
                "binx" to mapOf("BinX" to "2"),
                "biny" to mapOf("BinY" to "2"),
                "startx" to mapOf("StartX" to "10"),
                "starty" to mapOf("StartY" to "20"),
                "numx" to mapOf("NumX" to "640"),
                "numy" to mapOf("NumY" to "480"),
            ),
            forms,
        )
    }

    @Test
    fun filterWheelPositionIsNullWhileMoving() = runTest {
        assertNull(FilterWheel(client(mapOf("position" to "-1")), 0).position())
        assertEquals(2, FilterWheel(client(mapOf("position" to "2")), 0).position())
        assertEquals(listOf("Dark", "IR", "LP"), FilterWheel(client(mapOf("names" to """["Dark","IR","LP"]""")), 0).names())
    }

    @Test
    fun theSeestarDewHeaterIsOnOrOff() = runTest {
        val heater =
            mapOf(
                "getswitchname" to "\"Dew heater\"",
                "getswitchdescription" to "\"Dew heater\"",
                "canwrite" to "true",
                "minswitchvalue" to "0",
                "maxswitchvalue" to "1",
                "switchstep" to "1",
            )
        val switches = SwitchDevice(client(heater), 0)

        val info = switches.info(0)
        switches.setOn(0, true)

        assertTrue(info.isOnOff)
        assertEquals("0", requests.first().url.parameters["Id"])
        assertEquals(mapOf("Id" to "0", "State" to "true"), requests.last().form())
        assertTrue(!info.copy(max = 100.0).isOnOff)
    }

    @Test
    fun theFlatPanelReadsItsCoverAndLightAndSendsTheBrightness() = runTest {
        val panel = CoverCalibrator(client(mapOf("coverstate" to "3", "calibratorstate" to "1", "maxbrightness" to "255")), 0)

        assertEquals(CoverStatus.Open, panel.coverState())
        assertEquals(CalibratorStatus.Off, panel.calibratorState())
        assertEquals(255, panel.maxBrightness())
        panel.calibratorOn(102)

        assertEquals(mapOf("Brightness" to "102"), requests.last().form())
        assertTrue(requests.last().url.encodedPath.endsWith("/covercalibrator/0/calibratoron"))
    }

    @Test
    fun theSafetyMonitorSaysWhetherItIsSafe() = runTest {
        assertTrue(SafetyMonitor(client(mapOf("issafe" to "true")), 0).isSafe())
    }

    @Test
    fun unknownEnumValuesAreProtocolErrors() = runTest {
        assertFailsWith<AlpacaProtocolException> { Camera(client(mapOf("camerastate" to "9")), 0).state() }
        assertEquals(CameraState.Download, Camera(client(mapOf("camerastate" to "4")), 0).state())
    }

    @Test
    fun wholeNumbersMaySendDecimals() = runTest {
        assertEquals(600, Camera(client(mapOf("gain" to "600.0")), 0).gain())
    }

    private companion object {
        const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        val JSON = headersOf("Content-Type", "application/json")

        /** What the Seestar S50 Pro's telephoto camera answered in spike S3. */
        val SEESTAR_CAMERA =
            mapOf(
                "sensorname" to "\"\"",
                "cameraxsize" to "2160",
                "cameraysize" to "3840",
                "pixelsizex" to "2.9",
                "pixelsizey" to "2.9",
                "sensortype" to "2",
                "bayeroffsetx" to "1",
                "bayeroffsety" to "0",
                "maxbinx" to "1",
                "maxbiny" to "1",
                "canasymmetricbin" to "false",
                "gains" to "[]",
                "gainmin" to "0",
                "gainmax" to "600",
                "offsets" to NOT_IMPLEMENTED,
                "offsetmin" to "0",
                "offsetmax" to "0",
                "exposuremin" to "3e-05",
                "exposuremax" to "2000",
                "canabortexposure" to "true",
                "canstopexposure" to "true",
                "cansetccdtemperature" to "false",
                "ccdtemperature" to "24.6875",
                "cangetcoolerpower" to "false",
                "maxadu" to "65535",
                "readoutmodes" to """["0"]""",
            )

        val SEESTAR_INFO =
            CameraInfo(
                sensorName = "",
                width = 2160,
                height = 3840,
                pixelSizeX = 2.9,
                pixelSizeY = 2.9,
                sensorType = SensorType.Rggb,
                bayerOffsetX = 1,
                bayerOffsetY = 0,
                maxBinX = 1,
                maxBinY = 1,
                canAsymmetricBin = false,
                gain = GainControl.Range(0, 600),
                offset = GainControl.None,
                exposureMin = 3e-5,
                exposureMax = 2000.0,
                canAbortExposure = true,
                canStopExposure = true,
                canSetCcdTemperature = false,
                hasCcdTemperature = true,
                canGetCoolerPower = false,
                maxAdu = 65535,
                readoutModes = listOf("0"),
            )
    }
}
