// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.yacana.alpaca.GainControl
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.DEFAULT_GAIN
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.SectionCard
import app.yacana.ui.imaging.GainEntry
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.camera_gain_after
import app.yacana.ui.resources.camera_gain_after_help
import app.yacana.ui.resources.camera_gain_help
import app.yacana.ui.resources.camera_gain_none
import app.yacana.ui.resources.camera_gain_note
import app.yacana.ui.resources.camera_gain_range_known
import app.yacana.ui.resources.camera_gain_range_unknown
import app.yacana.ui.resources.camera_gain_why
import app.yacana.ui.resources.image_files_no_profile
import app.yacana.ui.resources.imaging_gain
import org.jetbrains.compose.resources.stringResource

/**
 * "Settings · camera": the profile's gain, which every frame takes, as Imaging's gain button
 * sets it too; tapping it opens the same keypad. The connected camera says its range.
 */
@Composable
fun CameraSettingsScreen(equipment: EquipmentController) {
    val state by equipment.state.collectAsState()
    val profile = state.active
    if (profile == null) {
        Text(
            stringResource(Res.string.image_files_no_profile),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val control = (state.connection as? Connection.Connected)?.equipment?.camera?.info?.gain
    val gain = profile.options.gain
    var editing by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Title(stringResource(Res.string.imaging_gain))
            Help(stringResource(Res.string.camera_gain_help))
            Row(
                Modifier.padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (control != GainControl.None) {
                    val shape = RoundedCornerShape(10.dp)
                    Text(
                        (control as? GainControl.Named)?.names?.getOrNull(gain) ?: gain.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier =
                        Modifier
                            .clip(shape)
                            .border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                            .clickable { editing = true }
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
                Text(
                    when (control) {
                        is GainControl.Range -> stringResource(Res.string.camera_gain_range_known, control.min, control.max)
                        GainControl.None -> stringResource(Res.string.camera_gain_none)
                        null -> stringResource(Res.string.camera_gain_range_unknown)
                        is GainControl.Named -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Help(stringResource(Res.string.camera_gain_why))
        }
        SectionCard {
            Title(stringResource(Res.string.camera_gain_after))
            Help(stringResource(Res.string.camera_gain_after_help))
        }
        Text(
            stringResource(Res.string.camera_gain_note, DEFAULT_GAIN),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    if (editing) {
        BottomSheet({ editing = false }) {
            Text(stringResource(Res.string.imaging_gain), style = MaterialTheme.typography.titleMedium)
            GainEntry(control, gain, null) { changed ->
                equipment.save(profile.copy(options = profile.options.copy(gain = changed)))
                editing = false
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Help(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
)
