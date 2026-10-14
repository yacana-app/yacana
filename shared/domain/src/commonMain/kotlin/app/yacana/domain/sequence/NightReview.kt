// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.imaging.FrameType

/** Where a warning points: an item by its indices from the root, or a block's [trigger] or [condition] by its index. */
data class Spot(val path: List<Int>, val trigger: Int? = null, val condition: Int? = null)

/** What the equipment can do, as far as it is known (null when not): whether the camera cools, and the wheel's filters. */
data class Kit(val cooler: Boolean? = null, val filters: List<String>? = null)

/** What two items at once both use. */
enum class Use { Camera, Mount, Focuser }

/** What would go wrong tonight; the screen says it in the user's words. */
sealed interface Problem {
    /** It points at a target, outside one. */
    data object NeedsTarget : Problem

    /** The camera does not cool, so it is skipped. */
    data object NoCooler : Problem

    data class NoFilter(val filter: String) : Problem

    /** A go to with the mount parked, before any unpark. */
    data object ParkedGoTo : Problem

    data object ParkedAutofocus : Problem

    data class ParkedLights(val target: String) : Problem

    /** Lights of [target] with the mount not sent to it first. */
    data class NotAimed(val target: String) : Problem

    data object NotTracking : Problem

    data object LightsWithoutTarget : Problem

    /** Lights after the camera started warming. */
    data object Warming : Problem

    /** Darks after the camera started warming, so they don't match the lights. */
    data object WarmDarks : Problem

    /** Two items of a parallel block, at [first] and [second], both use the same [use] at once. */
    data class Together(val first: List<Int>, val second: List<Int>, val use: Use) : Problem
}

data class Warning(val spot: Spot, val problem: Problem)

/**
 * "Review": what would go wrong tonight with [kit], found before it starts. It walks the
 * night in order from a parked mount, keeping where the mount points, whether it tracks and whether
 * the camera warms. It only warns: nothing stops the night from starting.
 */
fun Sequence.review(kit: Kit): List<Warning> = Review(kit).apply { block(root, emptyList(), null) }.warnings

/** A target's block, by its indices, and its target. */
private typealias Field = Pair<List<Int>, SkyTarget>

private class Review(private val kit: Kit) {
    val warnings = mutableListOf<Warning>()
    private var parked = true
    private var tracking = false
    private var aimed: List<Int>? = null
    private var warm = false

    fun block(block: Block, path: List<Int>, outer: Field?) {
        val field = block.target?.let { path to it } ?: outer
        if (field == null) {
            block.triggers.forEachIndexed { index, trigger ->
                if (trigger is Trigger.RecentreAfterDrift) warn(Spot(path, trigger = index), Problem.NeedsTarget)
            }
            block.conditions.forEachIndexed { index, condition ->
                if (condition is Condition.WhileAbove) warn(Spot(path, condition = index), Problem.NeedsTarget)
            }
        }
        if (block.parallel) together(block, path)
        block.items.forEachIndexed { index, item ->
            if (item is Block) {
                block(item, path + index, field)
            } else {
                instruction(item, Spot(path + index), field)
            }
        }
    }

    private fun instruction(item: SequenceItem, spot: Spot, field: Field?) {
        when (item) {
            Unpark -> parked = false

            Park -> {
                parked = true
                tracking = false
            }

            StopTracking -> tracking = false

            is GoToTarget -> goTo(spot, field)

            is WaitUntilAbove -> if (field == null) warn(spot, Problem.NeedsTarget)

            RunAutofocus -> if (parked) warn(spot, Problem.ParkedAutofocus)

            is CoolCamera -> cooler(spot) { warm = false }

            WarmCamera -> cooler(spot) { warm = true }

            is SwitchFilter -> filter(spot, item.filter)

            is Exposures -> exposures(spot, item, field)

            else -> Unit
        }
    }

    private fun goTo(spot: Spot, field: Field?) {
        if (field == null) return warn(spot, Problem.NeedsTarget)
        if (parked) warn(spot, Problem.ParkedGoTo)
        tracking = true
        aimed = field.first
    }

    private fun cooler(spot: Spot, then: () -> Unit) = if (kit.cooler == false) warn(spot, Problem.NoCooler) else then()

    private fun filter(spot: Spot, filter: String) {
        if (kit.filters?.none { it.equals(filter, ignoreCase = true) } == true) warn(spot, Problem.NoFilter(filter))
    }

    private fun exposures(spot: Spot, item: Exposures, field: Field?) {
        if (item.type == FrameType.Dark && warm) warn(spot, Problem.WarmDarks)
        if (item.type != FrameType.Light) return
        item.filter?.let { filter(spot, it) }
        val problem =
            when {
                field == null -> Problem.LightsWithoutTarget
                parked -> Problem.ParkedLights(field.second.name)
                aimed != field.first -> Problem.NotAimed(field.second.name)
                !tracking -> Problem.NotTracking
                else -> null
            }
        problem?.let { warn(spot, it) }
        if (warm) warn(spot, Problem.Warming)
    }

    /** A parallel block whose items both use the camera, the mount or the focuser at once. */
    private fun together(block: Block, path: List<Int>) {
        val first = mutableMapOf<Use, Int>()
        block.items.forEachIndexed { index, item ->
            item.uses().forEach { use ->
                val before = first[use]
                if (before != null) return warn(Spot(path), Problem.Together(path + before, path + index, use))
                first[use] = index
            }
        }
    }

    private fun warn(spot: Spot, problem: Problem) {
        warnings += Warning(spot, problem)
    }
}

/** What an item uses of the equipment while it runs. */
private fun SequenceItem.uses(): Set<Use> = when (this) {
    is Block -> items.flatMap { it.uses() }.toSet()
    is Exposures, is SwitchFilter -> setOf(Use.Camera)
    is GoToTarget -> if (centre) setOf(Use.Mount, Use.Camera) else setOf(Use.Mount)
    Unpark, Park, StopTracking -> setOf(Use.Mount)
    RunAutofocus -> setOf(Use.Camera, Use.Focuser)
    is MoveFocuser -> setOf(Use.Focuser)
    else -> emptySet()
}
