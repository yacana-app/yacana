// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import android.app.DownloadManager
import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import app.yacana.allocatableBytes
import app.yacana.domain.solver.Arrival
import app.yacana.domain.solver.ArrivalStep
import app.yacana.domain.solver.StarDatabase
import app.yacana.domain.solver.StarDatabaseException
import app.yacana.domain.solver.StarDatabaseFiles
import app.yacana.domain.solver.StarDatabaseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * The star databases in the app's `astap/` folder, one folder each, such as `astap/d50/`.
 * Downloads go through Android's download manager: only on WiFi, resuming after a cut, and on
 * while the app is closed, with their progress in a notification. Each one is installed when it
 * ends, the next time the app runs if it was closed.
 */
@Suppress("TooManyFunctions") // Downloads, installs, imports and deletes, of one folder.
class AndroidStarDatabaseStore(private val context: Context, private val scope: CoroutineScope) : StarDatabaseStore {
    private val root = databaseFolder(context)
    private val manager = context.getSystemService(DownloadManager::class.java)

    /** The downloads in progress: the database's name, then its download's id and the profile that asked. */
    private val prefs = context.getSharedPreferences("star_databases", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(StarDatabaseFiles())
    private var watching: Job? = null

    override val state: StateFlow<StarDatabaseFiles> = mutableState.asStateFlow()

    init {
        // What an install left when the app was closed in the middle.
        root.listFiles { file -> file.name.startsWith(DatabaseArchives.WORK) }?.forEach { it.deleteRecursively() }
        refresh()
        watch()
    }

    override fun download(database: StarDatabase, owner: String) {
        cancel(database.name)
        val request =
            DownloadManager
                .Request((SOURCE + database.file).toUri())
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setTitle("${database.name} · ASTAP")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, DOWNLOADS, database.file)
        val id = manager.enqueue(request)
        prefs.edit { putString(database.name, "$id $owner") }
        mutableState.update {
            it.copy(
                arriving = it.arriving + (database.name to Arrival(owner, ArrivalStep.Downloading, 0, database.downloadBytes)),
                failed = null,
            )
        }
        watch()
    }

    override fun cancel(name: String) {
        pending()[name]?.let { manager.remove(it.id) }
        prefs.edit { remove(name) }
        mutableState.update { it.copy(arriving = it.arriving - name) }
    }

    override fun delete(name: String) {
        scope.launch(Dispatchers.IO) {
            root.listFiles { file -> file.isDirectory && file.name.equals(name, ignoreCase = true) }?.forEach { it.deleteRecursively() }
            refresh()
        }
    }

