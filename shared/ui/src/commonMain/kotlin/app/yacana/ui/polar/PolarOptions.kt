// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.polar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
import app.yacana.domain.profile.PolarAlignmentOptions
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.polar_east
import app.yacana.ui.resources.polar_exposure
import app.yacana.ui.resources.polar_exposure_hint
import app.yacana.ui.resources.polar_exposure_value
import app.yacana.ui.resources.polar_hint_only
import app.yacana.ui.resources.polar_hint_only_hint
import app.yacana.ui.resources.polar_no
import app.yacana.ui.resources.polar_step
import app.yacana.ui.resources.polar_step_hint
import app.yacana.ui.resources.polar_step_value
import app.yacana.ui.resources.polar_tolerance
import app.yacana.ui.resources.polar_tolerance_hint
import app.yacana.ui.resources.polar_tolerance_off
import app.yacana.ui.resources.polar_west
import app.yacana.ui.resources.polar_what
import app.yacana.ui.resources.polar_where_north
import app.yacana.ui.resources.polar_where_south
import app.yacana.ui.resources.polar_yes
import org.jetbrains.compose.resources.stringResource

/** Which option a sheet edits. */
private enum class PolarOption { Step, Exposure, Tolerance, HintOnly }

/**
 * "Polar alignment · before starting": what the assistant does, where to point, and its options,
 * each changed on chips in a sheet and saved in the profile.
 */
@Composable
internal fun PolarOptions(equipment: EquipmentController) {
    val state by equipment.state.collectAsState()
    val profile = state.active
    val options = profile?.options?.polarAlignment ?: PolarAlignmentOptions()
    var editing by remember { mutableStateOf<PolarOption?>(null) }
    val comma = decimalComma()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val direction = stringResource(if (options.east) Res.string.polar_east else Res.string.polar_west)
    val step = formatDecimal(options.stepDeg, 0, comma)
    fun save(changed: PolarAlignmentOptions) {
        if (profile != null) equipment.save(profile.copy(options = profile.options.copy(polarAlignment = changed)))
    }

    SectionCard {
        Text(stringResource(Res.string.polar_what), style = MaterialTheme.typography.bodyLarge)
        val south = (profile?.location?.latitude ?: 1.0) < 0
        Text(
            stringResource(if (south) Res.string.polar_where_south else Res.string.polar_where_north, step, direction),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    SectionCard {
        OptionRow(stringResource(Res.string.polar_step), stringResource(Res.string.polar_step_value, step, direction), first = true) {
            editing = PolarOption.Step
        }
        OptionRow(
            stringResource(Res.string.polar_exposure),
            stringResource(Res.string.polar_exposure_value, formatDecimal(options.exposureSeconds, 0, comma), options.binning),
        ) { editing = PolarOption.Exposure }
        OptionRow(stringResource(Res.string.polar_tolerance), toleranceText(options.toleranceArcmin)) { editing = PolarOption.Tolerance }
        OptionRow(
            stringResource(Res.string.polar_hint_only),
            stringResource(if (options.hintOnly) Res.string.polar_yes else Res.string.polar_no),
        ) {
            editing = PolarOption.HintOnly
        }
    }
    editing?.let { option ->
        BottomSheet(onDismiss = { editing = null }) { OptionSheet(option, options, ::save) }
    }
}

@Composable
private fun toleranceText(arcmin: Double): String = if (arcmin <=
    0
) {
    stringResource(Res.string.polar_tolerance_off)
} else {
    "${formatDecimal(arcmin, if (arcmin % 1 == 0.0) 0 else 1, decimalComma())}′"
}

/** An option as the design draws it: its name, and its value on the right. */
@Composable
private fun OptionRow(label: String, value: String, first: Boolean = false, onClick: () -> Unit) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One option on chips, with what it does; each tap saves. */
@Composable
private fun OptionSheet(option: PolarOption, options: PolarAlignmentOptions, save: (PolarAlignmentOptions) -> Unit) {
    val comma = decimalComma()
    val (title, hint) =
        when (option) {
            PolarOption.Step -> Res.string.polar_step to Res.string.polar_step_hint
            PolarOption.Exposure -> Res.string.polar_exposure to Res.string.polar_exposure_hint
            PolarOption.Tolerance -> Res.string.polar_tolerance to Res.string.polar_tolerance_hint
            PolarOption.HintOnly -> Res.string.polar_hint_only to Res.string.polar_hint_only_hint
        }
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    @Composable
    fun chips(content: @Composable () -> Unit) =
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    when (option) {
        PolarOption.Step -> {
            chips {
                STEPS.forEach {
                    SettingChip(
                        "${formatDecimal(it, 0, comma)}°",
                        { save(options.copy(stepDeg = it)) },
                        selected =
                        options.stepDeg == it,
                    )
                }
            }
            chips {
                SettingChip(stringResource(Res.string.polar_west), { save(options.copy(east = false)) }, selected = !options.east)
                SettingChip(stringResource(Res.string.polar_east), { save(options.copy(east = true)) }, selected = options.east)
            }
        }

        PolarOption.Exposure -> {
            chips {
                EXPOSURES.forEach {
                    SettingChip(
                        "${formatDecimal(it, 0, comma)} s",
                        { save(options.copy(exposureSeconds = it)) },
                        selected =
                        options.exposureSeconds == it,
                    )
                }
            }
            chips {
                BINNINGS.forEach { SettingChip("$it×$it", { save(options.copy(binning = it)) }, selected = options.binning == it) }
            }
        }

        PolarOption.Tolerance -> chips {
            TOLERANCES.forEach {
                SettingChip(
                    toleranceText(it),
                    { save(options.copy(toleranceArcmin = it)) },
                    selected =
                    options.toleranceArcmin == it,
                )
            }
        }

        PolarOption.HintOnly -> chips {
            SettingChip(stringResource(Res.string.polar_no), { save(options.copy(hintOnly = false)) }, selected = !options.hintOnly)
            SettingChip(stringResource(Res.string.polar_yes), { save(options.copy(hintOnly = true)) }, selected = options.hintOnly)
        }
    }
}

private val STEPS = listOf(10.0, 15.0, 20.0, 30.0)
private val EXPOSURES = listOf(1.0, 2.0, 3.0, 5.0)
private val BINNINGS = listOf(1, 2)

/** In arcminutes, as the plugin's; 0 is off. */
private val TOLERANCES = listOf(0.0, 0.5, 1.0, 2.0, 5.0)
