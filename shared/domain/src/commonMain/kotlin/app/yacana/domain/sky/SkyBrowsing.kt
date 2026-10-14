// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the map was left: its centre and how wide it was; M 31 and the Seestar's field at first, as the design opens. */
@Serializable
data class MapPlace(val raHours: Double = 0.7123, val decDeg: Double = 41.27, val fieldDeg: Double = 8.0)

/**
 * What the Sky section keeps between visits, so that the map opens where it was left and the
 * Sky Atlas with its filters, also after the app is closed: kept in [file], or only while the app
 * is open without one.
 */
class SkyBrowsing(private val file: Path? = null) {
    private val first = read()
    private val places = MutableStateFlow(first.place)
    private val chosen = MutableStateFlow(first.filters)

    val place: StateFlow<MapPlace> = places.asStateFlow()

    val filters: StateFlow<AtlasFilters> = chosen.asStateFlow()

    /** The map is now at [place]; [save] keeps it in the file. */
    fun moveTo(place: MapPlace) {
        places.value = place
    }

    /** The Sky Atlas's filters are now [filters], kept in the file at once. */
    fun filter(filters: AtlasFilters) {
        chosen.value = filters
        save()
    }

    fun save() {
        val path = file ?: return
        val kept = Kept(places.value, chosen.value)
        runCatching { SystemFileSystem.sink(path).buffered().use { it.writeString(JSON.encodeToString(Kept.serializer(), kept)) } }
    }

    /** What is in the file; M 31 and the first filters without one, or with one that cannot be read. */
    private fun read(): Kept {
        val path = file?.takeIf { SystemFileSystem.exists(it) } ?: return Kept()
        return runCatching { JSON.decodeFromString(Kept.serializer(), SystemFileSystem.source(path).buffered().use { it.readString() }) }
            .getOrNull() ?: Kept()
    }

    @Serializable
    private data class Kept(val place: MapPlace = MapPlace(), val filters: AtlasFilters = AtlasFilters())

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