    override suspend fun import(location: String): String = withContext(Dispatchers.IO) {
        val uri = location.toUri()
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L
        var name: String? = null
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $location")
            Progress(input) { read -> name?.let { arriving(it, Arrival("", ArrivalStep.Installing, read, size)) } }.use { stream ->
                DatabaseArchives.install(stream, root) { name = it }
            }
        } catch (e: IOException) {
            throw StarDatabaseException(notADatabase = false, e.message ?: e::class.simpleName.orEmpty())
        } finally {
            refresh(done = name)
        }
    }

    /** Follows the downloads every second while there are any, and installs each one that ends. */
    private fun watch() {
        if (watching?.isActive == true || pending().isEmpty()) return
        watching =
            scope.launch(Dispatchers.IO) {
                while (true) {
                    val downloads = pending()
                    if (downloads.isEmpty()) break
                    downloads.forEach { (name, download) -> follow(name, download) }
                    delay(1.seconds)
                }
            }
    }

    private fun follow(name: String, download: Download) {
        val query = DownloadManager.Query().setFilterById(download.id)
        val row =
            manager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))

                // The status and its reason are integer columns.
                fun int(column: String) = cursor.getInt(cursor.getColumnIndexOrThrow(column))
                Row(
                    status = int(DownloadManager.COLUMN_STATUS),
                    reason = int(DownloadManager.COLUMN_REASON),
                    done = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    total = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
            }
        when (row?.status) {
            DownloadManager.STATUS_SUCCESSFUL -> install(name, download)

            DownloadManager.STATUS_PAUSED -> {
                val step = if (row.reason ==
                    DownloadManager.PAUSED_WAITING_TO_RETRY
                ) {
                    ArrivalStep.Downloading
                } else {
                    ArrivalStep.WaitingForWifi
                }
                arriving(name, Arrival(download.owner, step, row.done, total(name, row)))
            }

            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING ->
                arriving(name, Arrival(download.owner, ArrivalStep.Downloading, row.done, total(name, row)))

            // Failed, or removed from the system's downloads.
            else -> {
                cancel(name)
                mutableState.update { it.copy(failed = name) }
            }
        }
    }

    private fun install(name: String, download: Download) {
        val archive = File(context.getExternalFilesDir(DOWNLOADS), fileOf(download.id) ?: "")
        val size = archive.length()
        try {
            Progress(archive.inputStream()) { read -> arriving(name, Arrival(download.owner, ArrivalStep.Installing, read, size)) }.use {
                DatabaseArchives.install(it, root)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not install $name", e)
            mutableState.update { it.copy(failed = name) }
        } catch (e: StarDatabaseException) {
            Log.w(TAG, "Could not install $name", e)
            mutableState.update { it.copy(failed = name) }
        } finally {
            // Removing the download deletes its file.
            manager.remove(download.id)
            prefs.edit { remove(name) }
            refresh(done = name)
        }
    }

    private fun fileOf(id: Long): String? = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.toUri()?.lastPathSegment
    }

    private fun arriving(name: String, arrival: Arrival) = mutableState.update { it.copy(arriving = it.arriving + (name to arrival)) }

    /** The size of a download; the database's own until the server has said it. */
    private fun total(name: String, row: Row): Long = row.total.takeIf { it > 0 } ?: state.value.arriving[name]?.totalBytes ?: 0

    /** Reads the folder again; [done] is no longer arriving, in the same change so that it is never neither. */
    private fun refresh(done: String? = null) {
        root.mkdirs()
        mutableState.update {
            it.copy(
                installed = databasesIn(root),
                arriving = if (done == null) it.arriving else it.arriving - done,
                freeBytes = allocatableBytes(context, root),
            )
        }
    }

    private fun pending(): Map<String, Download> = prefs.all.mapNotNull { (name, value) ->
        val (id, owner) = (value as? String)?.split(' ', limit = 2) ?: return@mapNotNull null
        id.toLongOrNull()?.let { name to Download(it, owner) }
    }.toMap()

    private data class Download(val id: Long, val owner: String)

    private class Row(val status: Int, val reason: Int, val done: Long, val total: Long)

    /** A stream that tells [onRead] how much has been read, every few megabytes. */
    private class Progress(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var read = 0L
        private var told = 0L

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count(it) }

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        private fun count(n: Int) {
            if (n <= 0) return
            read += n
            if (read - told >= STEP) {
                told = read
                onRead(read)
            }
        }

        private companion object {
            const val STEP = 8L shl 20
        }
    }

    private companion object {
        const val SOURCE = "https://downloads.sourceforge.net/project/astap-program/star_databases/"
        const val DOWNLOADS = "downloads"
        const val TAG = "StarDatabases"
    }
}

/** The folder of the star databases: one folder in it for each, named as its files. */
fun databaseFolder(context: Context): File = File(context.getExternalFilesDir(null), "astap")

/** The databases in [root] with their files, such as `D50`. */
fun databasesIn(root: File): Set<String> = root
    .listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
    .orEmpty()
    .filter { folder -> folder.listFiles()?.any { it.name.startsWith(folder.name + "_") } == true }
    .map { it.name.uppercase(Locale.ROOT) }
    .toSet()
