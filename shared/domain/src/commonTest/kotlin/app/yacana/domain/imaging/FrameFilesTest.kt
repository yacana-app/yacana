// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.alpaca.CameraInfo
import app.yacana.alpaca.DriveRate
import app.yacana.alpaca.GainControl
import app.yacana.alpaca.SensorType
import app.yacana.domain.equipment.MountState
import app.yacana.domain.profile.Location
import app.yacana.domain.profile.Optics
import app.yacana.ports.HeaderValue
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class FrameFilesTest {
    private val seestar =
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

    private val capture =
        Capture(
            type = FrameType.Light,
            // 00:30 in Madrid on 5 October: the night of 4 October.
            start = Instant.parse("2026-10-04T22:30:05.123456Z"),
            exposureSeconds = 20.0,
            camera = seestar,
            cameraName = "Seestar S50 Pro Telephoto Camera",
            gain = 80,
            sensorTemperature = 12.34,
            target = "M 31",
            filter = "IR",
            focuserPosition = 1520,
            mount =
            MountState(
                rightAscensionHours = 0.712222,
                declinationDeg = 41.269167,
                altitudeDeg = 52.17,
                azimuthDeg = 78.53,
                siderealTimeHours = 1.5,
                slewing = false,
                tracking = true,
                trackingRate = DriveRate.Sidereal,
                atPark = false,
                atHome = false,
            ),
            mountName = "Seestar S50 Pro",
            site = Location(latitude = 12.34, longitude = 56.78, elevationM = 100.0),
            optics = Optics(focalLengthMm = 260.0, apertureMm = 50.0),
            software = "Yacana 0.1.0",
        )

    @Test
    fun aStackTakesStackAsItsTypeAndNumberSoItNeverHasAFramesName() {
        val madrid = TimeZone.of("Europe/Madrid")

        assertEquals(
            "2026-10-04/M 31/STACK/2026-10-05_00-30-05_IR_20.00s_STACK",
            FileNames.renderStack(FileNames.DEFAULT_PATTERN, capture, madrid),
        )
        assertEquals("M 31/2026-10-05_00-30-05_STACK", FileNames.renderStack("\$\$TARGETNAME\$\$/\$\$DATETIME\$\$", capture, madrid))
    }

    @Test
    fun theHeaderHasNinasKeywords() {
        val headers = frameHeaders(capture).associate { it.key to it.value }

        assertEquals(HeaderValue.Text("LIGHT"), headers["IMAGETYP"])
        assertEquals(HeaderValue.Real(20.0), headers["EXPTIME"])
        assertEquals(HeaderValue.Text("2026-10-04T22:30:05.123"), headers["DATE-OBS"])
        assertEquals(HeaderValue.Text("GRBG"), headers["BAYERPAT"])
        assertEquals(HeaderValue.Text("TOP-DOWN"), headers["ROWORDER"])
        assertEquals(HeaderValue.Integer(80), headers["GAIN"])
        assertEquals(HeaderValue.Real(0.712222 * 15), headers["RA"])
        assertEquals(HeaderValue.Text("00 42 44"), headers["OBJCTRA"])
        assertEquals(HeaderValue.Text("+41 16 09"), headers["OBJCTDEC"])
        assertEquals(HeaderValue.Real(5.2), headers["FOCRATIO"])
        assertEquals(HeaderValue.Text("IR"), headers["FILTER"])
        // No offset control on the Seestar, so no OFFSET keyword.
        assertFalse("OFFSET" in headers)
    }

    @Test
    fun aMonoCameraHasNoBayerPattern() {
        val mono = capture.copy(camera = seestar.copy(sensorType = SensorType.Monochrome, bayerOffsetX = null, bayerOffsetY = null))

        assertFalse(frameHeaders(mono).any { it.key == "BAYERPAT" })
    }

    @Test
    fun datesAndAnglesAreFormattedAsFitsExpects() {
        assertEquals("2026-10-04T22:00:00.000", fitsDate(Instant.parse("2026-10-04T22:00:00Z")))
        assertEquals("-05 23 28", sexagesimal(-5.391111, signed = true))
        assertEquals("23 59 59", sexagesimal(23.99972, signed = false))
    }

    @Test
    fun theExampleShowsWhatThePatternGives() {
        val path = FileNames.example(FileNames.DEFAULT_PATTERN, Instant.parse("2026-10-04T20:30:00Z"), TimeZone.of("Europe/Madrid"))

        assertEquals("2026-10-04/M 31/LIGHT/2026-10-04_22-30-00_LP_20.00s_0001", path)
    }

    @Test
    fun theDefaultPatternMakesAFolderPerNightAndTarget() {
        val path = FileNames.render(FileNames.DEFAULT_PATTERN, capture, frameNumber = 7, zone = TimeZone.of("Europe/Madrid"))

        assertEquals("2026-10-04/M 31/LIGHT/2026-10-05_00-30-05_IR_20.00s_0007", path)
    }

    @Test
    fun namesWithoutATargetOrFilterStillWork() {
        val snapshot = capture.copy(target = null, filter = " ")

        val path = FileNames.render(FileNames.DEFAULT_PATTERN, snapshot, frameNumber = 1, zone = TimeZone.UTC)

        assertEquals("2026-10-04/Snapshot/LIGHT/2026-10-04_22-30-05_NoFilter_20.00s_0001", path)
    }

    @Test
    fun forbiddenCharactersAndEmptyFoldersAreRemoved() {
        val odd = capture.copy(target = "NGC 7000: \"North America\"?")

        val path = FileNames.render(
            "\$\$TARGETNAME\$\$//\$\$GAIN\$\$_\$\$UNKNOWN\$\$\\\$\$BINNING\$\$",
            odd,
            frameNumber = 1,
            zone = TimeZone.UTC,
        )

        assertEquals("NGC 7000_ _North America__/80_\$\$UNKNOWN\$\$/1x1", path)
    }
}
