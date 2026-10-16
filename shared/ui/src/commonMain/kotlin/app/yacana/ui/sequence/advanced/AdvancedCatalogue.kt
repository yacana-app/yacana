// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence.advanced

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Blocked
import app.yacana.domain.sequence.Condition
import app.yacana.domain.sequence.CoolCamera
import app.yacana.domain.sequence.Entry
import app.yacana.domain.sequence.Exposures
import app.yacana.domain.sequence.GoToTarget
import app.yacana.domain.sequence.MoveFocuser
import app.yacana.domain.sequence.Note
import app.yacana.domain.sequence.Park
import app.yacana.domain.sequence.Place
import app.yacana.domain.sequence.RunAutofocus
import app.yacana.domain.sequence.SetSwitch
import app.yacana.domain.sequence.SkyTarget
import app.yacana.domain.sequence.StopTracking
import app.yacana.domain.sequence.SwitchFilter
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.Unpark
import app.yacana.domain.sequence.WaitFor
import app.yacana.domain.sequence.WaitUntilAbove
import app.yacana.domain.sequence.WaitUntilTime
import app.yacana.domain.sequence.WarmCamera
import app.yacana.domain.sequence.blockAt
import app.yacana.domain.sequence.blocked
import app.yacana.domain.sequence.blockedIn
import app.yacana.domain.sequence.changeAt
import app.yacana.ui.AppServices
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.SearchField
import app.yacana.ui.design.SettingChip
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.adv_add
import app.yacana.ui.resources.adv_cancel
import app.yacana.ui.resources.adv_e_parallel
import app.yacana.ui.resources.adv_e_sequential
import app.yacana.ui.resources.adv_hint_target
import app.yacana.ui.resources.adv_m_retarget
import app.yacana.ui.resources.adv_nothing_found
import app.yacana.ui.resources.adv_pick
import app.yacana.ui.resources.adv_pick_about
import app.yacana.ui.resources.adv_pick_found
import app.yacana.ui.resources.adv_pick_framed
import app.yacana.ui.resources.adv_pick_mosaic
import app.yacana.ui.resources.adv_pick_mosaic_whole
import app.yacana.ui.resources.adv_pick_none
import app.yacana.ui.resources.adv_pick_search
import app.yacana.ui.resources.adv_search
import app.yacana.ui.resources.adv_template_target
import app.yacana.ui.resources.adv_template_target_desc
import app.yacana.ui.resources.adv_word_template
import app.yacana.ui.sequence.Muted
import org.jetbrains.compose.resources.stringResource

/**
 * "Sequence · add an instruction": the catalogue to add to the level at [path], with
 * its search and categories, as N.I.N.A.'s, starting at [category]'s. What can't go there is greyed
 * out with why; an entry with settings opens them first, a target opens the list of Sky's, and
 * the rest goes in at once. [onDone] goes back to the level.
 */
