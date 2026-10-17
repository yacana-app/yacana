// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.profile.Pace
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.NightPlan
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SavedSequences
import app.yacana.domain.sequence.Sequence
import app.yacana.domain.sequence.SequenceRunner
import app.yacana.domain.sequence.Target
import app.yacana.domain.sequence.Targets
import app.yacana.domain.sequence.areas
import app.yacana.domain.sequence.duration
import app.yacana.domain.sequence.simpleTargets
import app.yacana.domain.sky.Ephemerides
import app.yacana.ui.Section
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.imaging.clockTime
import app.yacana.ui.navigation.Screen
import app.yacana.ui.night.hoursAndMinutes
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.my_delete
import app.yacana.ui.resources.my_empty_tonight
import app.yacana.ui.resources.my_new
import app.yacana.ui.resources.my_panels
import app.yacana.ui.resources.my_saved
import app.yacana.ui.resources.my_saved_none
import app.yacana.ui.resources.my_targets
import app.yacana.ui.resources.my_templates
import app.yacana.ui.resources.my_templates_note
import app.yacana.ui.resources.sequence_running
import app.yacana.ui.resources.sequence_tonight
import app.yacana.ui.resources.template_all_night
import app.yacana.ui.resources.template_flats
import app.yacana.ui.resources.template_mosaic
import app.yacana.ui.resources.template_several
import app.yacana.ui.sky.planNight
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * The Sequence section, as the design "Sequence · my sequences": tonight's sequence, which opens
 * the simple editor; the saved ones, which open in it as tonight's, the plan before undoable, and
 * are deleted by holding them; and the templates, which start a new plan and go to the sky to
 * choose its targets. "New" starts an empty one.
 */
@Composable
@Suppress("LongParameterList") // The list shows tonight's run and plan, the saved ones and the templates.
fun MySequences(
    targets: Targets,
    saved: SavedSequences,
    runner: SequenceRunner,
    equipment: EquipmentController,
    clock: Clock,
    zone: () -> TimeZone,
    open: (Screen) -> Unit,
) {
    val list by targets.targets.collectAsState()
    val sequences by saved.saved.collectAsState()
    val run by runner.state.collectAsState()
    val profile = equipment.state.collectAsState().value.active
    val dither = profile?.options?.dither ?: DitherOptions()
    val triggers by targets.triggers.collectAsState()
    var deleting by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) {
            val now = clock.now()
            val minute = now.epochSeconds / SECONDS_PER_MINUTE
            val night = remember(profile?.location, minute) { profile?.location?.let { planNight(now, zone(), it) } }
            val plan = NightPlan.of(
                list,
                now,
                night?.astronomicalDusk,
                night?.astronomicalDawn,
                dither,
                profile.pace(),
                profile.foreseeing(triggers),
            )
            Tonight(list, plan, run?.status == RunStatus.Running, zone()) { open(Screen.SequenceTonight) }
            Heading(stringResource(Res.string.my_saved))
            if (sequences.isEmpty()) Muted(stringResource(Res.string.my_saved_none), Modifier.padding(horizontal = 20.dp))
            sequences.forEach { sequence ->
                SavedRow(sequence, dither, profile.pace(), onOpen = {
                    // In the plan, with its triggers or tonight's if it was saved without a focuser; or as an advanced night.
                    targets.open(sequence)
                    open(Screen.SequenceTonight)
                }, onHold = { deleting = sequence.name })
            }
            Heading(stringResource(Res.string.my_templates))
            Templates(onFlats = { open(Screen.Flats) }) { allNight ->
                targets.startNew(fillTheNight = allNight)
                open(Screen.Root(Section.Sky))
            }
            Muted(stringResource(Res.string.my_templates_note), Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        ExtendedFloatingActionButton(
            onClick = {
                targets.startNew()
                open(Screen.SequenceTonight)
            },
            icon = { Icon(YacanaIcons.Add, contentDescription = null) },
            text = { Text(stringResource(Res.string.my_new)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    deleting?.let { name ->
        BottomSheet({ deleting = null }) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            HoldButton(stringResource(Res.string.my_delete), null, {
                saved.delete(name)
                deleting = null
            }, danger = true, icon = YacanaIcons.Trash)
        }
    }
}

/** "Tonight": its targets and when it runs, with "Running" while it does; it opens the editor. */
@Composable
private fun Tonight(list: List<Target>, plan: NightPlan, running: Boolean, zone: TimeZone, onOpen: () -> Unit) {
    SectionCard(onClick = onOpen, highlighted = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.sequence_tonight), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (running) Text(stringResource(Res.string.sequence_running), color = MaterialTheme.colorScheme.primary)
        }
        if (list.isEmpty()) {
            Muted(stringResource(Res.string.my_empty_tonight))
        } else {
            val names = list.joinToString(" · ") { it.name }
            // When it runs, with the profile's site to plan the night.
            val times = if (plan.dawn == null) "" else " · ${clockTime(plan.start, zone)} – ${clockTime(plan.end, zone)}"
            Muted(names + times)
        }
    }
}

/** A saved sequence: its name, how many targets or panels, and how long; a tap opens it, holding it deletes it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedRow(sequence: Sequence, dither: DitherOptions, pace: Pace, onOpen: () -> Unit, onHold: () -> Unit) {
    val targets = sequence.simpleTargets().orEmpty()
    val panels = targets.singleOrNull()?.panels?.size?.takeIf { it > 1 }
    // An advanced night counts the blocks of its targets' area.
    val count = if (sequence.simpleTargets() == null) sequence.areas().targets.items.count { it is Block } else targets.size
    val size =
        panels?.let { stringResource(Res.string.my_panels, it) }
            ?: pluralStringResource(Res.plurals.my_targets, count, count)
    val time = targets.fold(Duration.ZERO) { sum, target -> sum + target.duration(dither, pace) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onHold)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(YacanaIcons.Sequence, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(sequence.name, style = MaterialTheme.typography.bodyLarge)
            Muted("$size · ${hoursAndMinutes(time)}")
        }
        Icon(YacanaIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The design's four templates; each starts a new plan, "One target all night" with its first
 * target filling the night; "Flats" opens the flats assistant.
 */
@Composable
private fun Templates(onFlats: () -> Unit, onStart: (allNight: Boolean) -> Unit) {
    val tiles =
        listOf(
            Triple(YacanaIcons.Night, Res.string.template_all_night, { onStart(true) }),
            Triple(YacanaIcons.Sky, Res.string.template_several, { onStart(false) }),
            Triple(YacanaIcons.Mosaic, Res.string.template_mosaic, { onStart(false) }),
            Triple(YacanaIcons.Bulb, Res.string.template_flats, onFlats),
        )
    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, text, onClick) -> Tile(icon, text, onClick, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, text: StringResource, onClick: (() -> Unit)?, modifier: Modifier) {
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 72.dp).alpha(if (onClick != null) 1f else DISABLED_ALPHA),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

private const val DISABLED_ALPHA = 0.5f
private const val SECONDS_PER_MINUTE = 60
