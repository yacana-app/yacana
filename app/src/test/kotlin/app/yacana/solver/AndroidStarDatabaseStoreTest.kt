// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.yacana.FakeFrame
import app.yacana.domain.solver.ArrivalStep
import app.yacana.domain.solver.STAR_DATABASES
import app.yacana.domain.solver.StarDatabaseException
import app.yacana.domain.solver.StarDatabaseFiles
import app.yacana.ports.SolveRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * ASTAP's star databases on the phone: imported from a file, deleted, and
 * downloaded through the system's downloads, installed when they end even with the app closed.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStarDatabaseStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outside = File(context.cacheDir, "outside").apply { mkdirs() }

    @After
    fun tearDown() {
        scope.cancel()
        databaseFolder(context).deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun aDatabaseImportedFromAFileIsInstalled() {
        val store = AndroidStarDatabaseStore(context, scope)

        val name = runBlocking { store.import(Uri.fromFile(d05()).toString()) }

        assertEquals("D05", name)
        assertEquals(setOf("D05"), store.state.value.installed)
        assertEquals(setOf("D05"), AstapPlateSolver(context).installedDatabases())
    }

    @Test
    fun aFileWithoutADatabaseIsSaidToBeNone() {
        val store = AndroidStarDatabaseStore(context, scope)
        val notes = File(outside, "notes.zip").apply { writeBytes(zip("notes.txt")) }

        val failure = assertFailsWith<StarDatabaseException> { runBlocking { store.import(Uri.fromFile(notes).toString()) } }

        assertTrue(failure.notADatabase)
        assertTrue(store.state.value.installed.isEmpty())
    }

    @Test
    fun aDatabaseDeletedIsNoLongerOnThePhone() {
        val store = AndroidStarDatabaseStore(context, scope)
        runBlocking { store.import(Uri.fromFile(d05()).toString()) }

        store.delete("D05")

        until(store) { it.installed.isEmpty() }
    }

    @Test
    fun aDownloadThatEndedWithTheAppClosedIsInstalled() {
        val archive = File(context.getExternalFilesDir("downloads"), "d05_star_database.zip")
        d05().copyTo(archive)
        downloadEnded("D05", DownloadManager.STATUS_SUCCESSFUL, archive)

        val store = AndroidStarDatabaseStore(context, scope)

        val state = until(store) { "D05" in it.installed }
        assertNull(state.arriving["D05"])
        assertNull(state.failed)
    }

    @Test
    fun aDownloadTheSystemGaveUpOnIsSaidToHaveFailed() {
        downloadEnded("D20", DownloadManager.STATUS_FAILED, null)

        val store = AndroidStarDatabaseStore(context, scope)

        assertEquals("D20", until(store) { it.failed != null }.failed)
    }

    @Test
    fun aDownloadIsArrivingForTheProfileThatAskedUntilCancelled() {
        val store = AndroidStarDatabaseStore(context, scope)
        val d50 = STAR_DATABASES.first { it.name == "D50" }

        store.download(d50, "seestar-s50")
        val arrival = store.state.value.arriving["D50"]
        store.cancel("D50")

        // The system's downloads may have dropped it already, as nothing downloads here.
        assertTrue(arrival == null || (arrival.owner == "seestar-s50" && arrival.step == ArrivalStep.Downloading))
        assertTrue(store.state.value.arriving.isEmpty())
    }

    @Test
    fun aDatabaseNotOnThePhoneIsNotSolvedWith() {
        val failure = runCatching {
            runBlocking {
                AstapPlateSolver(context).solve(
                    FakeFrame(),
                    emptyList(),
                    SolveRequest(
                        database = "D80",
                        fieldHeightDeg = 1.3,
                        hint = null,
                        radiusDeg = 5.0,
                        downsample = 0,
                        timeout = 30.seconds,
                    ),
                )
            }
        }.exceptionOrNull()

        assertEquals("The D80 star database is not on the phone", failure?.message)
    }

    /** A download of [name] in the system's downloads, ended with [status] into [file], as the app keeps it between runs. */
    private fun downloadEnded(name: String, status: Int, file: File?) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse("https://example.org/$name.zip"))
        // Where Android puts it; the app reads only the file's name from it.
        file?.let { request.setDestinationUri(Uri.parse("file:///storage/emulated/0/Android/data/app.yacana/files/downloads/${it.name}")) }
        val id = manager.enqueue(request)
        shadowOf(shadowOf(manager).getRequest(id)).status = status
        context.getSharedPreferences("star_databases", Context.MODE_PRIVATE).edit().putString(name, "$id seestar-s50").commit()
    }

    /** D05 as ASTAP's site has it, in a ZIP outside the app. */
    private fun d05() = File(outside, "d05_star_database.zip").apply { writeBytes(zip("d05_0101.1476", "d05_0201.1476")) }

    private fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun until(store: AndroidStarDatabaseStore, holds: (StarDatabaseFiles) -> Boolean) =
        runBlocking { withTimeout(WAIT_MILLIS) { store.state.first(holds) } }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