@Composable
fun AdvancedCatalogue(path: List<Int>, category: String?, services: AppServices, onDone: () -> Unit) {
    val tonight = rememberTonight(services)
    val block = tonight.tree.blockAt(path) ?: return
    val place = tonight.place(path)
    val inTarget = tonight.chain(path).any { it.target != null }
    val holder = levelTitle(tonight, path)
    val templates by services.blockTemplates.saved.collectAsState()
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(Category.entries.firstOrNull { it.name == category } ?: Category.All) }
    var adding by remember { mutableStateOf<Draft?>(null) }
    var picking by remember { mutableStateOf<PickMode?>(null) }
    val names = ContainerNames(stringResource(Res.string.adv_e_sequential), stringResource(Res.string.adv_e_parallel))
    val add: (Draft) -> Unit = { draft ->
        services.saveTonight(tonight.tree.changeAt(path) { it.adding(draft) }, tonight.sequence.name)
        onDone()
    }
    val words = query.trim().lowercase()
    val shown =
        Entry.entries.filter { entry ->
            val look = entry.look()
            (chosen == Category.All || look.category == chosen) &&
                (words.isEmpty() || "${stringResource(look.name)} ${stringResource(look.about)}".lowercase().contains(words))
        }
    val builtin = stringResource(Res.string.adv_template_target)
    val withTemplates = chosen == Category.All || chosen == Category.Templates
    val saved = if (withTemplates) templates.filter { words.isEmpty() || it.name.lowercase().contains(words) } else emptyList()
    val showBuiltin = withTemplates && (words.isEmpty() || builtin.lowercase().contains(words))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp)) { SearchField(query, stringResource(Res.string.adv_search)) { query = it } }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Category.entries.forEach { SettingChip(stringResource(it.title), onClick = { chosen = it }, selected = it == chosen) }
        }
        if (place != Place.Night &&
            shown.any { it.blocked(place, inTarget, tonight.cooler, tonight.darkFilter) == Blocked.OnlyInATarget }
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.Top) {
                Icon(YacanaIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Muted(stringResource(Res.string.adv_hint_target, holder), Modifier.padding(start = 8.dp))
            }
        }
        Rows {
            shown.forEachIndexed { index, entry ->
                val look = entry.look()
                val why = entry.blocked(place, inTarget, tonight.cooler, tonight.darkFilter)
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ItemRow(
                    icon = look.icon,
                    title = stringResource(look.name),
                    summary =
                    why?.let { blockedText(it, holder) }
                        ?: listOfNotNull(
                            stringResource(look.about),
                            look.category.word?.let {
                                stringResource(it)
                            },
                        ).joinToString(" · "),
                    warning = null,
                    enabled = why == null,
                    onClick = {
                        when (val draft = entry.draft(tonight, names)) {
                            null -> picking = PickMode.New
                            else -> if (entry.hasSettings()) adding = draft else add(draft)
                        }
                    },
                )
            }
            val templateWhy = Entry.Target.blocked(place, inTarget, tonight.cooler)
            if (showBuiltin) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ItemRow(
                    icon = YacanaIcons.Template,
                    title = builtin,
                    summary =
                    templateWhy?.let { blockedText(it, holder) }
                        ?: "${stringResource(Res.string.adv_template_target_desc)} · ${stringResource(Res.string.adv_word_template)}",
                    warning = null,
                    enabled = templateWhy == null,
                    onClick = { picking = PickMode.Template },
                )
            }
            saved.forEach { template ->
                val why = template.blockedIn(place, inTarget)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ItemRow(
                    icon = YacanaIcons.Template,
                    title = template.name,
                    summary =
                    why?.let { blockedText(it, holder) }
                        ?: "${kindLine(template, Place.Block, false)} · ${stringResource(Res.string.adv_word_template)}",
                    warning = null,
                    enabled = why == null,
                    onClick = { add(Draft.OfItem(template)) },
                )
            }
        }
        if (shown.isEmpty() && saved.isEmpty() &&
            !showBuiltin
        ) {
            Muted(stringResource(Res.string.adv_nothing_found), Modifier.padding(horizontal = 20.dp))
        }
    }

    adding?.let { draft ->
        BottomSheet({ adding = null }) {
            SettingsBody(draft, tonight, path, RunMark.None, stringResource(Res.string.adv_add), onSave = add, onDelete = null, onCancel = {
                adding =
                    null
            })
        }
    }
    picking?.let { mode ->
        // A new target recentres as the plan's targets do, so a night the simple editor made stays its.
        val recentre = services.targets.triggers.value.filterIsInstance<Trigger.RecentreAfterDrift>()
        TargetPicker(services, tonight, mode, onClose = {
            picking = null
        }) { picked -> add(Draft.OfItem(picked.block.copy(triggers = recentre))) }
    }
}

/** The names a new container takes, in the user's words. */
internal class ContainerNames(val sequential: String, val parallel: String)

/** Whether an entry opens its settings before it is added. */
private fun Entry.hasSettings() = this !in setOf(
    Entry.Warm, Entry.UnparkMount, Entry.ParkMount, Entry.Centre, Entry.GoTo, Entry.StopMount, Entry.Autofocus,
    Entry.Sequential, Entry.Parallel, Entry.AutofocusFilter,
)

