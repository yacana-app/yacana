// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sky

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android's decoder, which skips pixels as it reads and then scales to [maxWidth]: a full photo of
 * the cache takes 16 MB otherwise, and the map draws each at about its size on the screen.
 */
actual fun decodeJpeg(bytes: ByteArray, maxWidth: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    val sampled = bounds.outWidth / sample
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sample
            // Down to the width asked for, never up.
            if (sampled > maxWidth) {
                inScaled = true
                inDensity = sampled
                inTargetDensity = maxWidth
            }
        }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
