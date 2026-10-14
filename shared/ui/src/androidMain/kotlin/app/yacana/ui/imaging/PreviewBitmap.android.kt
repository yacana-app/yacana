// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.imaging

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.yacana.ports.Preview

/**
 * The engine's red, green, blue and alpha bytes as ARGB colours. Copying the bytes straight in
 * would depend on the platform's byte order for bitmaps, which differs between phones and the PC.
 */
actual fun Preview.toImageBitmap(): ImageBitmap {
    val colours = IntArray(width * height)
    for (i in colours.indices) {
        val at = i * 4
        val red = rgba[at].toInt() and BYTE
        val green = rgba[at + 1].toInt() and BYTE
        val blue = rgba[at + 2].toInt() and BYTE
        val alpha = rgba[at + 3].toInt() and BYTE
        colours[i] = (alpha shl ALPHA) or (red shl RED) or (green shl GREEN) or blue
    }
    return Bitmap.createBitmap(colours, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val BYTE = 0xFF
private const val ALPHA = 24
private const val RED = 16
private const val GREEN = 8
