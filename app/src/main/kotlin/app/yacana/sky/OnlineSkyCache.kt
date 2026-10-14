// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import app.yacana.domain.sky.Hips2Fits
import app.yacana.domain.sky.OnlinePhoto
import app.yacana.ports.SkyCache
import app.yacana.ports.SkyTile
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * The photos fetched online, in a folder as N.I.N.A. keeps its cache: each JPEG with its
 * thumbnails of 500, 150 and 75 pixels, and its entry in `CacheInfo.xml` with N.I.N.A.'s own
 * attributes, so that N.I.N.A. on a PC reads the folder as its cache.
 */
class OnlineSkyCache(private val folder: File) : SkyCache {
    private val index = File(folder, NinaCacheIndex.FILE)
    private val images = if (index.isFile) index.inputStream().use(NinaCacheIndex::attributes).toMutableList() else mutableListOf()

    @Volatile
    override var tiles: List<SkyTile> = if (index.isFile) index.inputStream().use(NinaCacheIndex::parse) else emptyList()
        private set

    override val survey = "DSS"

    override fun jpeg(tile: SkyTile, pixels: Int): ByteArray? =
        (File(folder, NinaCacheIndex.fileFor(tile, pixels)).takeIf { it.isFile } ?: File(folder, tile.fileName).takeIf { it.isFile })
            ?.readBytes()

    override fun close() = Unit

    /** Keeps [photo]'s [jpeg] with its thumbnails and its entry, as N.I.N.A. does, and adds it to [tiles]. */
    @Synchronized
    fun add(photo: OnlinePhoto, jpeg: ByteArray) {
        folder.mkdirs()
        val name = fileName(photo)
        File(folder, "$name.jpg").writeBytes(jpeg)
        thumbnails(jpeg, name)
        images +=
            linkedMapOf(
                "Id" to UUID.randomUUID().toString(),
                "RA" to number(photo.center.raHours),
                "Dec" to number(photo.center.decDeg),
                "Rotation" to "0",
                "FoVW" to number(photo.fovArcmin),
                "FoVH" to number(photo.fovArcmin),
                "FileName" to "$name.jpg",
                "Source" to Hips2Fits.SOURCE,
                "Name" to photo.name,
            )
        // Written aside and moved into place, so that the index is never half written.
        val written = File(folder, NinaCacheIndex.FILE + ".new")
        written.outputStream().use { NinaCacheIndex.write(images, it) }
        written.renameTo(index)
        tiles = tiles + photo.tile("$name.jpg")
    }

    /** N.I.N.A.'s thumbnails, square as it makes them, in JPEG of quality 70 as its own. */
    private fun thumbnails(jpeg: ByteArray, name: String) {
        val largest = NinaCacheIndex.THUMBNAILS.max()
        val options = BitmapFactory.Options().apply { inSampleSize = Hips2Fits.PIXELS / largest }
        val photo = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return
        NinaCacheIndex.THUMBNAILS.forEach { size ->
            val thumbnail = photo.scale(size, size)
            File(folder, "${name}_${size}px.jpg").outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        }
    }

    /** The photo's name with what Windows does not take in a file name changed, and a number if it is taken, as N.I.N.A. does. */
    private fun fileName(photo: OnlinePhoto): String {
        val base = photo.name.map { if (it.isLetterOrDigit() || it in " -.") it else '_' }.joinToString("")
        val names = sequenceOf(base) + generateSequence(1) { it + 1 }.map { "$base($it)" }
        return names.first { !File(folder, "$it.jpg").exists() }
    }

    private companion object {
        const val QUALITY = 70

        /** Below this, a whole number is written without decimals. */
        const val WHOLE = 1e15

        /** A number as .NET writes it, without ".0" when it is whole, so that N.I.N.A. reads it as its own. */
        fun number(value: Double): String {
            val whole = value % 1.0 == 0.0 && abs(value) < WHOLE
            return if (whole) value.toLong().toString() else value.toString()
        }
    }
}
