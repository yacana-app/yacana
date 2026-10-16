// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.domain.equipment.Connection
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentState
import app.yacana.domain.imaging.DARK_FILTER
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.profile.DitherOptions
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.DarkRules
import app.yacana.domain.sequence.HeaterRule
import app.yacana.domain.sequence.NightPlan
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.SavedSequences
import app.yacana.domain.sequence.Sequence
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.SequenceRunner
import app.yacana.domain.sequence.Target
import app.yacana.domain.sequence.Targets
import app.yacana.domain.sequence.Trigger
import app.yacana.domain.sequence.dewHeater
import app.yacana.domain.sequence.tonight
import app.yacana.domain.sky.Ephemerides
import app.yacana.ui.Section
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.AccentButton
import app.yacana.ui.design.BottomSheet
import app.yacana.ui.design.HoldButton
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.StatusKind
import app.yacana.ui.design.StatusLine
import app.yacana.ui.design.Touch
import app.yacana.ui.format.errorText
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.alert_safe_stop
import app.yacana.ui.resources.my_save
import app.yacana.ui.resources.my_saved_as
import app.yacana.ui.resources.run_paused
import app.yacana.ui.resources.sequence_connect
import app.yacana.ui.resources.sequence_empty
import app.yacana.ui.resources.sequence_failed
import app.yacana.ui.resources.sequence_finished
import app.yacana.ui.resources.sequence_paused
import app.yacana.ui.resources.sequence_start
import app.yacana.ui.resources.sequence_stop
import app.yacana.ui.resources.sequence_stopped
import app.yacana.ui.resources.sequence_tonight
import app.yacana.ui.sequence.advanced.AdvancedTonight
import app.yacana.ui.sky.planNight
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * Tonight's sequence in the simple editor, as the design "Sequence · tonight, before
 * starting": the plan on the night's timeline, with a warning if it does not fit before dawn and
 * "Undo"; each target framed with its exposures, which a tap edits on the sheet "Sequence · edit
 * exposures", and, while it runs, their progress; the rules it follows besides the
 * frames; "Add a target", which goes to the sky; and "Start", which runs it with the
 * equipment connected, or "Hold to stop" while it runs. The top bar's menu, [menuOpen], saves
 * it in "My sequences".
 */
