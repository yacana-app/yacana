// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.ports.ColourStretch
import app.yacana.ports.FrameStatistics
import app.yacana.ports.HeaderValue
import app.yacana.ports.Stretch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CalibrationTest {
    private val engine = FakeEngine().apply { stacking = true }
    private val store = FakeStore()
    private val night = Instant.parse("2026-10-05T00:30:00Z")

    private fun calibration(store: FrameStore? = this.store) = Calibration(engine, store, zone = { TimeZone.UTC })

    /** A frame of the Seestar at gain 80: [type], [exposure] long, with the IR filter and the sensor at [temperature]. */
    private fun frame(
        type: FrameType,
        exposure: Double = 60.0,
        temperature: Double? = 18.0,
        filter: String? = "IR",
        start: Instant = night,
    ) = CapturedFrame(
        frame = TestFrame(),
        capture = seestar(type, start, exposure, temperature, filter),
        statistics = FrameStatistics(0, 65535, 1000.0, 1000.0, 10.0, 20.0),
        autoStretch = ColourStretch.linked(Stretch(0.01f, 0.1f)),
        histogram = LongArray(0),
        savedAs = "saved",
    )

    private fun seestar(type: FrameType, start: Instant, exposure: Double, temperature: Double?, filter: String?) = Capture(
        type = type,
        start = start,
        exposureSeconds = exposure,
        camera = SEESTAR_CAMERA,
        cameraName = "Seestar",
        gain = 80,
        sensorTemperature = temperature,
        filter = filter,
        software = "test",
    )

    @Test
    fun aLightGetsTheDarksOfItsSettingsAndNightAtTheClosestTemperature() = runTest {
        val calibration = calibration()
        repeat(3) { calibration.offer(frame(FrameType.Dark, temperature = 20.0)) }
        calibration.offer(frame(FrameType.Bias))
        repeat(2) { calibration.offer(frame(FrameType.Dark, temperature = 14.0)) }
        calibration.offer(frame(FrameType.Dark, exposure = 30.0, temperature = 14.0))

        val cold = calibration.forLight(frame(FrameType.Light, temperature = 15.0).capture).first
        assertEquals(14.0, cold?.temperatureC)
        assertEquals(2, (cold?.master as FakeMaster).frames)
        assertEquals(20.0, calibration.forLight(frame(FrameType.Light, temperature = 19.0).capture).first?.temperatureC)
        assertNull(calibration.forLight(frame(FrameType.Light, exposure = 120.0).capture).first, "no darks of 120 s")
        val tomorrow = Instant.parse("2026-10-06T00:30:00Z")
        assertNull(calibration.forLight(frame(FrameType.Light, start = tomorrow).capture).first, "darks are a night's")
    }

    @Test
    fun withoutAStackingEngineDarksTakeNoMemory() = runTest {
        engine.stacking = false
        val calibration = calibration()

        calibration.offer(frame(FrameType.Dark))

        assertTrue(engine.masters.isEmpty())
        assertNull(calibration.forLight(frame(FrameType.Light).capture).first)
    }

    @Test
    fun theFlatsOfEachFilterBecomeItsMasterFlatInFlatsForTheNextNights() = runTest {
        val calibration = calibration()
        // As the flats assistant takes them at home, one filter after the other, and ends.
        repeat(3) { calibration.offer(frame(FrameType.Flat, exposure = 2.0)) }
        repeat(2) { calibration.offer(frame(FrameType.Flat, exposure = 9.0, filter = "LP")) }

        calibration.finish()

        val flat = calibration.forLight(frame(FrameType.Light).capture).second
        assertEquals(LocalDate(2026, 10, 5), flat?.night, "the day the flats were taken")
        assertEquals("FLATS/MasterFlat_IR.fits", flat?.file)
        assertEquals(false, (flat?.master as FakeMaster).bias)
        assertEquals(3, flat.master.frames())
        assertEquals(setOf("FLATS/MasterFlat_IR", "FLATS/MasterFlat_LP"), store.masters.keys)
        val headers = store.masters.getValue("FLATS/MasterFlat_IR").second.associate { it.key to it.value }
        assertEquals(HeaderValue.Text("Master Flat"), headers["IMAGETYP"])
        assertEquals(HeaderValue.Integer(3), headers["NCOMBINE"])
        assertEquals(HeaderValue.Text("IR"), headers["FILTER"])

        // The next night, after the app was closed: each filter's own flat, read from FLATS.
        val later = calibration()
        val ir = later.forLight(frame(FrameType.Light).capture).second
        assertEquals(LocalDate(2026, 10, 5), ir?.night)
        assertEquals(3, ir?.master?.frames())
        assertEquals(2, later.forLight(frame(FrameType.Light, filter = "LP").capture).second?.master?.frames())
        assertNull(later.forLight(frame(FrameType.Light, filter = "Ha").capture).second, "no flat of Ha")
    }

    @Test
    fun flatsWithTheirBiasAreMadeWhenTheLightsCome() = runTest {
        val calibration = calibration(store = null)
        repeat(2) { calibration.offer(frame(FrameType.Flat, exposure = 2.0)) }
        calibration.offer(frame(FrameType.Bias))

        calibration.lightCame()

        val flat = calibration.forLight(frame(FrameType.Light).capture).second
        assertEquals(true, (flat?.master as FakeMaster).bias)
        assertTrue(store.masters.isEmpty(), "no store, nothing kept")
    }
}
