// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.IntSize
import app.yacana.domain.sky.MapLayer
import app.yacana.domain.sky.OnlinePhotos

/**
 * The caches in use [inUse] as a view of the sky draws them on [size]: one budget of decoded
 * photos for all the layers, not one each, as with two memory and the GPU ran out; and the photos
 * online only zoomed in, where they bring the detail, as wider they are patches of another tone.
 * N.I.N.A.'s photos are never decoded past their 2,000 pixels, as only those online have more.
 */
@Composable
internal fun drawnLayers(inUse: List<MapLayer>, view: MapView, size: IntSize): List<DrawnLayer> {
    val scope = rememberCoroutineScope()
    val images = remember(inUse.map { it.cache }) { inUse.map { TileImages(it.cache, scope, TILE_BUDGET / inUse.size) } }
    val online = OnlinePhotos.shown(view.fieldDeg, view.fieldDeg * size.height / size.width.coerceAtLeast(1))
    return inUse.zip(images).filter { (layer, _) -> online || !layer.online }.map { (layer, photos) ->
        DrawnLayer(layer.tiles, photos, layer.opacity, if (layer.online) Double.MAX_VALUE else CACHE_PIXELS)
    }
}

/** The width of the photos in N.I.N.A.'s caches. */
private const val CACHE_PIXELS = 2000.0
