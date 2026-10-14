// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.solver

import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * The few FITS header values the solver needs: the pointing, to use as a hint, and the
 * optics, to compute the field height. Only the primary header is read.
 */
data class FitsHeader(val raDeg: Double?, val decDeg: Double?, val fieldHeightDeg: Double?) {
    companion object {
        private const val CARD = 80
        private const val KEY_LENGTH = 8
        private const val VALUE_START = 10
        private const val BLOCK = 2880
        private const val MAX_BLOCKS = 36
        private const val ARCSEC_PER_RADIAN_PER_1000 = 206.264806
        private const val ARCSEC_PER_DEG = 3600.0

        fun read(file: File): FitsHeader {
            val cards = cards(file)
            return FitsHeader(
                raDeg = cards["RA"]?.toDoubleOrNull(),
                decDeg = cards["DEC"]?.toDoubleOrNull(),
                fieldHeightDeg = fieldHeight(cards),
            )
        }

        /** NAXIS2 rows times the pixel scale, from the pixel size (µm, binned) and the focal length (mm). */
        private fun fieldHeight(cards: Map<String, String>): Double? {
            val rows = cards["NAXIS2"]?.toDoubleOrNull()
            val pixelUm = cards["XPIXSZ"]?.toDoubleOrNull()
            val focalMm = cards["FOCALLEN"]?.toDoubleOrNull()?.takeIf { it > 0 }
            if (rows == null || pixelUm == null || focalMm == null) return null
            return rows * ARCSEC_PER_RADIAN_PER_1000 * pixelUm / focalMm / ARCSEC_PER_DEG
        }

        /** Keyword to value, with quotes and comments removed, until the END card. */
        private fun cards(file: File): Map<String, String> {
            val values = mutableMapOf<String, String>()
            DataInputStream(file.inputStream().buffered()).use { input ->
                val block = ByteArray(BLOCK)
                var ended = false
                var blocks = 0
                while (!ended && blocks < MAX_BLOCKS && input.readBlock(block)) {
                    blocks++
                    for (offset in 0 until BLOCK step CARD) {
                        val card = String(block, offset, CARD, Charsets.US_ASCII)
                        val key = card.take(KEY_LENGTH).trim()
                        ended = key == "END"
                        if (ended) break
                        if (card[KEY_LENGTH] == '=') values[key] = card.substring(VALUE_START).substringBefore('/').trim().trim('\'').trim()
                    }
                }
            }
            return values
        }

        private fun DataInputStream.readBlock(block: ByteArray): Boolean = try {
            readFully(block)
            true
        } catch (_: EOFException) {
            false
        }
    }
}
