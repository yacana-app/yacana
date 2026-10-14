// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The few values the solver benchmark reads from a frame's FITS header: its pointing and its field. */
class FitsHeaderTest {
    private val folder: File = Files.createTempDirectory("yacana-fits").toFile()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun thePointingAndTheFieldAreRead() {
        val header = FitsHeader.read(fits(SEESTAR + "END"))

        assertEquals(10.684, header.raDeg)
        assertEquals(41.269, header.decDeg)
        assertEquals(1920 * 206.264806 * 2.9 / 250 / 3600, assertNotNull(header.fieldHeightDeg), 1e-9)
    }

    @Test
    fun withoutTheFocalLengthThereIsNoField() {
        val header = FitsHeader.read(fits(SEESTAR.filterNot { it.startsWith("FOCALLEN") } + "END"))

        assertEquals(10.684, header.raDeg)
        assertNull(header.fieldHeightDeg)
    }

    @Test
    fun nothingAfterTheEndCardIsRead() {
        val header = FitsHeader.read(fits(listOf("END", card("RA", "10.684"))))

        assertNull(header.raDeg)
    }

    @Test
    fun aHeaderCutShortReadsNothing() {
        val file = fits(SEESTAR + "END")
        file.writeBytes(file.readBytes().copyOf(CARD * 3))

        assertNull(FitsHeader.read(file).raDeg)
    }

    /** A primary header of [cards], in blocks of 2880 bytes as FITS keeps them. */
    private fun fits(cards: List<String>): File {
        val text = cards.joinToString("") { it.padEnd(CARD) }
        val blocks = (text.length + BLOCK - 1) / BLOCK
        return File(folder, "frame.fits").apply { writeBytes(text.padEnd(blocks * BLOCK).toByteArray(Charsets.US_ASCII)) }
    }

    private companion object {
        const val CARD = 80
        const val BLOCK = 2880

        /** `KEY     =                value / comment`, as FITS writes a card. */
        fun card(key: String, value: String, comment: String = "") =
            "${key.padEnd(8)}= ${value.padStart(20)}" + if (comment.isEmpty()) "" else " / $comment"

        val SEESTAR = listOf(
            card("SIMPLE", "T"),
            card("BITPIX", "16"),
            card("NAXIS", "2"),
            card("NAXIS1", "1080"),
            card("NAXIS2", "1920"),
            card("OBJECT", "'M 31    '", "Target"),
            card("RA", "10.684", "[deg] Pointing"),
            card("DEC", "41.269", "[deg] Pointing"),
            card("XPIXSZ", "2.9", "[um] Pixel size"),
            card("FOCALLEN", "250.0", "[mm] Focal length"),
            "COMMENT A frame of the fake Seestar",
        )
    }
}
