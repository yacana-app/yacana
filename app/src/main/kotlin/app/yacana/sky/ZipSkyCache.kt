// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import app.yacana.ports.SkyCache
import app.yacana.ports.SkyTile
import java.io.File
import java.util.zip.ZipFile

/**
 * A N.I.N.A. sky cache read straight from its ZIP, without unpacking it: Android's
 * `ZipFile` reads the central directory and any one entry, also of a ZIP64 of 3.5 GB. The index
 * is `CacheInfo.xml`, parsed into memory; a few thousand photos need no database (spike S4).
 */
class ZipSkyCache private constructor(private val zip: ZipFile, private val folder: String, override val tiles: List<SkyTile>) : SkyCache {
    override val survey: String = NinaCacheIndex.surveyOf(tiles)

    override fun jpeg(tile: SkyTile, pixels: Int): ByteArray? {
        val entry = zip.getEntry(folder + NinaCacheIndex.fileFor(tile, pixels)) ?: zip.getEntry(folder + tile.fileName) ?: return null
        // A photo asked for as the cache is closed, hidden or deleted, is simply not there.
        return synchronized(zip) { runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull() }
    }

    override fun close() = zip.close()

    companion object {
        /** Opens the cache in [file] and reads its index. */
        fun open(file: File): ZipSkyCache {
            val zip = ZipFile(file)
            val info =
                zip.entries().asSequence().firstOrNull { it.name.endsWith(NinaCacheIndex.FILE, ignoreCase = true) }
                    ?: run {
                        zip.close()
                        error("No ${NinaCacheIndex.FILE} in ${file.name}")
                    }
            val folder = info.name.removeSuffix(info.name.substringAfterLast('/'))
            return ZipSkyCache(zip, folder, zip.getInputStream(info).use(NinaCacheIndex::parse))
        }
    }
}