/** What an entry adds, as N.I.N.A. starts it; null for a target, which is chosen from Sky's. */
@Suppress("CyclomaticComplexMethod") // One start per entry.
internal fun Entry.draft(tonight: Tonight, names: ContainerNames): Draft? = when (this) {
    Entry.TakeExposures -> Draft.OfItem(
        Exposures(count = NEW_FRAMES, exposureSeconds = NEW_SECONDS, filter = tonight.filters.firstOrNull(), type = FrameType.Light),
    )

    Entry.Cool -> Draft.OfItem(CoolCamera(NEW_TEMPERATURE, NEW_COOLING_MINUTES))

    Entry.Warm -> Draft.OfItem(WarmCamera)

    Entry.UnparkMount -> Draft.OfItem(Unpark)

    Entry.ParkMount -> Draft.OfItem(Park)

    Entry.Centre -> Draft.OfItem(GoToTarget(centre = true))

    Entry.GoTo -> Draft.OfItem(GoToTarget(centre = false))

    Entry.StopMount -> Draft.OfItem(StopTracking)

    Entry.Autofocus -> Draft.OfItem(RunAutofocus)

    Entry.Focuser -> Draft.OfItem(MoveFocuser(NEW_POSITION))

    Entry.Filter -> Draft.OfItem(SwitchFilter(tonight.filters.firstOrNull() ?: DEFAULT_FILTER))

    Entry.Switch -> Draft.OfItem(SetSwitch(tonight.heater ?: DEFAULT_HEATER, 1.0))

    Entry.WaitTime -> Draft.OfItem(WaitUntilTime(NEW_WAIT_MINUTE))

    Entry.WaitSpan -> Draft.OfItem(WaitFor(NEW_WAIT))

    Entry.WaitAltitude -> Draft.OfItem(WaitUntilAbove(NEW_ALTITUDE))

    Entry.Annotation -> Draft.OfItem(Note(""))

    Entry.Target -> null

    Entry.Sequential -> Draft.OfItem(Block(names.sequential))

    Entry.Parallel -> Draft.OfItem(Block(names.parallel, parallel = true))

    Entry.AutofocusTemperature -> Draft.OfTrigger(Trigger.AutofocusAfterTemperature(1.0))

    Entry.AutofocusTime -> Draft.OfTrigger(Trigger.AutofocusAfterTime(NEW_PERIOD))

    Entry.AutofocusFilter -> Draft.OfTrigger(Trigger.AutofocusAfterFilter)

    Entry.AutofocusHfr -> Draft.OfTrigger(Trigger.AutofocusAfterHfr(NEW_HFR))

    Entry.Recentre -> Draft.OfTrigger(Trigger.RecentreAfterDrift(1.0))

    Entry.DarksTemperature -> Draft.OfTrigger(Trigger.DarksAfterTemperature())

    Entry.Repeat -> Draft.OfCondition(Condition.Loop(NEW_ROUNDS))

    Entry.RepeatUntil -> Draft.OfCondition(Condition.UntilTime(NEW_UNTIL_MINUTE))

    Entry.RepeatWhileHigh -> Draft.OfCondition(Condition.WhileAbove(NEW_ALTITUDE))
}

/** How the list of targets opens: to add a target or the template of one, or to change a target's. */
internal enum class PickMode { New, Template, Change }

/** A target chosen from Sky's: its name, where it is, and its block as it goes into the tree. */
internal class Picked(val name: String, val target: SkyTarget, val block: Block)

/**
 * "Sequence · choose a target": the targets framed in Sky, a mosaic with one target per panel,
 * and what Sky's search finds, with its centre; each with its hours above 30° tonight. A target
 * comes with "Go to and centre"; the template's, with an autofocus and exposures too. A mosaic is only
 * added whole.
 */
