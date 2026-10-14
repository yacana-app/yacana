// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.SafetyOptions
import app.yacana.ui.design.SectionCard
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.image_files_no_profile
import app.yacana.ui.resources.safety_battery
import app.yacana.ui.resources.safety_battery_help
import app.yacana.ui.resources.safety_lost
import app.yacana.ui.resources.safety_lost_help
import app.yacana.ui.resources.safety_minutes
import app.yacana.ui.resources.safety_note
import app.yacana.ui.resources.safety_park
import app.yacana.ui.resources.safety_stop
import app.yacana.ui.resources.safety_stop_help
import app.yacana.ui.resources.safety_tracking
import org.jetbrains.compose.resources.stringResource

/** The design's choices. */
private val WAITS = listOf(1.0, 2.0, 5.0, 10.0)
private val BATTERY = listOf(15, 20, 30)

/**
 * "Settings · safety": what the session does on a serious failure, a lost link or a low battery
 *, by profile, with buttons, never the system keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SafetySettingsScreen(equipment: EquipmentController) {
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
    val safety = profile.options.safety
    fun save(changed: SafetyOptions) = equipment.save(profile.copy(options = profile.options.copy(safety = changed)))

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Title(stringResource(Res.string.safety_stop))
            Help(stringResource(Res.string.safety_stop_help))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = safety.park, onClick = {
                    save(safety.copy(park = true))
                }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(Res.string.safety_park))
                }
                SegmentedButton(selected = !safety.park, onClick = {
                    save(safety.copy(park = false))
                }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(Res.string.safety_tracking))
                }
            }
        }
        SectionCard {
            Title(stringResource(Res.string.safety_lost))
            Help(stringResource(Res.string.safety_lost_help))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WAITS.forEach { minutes ->
                    FilterChip(
                        selected = safety.waitMinutes == minutes,
                        onClick = { save(safety.copy(waitMinutes = minutes)) },
                        label = { Text(stringResource(Res.string.safety_minutes, minutes.toInt())) },
                    )
                }
            }
        }
        SectionCard {
            Title(stringResource(Res.string.safety_battery))
            Help(stringResource(Res.string.safety_battery_help))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BATTERY.forEach { percent ->
                    FilterChip(
                        selected = safety.lowBatteryPercent == percent,
                        onClick = { save(safety.copy(lowBatteryPercent = percent)) },
                        label = { Text("$percent %") },
                    )
                }
            }
        }
        Text(
            stringResource(Res.string.safety_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
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