@Composable
@Suppress("LongMethod", "LongParameterList") // The editor's parts share the plan, the run and the sheet.
fun SequenceTonight(
    targets: Targets,
    runner: SequenceRunner,
    equipment: EquipmentController,
    clock: Clock,
    zone: () -> TimeZone,
    open: (Screen) -> Unit,
    saved: SavedSequences,
    menuOpen: Boolean = false,
    onMenuClose: () -> Unit = {},
) {
    val list by targets.targets.collectAsState()
    val canUndo by targets.canUndo.collectAsState()
    val run by runner.state.collectAsState()
    val equipmentState by equipment.state.collectAsState()
    val profile = equipmentState.active
    val connected = (equipmentState.connection as? Connection.Connected)?.equipment
    val filters =
        (connected?.filterWheel?.names ?: profile?.devices?.filterNames.orEmpty()).filterNot { it.equals(DARK_FILTER, ignoreCase = true) }
    val current = run
    val running = current?.status == RunStatus.Running
    // The run's progress while it runs, and after, how it went, as long as the targets are the same.
    val shown = current?.takeIf { running || it.sequence.targetNames() == list.map { target -> target.name } }
    val now = clock.now()
    val site = profile?.location
    // Tonight's, or the next once it is over; worked out again each minute.
    val minute = now.epochSeconds / SECONDS_PER_MINUTE
    val night = remember(site, minute) { site?.let { planNight(now, zone(), it) } }
    val dither = profile?.options?.dither ?: DitherOptions()
    val plan = NightPlan.of(list, now, night?.astronomicalDusk, night?.astronomicalDawn, dither)
    val triggers by targets.triggers.collectAsState()
    val darks by targets.darks.collectAsState()
    val advanced by targets.advanced.collectAsState()
    val ditherEvery by targets.dither.collectAsState()
    val heaterRule by targets.heater.collectAsState()
    val sequence = advanced ?: tonightSequence(list, triggers, equipmentState, darks, heaterRule)
    val heater = connected?.switches?.switches?.let(::dewHeater)
    // The connected camera's scale, so recentring says its pixels as the night will run it.
    val pixelScale = connected?.camera?.info?.let { connected.profile.optics.pixelScaleArcsec(it.pixelSizeX) }
    // The profile's focuser runs the triggers, connected or not yet.
    val focuser = profile?.devices?.focuser != null
    var autofocusOpen by remember { mutableStateOf(false) }
    var darksOpen by remember { mutableStateOf(false) }
    var recentreOpen by remember { mutableStateOf(false) }
    var ditherOpen by remember { mutableStateOf(false) }
    var heaterOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var savedAs by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val edited = advanced
            if (edited != null) {
                // What the simple editor can't show is edited in the advanced one.
                AdvancedTonight(edited, onOpen = {
                    open(Screen.Advanced())
                }, onSimple = if (running) null else ({ targets.setAdvanced(null) }))
                shown?.let { RunStatusLine(it) }
            } else if (list.isEmpty()) {
                SectionCard {
                    Muted(stringResource(Res.string.sequence_tonight))
                    Text(stringResource(Res.string.sequence_empty), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                PlanCard(plan, site != null, zone(), if (canUndo && !running) targets::undo else null)
            }
            savedAs?.let { StatusLine(stringResource(Res.string.my_saved_as, it), StatusKind.Ok, Modifier.padding(horizontal = 20.dp)) }
            if (edited == null) {
                list.forEachIndexed { index, target ->
                    TargetCard(
                        target,
                        plan.targets.getOrNull(index),
                        plan.late?.let { late -> plan.targets.indexOf(late) <= index } ?: false,
                        zone(),
                        running = shown?.isAt(index) == true,
                        done = { row -> shown?.done(index, row) ?: 0 },
                        onEdit = if (running) null else ({ row -> editing = index to row }),
                        onRemove = if (running) null else ({ targets.remove(index) }),
                    )
                }
                if (list.isNotEmpty()) {
                    Rules(
                        sequence,
                        RuleSheets(
                            autofocus = { autofocusOpen = true },
                            darks = { darksOpen = true },
                            recentre = { recentreOpen = true },
                            dither = { ditherOpen = true },
                            heater = heater?.let { { heaterOpen = true } },
                        ).takeUnless { running },
                        pixelScale,
                    )
                }
                if (!running) AddTarget { open(Screen.Root(Section.Sky)) }
                shown?.let { RunStatusLine(it) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (running) {
                HoldButton(stringResource(Res.string.sequence_stop), null, runner::stop, danger = true, icon = YacanaIcons.Stop)
            } else {
                if (connected == null &&
                    list.isNotEmpty()
                ) {
                    Muted(stringResource(Res.string.sequence_connect), Modifier.padding(horizontal = 8.dp))
                }
                AccentButton(
                    onClick = { runner.start(sequence) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Touch.Main),
                    enabled = connected != null && (advanced != null || list.any { it.exposures.isNotEmpty() }),
                ) { Text(stringResource(Res.string.sequence_start)) }
            }
        }
    }

    if (autofocusOpen) {
        BottomSheet({ autofocusOpen = false }) { AutofocusSheet(triggers, focuser, targets::setTriggers) }
    }

    if (darksOpen) {
        val names = connected?.filterWheel?.names ?: profile?.devices?.filterNames
        val darkFilter = names?.takeIf { it.isNotEmpty() }?.any { it.equals(DARK_FILTER, ignoreCase = true) }
        val temperature = triggers.filterIsInstance<Trigger.DarksAfterTemperature>().firstOrNull()?.amount
        val light = list.flatMap { it.exposures }.firstOrNull { it.type == FrameType.Light }?.exposureSeconds
        BottomSheet({ darksOpen = false }) { DarksSheet(darks, temperature, darkFilter, light, targets::setDarks) }
    }

    if (recentreOpen) {
        BottomSheet({ recentreOpen = false }) { RecentringSheet(triggers, pixelScale, targets::setTriggers) }
    }

    if (ditherOpen) {
        BottomSheet({ ditherOpen = false }) { DitherSheet(ditherEvery, targets::setDither) }
    }

    if (heaterOpen) {
        BottomSheet({ heaterOpen = false }) { HeaterSheet(heaterRule, heater?.isOnOff == false, targets::setHeater) }
    }

    if (menuOpen) {
        BottomSheet(onMenuClose) {
            OutlinedButton(
                onClick = {
                    savedAs = if (advanced != null) saved.save(sequence) else saved.save(sequence, list)
                    onMenuClose()
                },
                enabled = list.isNotEmpty() || advanced != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.my_save)) }
        }
    }

    editing?.let { (index, row) ->
        val target = list.getOrNull(index) ?: return@let
        val close = { editing = null }
        BottomSheet(close) {
            ExposuresSheet(
                target,
                row,
                filters,
                dither,
                plan.targets.getOrNull(index)?.start,
                plan.dawn,
                zone(),
                onSave = { exposures ->
                    targets.setExposures(index, exposures)
                    close()
                },
                onRemove =
                if (row < target.exposures.size && target.exposures.size > 1) {
                    {
                        targets.setExposures(index, target.exposures.filterIndexed { i, _ -> i != row })
                        close()
                    }
                } else {
                    null
                },
            )
        }
    }
}

private fun Sequence.targetNames() = root.items.drop(1).dropLast(1).mapNotNull { (it as? Block)?.name }

/**
 * Tonight's sequence from the plan's [targets] and [triggers], with the connected dew heater as
 * [heater] says; the profile's focuser runs the triggers, connected or not yet.
 */
internal fun tonightSequence(
    targets: List<Target>,
    triggers: List<Trigger>,
    state: EquipmentState,
    darks: DarkRules = DarkRules(),
    heater: HeaterRule = HeaterRule(),
): Sequence {
    val connected = (state.connection as? Connection.Connected)?.equipment
    val switch = connected?.switches?.switches?.let(::dewHeater)
    return tonight(targets, switch, state.active?.devices?.focuser != null, triggers, darks, heater)
}

/** What the run is doing now, or how it ended. */
@Composable
private fun RunStatusLine(run: SequenceRun) {
    val (text, kind) =
        when (run.status) {
            RunStatus.Running -> (run.step()?.let { stepText(it) } ?: return) to StatusKind.Busy

            RunStatus.Finished -> stringResource(Res.string.sequence_finished) to StatusKind.Ok

            // With a reason, the session's guard stopped it.
            RunStatus.Stopped -> stringResource(if (run.error == null) Res.string.sequence_stopped else Res.string.alert_safe_stop) to
                StatusKind.Warning

            // Without a reason, by hand.
            RunStatus.Paused -> stringResource(if (run.error == null) Res.string.run_paused else Res.string.sequence_paused) to
                StatusKind.Warning

            RunStatus.Failed -> stringResource(Res.string.sequence_failed, errorText(run.error.orEmpty())) to StatusKind.Error
        }
    StatusLine(text, kind, Modifier.padding(horizontal = 20.dp))
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private const val SECONDS_PER_MINUTE = 60
