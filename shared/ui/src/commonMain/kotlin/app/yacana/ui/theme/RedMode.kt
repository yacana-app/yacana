// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Red mode for photos: each pixel keeps its brightness, in red only. Rec. 601 weights.
 * Draw every photo with [LocalPhotoFilter], which is null in the dark theme.
 */
val RedPhotoFilter: ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

/** The filter for photos in the current theme. */
val LocalPhotoFilter = staticCompositionLocalOf<ColorFilter?> { null }

/** Whether red mode is on, for what must not open the system's windows, as its keyboard, which it cannot tint. */
val LocalRedMode = staticCompositionLocalOf { false }

/**
 * Where the telescope points on the sky map: blue as the designs', and in red mode only red,
 * where its solid frame tells it from the dashed one.
 */
val LocalTelescopeColor = staticCompositionLocalOf { TELESCOPE_BLUE }

internal val TELESCOPE_BLUE = Color(0xFF5EA8FF)
internal val TELESCOPE_RED = Color(0xFFE00000)
