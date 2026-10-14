// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.yacana.allocatableBytes
import app.yacana.domain.sky.Hips2Fits
import app.yacana.domain.sky.InstalledCache
import app.yacana.domain.sky.MapLayer
import app.yacana.domain.sky.NINA_CACHES
import app.yacana.domain.sky.NinaCache
import app.yacana.domain.sky.ONLINE_CACHE
import app.yacana.domain.sky.OnlinePhoto
import app.yacana.domain.sky.SkyCacheException
import app.yacana.domain.sky.SkyCacheFiles
import app.yacana.domain.sky.SkyCacheStore
import app.yacana.domain.sky.SkyLayer
import app.yacana.domain.solver.Arrival
import app.yacana.domain.solver.ArrivalStep
import app.yacana.ports.SkyCache
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.seconds

/**
 * N.I.N.A.'s sky caches in the app's `sky/` folder: ZIPs, read where they are, and
 * folders as NINA keeps them on the PC. Downloads go through Android's download manager, as the
 * star databases', to `sky/.downloads/` and into `sky/` once complete. Imports are copied
 * into a hidden name and renamed once complete, so a half cache never shows.
 */
@Suppress("TooManyFunctions") // Downloads, imports and what the map shows, of one folder.
class AndroidSkyCaches(private val context: Context, private val scope: CoroutineScope) : SkyCacheStore {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "sky")
    private val manager = context.getSystemService(DownloadManager::class.java)

    /** The downloads in progress by file, the caches hidden from the map, each layer's opacity, and WiFi only. */
    private val prefs = context.getSharedPreferences("sky_caches", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(SkyCacheFiles())
    private val mutableLayers = MutableStateFlow<List<MapLayer>>(emptyList())
    private val open = mutableMapOf<String, SkyCache>()
    private var watching: Job? = null

    /** The photos online while they are not kept: in the app's cache folder, gone when it starts again. */
    private val passing = File(context.cacheDir, "sky-online")
    private var passingCache: OnlineSkyCache? = null
    private val http by lazy { OkHttpClient.Builder().readTimeout(ONLINE_TIMEOUT_S, TimeUnit.SECONDS).build() }

    override val state: StateFlow<SkyCacheFiles> = mutableState.asStateFlow()
    override val layers: StateFlow<List<MapLayer>> = mutableLayers.asStateFlow()

    init {
        // What an import left when the app was closed in the middle.
        root.listFiles { file -> file.name.startsWith(IMPORTING) }?.forEach { it.deleteRecursively() }
        passing.deleteRecursively()
        scope.launch(Dispatchers.IO) { refresh() }
        watch()
    }

    override fun download(cache: NinaCache) {
        cancel(cache.file)
        val networks =
            if (state.value.wifiOnly) {
                DownloadManager.Request.NETWORK_WIFI
            } else {
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            }
        val request =
            DownloadManager
                .Request((SOURCE + cache.file).toUri())
                .setAllowedNetworkTypes(networks)
                .setTitle("${cache.file.removeSuffix(".zip")} · NINA")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, DOWNLOADS, cache.file)
        val id = manager.enqueue(request)
        prefs.edit { putLong(DOWNLOAD + cache.file, id) }
        mutableState.update {
            it.copy(arriving = it.arriving + (cache.file to Arrival("", ArrivalStep.Downloading, 0, cache.bytes)), failed = null)
        }
        watch()
    }

    override fun cancel(name: String) {
        downloads()[name]?.let { manager.remove(it) }
        prefs.edit { remove(DOWNLOAD + name) }
        mutableState.update { it.copy(arriving = it.arriving - name) }
    }

    override suspend fun import(location: String, folder: Boolean): String = withContext(Dispatchers.IO) {
        val uri = location.toUri()
        val work = File(root, IMPORTING + System.nanoTime())
        try {
            val name = if (folder) importFolder(uri, work) else importZip(uri, work)
            val target = File(root, name)
            if (target.exists()) {
                open.remove(name)?.close()
                target.deleteRecursively()
            }
            if (!work.renameTo(target)) throw IOException("Cannot move $name into place")
            prefs.edit { remove(HIDDEN + name) }
            name
        } catch (e: IOException) {
            throw SkyCacheException(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            work.deleteRecursively()
            // Downloads are never installing: what is, is this import, done or not.
            mutableState.update { it.copy(arriving = it.arriving.filterValues { arrival -> arrival.step != ArrivalStep.Installing }) }
            refresh()
        }
    }

    override fun delete(name: String) {
        scope.launch(Dispatchers.IO) {
            prefs.edit { putBoolean(HIDDEN + name, true) }
            // Off the map and closed first, then gone.
            refresh()
            File(root, name).deleteRecursively()
            prefs.edit { remove(HIDDEN + name) }
            refresh()
        }
    }

    override fun use(name: String, inUse: Boolean) {
        prefs.edit { if (inUse) remove(HIDDEN + name) else putBoolean(HIDDEN + name, true) }
        scope.launch(Dispatchers.IO) { refresh() }
    }

    override fun setOpacity(layer: SkyLayer, opacity: Float) {
        prefs.edit { putFloat(OPACITY + layer.name, opacity) }
        mutableState.update { it.copy(opacity = it.opacity + (layer to opacity)) }
        mutableLayers.update { layers -> layers.map { if (it.layer == layer) it.copy(opacity = opacity) else it } }
    }

    override fun setWifiOnly(wifiOnly: Boolean) {
        prefs.edit { putBoolean(WIFI_ONLY, wifiOnly) }
        mutableState.update { it.copy(wifiOnly = wifiOnly) }
    }

    override fun setFetchOnline(fetch: Boolean) {
        prefs.edit { putBoolean(FETCH_ONLINE, fetch) }
        mutableState.update { it.copy(fetchOnline = fetch) }
    }

    override fun setSaveOnline(save: Boolean) {
        prefs.edit { putBoolean(SAVE_ONLINE, save) }
        mutableState.update { it.copy(saveOnline = save) }
        scope.launch(Dispatchers.IO) { refresh() }
    }

    override suspend fun fetchOnline(photo: OnlinePhoto): Boolean = withContext(Dispatchers.IO) {
        val metered = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        val off = !state.value.fetchOnline || prefs.getBoolean(HIDDEN + ONLINE_CACHE, false)
        if (off || (state.value.wifiOnly && metered)) return@withContext false
        mutableState.update { it.copy(fetchingOnline = true) }
        try {
            // N.I.N.A.'s second server first, as it does, and its first if that one fails.
            val jpeg = Hips2Fits.SERVERS.firstNotNullOfOrNull { fetch(Hips2Fits.url(it, photo)) } ?: return@withContext false
            onlineCache().add(photo, jpeg)
            refresh()
            true
        } finally {
            mutableState.update { it.copy(fetchingOnline = false) }
        }
    }

    override suspend fun export(name: String, location: String) = withContext(Dispatchers.IO) {
        val folder = File(root, name)
        try {
            val output = context.contentResolver.openOutputStream(location.toUri(), "wt") ?: throw IOException("Cannot write $location")
            // With the folder inside, as N.I.N.A.'s own ZIPs, so that it unpacks as a cache and imports back.
            ZipOutputStream(output.buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                folder.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry("$name/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            throw SkyCacheException(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** A photo from hips2fits, or null if it did not come as one. */
    private fun fetch(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.body.bytes().takeIf { response.isSuccessful && response.header("Content-Type").orEmpty().startsWith("image/") }
        }
    }.onFailure { Log.i(TAG, "No photo online: ${it.message}") }.getOrNull()

    /** Where a photo online goes: the folder the map keeps them in, or the app's cache folder while they are not kept. */
    @Synchronized
    private fun onlineCache(): OnlineSkyCache = if (state.value.saveOnline) {
        open[ONLINE_CACHE] as? OnlineSkyCache ?: OnlineSkyCache(File(root, ONLINE_CACHE)).also { open[ONLINE_CACHE] = it }
    } else {
        passingCache ?: OnlineSkyCache(passing).also { passingCache = it }
    }

    /** A ZIP copied as it is; it must hold NINA's index. */
    private fun importZip(uri: android.net.Uri, work: File): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) (it.getString(0) ?: "") to (if (it.isNull(1)) 0L else it.getLong(1)) else null
        } ?: ("" to 0L)
        val (fileName, size) = name.first.ifBlank { "cache.zip" } to name.second
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $fileName")
        input.use { copy(it, work.outputStream(), fileName, size) }
        val isCache = runCatching {
            ZipFile(work).use { zip -> zip.entries().asSequence().any { it.name.endsWith(NinaCacheIndex.FILE) } }
        }.getOrDefault(false)
        if (!isCache) throw IOException("$fileName has no ${NinaCacheIndex.FILE}")
        return fileName
    }

    /** A folder copied file by file; it must hold NINA's index. */
    private fun importFolder(uri: android.net.Uri, work: File): String {
        val folder = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Cannot open the folder")
        val files = folder.listFiles().filter { it.isFile }
        if (files.none { it.name.equals(NinaCacheIndex.FILE, ignoreCase = true) }) {
            throw IOException("${folder.name} has no ${NinaCacheIndex.FILE}")
        }
        val name = folder.name ?: "Cache"
        val total = files.sumOf { it.length() }
        work.mkdirs()
        var done = 0L
        files.forEach { file ->
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                File(work, file.name!!).outputStream().use { output -> done += input.copyTo(output) }
            }
            arriving(name, Arrival("", ArrivalStep.Installing, done, total))
        }
        return name
    }

    private fun copy(input: InputStream, output: OutputStream, name: String, size: Long) = output.use {
        val buffer = ByteArray(BUFFER)
        var done = 0L
        var told = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            it.write(buffer, 0, read)
            done += read
            if (done - told >= STEP) {
                told = done
                arriving(name, Arrival("", ArrivalStep.Installing, done, size))
            }
        }
    }

    /** Follows the downloads every second while there are any, and moves each one that ends into place. */
    private fun watch() {
        if (watching?.isActive == true || downloads().isEmpty()) return
        watching =
            scope.launch(Dispatchers.IO) {
                while (true) {
                    val downloads = downloads()
                    if (downloads.isEmpty()) break
                    downloads.forEach { (name, id) -> follow(name, id) }
                    delay(1.seconds)
                }
            }
    }

    private fun follow(name: String, id: Long) {
        val row =
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))

                // The status and its reason are integer columns.
                fun int(column: String) = cursor.getInt(cursor.getColumnIndexOrThrow(column)).toLong()
                listOf(
                    int(DownloadManager.COLUMN_STATUS),
                    int(DownloadManager.COLUMN_REASON),
                    long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
            }
        val total = row?.get(TOTAL)?.takeIf { it > 0 } ?: NINA_CACHES.firstOrNull { it.file == name }?.bytes ?: 0
        when (row?.get(STATUS)?.toInt()) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                val file = File(context.getExternalFilesDir(DOWNLOADS), name)
                val moved = file.renameTo(File(root, name))
                // Removing the download deletes its file, which is no longer there once moved.
                manager.remove(id)
                prefs.edit { remove(DOWNLOAD + name) }
                if (!moved) mutableState.update { it.copy(failed = name) }
                mutableState.update { it.copy(arriving = it.arriving - name) }
                refresh()
            }

            DownloadManager.STATUS_PAUSED -> {
                val retrying = row[REASON] == DownloadManager.PAUSED_WAITING_TO_RETRY.toLong()
                arriving(name, Arrival("", if (retrying) ArrivalStep.Downloading else ArrivalStep.WaitingForWifi, row[DONE], total))
            }

            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING ->
                arriving(name, Arrival("", ArrivalStep.Downloading, row[DONE], total))

            // Failed, or removed from the system's downloads.
            else -> {
                cancel(name)
                mutableState.update { it.copy(failed = name) }
            }
        }
    }

    private fun arriving(name: String, arrival: Arrival) = mutableState.update { it.copy(arriving = it.arriving + (name to arrival)) }

    /** Reads the folder and the settings again, and opens the caches in use for the map. */
    @Synchronized
    private fun refresh() {
        root.mkdirs()
        val installed =
            root.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") && it.name !in downloads() }
                .filter { (it.isFile && it.name.endsWith(".zip", ignoreCase = true)) || File(it, NinaCacheIndex.FILE).isFile }
                .map { InstalledCache(it.name, layerOf(it.name), !prefs.getBoolean(HIDDEN + it.name, false), sizeOf(it), it.isDirectory) }
                // The whole sky first and narrowband over it; the photos online over the rest of their layer.
                .sortedWith(compareBy<InstalledCache> { it.layer }.thenBy { it.name == ONLINE_CACHE })
        val opacity = SkyLayer.entries.associateWith { prefs.getFloat(OPACITY + it.name, SkyCacheFiles.DEFAULT_OPACITY.getValue(it)) }
        mutableState.update {
            it.copy(
                installed = installed,
                freeBytes = allocatableBytes(context, root),
                wifiOnly = prefs.getBoolean(WIFI_ONLY, true),
                opacity = opacity,
                fetchOnline = prefs.getBoolean(FETCH_ONLINE, true),
                saveOnline = prefs.getBoolean(SAVE_ONLINE, true),
            )
        }
        val shown = installed.filter { it.inUse }
        val kept =
            shown.mapNotNull { cache ->
                val opened = open[cache.name] ?: openCache(File(root, cache.name))?.also { open[cache.name] = it }
                opened?.let { MapLayer(it, cache.layer, opacity.getValue(cache.layer), online = cache.name == ONLINE_CACHE) }
            }
        val passingLayer =
            passingCache?.takeIf { !state.value.saveOnline && !prefs.getBoolean(HIDDEN + ONLINE_CACHE, false) }?.let {
                MapLayer(it, SkyLayer.Dss, opacity.getValue(SkyLayer.Dss), online = true)
            }
        // Sorting keeps the order within a layer: the passing photos go over the rest of the whole sky.
        mutableLayers.value = (kept + listOfNotNull(passingLayer)).sortedBy { it.layer }
        // Closed once the map no longer has them.
        (open.keys - shown.map { it.name }.toSet()).forEach { open.remove(it)?.close() }
    }

    private fun openCache(file: File): SkyCache? = runCatching {
        when {
            file.name == ONLINE_CACHE -> OnlineSkyCache(file)
            file.isDirectory -> FolderSkyCache.open(file)
            else -> ZipSkyCache.open(file)
        }
    }
        .onFailure { Log.w(TAG, "Cannot open ${file.name}", it) }
        .getOrNull()

    /** NINA's caches by their file; others by their name, which says narrowband when they are. */
    private fun layerOf(name: String): SkyLayer {
        val narrowband = name.contains("Narrowband", ignoreCase = true) || name.contains("NSNS", ignoreCase = true)
        return NINA_CACHES.firstOrNull { it.file == name }?.layer ?: if (narrowband) SkyLayer.Narrowband else SkyLayer.Dss
    }

    private fun sizeOf(file: File): Long = if (file.isFile) file.length() else file.walk().filter { it.isFile }.sumOf { it.length() }

    /** The downloads in progress, by file, with the download manager's id. */
    private fun downloads(): Map<String, Long> = prefs.all
        .filterKeys { it.startsWith(DOWNLOAD) }
        .mapNotNull { (key, value) -> (value as? Long)?.let { key.removePrefix(DOWNLOAD) to it } }
        .toMap()

    private companion object {
        const val SOURCE = "https://nighttime-imaging.eu/downloads/Setup/Releases/"
        const val DOWNLOADS = "sky/.downloads"
        const val IMPORTING = ".importing-"
        const val DOWNLOAD = "download:"
        const val HIDDEN = "hidden:"
        const val OPACITY = "opacity:"
        const val WIFI_ONLY = "wifiOnly"
        const val FETCH_ONLINE = "fetchOnline"
        const val SAVE_ONLINE = "saveOnline"

        /** hips2fits takes about 25 s to start sending a photo of 3,000 pixels. */
        const val ONLINE_TIMEOUT_S = 60L
        const val TAG = "SkyCaches"
        const val BUFFER = 1 shl 16
        const val STEP = 16L shl 20
        const val STATUS = 0
        const val REASON = 1
        const val DONE = 2
        const val TOTAL = 3
    }
}
