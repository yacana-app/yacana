// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.ui.YacanaIcons

/**
 * A number typed on the app's [NumberPad], as in the designs' exposure editor: the value large,
 * with its [unit], what is allowed ([hint]), and a save button. [initial] shows until the first
 * key replaces it. [decimal] is the decimal separator, or null for whole numbers; the value
 * passed to [valid], [onSave] and [onChange], which hears each key, uses a point. A [note] goes
 * between the value and the keypad.
 */
@Composable
@Suppress("LongParameterList") // Each label comes from the caller's strings.
fun NumberEntry(
    initial: String,
    unit: String,
    hint: String,
    saveLabel: String,
    deleteLabel: String,
    decimal: Char?,
    valid: (Double) -> Boolean,
    onChange: (Double?) -> Unit = {},
    note: String? = null,
    onSave: (Double) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var replace by remember(initial) { mutableStateOf(true) }
    val value = (if (decimal == null) text else text.replace(decimal, '.')).toDoubleOrNull()
    val ok = value != null && valid(value)
    val changed by rememberUpdatedState(onChange)
    LaunchedEffect(value) { changed(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text.ifEmpty { "0" } + if (unit.isEmpty() || unit == "°") unit else " $unit",
            style = MaterialTheme.typography.headlineMedium,
            modifier =
            Modifier
                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 2.dp),
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok || text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    NumberPad(
        onKey = { key ->
            val base = if (replace) "" else text
            replace = false
            text = if (base.length < MAX_TYPED && !(key == decimal && key in base)) base + key else base
        },
        onDelete = {
            text = if (replace) "" else text.dropLast(1)
            replace = false
        },
        deleteLabel = deleteLabel,
        decimal = decimal,
    )
    AccentButton(onClick = { value?.let(onSave) }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text(saveLabel) }
}

private const val MAX_TYPED = 8

/**
 * The app's own keypad for numbers, laid out like a phone's, instead of the system keyboard,
 * which red mode cannot tint (docs/design/STYLE.md). [decimal] is the decimal
 * separator key, or null for whole numbers.
 */
@Composable
fun NumberPad(onKey: (Char) -> Unit, onDelete: () -> Unit, deleteLabel: String, modifier: Modifier = Modifier, decimal: Char? = null) {
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', decimal, '0')
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (keys + null).chunked(KEYPAD_COLUMNS).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEachIndexed { column, key ->
                    val cell = Modifier.weight(1f).height(KEY_HEIGHT)
                    when {
                        row == KEYPAD_ROWS - 1 && column == KEYPAD_COLUMNS - 1 ->
                            FilledTonalButton(onClick = onDelete, cell, shape = RoundedCornerShape(10.dp), colors = keyColors()) {
                                Icon(YacanaIcons.Backspace, contentDescription = deleteLabel)
                            }

                        key == null -> Spacer(cell)

                        else -> FilledTonalButton(onClick = { onKey(key) }, cell, shape = RoundedCornerShape(10.dp), colors = keyColors()) {
                            Text(key.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

private const val KEYPAD_COLUMNS = 3
private const val KEYPAD_ROWS = 4
private val KEY_HEIGHT = 48.dp

/** The designs' keys: the raised surface, not the accent. */
@Composable
private fun keyColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface,
)
