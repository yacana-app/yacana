// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.sequence.DITHER_EVERY
import app.yacana.domain.sequence.HeaterRule
import app.yacana.domain.sequence.Trigger
import app.yacana.ui.YacanaIcons
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.dither_every
import app.yacana.ui.resources.dither_every_frame
import app.yacana.ui.resources.dither_note
import app.yacana.ui.resources.dither_switch
import app.yacana.ui.resources.heater_intro
import app.yacana.ui.resources.heater_note
import app.yacana.ui.resources.heater_switch
import app.yacana.ui.resources.imaging_dither_hint
import app.yacana.ui.resources.recentre_intro
import app.yacana.ui.resources.recentre_note
import app.yacana.ui.resources.recentre_switch
import app.yacana.ui.resources.rules_dither
import app.yacana.ui.resources.rules_heater
import app.yacana.ui.resources.rules_recentre
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The design's choices: how far the field may drift, every how many frames the lights dither, the heater's power. */
private val ARCMINUTES = listOf(0.5, 1.0, 2.0, 5.0)
private val FRAMES = listOf(1, DITHER_EVERY, 5, 10)
private val POWERS = listOf(0.25, 0.5, 0.75, 1.0)
private const val PERCENT = 100

/**
 * The design "Sequence · recentring": whether tonight's plan recentres when the field drifts from
 * the target, and how far it may drift; the other [triggers] stay as they are. Each change is the
 * plan's, so "Undo" undoes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecentringSheet(triggers: List<Trigger>, onChange: (List<Trigger>) -> Unit) {
    val comma = decimalComma()
    val recentre = triggers.filterIsInstance<Trigger.RecentreAfterDrift>().firstOrNull()

    fun setting(trigger: Trigger.RecentreAfterDrift?) = onChange(
        triggers.filterNot {
            it is Trigger.RecentreAfterDrift
        } + listOfNotNull(trigger),
    )

    Text(stringResource(Res.string.rules_recentre), style = MaterialTheme.typography.titleMedium)
    Muted(stringResource(Res.string.recentre_intro))
    Toggle(stringResource(Res.string.recentre_switch), recentre != null) { on ->
        setting(if (on) Trigger.RecentreAfterDrift(ARCMINUTES[1]) else null)
    }
    recentre?.let { current ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ARCMINUTES.forEach { arcminutes ->
                FilterChip(
                    selected = current.arcminutes == arcminutes,
                    onClick = { setting(Trigger.RecentreAfterDrift(arcminutes)) },
                    label = { Text("${formatDecimal(arcminutes, if (arcminutes % 1.0 == 0.0) 0 else 1, comma)}′") },
                )
            }
        }
    }
    Muted(stringResource(Res.string.recentre_note), Modifier.padding(top = 8.dp))
}

/** The design "Sequence · dither": whether tonight's lights dither, and every how many [frames]; 0 for never. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DitherSheet(frames: Int, onChange: (Int) -> Unit) {
    Text(stringResource(Res.string.rules_dither), style = MaterialTheme.typography.titleMedium)
    Muted(stringResource(Res.string.imaging_dither_hint))
    Toggle(stringResource(Res.string.dither_switch), frames > 0) { on -> onChange(if (on) DITHER_EVERY else 0) }
    if (frames > 0) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FRAMES.forEach { every ->
                FilterChip(
                    selected = frames == every,
                    onClick = { onChange(every) },
                    label = {
                        Text(
                            if (every ==
                                1
                            ) {
                                stringResource(Res.string.dither_every_frame)
                            } else {
                                stringResource(Res.string.dither_every, every)
                            },
                        )
                    },
                )
            }
        }
    }
    Muted(stringResource(Res.string.dither_note), Modifier.padding(top = 8.dp))
}

/**
 * The design "Sequence · dew heater": whether tonight's plan turns the heater on at the start and
 * off at the end; a heater that takes a value, not only on and off ([adjustable]), with its power.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeaterSheet(rule: HeaterRule, adjustable: Boolean, onChange: (HeaterRule) -> Unit) {
    Text(stringResource(Res.string.rules_heater), style = MaterialTheme.typography.titleMedium)
    Muted(stringResource(Res.string.heater_intro))
    Toggle(stringResource(Res.string.heater_switch), rule.on) { on -> onChange(rule.copy(on = on)) }
    if (rule.on && adjustable) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            POWERS.forEach { power ->
                FilterChip(
                    selected = rule.power == power,
                    onClick = { onChange(rule.copy(power = power)) },
                    label = { Text("${(power * PERCENT).roundToInt()} %") },
                )
            }
        }
    }
    Muted(stringResource(Res.string.heater_note), Modifier.padding(top = 8.dp))
}

/** What each rule button under "Rules" opens; the dew heater's only with a heater connected. */
internal class RuleSheets(
    val autofocus: () -> Unit,
    val darks: () -> Unit,
    val recentre: () -> Unit,
    val dither: () -> Unit,
    val heater: (() -> Unit)?,
)

/** A button under "Rules" that opens its rule's sheet. */
@Composable
internal fun RuleButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(YacanaIcons.Adjustments, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, modifier = Modifier.padding(start = 4.dp))
    }
}
