// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence.advanced

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.yacana.domain.equipment.Connection
import app.yacana.domain.imaging.DARK_FILTER
import app.yacana.domain.profile.Location
import app.yacana.domain.sequence.Block
import app.yacana.domain.sequence.Kit
import app.yacana.domain.sequence.Place
import app.yacana.domain.sequence.Problem
import app.yacana.domain.sequence.RunStatus
import app.yacana.domain.sequence.Sequence
import app.yacana.domain.sequence.SequenceRun
import app.yacana.domain.sequence.SkyTarget
import app.yacana.domain.sequence.Spot
import app.yacana.domain.sequence.Warning
import app.yacana.domain.sequence.areaPath
import app.yacana.domain.sequence.areas
import app.yacana.domain.sequence.asAreas
import app.yacana.domain.sequence.asBlock
import app.yacana.domain.sequence.darkRules
import app.yacana.domain.sequence.dewHeater
import app.yacana.domain.sequence.planTriggers
import app.yacana.domain.sequence.review
import app.yacana.domain.sequence.simpleTargets
import app.yacana.domain.sky.Night
import app.yacana.ui.AppServices
import app.yacana.ui.sequence.tonightSequence
import app.yacana.ui.sky.planNight
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Tonight as the advanced editor shows it: the run's sequence while one goes on or
 * waits, or the advanced sequence kept with the plan, or the plan's as the simple editor makes
 * it; as one block of its three areas ([tree]), with what the equipment can do, the warnings of
 * "Review" at their places in the tree, and tonight's night at the profile's site.
 */
internal class Tonight(
    val sequence: Sequence,
    val tree: Block,
    val run: SequenceRun?,
    val warnings: List<Warning>,
    val profile: String?,
    val heater: String?,
    val filters: List<String>,
    val cooler: Boolean?,
    /** Whether the wheel has a filter that blocks the light; null when not known. */
    val darkFilter: Boolean?,
    val night: Night?,
    val site: Location?,
    val canUndo: Boolean,
    val now: Instant,
    val zone: TimeZone,
) {
    /** The item running, as the tree numbers it. */
    val current: List<Int>? = run?.let { sequence.areaPath(it.current) }

    /** The frames each exposures has taken, as the tree numbers them. */
    val done: Map<List<Int>, Int> = run?.done?.mapKeys { (path, _) -> sequence.areaPath(path) }.orEmpty()

    /** What a run going on or done marks on the item at [at]: done, running, or holding what runs. */
    fun mark(at: List<Int>, block: Boolean): RunMark {
        val now = current ?: return RunMark.None
        return when {
            block && now.size > at.size && now.take(at.size) == at -> RunMark.Holds
            at == now -> RunMark.Running
            at.comesBefore(now) -> RunMark.Done
            else -> RunMark.None
        }
    }

    /** Whether the item at [at] can't change: it is done, it runs, or what runs is inside it. */
    fun locked(at: List<Int>, block: Boolean) = mark(at, block) != RunMark.None

    /** The blocks from the tree down to the one at [path], the tree first; shorter if the path no longer is. */
    fun chain(path: List<Int>): List<Block> {
        val chain = mutableListOf(tree)
        for (index in path) chain += chain.last().items.getOrNull(index) as? Block ?: break
        return chain
    }

    /** The target the item at [path] goes to: its block's, or the nearest around it. */
    fun target(path: List<Int>): SkyTarget? = chain(path).lastOrNull { it.target != null }?.target

    /** Where the block at [path] is, for what it can take. */
    fun place(path: List<Int>): Place = when {
        path.isEmpty() -> Place.Night
        path.size > 1 -> Place.Block
        path[0] == 0 -> Place.Start
        path[0] == 1 -> Place.Targets
        else -> Place.End
    }

    /** The warnings at the item at [path], or at the trigger or condition of the block there. */
    fun warningsAt(spot: Spot): List<Warning> = warnings.filter { it.spot == spot }

    /** How many warnings there are inside the block at [path], its own triggers and conditions included. */
    fun warningsInside(path: List<Int>): Int = warnings.count { warning ->
        val at = warning.spot.path
        at.size >= path.size && at.take(path.size) == path &&
            (at.size > path.size || warning.spot.trigger != null || warning.spot.condition != null)
    }
}

