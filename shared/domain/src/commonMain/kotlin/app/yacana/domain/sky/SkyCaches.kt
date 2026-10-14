// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.domain.solver.Arrival
import app.yacana.ports.SkyCache
import app.yacana.ports.SkyTile
import kotlinx.coroutines.flow.StateFlow

/** The map's layers: the photos of the whole sky, and narrowband over them. */
enum class SkyLayer { Dss, Narrowband }

/** One of N.I.N.A.'s official sky caches: its file on NINA's server, its size, its layer, and whether it has stars. */
data class NinaCache(val file: String, val bytes: Long, val layer: SkyLayer, val stars: Boolean = true)

/** The caches NINA publishes. */
val NINA_CACHES =
    listOf(
        NinaCache("FramingAssistantCache_Full.zip", 3_526_916_102, SkyLayer.Dss),
        NinaCache("FramingAssistantCache_NorthernSkyNarrowbandSurvey_OHS_withStars.zip", 1_428_767_069, SkyLayer.Narrowband),
        NinaCache("FramingAssistantCache_NorthernSkyNarrowbandSurvey_OHS_starless.zip", 471_106_178, SkyLayer.Narrowband, stars = false),
    )

/** The folder of the photos fetched online, as N.I.N.A.'s own cache but apart from one imported from it. */
const val ONLINE_CACHE = "FramingAssistantCache_Online"

/**
 * A cache on the phone, a ZIP or a [folder] by its [name]: its layer, whether the map shows it,
 * and how much it takes.
 */
data class InstalledCache(val name: String, val layer: SkyLayer, val inUse: Boolean, val bytes: Long, val folder: Boolean = false)

/**
 * The caches on the phone and those [arriving], by name; the free space where they go; the last
 * download or import that [failed]; whether downloads wait for WiFi; each layer's opacity; whether
 * the map asks for photos online when zoomed in, whether they stay on the phone, and whether one is
 * on its way.
 */
data class SkyCacheFiles(
    val installed: List<InstalledCache> = emptyList(),
    val arriving: Map<String, Arrival> = emptyMap(),
    val freeBytes: Long = 0,
    val failed: String? = null,
    val wifiOnly: Boolean = true,
    val opacity: Map<SkyLayer, Float> = DEFAULT_OPACITY,
    val fetchOnline: Boolean = true,
    val saveOnline: Boolean = true,
    val fetchingOnline: Boolean = false,
) {
    companion object {
        /** As the design: the whole sky solid, narrowband faint over it. */
        val DEFAULT_OPACITY = mapOf(SkyLayer.Dss to 1f, SkyLayer.Narrowband to 0.4f)
    }
}

/**
 * A cache the map draws, with its layer's opacity and its photos now: those [online] grow as they
 * arrive.
 */
data class MapLayer(
    val cache: SkyCache,
    val layer: SkyLayer,
    val opacity: Float,
    val tiles: List<SkyTile> = cache.tiles,
    val online: Boolean = false,
)

/**
 * N.I.N.A.'s sky caches on the phone: downloaded from NINA's server, on WiFi
 * only if asked, resuming after a cut and on while the app is closed, or imported from a ZIP or a
 * folder, also from a card. ZIPs are read where they are, without unpacking. A cache that arrives
 * is shown at once; each can be hidden.
 */
interface SkyCacheStore {
    val state: StateFlow<SkyCacheFiles>

    /** The caches in use, open, for the map, the whole sky first. */
    val layers: StateFlow<List<MapLayer>>

    fun download(cache: NinaCache)

    /** Stops a download and forgets what it had. */
    fun cancel(name: String)

    /**
     * Copies the cache at [location], a ZIP or a folder in NINA's format, to the phone, and
     * returns its name.
     * @throws SkyCacheException if it is not a cache or cannot be read.
     */
    suspend fun import(location: String, folder: Boolean): String

    /** Deletes [name] from the phone, to free its space; NINA's can be downloaded again. */
    fun delete(name: String)

    /** Shows [name] on the map, or not. */
    fun use(name: String, inUse: Boolean)

    fun setOpacity(layer: SkyLayer, opacity: Float)

    fun setWifiOnly(wifiOnly: Boolean)

    /** Whether the map asks for photos online when zoomed in, so that none uses the phone's data unasked. */
    fun setFetchOnline(fetch: Boolean)

    /** Whether the photos online stay on the phone, in [ONLINE_CACHE], or only while the app is open. */
    fun setSaveOnline(save: Boolean)

    /**
     * Fetches [photo] from hips2fits for the map, unless the photos online are off or hidden, or
     * downloads wait for WiFi and there is none; false if it did not come.
     */
    suspend fun fetchOnline(photo: OnlinePhoto): Boolean

    /**
     * Writes the cache in folder [name] as a ZIP at [location], with the folder inside, for N.I.N.A.
     * on a PC.
     * @throws SkyCacheException if it cannot be written.
     */
    suspend fun export(name: String, location: String)
}

/** A file that is not a sky cache in NINA's format, or cannot be read: [message] says why. */
class SkyCacheException(message: String) : Exception(message)
