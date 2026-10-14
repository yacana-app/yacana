// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sky cache's download, resumable: against a server inside OkHttp that answers as
 * the cache's does, with its ETag and its ranges, and without the network.
 */
@RunWith(RobolectricTestRunner::class)
class CacheDownloaderTest {
    private val folder: File = Files.createTempDirectory("yacana-download").toFile()
    private val target = File(folder, "cache.zip")
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("downloads", Context.MODE_PRIVATE)

    /** What the server was asked, in order. */
    private val requests = mutableListOf<Request>()
    private val progress = mutableListOf<Pair<Long, Long>>()

    @Before
    fun space() {
        // About 400 MB free, as StatFs sees the folder.
        ShadowStatFs.registerStats(folder, FREE_BLOCKS, FREE_BLOCKS, FREE_BLOCKS)
    }

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun theFileArrivesWholeAndItsEtagIsKept() {
        download(server())

        assertContentEquals(FILE, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
        assertEquals(ETAG, prefs.getString("etag:cache.zip", null))
        assertNull(requests.single().header("Range"))
        assertEquals(FILE.size.toLong() to FILE.size.toLong(), progress.last())
    }

    @Test
    fun aPartWithTheSameEtagIsContinued() {
        prefs.edit().putString("etag:cache.zip", ETAG).commit()
        File(target.path + ".part").writeBytes(FILE.copyOf(4))

        download(server())

        assertEquals("bytes=4-", requests.single().header("Range"))
        assertEquals(ETAG, requests.single().header("If-Range"))
        assertContentEquals(FILE, target.readBytes())
        assertEquals(4L to FILE.size.toLong(), progress.first())
    }

    @Test
    fun aPartOfAnotherFileStartsAgain() {
        prefs.edit().putString("etag:cache.zip", "\"old\"").commit()
        File(target.path + ".part").writeBytes(byteArrayOf(9, 9, 9, 9))

        download(server())

        assertContentEquals(FILE, target.readBytes())
    }

    @Test
    fun anErrorOfTheServerIsSaid() {
        val failure = assertFailsWith<IOException> { download(server(code = 404)) }

        assertEquals("HTTP 404 downloading cache.zip", failure.message)
        assertFalse(target.exists())
    }

    @Test
    fun aDownloadCutShortIsSaidAndKeptToContinue() {
        val failure = assertFailsWith<IOException> { download(server(cutAt = 6)) }

        assertTrue(failure.message!!.startsWith("The download stopped at"), failure.message)
        assertFalse(target.exists())
        assertEquals(6, File(target.path + ".part").length())
    }

    @Test
    fun withoutSpaceNothingIsWritten() {
        ShadowStatFs.registerStats(folder, 1, 1, 1)

        val failure = assertFailsWith<IOException> { download(server()) }

        assertTrue(failure.message!!.startsWith("Not enough space"), failure.message)
    }

    private fun download(client: OkHttpClient) = runBlocking {
        CacheDownloader(client, prefs) { written, total -> progress += written to total }.download(URL, target)
    }

    /**
     * The cache's server: [FILE] with its ETag, from where a range asks when its If-Range is the
     * ETag; [code] for an error; and a body that says it is whole but ends at [cutAt].
     */
    private fun server(code: Int = 200, cutAt: Int? = null) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val from = request.header("Range")?.takeIf { request.header("If-Range") == ETAG }
                ?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            val body = FILE.copyOfRange(from ?: 0, FILE.size)
            val sent = cutAt?.let { body.copyOf(it) } ?: body
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(
                    if (code != 200) {
                        code
                    } else if (from != null) {
                        206
                    } else {
                        200
                    },
                )
                .message("")
                .header("ETag", ETAG)
                .body(Body(sent, body.size.toLong()))
                .build()
        }
        .build()

    /** A body of [bytes] that says it is [length] long, as a connection cut short. */
    private class Body(private val bytes: ByteArray, private val length: Long) : okhttp3.ResponseBody() {
        override fun contentType() = "application/zip".toMediaType()

        override fun contentLength() = length

        override fun source() = bytes.toResponseBody().source()
    }

    private companion object {
        const val URL = "https://example.org/cache.zip"
        const val ETAG = "\"cache-v1\""
        const val FREE_BLOCKS = 100_000
        val FILE = ByteArray(10) { it.toByte() }
    }
}
