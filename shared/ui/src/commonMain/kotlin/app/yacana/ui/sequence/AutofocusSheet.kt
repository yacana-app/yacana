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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.sequence.Trigger
import app.yacana.ui.design.NumberEntry
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.autofocus_rules
import app.yacana.ui.resources.autofocus_rules_filter
import app.yacana.ui.resources.autofocus_rules_hfr
import app.yacana.ui.resources.autofocus_rules_intro
import app.yacana.ui.resources.autofocus_rules_minutes
import app.yacana.ui.resources.autofocus_rules_no_focuser
import app.yacana.ui.resources.autofocus_rules_note
import app.yacana.ui.resources.autofocus_rules_temperature
import app.yacana.ui.resources.autofocus_rules_time
import app.yacana.ui.resources.imaging_exposure_other
import app.yacana.ui.resources.number_delete
import app.yacana.ui.resources.save
import org.jetbrains.compose.resources.stringResource

/** The design's choices for each trigger. */
private val DEGREES = listOf(0.5, 1.0, 2.0, 5.0)
private val PERCENTS = listOf(5.0, 10.0, 20.0)
private val MINUTES = listOf(15.0, 30.0, 60.0, 120.0)
private const val MAX_MINUTES = 600.0

/**
 * The design "Sequence · autofocus rules": N.I.N.A.'s four autofocus triggers, each
 * turned on and set with buttons; any other time goes on the app's keypad. Each change is
 * the plan's, so "Undo" undoes it. Without a [focuser] in the profile they would not run, and
 * it says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod") // One section per trigger, as the design.
internal fun AutofocusSheet(triggers: List<Trigger>, focuser: Boolean, onChange: (List<Trigger>) -> Unit) {
    val comma = decimalComma()
    val temperature = triggers.filterIsInstance<Trigger.AutofocusAfterTemperature>().firstOrNull()
    val filter = triggers.any { it is Trigger.AutofocusAfterFilter }
    val hfr = triggers.filterIsInstance<Trigger.AutofocusAfterHfr>().firstOrNull()
    val time = triggers.filterIsInstance<Trigger.AutofocusAfterTime>().firstOrNull()
    var typingMinutes by remember { mutableStateOf(false) }

    /** The triggers with the one of [kind] replaced by [trigger], or taken out without it. */
    fun setting(kind: (Trigger) -> Boolean, trigger: Trigger?) = onChange(triggers.filterNot(kind) + listOfNotNull(trigger))

    Text(stringResource(Res.string.autofocus_rules), style = MaterialTheme.typography.titleMedium)
    Muted(stringResource(if (focuser) Res.string.autofocus_rules_intro else Res.string.autofocus_rules_no_focuser))
    if (typingMinutes) {
        NumberEntry(
            initial = formatDecimal(time?.minutes ?: MINUTES[1], 0, comma),
            unit = "min",
            hint = "",
            saveLabel = stringResource(Res.string.save),
            deleteLabel = stringResource(Res.string.number_delete),
            decimal = null,
            valid = { it >= 1 && it <= MAX_MINUTES },
        ) {
            setting({ it is Trigger.AutofocusAfterTime }, Trigger.AutofocusAfterTime(it))
            typingMinutes = false
        }
        return
    }
    Toggle(stringResource(Res.string.autofocus_rules_temperature), temperature != null) { on ->
        setting({ it is Trigger.AutofocusAfterTemperature }, if (on) Trigger.AutofocusAfterTemperature(DEGREES[1]) else null)
    }
    temperature?.let { current ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DEGREES.forEach { degrees ->
                FilterChip(
                    selected = current.amount == degrees,
                    onClick = { setting({ it is Trigger.AutofocusAfterTemperature }, Trigger.AutofocusAfterTemperature(degrees)) },
                    label = { Text("${formatDecimal(degrees, if (degrees % 1.0 == 0.0) 0 else 1, comma)} °C") },
                )
            }
        }
    }
    Toggle(stringResource(Res.string.autofocus_rules_filter), filter) { on ->
        setting({ it is Trigger.AutofocusAfterFilter }, if (on) Trigger.AutofocusAfterFilter else null)
    }
    Toggle(stringResource(Res.string.autofocus_rules_hfr), hfr != null) { on ->
        setting({ it is Trigger.AutofocusAfterHfr }, if (on) Trigger.AutofocusAfterHfr(PERCENTS[1]) else null)
    }
    hfr?.let { current ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PERCENTS.forEach { percent ->
                FilterChip(
                    selected = current.amount == percent,
                    onClick = { setting({ it is Trigger.AutofocusAfterHfr }, Trigger.AutofocusAfterHfr(percent)) },
                    label = { Text("${formatDecimal(percent, 0, comma)} %") },
                )
            }
        }
    }
    Toggle(stringResource(Res.string.autofocus_rules_time), time != null) { on ->
        setting({ it is Trigger.AutofocusAfterTime }, if (on) Trigger.AutofocusAfterTime(MINUTES[1]) else null)
    }
    time?.let { current ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MINUTES.forEach { minutes ->
                FilterChip(
                    selected = current.minutes == minutes,
                    onClick = { setting({ it is Trigger.AutofocusAfterTime }, Trigger.AutofocusAfterTime(minutes)) },
                    label = { Text(stringResource(Res.string.autofocus_rules_minutes, formatDecimal(minutes, 0, comma))) },
                )
            }
            FilterChip(
                selected = current.minutes !in MINUTES,
                onClick = { typingMinutes = true },
                label = {
                    Text(
                        if (current.minutes in MINUTES) {
                            stringResource(Res.string.imaging_exposure_other)
                        } else {
                            stringResource(Res.string.autofocus_rules_minutes, formatDecimal(current.minutes, 0, comma))
                        },
                    )
                },
            )
        }
    }
    Muted(stringResource(Res.string.autofocus_rules_note), Modifier.padding(top = 8.dp))
}
