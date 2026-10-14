// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import android.content.SharedPreferences
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads a large file, resumable: it grows as `<name>.part` and is renamed when complete.
 * A later call continues the part with an HTTP range request if the server's ETag is the same;
 * otherwise the server sends the whole file again and the part starts from zero.
 *
 * @param prefs where the ETag of each target is kept between runs.
 * @param onProgress called about once a second with the bytes written so far and the total.
 */
class CacheDownloader(
    private val client: OkHttpClient,
    private val prefs: SharedPreferences,
    private val onProgress: (written: Long, total: Long) -> Unit,
) {
    /** Downloads [url] to [target]. Throws [IOException] with a message for the user if it fails. */
    suspend fun download(url: String, target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val (request, resumeFrom) = request(url, target, part)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) fail("HTTP ${response.code} downloading ${target.name}")
            // 206 means the server continues the part; 200 means it starts again from zero.
            val offset = if (response.code == HTTP_PARTIAL) resumeFrom else 0L
            val total = offset + response.body.contentLength()
            response.header("ETag")?.let { prefs.edit { putString(KEY_ETAG + target.name, it) } }
            checkSpace(target, total - offset)
            onProgress(offset, total)
            val written = FileOutputStream(part, offset > 0).use { out -> copy(response.body.byteStream(), out, offset, total) }
            if (written != total) fail("The download stopped at ${written / MB} of ${total / MB} MB")
        }
        if (!part.renameTo(target)) fail("Could not rename ${part.name}")
    }

    /** The request, with a range that continues [part] when the server file has the same ETag. */
    private fun request(url: String, target: File, part: File): Pair<Request, Long> {
        val etag = prefs.getString(KEY_ETAG + target.name, null)
        val resumeFrom = if (part.isFile && etag != null) part.length() else 0L
        val builder = Request.Builder().url(url)
        if (resumeFrom > 0 && etag != null) builder.header("Range", "bytes=$resumeFrom-").header("If-Range", etag)
        return builder.build() to resumeFrom
    }

    /** Copies the response to the file and returns the bytes written, including [offset]. */
    private suspend fun copy(input: InputStream, out: FileOutputStream, offset: Long, total: Long): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = offset
        var lastUpdate = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            coroutineContext.ensureActive()
            out.write(buffer, 0, read)
            written += read
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate >= UPDATE_MILLIS) {
                lastUpdate = now
                onProgress(written, total)
            }
            read = input.read(buffer)
        }
        onProgress(written, total)
        return written
    }

    private fun checkSpace(target: File, needed: Long) {
        val folder = target.parentFile ?: return
        val free = StatFs(folder.path).availableBytes
        if (needed > free - SPACE_MARGIN_BYTES) fail("Not enough space: ${needed / MB} MB needed")
    }

    private fun fail(message: String): Nothing = throw IOException(message)

    private companion object {
        const val KEY_ETAG = "etag:"
        const val HTTP_PARTIAL = 206
        const val BUFFER_BYTES = 256 * 1024
        const val UPDATE_MILLIS = 1_000L
        const val MB = 1_000_000L
        const val SPACE_MARGIN_BYTES = 200 * MB
    }
}
