// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import app.yacana.ports.SkyCache
import app.yacana.ports.SkyTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/** [bytes], a JPEG, decoded no wider than about [maxWidth] pixels, or null if it is not one. */
expect fun decodeJpeg(bytes: ByteArray, maxWidth: Int): ImageBitmap?

/**
 * The cache's photos as the map draws them, decoded in the background at the size each needs:
 * the thumbnails of 75, 150 or 500 pixels, or the full photo when zoomed in. The ones used last
 * stay in memory, up to [budgetPixels] but never those in view; while a sharper one loads, another size is drawn. Workers
 * read and decode; what they bring is taken in as the map draws, on its thread. Shared by [many] small photos, as the
 * Sky Atlas's thumbnails, none is dropped as out of view: the ones used last stay.
 */
class TileImages(
    private val cache: SkyCache,
    scope: CoroutineScope,
    private val budgetPixels: Long = TILE_BUDGET,
    private val many: Boolean = false,
) {
    private data class Key(val tile: SkyTile, val level: Int)

    /** Oldest first: each image used goes back to the end, so the ones not used for longest go first. */
    private val decoded = LinkedHashMap<Key, ImageBitmap>()
    private val loading = mutableSetOf<Key>()
    private val requests = Channel<Key>(Channel.UNLIMITED)
    private val results = Channel<Pair<Key, ImageBitmap?>>(Channel.UNLIMITED)
    private var pixels = 0L

    @Volatile
    private var wanted = emptySet<SkyTile>()

    /** Grows each time a photo arrives, so the map draws again. */
    val arrived = mutableIntStateOf(0)

    init {
        repeat(WORKERS) {
            scope.launch(Dispatchers.Default) {
                for (key in requests) {
                    val image =
                        if (many ||
                            key.tile in wanted
                        ) {
                            cache.jpeg(key.tile, LEVELS[key.level])?.let { decodeJpeg(it, LEVELS[key.level]) }
                        } else {
                            null
                        }
                    results.trySend(key to image)
                    arrived.intValue++
                }
            }
        }
    }

    /** The tiles the map shows now: requests for others still waiting are dropped. */
    fun show(tiles: Set<SkyTile>) {
        if (!many) wanted = tiles
    }

    /** [tile] at least [pixels] wide if it is here, else the closest size there is; asks for it. */
    fun image(tile: SkyTile, pixels: Double): ImageBitmap? {
        while (true) {
            val (done, image) = results.tryReceive().getOrNull() ?: break
            arrive(done, image)
        }
        val level = LEVELS.indexOfFirst { it >= pixels }.let { if (it < 0) LEVELS.lastIndex else it }
        val key = Key(tile, level)
        decoded.remove(key)?.let {
            decoded[key] = it
            return it
        }
        if (loading.add(key)) requests.trySend(key)
        return (level - 1 downTo 0).firstNotNullOfOrNull { decoded[Key(tile, it)] }
            ?: (level + 1..LEVELS.lastIndex).firstNotNullOfOrNull { decoded[Key(tile, it)] }
    }

    private fun arrive(key: Key, image: ImageBitmap?) {
        loading.remove(key)
        if (image == null) return
        decoded[key] = image
        pixels += image.width.toLong() * image.height
        // The ones out of view go first, the oldest first; those in view stay, or the map would flicker.
        val shown = wanted
        val oldest = decoded.entries.iterator()
        while (pixels > budgetPixels && oldest.hasNext()) {
            val entry = oldest.next()
            if (!many && entry.key.tile in shown) continue
            pixels -= entry.value.width.toLong() * entry.value.height
            oldest.remove()
        }
    }

    private companion object {
        /**
         * The widths asked for: N.I.N.A.'s thumbnails, then the full photo of 2,000 pixels decoded to
         * 750, 1,000, 1,500 or all of it, close to the map's need: the photos drawn in each frame go
         * to the GPU, and twice what the screen shows, with two layers, made the map crawl. The
         * photos online, of 3,000 pixels, also whole.
         */
        val LEVELS = listOf(75, 150, 500, 750, 1000, 1500, 2000, 3000)
        const val WORKERS = 3
    }
}

/** About 120 MB of decoded photos, for all the map's layers together. */
const val TILE_BUDGET = 30_000_000L
