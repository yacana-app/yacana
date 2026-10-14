// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.flats

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import app.yacana.domain.flats.FlatsAssistant

/**
 * "Flats · the phone as the light": all white over everything, bars included, while the phone is
 * the flats' light (the platform also turns its brightness up full); a double tap pauses them.
 */
@Composable
fun ScreenLight(flats: FlatsAssistant?) {
    val state = flats?.state?.collectAsState()?.value
    if (state?.screenLit != true) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .testTag(SCREEN_LIGHT)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { flats.pause() }) },
    )
}

/** The test tag of the white screen the flats take as their light. */
const val SCREEN_LIGHT = "screen-light"
