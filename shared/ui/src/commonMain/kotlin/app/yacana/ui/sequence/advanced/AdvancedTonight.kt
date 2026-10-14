// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence.advanced

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Place
import app.yacana.domain.sequence.Sequence
import app.yacana.domain.sequence.areas
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.Touch
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.adv_back_to_simple_about
import app.yacana.ui.resources.adv_hold_to_simple
import app.yacana.ui.resources.adv_open
import app.yacana.ui.resources.adv_tonight_advanced
import app.yacana.ui.resources.adv_tonight_advanced_about
import app.yacana.ui.resources.adv_tonight_targets
import app.yacana.ui.sequence.Muted
import org.jetbrains.compose.resources.stringResource

/**
 * "Sequence · tonight, advanced": when tonight has what the simple editor can't show, as
 * conditions or a parallel block, it says so and opens it in the advanced editor, lists
 * its targets with how they repeat, and [onSimple], while nothing runs, goes back to the plan.
 */
@Composable
fun AdvancedTonight(sequence: Sequence, onOpen: () -> Unit, onSimple: (() -> Unit)?) {
    SectionCard(highlighted = true) {
        Text(stringResource(Res.string.adv_tonight_advanced), style = MaterialTheme.typography.titleMedium)
        Muted(stringResource(Res.string.adv_tonight_advanced_about), Modifier.padding(top = 4.dp))
        AccentButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = Touch.Main)) {
            Icon(YacanaIcons.Sequence, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.adv_open), modifier = Modifier.padding(start = 6.dp))
        }
    }
    val targets = sequence.areas().targets.items.filterIsInstance<Block>()
    if (targets.isNotEmpty()) {
        Label(stringResource(Res.string.adv_tonight_targets))
        Rows {
            targets.forEachIndexed { index, block ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val conditions = block.conditions.map { summary(it, block.target?.name) }
                ItemRow(
                    icon = if (block.items.any { it is Block && it.target != null }) YacanaIcons.Mosaic else entryOf(block).look().icon,
                    title = block.name,
                    summary = conditions.joinToString(" · ").ifEmpty { kindLine(block, Place.Block, false) },
                    warning = null,
                    onClick = onOpen,
                )
            }
        }
    }
    if (onSimple != null) {
        SectionCard {
            Muted(stringResource(Res.string.adv_back_to_simple_about))
            HoldButton(
                stringResource(Res.string.adv_hold_to_simple),
                null,
                onSimple,
                modifier = Modifier.padding(top = 8.dp),
                icon = YacanaIcons.Undo,
                danger = true,
            )
        }
    }
}
