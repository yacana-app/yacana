// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.EquipmentController
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.focuser_offset_hint
import app.yacana.ui.resources.focuser_offset_title
import app.yacana.ui.resources.focuser_offsets
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.save
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * "Focus offset by filter" as "Equipment · focuser" draws it: each filter of the wheel
 * with its focus offset; tapping one types it, with its sign.
 */
@Composable
internal fun FilterOffsets(equipment: EquipmentController, names: List<String>) {
    val state by equipment.state.collectAsState()
    val profile = state.active ?: return
    val offsets = profile.options.autofocus.filterOffsets
    var editing by remember { mutableStateOf<String?>(null) }
    SectionCard {
        Text(
            stringResource(Res.string.focuser_offsets),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        names.forEach { name ->
            Row(Modifier.fillMaxWidth().clickable { editing = name }.padding(vertical = 8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    formatOffset(offsets[name] ?: 0),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    editing?.let { name ->
        val current = offsets[name] ?: 0
        var positive by remember(name) { mutableStateOf(current >= 0) }
        BottomSheet(onDismiss = { editing = null }) {
            Text(stringResource(Res.string.focuser_offset_title, name), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingChip("+", onClick = { positive = true }, selected = positive)
                SettingChip("−", onClick = { positive = false }, selected = !positive)
            }
            NumberEntry(
                initial = abs(current).toString(),
                unit = "",
                hint = stringResource(Res.string.focuser_offset_hint),
                saveLabel = stringResource(Res.string.save),
                deleteLabel = stringResource(Res.string.number_delete),
                decimal = null,
                valid = { it <= MAX_OFFSET },
            ) { value ->
                val signed = if (positive) value.toInt() else -value.toInt()
                val changed = if (signed == 0) offsets - name else offsets + (name to signed)
                equipment.save(
                    profile.copy(options = profile.options.copy(autofocus = profile.options.autofocus.copy(filterOffsets = changed))),
                )
                editing = null
            }
        }
    }
}

/** "0", "+12" or "−5", as the design writes them. */
private fun formatOffset(steps: Int) = when {
    steps > 0 -> "+$steps"
    steps < 0 -> "−${-steps}"
    else -> "0"
}

private const val MAX_OFFSET = 10_000
