// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.DarkRules
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.Sequence
import app.yacana.domain.sequence.Trigger
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.imaging.formatSeconds
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.darks_rules
import app.yacana.ui.resources.darks_rules_count
import app.yacana.ui.resources.darks_rules_end
import app.yacana.ui.resources.darks_rules_intro
import app.yacana.ui.resources.darks_rules_no_filter
import app.yacana.ui.resources.darks_rules_note
import app.yacana.ui.resources.darks_rules_note_time
import app.yacana.ui.resources.darks_rules_start
import app.yacana.ui.resources.darks_rules_temperature
import app.yacana.ui.resources.rule_darks_start
import app.yacana.ui.resources.rule_darks_start_temperature
import app.yacana.ui.resources.rule_darks_temperature
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The design's choices: the sensor's degrees, and darks each time. */
private val DEGREES = listOf(2.0, 3.0, 5.0)
private val COUNTS = listOf(5, 10, 20)

/** A frame's download, as the plan counts it. */
private const val DOWNLOAD_SECONDS = 3.0
private const val SECONDS_PER_MINUTE = 60.0

/**
 * The design "Sequence · dark rules": when tonight's plan takes darks with each setting
 * of its lights, and how many each time. At the start and by the sensor's [temperature] they need a
 * filter that blocks the light, which the wheel has or not ([darkFilter], null when not known);
 * without one, they are off and say why. [lightSeconds], the first light's exposure, tells what a set
 * of darks takes from the night. Each change is the plan's, so "Undo" undoes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DarksSheet(
    rules: DarkRules,
    temperature: Double?,
    darkFilter: Boolean?,
    lightSeconds: Double?,
    onChange: (DarkRules, Double?) -> Unit,
) {
    val comma = decimalComma()
    val blocked = darkFilter == false
    Text(stringResource(Res.string.darks_rules), style = MaterialTheme.typography.titleMedium)
    Muted(stringResource(Res.string.darks_rules_intro))
    if (blocked) Muted(stringResource(Res.string.darks_rules_no_filter), Modifier.padding(top = 4.dp))
    Toggle(stringResource(Res.string.darks_rules_start), rules.atStart && !blocked, enabled = !blocked) {
        onChange(rules.copy(atStart = it), temperature)
    }
    Toggle(stringResource(Res.string.darks_rules_temperature), temperature != null && !blocked, enabled = !blocked) { on ->
        onChange(rules, if (on) DEGREES[1] else null)
    }
    if (temperature != null && !blocked) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DEGREES.forEach { degrees ->
                FilterChip(
                    selected = temperature == degrees,
                    onClick = { onChange(rules, degrees) },
                    label = { Text("${formatDecimal(degrees, 0, comma)} °C") },
                )
            }
        }
    }
    Toggle(stringResource(Res.string.darks_rules_end), rules.atEnd) { onChange(rules.copy(atEnd = it), temperature) }
    Muted(stringResource(Res.string.darks_rules_count), Modifier.padding(top = 12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COUNTS.forEach { count ->
            FilterChip(selected = rules.count == count, onClick = {
                onChange(rules.copy(count = count), temperature)
            }, label = { Text("$count") })
        }
    }
    val note = stringResource(Res.string.darks_rules_note)
    Muted(
        lightSeconds?.let { seconds ->
            val minutes = (rules.count * (seconds + DOWNLOAD_SECONDS) / SECONDS_PER_MINUTE).roundToInt().coerceAtLeast(1)
            note + " " + stringResource(Res.string.darks_rules_note_time, rules.count, formatSeconds(seconds, comma), minutes)
        } ?: note,
        Modifier.padding(top = 8.dp),
    )
}

/** A rule's switch, with what it does; off and greyed when it can't be. */
@Composable
private fun Toggle(text: String, on: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = on, onCheckedChange = onToggle, enabled = enabled)
    }
}

/** When the sequence takes darks besides the end's: "Darks at the start and every 3 °C"; null if it does not. */
@Composable
internal fun darksRule(sequence: Sequence): String? {
    val start = (sequence.root.items.firstOrNull() as? Block)?.items.orEmpty()
    val atStart = start.any { it is Exposures && it.type == FrameType.Dark }
    val degrees = sequence.root.triggers.filterIsInstance<Trigger.DarksAfterTemperature>().firstOrNull()?.amount
    val comma = decimalComma()
    val text = degrees?.let { formatDecimal(it, if (it % 1.0 == 0.0) 0 else 1, comma) }
    return when {
        atStart && text != null -> stringResource(Res.string.rule_darks_start_temperature, text)
        atStart -> stringResource(Res.string.rule_darks_start)
        text != null -> stringResource(Res.string.rule_darks_temperature, text)
        else -> null
    }
}
