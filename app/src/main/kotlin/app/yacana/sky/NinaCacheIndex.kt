// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import android.util.Xml
import app.yacana.ports.SkyCache
import app.yacana.ports.SkyTile
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** N.I.N.A.'s `CacheInfo.xml`: one `Image` element per photo, with its place, field, file and source. */
object NinaCacheIndex {
    const val FILE = "CacheInfo.xml"

    /** The thumbnails NINA keeps beside each photo, by width. */
    val THUMBNAILS = listOf(75, 150, 500)
    private const val ARCMIN_PER_DEG = 60.0
    private const val DEG_PER_HOUR = 15.0

    fun parse(input: InputStream): List<SkyTile> {
        val parser = Xml.newPullParser().apply { setInput(input, null) }
        val tiles = mutableListOf<SkyTile>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Image") {
                fun attr(name: String): String? = parser.getAttributeValue(null, name)
                tiles +=
                    SkyTile(
                        raDeg = attr("RA")!!.toDouble() * DEG_PER_HOUR,
                        decDeg = attr("Dec")!!.toDouble(),
                        rotationDeg = attr("Rotation")?.toDoubleOrNull() ?: 0.0,
                        widthDeg = attr("FoVW")!!.toDouble() / ARCMIN_PER_DEG,
                        heightDeg = attr("FoVH")!!.toDouble() / ARCMIN_PER_DEG,
                        fileName = attr("FileName")!!,
                        source = attr("Source").orEmpty(),
                    )
            }
        }
        return tiles
    }

    /** Each `Image` element's attributes in their order, to write the index again with one more. */
    fun attributes(input: InputStream): List<Map<String, String>> {
        val parser = Xml.newPullParser().apply { setInput(input, null) }
        val images = mutableListOf<Map<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Image") {
                images += (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
            }
        }
        return images
    }

    /** Writes [images] as N.I.N.A. writes its index: an `ImageCacheInfo` with an `Image` element each. */
    fun write(images: List<Map<String, String>>, output: OutputStream) {
        val xml = Xml.newSerializer()
        xml.setOutput(output, "UTF-8")
        xml.startDocument("UTF-8", null)
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        xml.startTag(null, "ImageCacheInfo")
        images.forEach { attributes ->
            xml.startTag(null, "Image")
            attributes.forEach { (name, value) -> xml.attribute(null, name, value) }
            xml.endTag(null, "Image")
        }
        xml.endTag(null, "ImageCacheInfo")
        xml.endDocument()
    }

    /** The file to read for [tile] at least [pixels] wide: a thumbnail, or the photo itself. */
    fun fileFor(tile: SkyTile, pixels: Int): String {
        val base = tile.fileName.removeSuffix(".jpg")
        return THUMBNAILS.firstOrNull { it >= pixels }?.let { "${base}_${it}px.jpg" } ?: tile.fileName
    }

    /**
     * N.I.N.A.'s survey names as people know them: its DSS caches come from hips2fits, and its
     * narrowband ones say NSNBS, the survey's data release.
     */
    fun surveyOf(tiles: List<SkyTile>): String {
        val source = tiles.firstOrNull()?.source.orEmpty()
        return when {
            listOf("NSNS", "NSNB", "Narrowband").any { source.contains(it, ignoreCase = true) } -> "NSNS"
            source.isEmpty() || source.contains("Hips2Fits", ignoreCase = true) || source.contains("DSS", ignoreCase = true) -> "DSS"
            else -> source
        }
    }
}

/** A cache in a folder, as NINA keeps it on the PC: `CacheInfo.xml` beside the photos. */
class FolderSkyCache private constructor(private val folder: File, override val tiles: List<SkyTile>) : SkyCache {
    override val survey: String = NinaCacheIndex.surveyOf(tiles)

    override fun jpeg(tile: SkyTile, pixels: Int): ByteArray? =
        File(folder, NinaCacheIndex.fileFor(tile, pixels)).takeIf { it.isFile }?.readBytes()
            ?: File(folder, tile.fileName).takeIf { it.isFile }?.readBytes()

    override fun close() = Unit

    companion object {
        fun open(folder: File): FolderSkyCache =
            FolderSkyCache(folder, File(folder, NinaCacheIndex.FILE).inputStream().use(NinaCacheIndex::parse))
    }
}
