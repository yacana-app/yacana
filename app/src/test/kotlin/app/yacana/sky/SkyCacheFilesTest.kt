// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import app.yacana.domain.sky.OnlinePhoto
import app.yacana.ports.SkyPosition
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N.I.N.A.'s framing caches as the app reads them: its index, a cache in a
 * ZIP or in a folder, a zone drawn from it, and the photos fetched online kept as N.I.N.A. keeps them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyCacheFilesTest {
    private val folder: File = Files.createTempDirectory("yacana-sky-cache").toFile()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun theIndexGivesEachPhotoInDegrees() {
        val tiles = NinaCacheIndex.parse(INDEX.byteInputStream())

        assertEquals(2, tiles.size)
        assertEquals(0.7123 * 15, tiles[0].raDeg, 1e-9)
        assertEquals(41.269, tiles[0].decDeg)
        assertEquals(1.0, tiles[0].widthDeg)
        assertEquals(0.5, tiles[1].heightDeg)
        assertEquals("M 31.jpg", tiles[0].fileName)
        assertEquals("NSNS", NinaCacheIndex.surveyOf(tiles))
    }

    @Test
    fun theSurveyIsNamedAsPeopleKnowIt() {
        fun survey(source: String) = NinaCacheIndex.surveyOf(NinaCacheIndex.parse(index(source).byteInputStream()))

        assertEquals("DSS", survey("Hips2FitsSurvey"))
        assertEquals("DSS", survey(""))
        assertEquals("NSNS", survey("NSNBS DR0.1"))
        assertEquals("SkyView", survey("SkyView"))
    }

    @Test
    fun theSmallestThumbnailSharpEnoughIsRead() {
        val tile = NinaCacheIndex.parse(INDEX.byteInputStream()).first()

        assertEquals("M 31_75px.jpg", NinaCacheIndex.fileFor(tile, 60))
        assertEquals("M 31_150px.jpg", NinaCacheIndex.fileFor(tile, 100))
        assertEquals("M 31_500px.jpg", NinaCacheIndex.fileFor(tile, 500))
        assertEquals("M 31.jpg", NinaCacheIndex.fileFor(tile, 900))
    }

    @Test
    fun theIndexIsWrittenAgainAsItWasRead() {
        val images = NinaCacheIndex.attributes(INDEX.byteInputStream())
        val written = ByteArrayOutputStream().also { NinaCacheIndex.write(images, it) }

        assertEquals(images, NinaCacheIndex.attributes(written.toByteArray().inputStream()))
        assertEquals("M 31", images.first()["Name"])
    }

    @Test
    fun aCacheInAZipIsReadWithoutUnpackingIt() {
        val zip =
            zip(
                "FramingAssistantCache/" to mapOf(
                    NinaCacheIndex.FILE to INDEX.toByteArray(),
                    "M 31.jpg" to WHOLE,
                    "M 31_150px.jpg" to SMALL,
                ),
            )

        ZipSkyCache.open(zip).use { cache ->
            val m31 = cache.tiles.first()
            assertEquals("NSNS", cache.survey)
            assertContentEquals(SMALL, cache.jpeg(m31, 100))
            // Without the 500 px thumbnail, the photo itself.
            assertContentEquals(WHOLE, cache.jpeg(m31, 400))
            assertNull(cache.jpeg(cache.tiles[1], 100))
        }
    }

    @Test
    fun aZipWithoutAnIndexIsNotACache() {
        val zip = zip("" to mapOf("M 31.jpg" to WHOLE))

        assertFailsWith<IllegalStateException> { ZipSkyCache.open(zip) }
    }

    @Test
    fun aCacheInAFolderIsReadAsNinaKeepsItOnThePc() {
        File(folder, NinaCacheIndex.FILE).writeText(INDEX)
        File(folder, "M 31.jpg").writeBytes(WHOLE)

        val cache = FolderSkyCache.open(folder)

        assertEquals(2, cache.tiles.size)
        assertContentEquals(WHOLE, cache.jpeg(cache.tiles.first(), 100))
        assertNull(cache.jpeg(cache.tiles[1], 100))
    }

    @Test
    fun aZoneIsDrawnWithThePhotosInIt() {
        val zip = zip("" to mapOf(NinaCacheIndex.FILE to INDEX.toByteArray(), "M 31.jpg" to jpeg(200), "NGC 7000.jpg" to jpeg(200)))
        val opened = SkyCache.open(zip)

        opened.cache.use { cache ->
            val render = cache.render(0.7123 * 15, 41.269, 3.0, 300, 300, 1.3, 0.7)
            assertEquals(1, render.imagesDrawn)
            assertEquals(300, render.bitmap.width)
            // The frame's outline is drawn over the photo, at the centre.
            assertTrue(render.bitmap.getPixel(150 - (1.3 / 2 * 100).toInt(), 150) != Color.BLACK)
        }
        assertEquals(2, opened.cache.images.size)
    }

    @Test
    fun theDistanceBetweenTwoPointsIsOnTheSphere() {
        assertEquals(90.0, SkyCache.angularDistanceDeg(0.0, 0.0, 90.0, 0.0), 1e-9)
        assertEquals(1.0, SkyCache.angularDistanceDeg(10.0, 40.0, 10.0, 41.0), 1e-9)
    }

    @Test
    fun aPhotoFetchedOnlineIsKeptWithItsThumbnailsAndItsEntry() {
        val photo = OnlinePhoto(SkyPosition(0.7123, 41.269), 60.0)

        OnlineSkyCache(folder).add(photo, jpeg(2000))

        val name = photo.name.map { if (it.isLetterOrDigit() || it in " -.") it else '_' }.joinToString("")
        listOf("", "_75px", "_150px", "_500px").forEach { assertTrue(File(folder, "$name$it.jpg").isFile, "$name$it.jpg") }
        // N.I.N.A. reads the folder as its cache, and so does the app the next time.
        val again = OnlineSkyCache(folder)
        assertEquals(listOf("$name.jpg"), again.tiles.map { it.fileName })
        assertEquals(1.0, again.tiles.first().widthDeg)
        val index = File(folder, NinaCacheIndex.FILE).readText()
        assertTrue("FoVW=\"60\"" in index && "Rotation=\"0\"" in index, index)
        assertEquals(500, BitmapFactory.decodeFile(File(folder, "${name}_500px.jpg").path).width)
        assertContentEquals(File(folder, "${name}_150px.jpg").readBytes(), again.jpeg(again.tiles.first(), 120))
    }

    @Test
    fun aSecondPhotoOfTheSameNameTakesANumber() {
        val cache = OnlineSkyCache(folder)
        val photo = OnlinePhoto(SkyPosition(0.7123, 41.269), 60.0)

        cache.add(photo, jpeg(2000))
        cache.add(photo, jpeg(2000))

        val name = photo.name.map { if (it.isLetterOrDigit() || it in " -.") it else '_' }.joinToString("")
        assertEquals(listOf("$name.jpg", "$name(1).jpg"), cache.tiles.map { it.fileName })
        assertTrue(File(folder, "$name(1)_75px.jpg").isFile)
    }

    /** A ZIP of the files of [entries], each under its folder. */
    private fun zip(vararg entries: Pair<String, Map<String, ByteArray>>): File {
        val file = File(folder, "cache.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (prefix, files) ->
                files.forEach { (name, bytes) ->
                    out.putNextEntry(ZipEntry(prefix + name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        return file
    }

    /** A grey JPEG [pixels] wide and high. */
    private fun jpeg(pixels: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private companion object {
        val WHOLE = byteArrayOf(1, 2, 3)
        val SMALL = byteArrayOf(4, 5)

        /** An index of two photos from [source], as N.I.N.A. writes it: right ascension in hours, fields in arcminutes. */
        fun index(source: String) = """
            <?xml version="1.0" encoding="utf-8"?>
            <ImageCacheInfo>
              <Image Id="1" RA="0.7123" Dec="41.269" Rotation="35" FoVW="60" FoVH="60" FileName="M 31.jpg" Source="$source" Name="M 31" />
              <Image Id="2" RA="20.98" Dec="44.3" FoVW="30" FoVH="30" FileName="NGC 7000.jpg" Source="$source" Name="NGC 7000" />
            </ImageCacheInfo>
        """.trimIndent()

        val INDEX = index("NSNBS DR0.1")
    }
}