/** What a run marks on an item. */
internal enum class RunMark { None, Done, Running, Holds }

/** Whether the item at this path runs before the one at [other], and is not one that holds it. */
private fun List<Int>.comesBefore(other: List<Int>): Boolean {
    val differs = indices.firstOrNull { it >= other.size || this[it] != other[it] } ?: return false
    return differs < other.size && this[differs] < other[differs]
}

/** Tonight as the advanced editor shows it, following the run, the plan and the equipment. */
@Composable
internal fun rememberTonight(services: AppServices): Tonight {
    val runState by services.sequence.state.collectAsState()
    val advanced by services.targets.advanced.collectAsState()
    val list by services.targets.targets.collectAsState()
    val triggers by services.targets.triggers.collectAsState()
    val darks by services.targets.darks.collectAsState()
    val heater by services.targets.heater.collectAsState()
    val canUndo by services.targets.canUndo.collectAsState()
    val equipment by services.equipment.state.collectAsState()
    val run = runState?.takeIf { it.status == RunStatus.Running || it.status == RunStatus.Paused }
    val sequence = run?.sequence ?: advanced ?: tonightSequence(list, triggers, equipment, darks, heater)
    val connected = (equipment.connection as? Connection.Connected)?.equipment
    val profile = equipment.active
    val filters = connected?.filterWheel?.names ?: profile?.devices?.filterNames.orEmpty()
    val kit = Kit(cooler = connected?.camera?.info?.canSetCcdTemperature, filters = filters.ifEmpty { null })
    val warnings =
        remember(sequence, kit) {
            sequence.review(kit).map { warning ->
                warning.copy(
                    spot = warning.spot.copy(path = sequence.areaPath(warning.spot.path)),
                    problem = warning.problem.let { problem ->
                        if (problem is Problem.Together) {
                            problem.copy(first = sequence.areaPath(problem.first), second = sequence.areaPath(problem.second))
                        } else {
                            problem
                        }
                    },
                )
            }
        }
    val site = profile?.location
    val now = services.clock.now()
    val night = remember(site, now.epochSeconds / SECONDS_PER_HOUR) { site?.let { planNight(now, services.zone(), it) } }
    return Tonight(
        sequence = sequence,
        tree = sequence.areas().asBlock(sequence.name),
        run = run,
        warnings = warnings,
        profile = profile?.name,
        heater = connected?.switches?.switches?.let(::dewHeater)?.name,
        filters = filters.filterNot { it.equals(DARK_FILTER, ignoreCase = true) },
        cooler = kit.cooler,
        darkFilter = kit.filters?.any { it.equals(DARK_FILTER, ignoreCase = true) },
        night = night,
        site = site,
        canUndo = canUndo && run == null,
        now = now,
        zone = services.zone(),
    )
}

/**
 * Tonight becomes [tree], the areas as the advanced editor changed them, kept in the plan when the
 * simple editor can show it as it is, or as the plan's advanced sequence; and the run going on, if
 * any, goes on with it.
 */
internal fun AppServices.saveTonight(tree: Block, name: String) {
    val sequence = tree.asAreas().toSequence(name)
    val state = equipment.state.value
    // Without a focuser the plan keeps its autofocus triggers, which tonight's sequence leaves out.
    val focuser = state.active?.devices?.focuser != null
    val triggers = if (focuser) {
        sequence.planTriggers()
    } else {
        targets.triggers.value.filter { it.autofocus() != null } +
            sequence.planTriggers()
    }
    val darks = sequence.darkRules()
    val simple = sequence.simpleTargets()?.takeIf { tonightSequence(it, triggers, state, darks, targets.heater.value) == sequence }
    if (simple != null) targets.replace(simple, triggers, darks) else targets.setAdvanced(sequence)
    val status = this.sequence.state.value?.status
    if (status == RunStatus.Running || status == RunStatus.Paused) this.sequence.update(sequence)
}

private const val SECONDS_PER_HOUR = 3600
