// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ports

/**
 * One photo of a sky cache, as N.I.N.A.'s `CacheInfo.xml` describes it: where its centre is, in
 * J2000 degrees, how it is turned, how much sky it covers, its file and the survey it comes from.
 */
data class SkyTile(
    val raDeg: Double,
    val decDeg: Double,
    val rotationDeg: Double,
    val widthDeg: Double,
    val heightDeg: Double,
    val fileName: String,
    val source: String,
)

/**
 * A sky cache in N.I.N.A.'s format, read where it is without unpacking it:
 * its photos, and each one's JPEG at least so many pixels wide, from its thumbnails of 75, 150
 * and 500 pixels or the full photo.
 */
interface SkyCache : AutoCloseable {
    /** What to show as the photos' source, such as "DSS". */
    val survey: String

    val tiles: List<SkyTile>

    /** The JPEG of [tile] at least [pixels] wide, or the largest there is; null if it cannot be read. Blocks. */
    fun jpeg(tile: SkyTile, pixels: Int): ByteArray?
}
