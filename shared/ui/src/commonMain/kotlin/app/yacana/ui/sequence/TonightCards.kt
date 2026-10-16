// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.GoToTarget
import app.yacana.domain.sequence.NightPlan
import app.yacana.domain.sequence.Park
import app.yacana.domain.sequence.PlannedTarget
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.Sequence
import app.yacana.domain.sequence.SequenceItem
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.SetSwitch
import app.yacana.domain.sequence.Target
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.Unpark
import app.yacana.domain.sequence.planTriggers
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.SectionCard
import app.yacana.ui.equipment.decimalComma
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.autofocus_rules
import app.yacana.ui.resources.darks_rules
import app.yacana.ui.resources.rule_autofocus
import app.yacana.ui.resources.rule_autofocus_filter
import app.yacana.ui.resources.rule_autofocus_hfr
import app.yacana.ui.resources.rule_autofocus_time
import app.yacana.ui.resources.rule_calibration
import app.yacana.ui.resources.rule_centre
import app.yacana.ui.resources.rule_darks_start
import app.yacana.ui.resources.rule_darks_start_temperature
import app.yacana.ui.resources.rule_darks_temperature
import app.yacana.ui.resources.rule_dither
import app.yacana.ui.resources.rule_heater
import app.yacana.ui.resources.rule_park
import app.yacana.ui.resources.rule_recentre
import app.yacana.ui.resources.rule_unpark
import app.yacana.ui.resources.rules_dither
import app.yacana.ui.resources.rules_heater
import app.yacana.ui.resources.rules_recentre
import app.yacana.ui.resources.sequence_add_exposures
import app.yacana.ui.resources.sequence_add_target
import app.yacana.ui.resources.sequence_done
import app.yacana.ui.resources.sequence_exposures
import app.yacana.ui.resources.sequence_late
import app.yacana.ui.resources.sequence_mosaic
import app.yacana.ui.resources.sequence_no_exposures
import app.yacana.ui.resources.sequence_no_site
import app.yacana.ui.resources.sequence_per_panel
import app.yacana.ui.resources.sequence_plan
import app.yacana.ui.resources.sequence_remove
import app.yacana.ui.resources.sequence_rules
import app.yacana.ui.resources.sequence_running
import app.yacana.ui.resources.sequence_undo
import app.yacana.ui.resources.sequence_until
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource

/**
 * "Tonight's plan": the targets on the night's timeline, from when the night starts or now to
 * dawn, the part past dawn in the error colour, and a warning naming the target that ends too late
 *; "Undo" undoes the last change. Without the profile's site there is no dawn to plan
 * against, and it says so.
 */
