// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.yacana.domain.focus.FocusFitting
import app.yacana.domain.profile.AutofocusOptions
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.SettingChip
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.af_attempts
import app.yacana.ui.resources.af_attempts_hint
import app.yacana.ui.resources.af_backlash_hint
import app.yacana.ui.resources.af_backlash_in
import app.yacana.ui.resources.af_backlash_out
import app.yacana.ui.resources.af_exposure
import app.yacana.ui.resources.af_exposure_hint
import app.yacana.ui.resources.af_exposure_value
import app.yacana.ui.resources.af_fitting
import app.yacana.ui.resources.af_fitting_hint
import app.yacana.ui.resources.af_frames
import app.yacana.ui.resources.af_frames_hint
import app.yacana.ui.resources.af_points
import app.yacana.ui.resources.af_points_hint
import app.yacana.ui.resources.af_r2
import app.yacana.ui.resources.af_r2_hint
import app.yacana.ui.resources.af_r2_none
import app.yacana.ui.resources.af_settings_note
import app.yacana.ui.resources.af_settle
import app.yacana.ui.resources.af_settle_hint
import app.yacana.ui.resources.af_step
import app.yacana.ui.resources.af_step_hint
import app.yacana.ui.resources.image_files_no_profile
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.save
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Which option a sheet edits. */
private enum class AutofocusOption { Step, Points, Exposure, Frames, Fitting, BacklashIn, BacklashOut, RSquared, Attempts, Settle }

