// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.sky

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import app.yacana.ports.SkyTile
import java.io.Closeable
import java.io.File
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reads a N.I.N.A. framing cache straight from its ZIP, without unpacking it.
 *
 * The ZIP holds `CacheInfo.xml` and, for each image, a JPG with `_75px`, `_150px` and
 * `_500px` thumbnails. The index is the parsed XML, kept in memory: the official caches
 * have a few thousand images, so a database is not needed.
 */
class SkyCache private constructor(private val cache: ZipSkyCache, val images: List<CacheImage>) : Closeable {
    /** One image of the cache, as `CacheInfo.xml` describes it. */
    data class CacheImage(
        val raDeg: Double,
        val decDeg: Double,
        val rotationDeg: Double,
        val widthDeg: Double,
        val heightDeg: Double,
        val fileName: String,
        val tile: SkyTile,
    )

    /** A rendered view and what each step cost. */
    data class Render(val bitmap: Bitmap, val imagesDrawn: Int, val millis: Long)

    override fun close() = cache.close()

    /**
     * Draws the sky around ([raDeg], [decDeg]) in a [widthPx] × [heightPx] bitmap that spans
     * [fieldDeg] across, in gnomonic projection with north up and east to the left, and
     * outlines the camera field [frameWidthDeg] × [frameHeightDeg].
     */
    fun render(
        raDeg: Double,
        decDeg: Double,
        fieldDeg: Double,
        widthPx: Int,
        heightPx: Int,
        frameWidthDeg: Double,
        frameHeightDeg: Double,
    ): Render {
        val start = SystemClock.elapsedRealtime()
        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap).apply { drawColor(Color.BLACK) }
        val pxPerDeg = widthPx / fieldDeg
        val projection = Gnomonic(raDeg, decDeg, pxPerDeg, widthPx / 2.0, heightPx / 2.0)
        val reach = fieldDeg * REACH_FACTOR + TILE_REACH_DEG
        val visible = images.filter { angularDistanceDeg(raDeg, decDeg, it.raDeg, it.decDeg) < reach }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val drawn = visible.count { image -> drawImage(canvas, image, projection, pxPerDeg, paint) }
        drawFrame(canvas, widthPx, heightPx, pxPerDeg, frameWidthDeg, frameHeightDeg)
        return Render(bitmap, drawn, SystemClock.elapsedRealtime() - start)
    }

    /** Draws one cache image where it falls on the view. Returns false if it is not visible or not readable. */
    private fun drawImage(canvas: Canvas, image: CacheImage, projection: Gnomonic, pxPerDeg: Double, paint: Paint): Boolean {
        val center = projection.project(image.raDeg, image.decDeg)
        val north = projection.project(image.raDeg, image.decDeg + NORTH_STEP_DEG)
        val tile = if (center != null && north != null) decode(image, pxPerDeg) else null
        if (tile == null || center == null || north == null) return false
        // Angle of the image's north on screen, where north up is 0.
        val angle = Math.toDegrees(atan2(north.first - center.first, center.second - north.second))
        val scale = pxPerDeg * image.widthDeg / tile.width
        val matrix =
            Matrix().apply {
                postTranslate(-tile.width / 2f, -tile.height / 2f)
                postScale(scale.toFloat(), scale.toFloat())
                postRotate((angle + image.rotationDeg).toFloat())
                postTranslate(center.first.toFloat(), center.second.toFloat())
            }
        canvas.drawBitmap(tile, matrix, paint)
        tile.recycle()
        return true
    }

    /** The smallest version of the image that is sharp enough at [pxPerDeg]. */
    private fun decode(image: CacheImage, pxPerDeg: Double): Bitmap? =
        cache.jpeg(image.tile, (pxPerDeg * image.widthDeg).toInt())?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    private fun drawFrame(canvas: Canvas, widthPx: Int, heightPx: Int, pxPerDeg: Double, frameWidthDeg: Double, frameHeightDeg: Double) {
        val halfW = (frameWidthDeg * pxPerDeg / 2).toFloat()
        val halfH = (frameHeightDeg * pxPerDeg / 2).toFloat()
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = FRAME_STROKE_PX
                color = FRAME_COLOR
            }
        canvas.drawRect(widthPx / 2f - halfW, heightPx / 2f - halfH, widthPx / 2f + halfW, heightPx / 2f + halfH, paint)
    }

    /** Gnomonic (tangent plane) projection to screen pixels, north up and east to the left. */
    private class Gnomonic(raDeg: Double, decDeg: Double, private val pxPerDeg: Double, private val cx: Double, private val cy: Double) {
        private val ra0 = Math.toRadians(raDeg)
        private val sinDec0 = sin(Math.toRadians(decDeg))
        private val cosDec0 = cos(Math.toRadians(decDeg))

        fun project(raDeg: Double, decDeg: Double): Pair<Double, Double>? {
            val ra = Math.toRadians(raDeg)
            val dec = Math.toRadians(decDeg)
            val cosC = sinDec0 * sin(dec) + cosDec0 * cos(dec) * cos(ra - ra0)
            if (cosC <= 0) return null
            val x = cos(dec) * sin(ra - ra0) / cosC
            val y = (cosDec0 * sin(dec) - sinDec0 * cos(dec) * cos(ra - ra0)) / cosC
            val degPerRad = 180 / PI
            return (cx - x * degPerRad * pxPerDeg) to (cy - y * degPerRad * pxPerDeg)
        }
    }

    companion object {
        private const val REACH_FACTOR = 0.75
        private const val TILE_REACH_DEG = 3.6
        private const val NORTH_STEP_DEG = 0.1
        private const val FRAME_STROKE_PX = 3f
        private const val FRAME_COLOR = 0xFFE8B04A.toInt()

        /** Opens the cache ZIP and reads its index. Takes [Opened.millis] to do so. */
        fun open(file: File): Opened {
            val start = SystemClock.elapsedRealtime()
            val cache = ZipSkyCache.open(file)
            val images = cache.tiles.map { CacheImage(it.raDeg, it.decDeg, it.rotationDeg, it.widthDeg, it.heightDeg, it.fileName, it) }
            return Opened(SkyCache(cache, images), SystemClock.elapsedRealtime() - start)
        }

        data class Opened(val cache: SkyCache, val millis: Long)

        fun angularDistanceDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
            val d1 = Math.toRadians(dec1)
            val d2 = Math.toRadians(dec2)
            val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(Math.toRadians(ra1 - ra2))
            return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
        }
    }
}
