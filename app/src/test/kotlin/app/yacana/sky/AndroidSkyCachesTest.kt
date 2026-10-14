// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.yacana.domain.sky.NINA_CACHES
import app.yacana.domain.sky.SkyCacheException
import app.yacana.domain.sky.SkyLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sky caches on the phone: imported from a ZIP, shown on the map or not,
 * deleted, exported for N.I.N.A., downloaded through the system's downloads, and the map's settings.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSkyCachesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root = File(context.getExternalFilesDir(null), "sky")
    private val outside = File(context.cacheDir, "outside").apply { mkdirs() }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun aZipImportedIsOnTheMap() {
        val caches = AndroidSkyCaches(context, scope)

        val name = runBlocking { caches.import(Uri.fromFile(zip(withIndex = true)).toString(), folder = false) }

        val installed = until(caches.state) { it.installed.isNotEmpty() }.installed.single()
        assertEquals(name, installed.name)
        assertTrue(installed.inUse)
        assertEquals(SkyLayer.Dss, installed.layer)
        assertEquals(1, until(caches.layers) { it.isNotEmpty() }.single().tiles.size)
    }

    @Test
    fun aZipWithoutNinasIndexIsNotImported() {
        val caches = AndroidSkyCaches(context, scope)

        val failure = assertFailsWith<SkyCacheException> {
            runBlocking { caches.import(Uri.fromFile(zip(withIndex = false)).toString(), folder = false) }
        }

        assertTrue(failure.message!!.endsWith("has no CacheInfo.xml"), failure.message)
        assertTrue(caches.state.value.installed.isEmpty())
    }

    @Test
    fun aCacheTakenOffTheMapStaysOnThePhoneUntilDeleted() {
        val caches = AndroidSkyCaches(context, scope)
        folderCache("Mine")
        caches.use("Mine", true)
        until(caches.layers) { it.isNotEmpty() }

        caches.use("Mine", false)
        assertFalse(until(caches.state) { it.installed.singleOrNull()?.inUse == false }.installed.single().inUse)
        until(caches.layers) { it.isEmpty() }

        caches.delete("Mine")
        until(caches.state) { it.installed.isEmpty() }
        assertFalse(File(root, "Mine").exists())
    }

    @Test
    fun aFolderCacheIsExportedAsNinasZips() {
        val caches = AndroidSkyCaches(context, scope)
        folderCache("Mine")
        val target = File(outside, "export.zip")

        runBlocking { caches.export("Mine", Uri.fromFile(target).toString()) }

        val entries = ZipFile(target).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertEquals(setOf("Mine/CacheInfo.xml", "Mine/M 31.jpg"), entries)
    }

    @Test
    fun theMapsSettingsAreKeptForTheNextTime() {
        val caches = AndroidSkyCaches(context, scope)

        caches.setOpacity(SkyLayer.Narrowband, 0.65f)
        caches.setWifiOnly(false)
        caches.setSaveOnline(false)

        val again = AndroidSkyCaches(context, scope)
        val state = until(again.state) { it.opacity[SkyLayer.Narrowband] == 0.65f }
        assertFalse(state.wifiOnly)
        assertFalse(state.saveOnline)
    }

    @Test
    fun aDownloadThatEndedWithTheAppClosedIsMovedIntoPlace() {
        val nina = NINA_CACHES.first()
        downloadEnded(nina.file, DownloadManager.STATUS_SUCCESSFUL)
        zip(withIndex = true).copyTo(File(context.getExternalFilesDir("sky/.downloads"), nina.file))

        val caches = AndroidSkyCaches(context, scope)

        val state = until(caches.state) { it.installed.any { cache -> cache.name == nina.file } }
        assertNull(state.arriving[nina.file])
        assertTrue(File(root, nina.file).isFile)
        assertEquals(-1, downloadId(nina.file))
    }

    @Test
    fun aDownloadTheSystemGaveUpOnIsSaidToHaveFailed() {
        val nina = NINA_CACHES.last()
        downloadEnded(nina.file, DownloadManager.STATUS_FAILED)

        val caches = AndroidSkyCaches(context, scope)

        assertEquals(nina.file, until(caches.state) { it.failed != null }.failed)
        assertTrue(caches.state.value.arriving.isEmpty())
    }

    /** A download of [file] in the system's downloads, ended with [status], as the app keeps it between runs. */
    private fun downloadEnded(file: String, status: Int) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val id = manager.enqueue(DownloadManager.Request(Uri.parse("https://example.org/$file")))
        shadowOf(shadowOf(manager).getRequest(id)).status = status
        prefs().edit().putLong("download:$file", id).commit()
    }

    private fun downloadId(file: String) = prefs().getLong("download:$file", -1)

    private fun prefs() = context.getSharedPreferences("sky_caches", Context.MODE_PRIVATE)

    @Test
    fun aDownloadCancelledIsNoLongerArriving() {
        val caches = AndroidSkyCaches(context, scope)
        val nina = NINA_CACHES.last()

        caches.download(nina)
        caches.cancel(nina.file)

        assertTrue(caches.state.value.arriving.isEmpty())
    }

    /** A cache in a folder of the app's, as an import leaves it. */
    private fun folderCache(name: String) {
        File(root, name).apply {
            mkdirs()
            File(this, NinaCacheIndex.FILE).writeText(INDEX)
            File(this, "M 31.jpg").writeBytes(byteArrayOf(1, 2, 3))
        }
    }

    /** A ZIP outside the app, as N.I.N.A.'s, with its index or not. */
    private fun zip(withIndex: Boolean): File {
        val file = File(outside, "Cache.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            if (withIndex) {
                out.putNextEntry(ZipEntry("Cache/" + NinaCacheIndex.FILE))
                out.write(INDEX.toByteArray())
                out.closeEntry()
            }
            out.putNextEntry(ZipEntry("Cache/M 31.jpg"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
        }
        return file
    }

    /** The first value of [flow] that [holds], within ten seconds. */
    private fun <T> until(flow: StateFlow<T>, holds: (T) -> Boolean): T = runBlocking { withTimeout(WAIT_MILLIS) { flow.first(holds) } }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val INDEX = """<?xml version="1.0" encoding="utf-8"?>
<ImageCacheInfo>
  <Image Id="1" RA="0.7123" Dec="41.269" Rotation="0" FoVW="60" FoVH="60" FileName="M 31.jpg" Source="Hips2FitsSurvey" Name="M 31" />
</ImageCacheInfo>"""
    }
}
