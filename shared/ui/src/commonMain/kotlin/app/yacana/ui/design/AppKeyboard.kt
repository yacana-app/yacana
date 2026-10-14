// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.yacana.ui.YacanaIcons
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.keyboard_hide
import app.yacana.ui.resources.keyboard_space
import app.yacana.ui.resources.number_delete
import org.jetbrains.compose.resources.stringResource

/**
 * The app's own keyboard for text, as "Sky · Sky Atlas · searching in red mode": docked at the
 * bottom of the app, over its bottom bar, while it is in the composition, for red mode, which
 * cannot tint the system's keyboard. Digits, letters with ñ, a hyphen, space, delete, and
 * ⌄ or back to hide it. It is drawn by the app's [SheetHost], as the sheets; without one, where it is.
 */
@Composable
fun AppKeyboard(onKey: (Char) -> Unit, onDelete: () -> Unit, onHide: () -> Unit) {
    val host = LocalSheetHost.current
    val key by rememberUpdatedState(onKey)
    val delete by rememberUpdatedState(onDelete)
    val hide by rememberUpdatedState(onHide)
    if (host == null) {
        KeyboardLayout({ key(it) }, { delete() }, { hide() })
        return
    }
    DisposableEffect(host) {
        val keyboard: @Composable () -> Unit = { KeyboardLayout({ key(it) }, { delete() }, { hide() }) }
        host.keyboard = keyboard
        onDispose { if (host.keyboard === keyboard) host.keyboard = null }
    }
}

@Composable
private fun KeyboardLayout(onKey: (Char) -> Unit, onDelete: () -> Unit, onHide: () -> Unit) {
    // Back hides the keyboard before it leaves the screen, as the system's.
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onHide)
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(KEY_GAP),
            ) {
                ROWS.forEachIndexed { index, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                        row.forEach { char -> Key(char.toString(), onClick = { onKey(char) }) }
                        if (index == ROWS.lastIndex) {
                            Key(stringResource(Res.string.number_delete), onClick = onDelete) {
                                Icon(YacanaIcons.Backspace, contentDescription = null)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    val space = stringResource(Res.string.keyboard_space)
                    Key(space, Modifier.weight(SPACE_WEIGHT), onClick = { onKey(' ') }) {
                        Text(space, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Key(stringResource(Res.string.keyboard_hide), Modifier.weight(HIDE_WEIGHT), onClick = onHide) {
                        Icon(YacanaIcons.ChevronDown, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** A key on the raised surface, as the app's number keypad: [label] shown, or [content] in its place. */
@Composable
private fun RowScope.Key(
    label: String,
    modifier: Modifier = Modifier.weight(1f),
    onClick: () -> Unit,
    content: @Composable () -> Unit = { Text(label, style = MaterialTheme.typography.titleMedium) },
) {
    Box(
        modifier
            .height(KEY_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Digits, then the letters with ñ, as a Spanish phone's; the last row ends in delete. */
private val ROWS = listOf("1234567890", "qwertyuiop", "asdfghjklñ", "-zxcvbnm")
private val KEY_HEIGHT = 46.dp
private val KEY_GAP = 4.dp
private const val SPACE_WEIGHT = 5f
private const val HIDE_WEIGHT = 1.4f