@Composable
internal fun PlanCard(plan: NightPlan, hasSite: Boolean, zone: TimeZone, onUndo: (() -> Unit)?) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Muted(stringResource(Res.string.sequence_plan), Modifier.weight(1f))
            TextButton(onClick = { onUndo?.invoke() }, enabled = onUndo != null) {
                Icon(YacanaIcons.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.sequence_undo), modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (!hasSite) {
            Muted(stringResource(Res.string.sequence_no_site))
            return@SectionCard
        }
        Timeline(plan)
        val times = listOfNotNull(plan.start, plan.targets.takeIf { it.size > 1 }?.first()?.end, plan.dawn)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            times.forEach { Muted(clockTime(it, zone)) }
        }
        plan.late?.let { late ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(YacanaIcons.Alert, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(Res.string.sequence_late, late.name, clockTime(late.end, zone)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** The design's bar: each target as long as it takes, alternating colours, and what is past dawn in the error colour. */
@Composable
private fun Timeline(plan: NightPlan) {
    val until = maxOf(plan.dawn ?: plan.end, plan.end)
    val total = (until - plan.start).inWholeSeconds.coerceAtLeast(1).toFloat()
    val colors = MaterialTheme.colorScheme
    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(5.dp)).background(colors.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth()) {
            plan.targets.forEachIndexed { index, target ->
                val share = (target.end - target.start).inWholeSeconds / total
                if (share > 0f) {
                    val accent = index % 2 == 0
                    Box(
                        Modifier.weight(share).height(20.dp).background(if (accent) colors.primary else colors.outlineVariant),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            target.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (accent) colors.onPrimary else colors.onSurface,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
            val rest = (until - plan.end).inWholeSeconds / total
            if (rest > 0f) Box(Modifier.weight(rest))
        }
        plan.dawn?.takeIf { plan.end > it }?.let { dawn ->
            // Past dawn in the error colour; all of it when the plan starts after dawn.
            val late = ((plan.end - dawn).inWholeSeconds / total).coerceAtMost(1f)
            Row(Modifier.fillMaxWidth()) {
                if (late < 1f) Box(Modifier.weight(1 - late))
                Box(Modifier.weight(late).fillMaxHeight().background(colors.error.copy(alpha = LATE_ALPHA)))
            }
        }
    }
}

private const val LATE_ALPHA = 0.55f

/**
 * A target as the design's card: its name, "Running" while the run is in it or "Done" once its
 * frames are taken, until when it goes on the plan (in the error colour after dawn), its panels,
 * and each of its exposures with their progress, which a tap edits; "+ Exposures" adds more.
 * [done] gives the frames taken of each exposures, all panels together.
 */
@Composable
@Suppress("LongParameterList") // The card shows the target, the plan and the run, and edits them.
internal fun TargetCard(
    target: Target,
    planned: PlannedTarget?,
    late: Boolean,
    zone: TimeZone,
    running: Boolean,
    done: (Int) -> Int,
    onEdit: ((Int) -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val panels = target.panels.size.coerceAtLeast(1)
    val complete = target.exposures.isNotEmpty() && target.exposures.indices.all { done(it) >= target.exposures[it].count * panels }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(target.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                running -> Tag(stringResource(Res.string.sequence_running), accent = true)

                complete -> Tag(stringResource(Res.string.sequence_done))

                planned != null ->
                    Text(
                        stringResource(Res.string.sequence_until, clockTime(planned.end, zone)),
                        color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            onRemove?.let { remove ->
                IconButton(onClick = remove) {
                    Icon(YacanaIcons.Trash, contentDescription = stringResource(Res.string.sequence_remove, target.name))
                }
            }
        }
        if (panels > 1) Muted(stringResource(Res.string.sequence_mosaic, panels))
        if (target.exposures.isEmpty()) Muted(stringResource(Res.string.sequence_no_exposures))
        val comma = decimalComma()
        target.exposures.forEachIndexed { index, step ->
            val frames = stringResource(Res.string.sequence_exposures, formatDecimal(step.exposureSeconds, 0, comma), step.count)
            val label = listOfNotNull(
                step.filter,
                if (panels >
                    1
                ) {
                    stringResource(Res.string.sequence_per_panel, frames)
                } else {
                    frames
                },
            ).joinToString(" · ")
            val total = step.count * panels
            Column(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).let {
                    if (onEdit !=
                        null
                    ) {
                        it.clickable { onEdit(index) }
                    } else {
                        it
                    }
                }.padding(top = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Muted("${done(index)}/$total")
                }
                Bar(done(index).toFloat() / total)
            }
        }
        onEdit?.let { edit ->
            TextButton(onClick = { edit(target.exposures.size) }) {
                Icon(YacanaIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.sequence_add_exposures), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** The design's dashed "Add a target", which goes to the sky to frame one. */
@Composable
internal fun AddTarget(onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(YacanaIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            stringResource(Res.string.sequence_add_target),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * "Rules": what the sequence does besides the frames, as the design's buttons; under them, the
 * buttons that open each kind of rule's sheet in [open], those it has: autofocus, darks, recentring,
 * dither and the dew heater. While the night runs there are none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Rules(sequence: Sequence, open: RuleSheets?) {
    val all = sequence.root.flatten()
    val start = (sequence.root.items.firstOrNull() as? Block)?.items.orEmpty()
    val end = (sequence.root.items.lastOrNull() as? Block)?.items.orEmpty()
    val comma = decimalComma()
    val rules =
        listOfNotNull(
            stringResource(Res.string.rule_unpark).takeIf { Unpark in start },
            stringResource(Res.string.rule_heater).takeIf { start.any { it is SetSwitch && it.value > 0 } },
            stringResource(Res.string.rule_centre).takeIf { all.any { it is GoToTarget && it.centre } },
            all.firstNotNullOfOrNull { (it as? Exposures)?.ditherEvery?.takeIf { every -> every > 0 } }?.let {
                stringResource(Res.string.rule_dither, it)
            },
        ) + sequence.planTriggers().filterNot { it is Trigger.DarksAfterTemperature }.map { trigger -> triggerText(trigger) } +
            listOfNotNull(
                darksRule(sequence),
                stringResource(Res.string.rule_calibration).takeIf { end.any { it is Exposures && !it.type.needsLight } },
                stringResource(Res.string.rule_park).takeIf { Park in end },
            )
    SectionCard {
        Muted(stringResource(Res.string.sequence_rules))
        FlowRow(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rules.forEach { Tag(it) }
        }
        open?.let { sheets ->
            FlowRow {
                RuleButton(stringResource(Res.string.autofocus_rules), sheets.autofocus)
                RuleButton(stringResource(Res.string.darks_rules), sheets.darks)
                RuleButton(stringResource(Res.string.rules_recentre), sheets.recentre)
                RuleButton(stringResource(Res.string.rules_dither), sheets.dither)
                sheets.heater?.let { RuleButton(stringResource(Res.string.rules_heater), it) }
            }
        }
    }
}

/** A trigger as its rule: "Autofocus if ΔT > 1 °C". */
@Composable
private fun triggerText(trigger: Trigger): String {
    val comma = decimalComma()
    return when (trigger) {
        is Trigger.AutofocusAfterTemperature ->
            stringResource(Res.string.rule_autofocus, formatDecimal(trigger.amount, if (trigger.amount % 1.0 == 0.0) 0 else 1, comma))

        Trigger.AutofocusAfterFilter -> stringResource(Res.string.rule_autofocus_filter)

        is Trigger.AutofocusAfterHfr -> stringResource(Res.string.rule_autofocus_hfr, formatDecimal(trigger.amount, 0, comma))

        is Trigger.AutofocusAfterTime -> stringResource(Res.string.rule_autofocus_time, formatDecimal(trigger.minutes, 0, comma))

        is Trigger.DarksAfterTemperature ->
            stringResource(
                Res.string.rule_darks_temperature,
                formatDecimal(
                    trigger.amount,
                    if (trigger.amount % 1.0 ==
                        0.0
                    ) {
                        0
                    } else {
                        1
                    },
                    comma,
                ),
            )

        is Trigger.RecentreAfterDrift ->
            stringResource(
                Res.string.rule_recentre,
                formatDecimal(
                    trigger.arcminutes,
                    if (trigger.arcminutes % 1.0 ==
                        0.0
                    ) {
                        0
                    } else {
                        1
                    },
                    comma,
                ),
            )
    }
}

/** Every item under this block, blocks included, in order. */
private fun Block.flatten(): List<SequenceItem> = items.flatMap { item -> listOf(item) + ((item as? Block)?.flatten() ?: emptyList()) }

/**
 * The frames the run took of the [row]th exposures of its [target]th target, all panels together:
 * the targets' blocks come after the start, and each panel goes to it before its exposures.
 */
internal fun SequenceRun.done(target: Int, row: Int): Int {
    val at = listOf(1 + target)
    val block = sequence.root.items.getOrNull(1 + target) as? Block ?: return 0
    val panels = block.items.indices.filter { block.items[it] is Block }
    return if (panels.isEmpty()) done[at + (1 + row)] ?: 0 else panels.sumOf { done[at + it + (1 + row)] ?: 0 }
}

/** Whether the run is in its [target]th target now. */
internal fun SequenceRun.isAt(target: Int) = status == RunStatus.Running && current.firstOrNull() == 1 + target

/** The design's progress bar: the accent over the surface, as far as [fraction] of the way. */
@Composable
private fun Bar(fraction: Float) {
    Box(
        Modifier
            .padding(top = 5.dp)
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(2.dp)),
    ) {
        val filled = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp)
        Box(filled.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
    }
}

/** The design's buttons over a card (`ch`); the accent one says the run is there. */
@Composable
private fun Tag(text: String, accent: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier =
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
