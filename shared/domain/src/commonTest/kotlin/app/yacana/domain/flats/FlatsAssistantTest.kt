// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.flats

import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentPlatform
import app.yacana.domain.equipment.FakeAlpaca
import app.yacana.domain.equipment.fakeHttp
import app.yacana.domain.imaging.CaptureController
import app.yacana.domain.imaging.FakeEngine
import app.yacana.domain.imaging.FakeStore
import app.yacana.domain.imaging.seestarCamera
import app.yacana.domain.imaging.seestarFilterWheel
import app.yacana.domain.profile.DeviceRef
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileDevices
import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.profile.ProfileStore
import app.yacana.ports.HeaderCard
import app.yacana.ports.HeaderValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FlatsAssistantTest {
    /** The exposure of the last frame the camera was asked for. */
    private var seconds = 0.0

    /** How bright the light is, in ADU per second; the dawn sky's grows. */
    private var light: () -> Double = { 20_000.0 }

    private val seestar =
        FakeAlpaca("seestar").apply {
            seestarCamera()
            seestarFilterWheel(1)
            val camera = onPut
            onPut = { member, form ->
                if (member == "camera/0/startexposure") seconds = form.getValue("Duration").toDouble()
                camera(member, form)
            }
        }
    private val engine = FakeEngine().apply { mean = { (seconds * light()).coerceAtMost(65_535.0) } }
    private val store = FakeStore()
    private val directory = Path(SystemTemporaryDirectory, "yacana-flats-${ProfilePresets.newId()}")

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private suspend fun TestScope.assistant(panel: Boolean = false): FlatsAssistant {
        val http = fakeHttp(StandardTestDispatcher(testScheduler), seestar)
        val platform =
            object : EquipmentPlatform {
                override fun httpClient() = http

                override val discovery = AlpacaDiscovery { emptyList() }

                override fun networkHandle(): Long? = null

                override suspend fun location() = null
            }
        val equipment = EquipmentController(ProfileStore(directory), platform, backgroundScope)
        equipment.save(
            Profile(
                id = "p",
                name = "Seestar S50 Pro",
                devices =
                ProfileDevices(
                    camera = DeviceRef(seestar.url, 0, "camera-0", "Seestar Telephoto Camera"),
                    filterWheel = DeviceRef(seestar.url, 0, "filterwheel-0", "Seestar Filter Wheel"),
                    flatPanel = if (panel) DeviceRef(seestar.url, 0, "panel-1", "Flat panel") else null,
                ),
                options = ProfileOptions(sendTimeAndLocation = false),
            ),
        )
        equipment.connect()
        equipment.state.first { it.connection is Connection.Connected }
        val capture = CaptureController(equipment, engine, store, platform, backgroundScope, "Yacana test", zone = { TimeZone.UTC })
        return FlatsAssistant(equipment, capture, backgroundScope)
    }

    private suspend fun FlatsAssistant.toTheEnd(options: FlatsOptions): FlatsState {
        start(options)
        return state.first { it != null && it.phase in setOf(FlatsPhase.Done, FlatsPhase.Failed, FlatsPhase.Stopped) }!!
    }

    private fun saved(type: String) = store.saves.filter { (_, headers) -> headers.text("IMAGETYP") == type }

    private fun List<HeaderCard>.text(key: String) = (firstOrNull { it.key == key }?.value as? HeaderValue.Text)?.value

    private fun List<HeaderCard>.real(key: String) = (firstOrNull { it.key == key }?.value as? HeaderValue.Real)?.value

    @Test
    fun eachFilterFindsItsExposureAndKeepsOnlyFlatsWithinFivePercent() = runTest {
        val flats = assistant().toTheEnd(FlatsOptions(FlatSource.Screen, targetAdu = 30_000, count = 3, filters = listOf("IR", "LP")))

        assertEquals(FlatsPhase.Done, flats.phase)
        // From 1 s at 20 000 ADU a second: 1.5 s gives 30 000.
        assertEquals(listOf(1.5, 1.5), flats.filters.map { it.exposureSeconds })
        assertEquals(listOf(3, 3), flats.filters.map { it.done })
        val kept = saved("FLAT")
        assertEquals(6, kept.size)
        assertEquals(listOf("IR", "IR", "IR", "LP", "LP", "LP"), kept.map { (_, headers) -> headers.text("FILTER") })
        assertTrue(kept.all { (_, headers) -> headers.real("EXPOSURE") == 1.5 })
    }

    @Test
    fun thePhonesScreenIsTheLightOnlyWhileItsFlatsRun() {
        val running = FlatsState(FlatsOptions(FlatSource.Screen, targetAdu = 30_000), FlatsPhase.Taking, emptyList())

        assertTrue(running.screenLit)
        assertFalse(running.copy(paused = true).screenLit)
        assertFalse(running.copy(phase = FlatsPhase.Done).screenLit)
        assertFalse(running.copy(options = running.options.copy(source = FlatSource.Sky)).screenLit)
    }

    @Test
    fun asTheDawnSkyBrightensTheExposureFollowsIt() = runTest {
        var frames = 0
        light = { 20_000.0 * (1 + 0.02 * frames++) }

        val flats = assistant().toTheEnd(FlatsOptions(FlatSource.Sky, targetAdu = 30_000, count = 10))

        assertEquals(FlatsPhase.Done, flats.phase)
        val exposures = saved("FLAT").mapNotNull { (_, headers) -> headers.real("EXPOSURE") }
        assertEquals(10, exposures.size)
        assertTrue(exposures.zipWithNext().all { (a, b) -> b <= a }, "$exposures")
        // Each kept within ±5 % of the target as the sky brightened; the bias after them are of no light.
        assertEquals(10, flats.filters.single().done)
    }

    @Test
    fun tooMuchLightAtTheShortestExposureSaysSo() = runTest {
        // Saturated even at the camera's shortest, 30 µs.
        light = { 1e12 }

        val flats = assistant().toTheEnd(FlatsOptions(FlatSource.Sky, targetAdu = 30_000, count = 3))

        assertEquals(FlatsPhase.Failed, flats.phase)
        assertEquals(FlatsFailure.TooBright, flats.failure)
        assertTrue(saved("FLAT").isEmpty())
    }

    @Test
    fun tooLittleLightAtAMinuteSaysSoWithoutLongerExposures() = runTest {
        light = { 7.0 }

        val flats = assistant().toTheEnd(FlatsOptions(FlatSource.Panel, targetAdu = 30_000, count = 3))

        assertEquals(FlatsFailure.TooDark, flats.failure)
        // 1 s, then 8 s, then a minute, the longest: never the camera's 2000 s.
        assertTrue(seconds <= 60.0, "$seconds")
    }

    @Test
    fun darksWithEachExposureAndBiasFollowBehindTheDarkFilter() = runTest {
        val flats = assistant().toTheEnd(
            FlatsOptions(FlatSource.Screen, targetAdu = 30_000, count = 2, filters = listOf("IR"), darks = true),
        )

        assertEquals(FlatsPhase.Done, flats.phase)
        assertEquals(2, saved("FLAT").size)
        val darks = saved("DARK")
        assertEquals(2, darks.size)
        assertTrue(darks.all { (_, headers) -> headers.real("EXPOSURE") == 1.5 && headers.text("FILTER") == "Dark" })
        assertEquals(10, saved("BIAS").size)
    }

    @Test
    fun theFlatsEndWithTenBiasBehindTheDarkFilterSoTheirLevelIsKnown() = runTest {
        val flats = assistant().toTheEnd(FlatsOptions(FlatSource.Screen, targetAdu = 30_000, count = 2, filters = listOf("IR")))

        assertEquals(FlatsPhase.Done, flats.phase)
        assertTrue(saved("DARK").isEmpty(), "no darks unless asked")
        assertEquals(10, saved("BIAS").size)
    }

    @Test
    fun withAPanelItsCoverClosesAndItsLightGoesOnAtItsBrightnessAndOffAtTheEnd() = runTest {
        seestar.devices += Triple("CoverCalibrator", 0, "panel-1")
        mapOf("coverstate" to "3", "calibratorstate" to "1", "brightness" to "0", "maxbrightness" to "255")
            .forEach { (member, value) -> seestar.values["covercalibrator/0/$member"] = value }
        val camera = seestar.onPut
        seestar.onPut = { member, form ->
            when (member) {
                "covercalibrator/0/closecover" -> seestar.values["covercalibrator/0/coverstate"] = "1"
                "covercalibrator/0/calibratoron" -> seestar.values["covercalibrator/0/calibratorstate"] = "3"
                "covercalibrator/0/calibratoroff" -> seestar.values["covercalibrator/0/calibratorstate"] = "1"
            }
            camera(member, form)
        }

        val flats = assistant(panel = true).toTheEnd(
            FlatsOptions(FlatSource.Panel, targetAdu = 30_000, count = 2, filters = listOf("IR"), panelBrightness = 128),
        )

        assertEquals(FlatsPhase.Done, flats.phase)
        val panel = seestar.puts.filter { it.first.startsWith("covercalibrator/0/") && it.first != "covercalibrator/0/connected" }
        assertEquals(listOf("closecover", "calibratoron", "calibratoroff"), panel.map { it.first.substringAfterLast('/') })
        assertEquals("128", panel[1].second["Brightness"])
    }

    @Test
    fun pausedTheNextFrameWaitsAndStoppedTheFlatsEnd() = runTest {
        val flats = assistant()
        flats.start(FlatsOptions(FlatSource.Screen, targetAdu = 30_000, count = 50, filters = listOf("IR")))
        flats.state.first { it?.phase == FlatsPhase.Taking }

        flats.pause()
        assertTrue(flats.state.value!!.paused)
        val before = store.saves.size
        advanceTimeBy(10.minutes)
        // The frame on its way when paused is the last one.
        assertTrue(store.saves.size <= before + 1)

        flats.resume()
        assertFalse(flats.state.value!!.paused)
        flats.stop()

        assertEquals(FlatsPhase.Stopped, flats.state.value!!.phase)
        val stopped = store.saves.size
        advanceTimeBy(10.minutes)
        assertEquals(stopped, store.saves.size)
    }
}
