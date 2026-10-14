// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.yacana.ui.YacanaIcons
import app.yacana.ui.theme.LocalRedMode

/**
 * The design kit's search box (`fld`): a pill on the raised surface with a magnifier, and [hint] while
 * it is empty. In red mode a tap opens the app's own keyboard instead of the system's, which red
 * mode cannot tint, as "Sky · Sky Atlas · searching in red mode".
 */
@Composable
fun SearchField(query: String, hint: String, onQuery: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val red = LocalRedMode.current
    var typing by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(query)
    val system = LocalSoftwareKeyboardController.current
    val focus: FocusManager = LocalFocusManager.current
    // Red mode turned on while the system's keyboard is up: it goes; turned off, the app's goes.
    LaunchedEffect(red) {
        if (red) {
            system?.hide()
            focus.clearFocus()
        } else {
            typing = false
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .heightIn(min = Touch.Main)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerHigh)
            .clickable(enabled = red) { typing = true }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(YacanaIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty() && !(red && typing)) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            if (red) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(query, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1)
                    if (typing) Box(Modifier.width(2.dp).height(CARET_HEIGHT).background(colors.primary))
                }
            } else {
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    if (red && typing) {
        AppKeyboard(onKey = { onQuery(current + it) }, onDelete = { onQuery(current.dropLast(1)) }, onHide = { typing = false })
    }
}

private val CARET_HEIGHT = 20.dp
