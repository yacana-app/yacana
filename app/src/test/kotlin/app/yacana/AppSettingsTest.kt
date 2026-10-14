// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.yacana.domain.profile.Profile
import app.yacana.imaging.AndroidFrameStore
import app.yacana.ports.EngineBuildInfo
import app.yacana.ports.Frame
import app.yacana.ports.HeaderCard
import app.yacana.ports.MasterFrame
import app.yacana.session.JournalFiles
import app.yacana.session.LogArchive
import app.yacana.ui.DisplaySettings
import app.yacana.ui.theme.YacanaThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app keeps on the phone between runs: the display options, where the frames go, the
 * session log of the last days and the ZIP of it the user can share.
 */
@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder: File = Files.createTempDirectory("yacana-journal").toFile()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun theAppOpensInRedModeIfItWasLeftInIt() {
        AppDisplaySettings(context).apply {
            setThemeMode(YacanaThemeMode.Red)
            setRedBrightness(0f)
            setMarkStars(true)
        }

        val again = AppDisplaySettings(context)

        assertEquals(YacanaThemeMode.Red, again.themeMode.value)
        assertEquals(DisplaySettings.MIN_RED_BRIGHTNESS, again.redBrightness.value)
        assertTrue(again.markStars.value)
    }

    @Test
    fun framesGoToTheAppsFolderUntilTheUserPicksOne() {
        val store = AndroidFrameStore(context)
        val expected = File(context.getExternalFilesDir(null), "Yacana")

        val saved = runBlocking { store.save(FakeFrame(), "M 31/Light/M 31_0001", emptyList()) }

        assertEquals(expected.path, store.location.value)
        assertEquals(File(expected, "M 31/Light/M 31_0001.fits").path, saved)
    }

    @Test
    fun aMasterFlatIsKeptInFlats() {
        val store = AndroidFrameStore(context)
        val written = object : MasterFrame {
            override fun add(frame: Frame) = 1

            override fun frames() = 1

            override fun flat(bias: MasterFrame?) = this

            override fun saveFits(path: String, headers: List<HeaderCard>) = File(path).writeText("FITS")

            override fun writeFitsTo(descriptor: Int, headers: List<HeaderCard>) = Unit

            override fun label() = ""

            override fun close() = Unit
        }

        val saved = runBlocking { store.saveMaster(written, "FLATS/MasterFlat_IR", emptyList()) }

        val file = File(context.getExternalFilesDir(null), "Yacana/FLATS/MasterFlat_IR.fits")
        assertEquals(file.path, saved)
        assertEquals("FITS", file.readText())
        // Read back on the phone through a descriptor, which Robolectric cannot hand over: here only one that is not there.
        assertNull(runBlocking { store.readMaster("FLATS/MasterFlat_LP") { written } }, "no flat of LP")
    }

    @Test
    fun aFolderPickedIsShownAsAPathTheUserKnows() {
        val store = AndroidFrameStore(context)

        store.choose(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAstro"))
        assertEquals("/storage/emulated/0/Astro", store.location.value)

        store.choose(Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3A"))
        assertEquals("/storage/1234-5678", store.location.value)
        // Kept for the next time, with the right to write there.
        assertEquals("/storage/1234-5678", AndroidFrameStore(context).location.value)
    }

    @Test
    fun theLogKeepsTheLastFourteenDays() {
        (1..15).forEach { File(folder, "yacana-2026-09-%02d.jsonl".format(it)).writeText("{}\n") }
        val journal = JournalFiles(folder)

        journal.write(
            "night_started",
            mapOf(
                "profile" to "Seestar S50",
                "targets" to listOf("M 31"),
                "where" to mapOf("alt" to 40),
                "none" to null,
            ),
        )

        assertEquals(14, journal.files().size)
        val line = journal.read().last()
        assertTrue("\"event\":\"night_started\"" in line && "\"targets\":[\"M 31\"]" in line && "\"where\":{\"alt\":40}" in line, line)
    }

    @Test
    fun theLogsZipHasTheLogsTheProfileAndTheVersion() {
        val journal = JournalFiles(folder).apply { write("night_started", emptyMap()) }
        val profile = Profile(id = "seestar-s50", name = "Seestar S50")
        val zip = ByteArrayOutputStream()

        LogArchive.write(zip, journal.files(), profile, "0.1.2", EngineBuildInfo(version = "0.1.2", arch = "aarch64"))

        val entries = ZipInputStream(zip.toByteArray().inputStream()).use { input ->
            generateSequence { input.nextEntry }.associate { it.name to input.readBytes().decodeToString() }
        }
        assertEquals(setOf("version.txt", "profile.json", "logs/${journal.files().single().name}"), entries.keys)
        assertTrue(entries.getValue("version.txt").startsWith("Yacana 0.1.2\nPixel engine 0.1.2 (aarch64)\n"))
        assertTrue("\"name\": \"Seestar S50\"" in entries.getValue("profile.json"))
    }
}