/** The autofocus's options of the profile in use, as "Settings · autofocus". */
@Composable
fun AutofocusSettingsScreen(equipment: EquipmentController) {
    val state by equipment.state.collectAsState()
    val profile = state.active
    var editing by remember { mutableStateOf<AutofocusOption?>(null) }
    val comma = decimalComma()
    if (profile == null) {
        Text(
            stringResource(Res.string.image_files_no_profile),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val options = profile.options.autofocus
    fun save(changed: AutofocusOptions) = equipment.save(profile.copy(options = profile.options.copy(autofocus = changed)))

    @Composable
    fun row(option: AutofocusOption, name: StringResource, value: String, first: Boolean = false) =
        OptionRow(stringResource(name), value, first) { editing = option }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            row(AutofocusOption.Step, Res.string.af_step, "${options.stepSize}", first = true)
            row(AutofocusOption.Points, Res.string.af_points, "${2 * options.offsetSteps + 1}")
            row(
                AutofocusOption.Exposure,
                Res.string.af_exposure,
                stringResource(Res.string.af_exposure_value, formatDecimal(options.exposureSeconds, 0, comma), options.binning),
            )
            row(AutofocusOption.Frames, Res.string.af_frames, "${options.framesPerPoint}")
            row(AutofocusOption.Fitting, Res.string.af_fitting, fittingName(options.fitting))
        }
        SectionCard {
            row(AutofocusOption.BacklashIn, Res.string.af_backlash_in, "${options.backlashIn}", first = true)
            row(AutofocusOption.BacklashOut, Res.string.af_backlash_out, "${options.backlashOut}")
            row(
                AutofocusOption.RSquared,
                Res.string.af_r2,
                if (options.rSquaredThreshold <=
                    0
                ) {
                    stringResource(Res.string.af_r2_none)
                } else {
                    formatDecimal(options.rSquaredThreshold, 1, comma)
                },
            )
            row(AutofocusOption.Attempts, Res.string.af_attempts, "${options.attempts}")
            row(AutofocusOption.Settle, Res.string.af_settle, "${formatDecimal(options.settleSeconds, 0, comma)} s")
        }
        Text(
            stringResource(Res.string.af_settings_note, options.offsetSteps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    editing?.let { option ->
        BottomSheet(onDismiss = { editing = null }) {
            OptionSheet(option, options) { changed ->
                save(changed)
                if (option in NUMBERS) editing = null
            }
        }
    }
}

/** An option as the design draws it: its name, and its value on the right. */
@Composable
private fun OptionRow(label: String, value: String, first: Boolean, onClick: () -> Unit) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One option on chips, or the app's keypad for the step and the backlash, with what it does. */
@Composable
private fun OptionSheet(option: AutofocusOption, options: AutofocusOptions, save: (AutofocusOptions) -> Unit) {
    val comma = decimalComma()
    val (title, hint) = TEXTS.getValue(option)
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    @Composable
    fun <T> chips(values: List<T>, current: T, label: @Composable (T) -> String, change: (T) -> AutofocusOptions) {
        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { SettingChip(label(it), { save(change(it)) }, selected = it == current) }
        }
    }

    @Composable
    fun number(current: Int, valid: IntRange, change: (Int) -> AutofocusOptions) = NumberEntry(
        initial = current.toString(),
        unit = "",
        hint = stringResource(hint),
        saveLabel = stringResource(Res.string.save),
        deleteLabel = stringResource(Res.string.number_delete),
        decimal = null,
        valid = { it.toInt().toDouble() == it && it.toInt() in valid },
    ) { save(change(it.toInt())) }

    when (option) {
        AutofocusOption.Step -> number(options.stepSize, 1..MAX_STEP) { options.copy(stepSize = it) }

        AutofocusOption.BacklashIn -> number(options.backlashIn, 0..MAX_STEP) { options.copy(backlashIn = it) }

        AutofocusOption.BacklashOut -> number(options.backlashOut, 0..MAX_STEP) { options.copy(backlashOut = it) }

        AutofocusOption.Points -> chips(POINTS, 2 * options.offsetSteps + 1, { "$it" }) { options.copy(offsetSteps = (it - 1) / 2) }

        AutofocusOption.Exposure -> {
            chips(EXPOSURES, options.exposureSeconds, { "${formatDecimal(it, 0, comma)} s" }) { options.copy(exposureSeconds = it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BINNINGS.forEach { SettingChip("$it×$it", { save(options.copy(binning = it)) }, selected = options.binning == it) }
            }
        }

        AutofocusOption.Frames -> chips(FRAMES, options.framesPerPoint, { "$it" }) { options.copy(framesPerPoint = it) }

        AutofocusOption.Fitting -> chips(FocusFitting.entries, options.fitting, { fittingName(it) }) { options.copy(fitting = it) }

        AutofocusOption.RSquared -> chips(
            R_SQUARED,
            options.rSquaredThreshold,
            { if (it <= 0) stringResource(Res.string.af_r2_none) else formatDecimal(it, 1, comma) },
        ) { options.copy(rSquaredThreshold = it) }

        AutofocusOption.Attempts -> chips(ATTEMPTS, options.attempts, { "$it" }) { options.copy(attempts = it) }

        AutofocusOption.Settle -> chips(SETTLES, options.settleSeconds, {
            "${formatDecimal(it, 0, comma)} s"
        }) { options.copy(settleSeconds = it) }
    }
}

private val TEXTS =
    mapOf(
        AutofocusOption.Step to (Res.string.af_step to Res.string.af_step_hint),
        AutofocusOption.Points to (Res.string.af_points to Res.string.af_points_hint),
        AutofocusOption.Exposure to (Res.string.af_exposure to Res.string.af_exposure_hint),
        AutofocusOption.Frames to (Res.string.af_frames to Res.string.af_frames_hint),
        AutofocusOption.Fitting to (Res.string.af_fitting to Res.string.af_fitting_hint),
        AutofocusOption.BacklashIn to (Res.string.af_backlash_in to Res.string.af_backlash_hint),
        AutofocusOption.BacklashOut to (Res.string.af_backlash_out to Res.string.af_backlash_hint),
        AutofocusOption.RSquared to (Res.string.af_r2 to Res.string.af_r2_hint),
        AutofocusOption.Attempts to (Res.string.af_attempts to Res.string.af_attempts_hint),
        AutofocusOption.Settle to (Res.string.af_settle to Res.string.af_settle_hint),
    )

/** The options typed on the keypad, whose sheet closes once saved. */
private val NUMBERS = setOf(AutofocusOption.Step, AutofocusOption.BacklashIn, AutofocusOption.BacklashOut)

private val POINTS = listOf(5, 7, 9, 11, 13)
private val EXPOSURES = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 8.0)
private val BINNINGS = listOf(1, 2)
private val FRAMES = listOf(1, 2, 3)
private val R_SQUARED = listOf(0.0, 0.5, 0.7, 0.8, 0.9)
private val ATTEMPTS = listOf(1, 2, 3)
private val SETTLES = listOf(0.0, 1.0, 2.0, 5.0)
private const val MAX_STEP = 10_000