@Composable
internal fun TargetPicker(services: AppServices, tonight: Tonight, mode: PickMode, onClose: () -> Unit, onPick: (Picked) -> Unit) {
    val framed by services.targets.targets.collectAsState()
    var query by remember { mutableStateOf("") }
    val words = query.trim()
    val found = remember(words) { if (words.length < 2) emptyList() else services.objects.search(words).take(FOUND) }
    val extras =
        if (mode == PickMode.Template) {
            listOf(RunAutofocus, Exposures(count = NEW_FRAMES, exposureSeconds = NEW_SECONDS, filter = tonight.filters.firstOrNull()))
        } else {
            emptyList()
        }
    BottomSheet(onClose) {
        Text(
            stringResource(
                if (mode ==
                    PickMode.Change
                ) {
                    Res.string.adv_m_retarget
                } else {
                    Res.string.adv_pick
                },
            ),
            style = MaterialTheme.typography.titleLarge,
        )
        Row(Modifier.padding(vertical = 8.dp)) { SearchField(query, stringResource(Res.string.adv_pick_search)) { query = it } }
        Rows {
            val shown = framed.filter { words.isEmpty() || it.name.contains(words, ignoreCase = true) }
            shown.forEachIndexed { index, target ->
                val mosaic = target.panels.size > 1
                val whole = mosaic && mode != PickMode.New
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val about = if (mosaic) {
                    stringResource(
                        Res.string.adv_pick_mosaic,
                        target.panels.size,
                    )
                } else {
                    stringResource(Res.string.adv_pick_framed)
                }
                ItemRow(
                    icon = if (mosaic) YacanaIcons.Mosaic else YacanaIcons.Target,
                    title = target.name,
                    summary = if (whole) {
                        stringResource(
                            Res.string.adv_pick_mosaic_whole,
                        )
                    } else {
                        listOfNotNull(about, window(tonight, target.position)).joinToString(" · ")
                    },
                    warning = null,
                    enabled = !whole,
                    onClick = {
                        val centre = SkyTarget(target.name, target.raHours, target.decDeg, target.rotationDeg)
                        val block =
                            if (mosaic) {
                                Block(
                                    target.name,
                                    target.panels.map {
                                        Block(
                                            "${target.name} Panel ${it.number}",
                                            listOf(GoToTarget(centre = true)),
                                            SkyTarget("${target.name} Panel ${it.number}", it.raHours, it.decDeg, it.rotationDeg),
                                        )
                                    },
                                    centre,
                                )
                            } else {
                                val panel = target.panels.firstOrNull()
                                val at = panel?.let { SkyTarget(target.name, it.raHours, it.decDeg, it.rotationDeg) } ?: centre
                                Block(target.name, listOf(GoToTarget(centre = true)) + extras, at)
                            }
                        onPick(Picked(target.name, block.target ?: centre, block))
                    },
                )
            }
            found.forEachIndexed { index, item ->
                if (index > 0 || shown.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val name = listOfNotNull(item.designation, item.spanishName ?: item.englishNames.firstOrNull()).joinToString(" · ")
                ItemRow(
                    icon = YacanaIcons.Target,
                    title = name,
                    summary = listOfNotNull(stringResource(Res.string.adv_pick_found), window(tonight, item.position)).joinToString(" · "),
                    warning = null,
                    onClick = {
                        val target = SkyTarget(item.designation, item.position.raHours, item.position.decDeg)
                        onPick(
                            Picked(item.designation, target, Block(item.designation, listOf(GoToTarget(centre = true)) + extras, target)),
                        )
                    },
                )
            }
            if (shown.isEmpty() && found.isEmpty()) Muted(stringResource(Res.string.adv_pick_none), Modifier.padding(16.dp))
        }
        Muted(stringResource(Res.string.adv_pick_about), Modifier.padding(top = 10.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(stringResource(Res.string.adv_cancel))
        }
    }
}

private const val NEW_FRAMES = 10
private const val NEW_SECONDS = 60.0
private const val NEW_TEMPERATURE = -10.0
private const val NEW_COOLING_MINUTES = 10.0
private const val NEW_POSITION = 1500
private const val NEW_WAIT_MINUTE = 21 * 60 + 30
private const val NEW_UNTIL_MINUTE = 2 * 60
private const val NEW_WAIT = 5.0
private const val NEW_ALTITUDE = 30.0
private const val NEW_PERIOD = 60.0
private const val NEW_HFR = 10.0
private const val NEW_ROUNDS = 3
private const val FOUND = 20
private const val DEFAULT_FILTER = "L"
private const val DEFAULT_HEATER = "Dew heater"
