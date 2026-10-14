// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The names a night's areas keep in its JSON; the screens say them in the user's words. */
internal const val START = "Start"

internal const val TARGETS = "Targets"

internal const val END = "End"

/**
 * A night as the advanced editor shows it, in NINA's three areas: what goes before the
 * targets, the targets, and what goes after them; and the [triggers] of the whole night. The
 * targets' area is the root's middle items, as the simple editor makes them, or a block of its own
 * when it has conditions that repeat the list.
 */
data class NightAreas(val start: Block, val targets: Block, val end: Block, val triggers: List<Trigger> = emptyList()) {
    /** The night as a sequence called [name]; the targets' area without conditions as the root's middle items. */
    fun toSequence(name: String): Sequence {
        val middle = if (targets.conditions.isEmpty()) targets.items else listOf(targets)
        return Sequence(name, Block(name, listOf(start) + middle + end, triggers = triggers))
    }
}

/**
 * The areas as one block, as the advanced editor walks them: the start at 0, the targets at 1 and
 * the end at 2, with the night's triggers.
 */
fun NightAreas.asBlock(name: String): Block = Block(name, listOf(start, targets, end), triggers = triggers)

/** The areas of a block made by [asBlock]. */
fun Block.asAreas(): NightAreas = NightAreas(items[0] as Block, items[1] as Block, items[2] as Block, triggers)

/** Where the item at [path] of this sequence is in its areas' block ([asBlock]). */
fun Sequence.areaPath(path: List<Int>): List<Int> {
    val items = root.items
    val classic = items.size >= 2 && items.first() is Block && items.last() is Block
    val own = classic && items.size == AREAS && (items[1] as? Block)?.let { it.name == TARGETS && it.target == null } == true
    return when {
        path.isEmpty() || own -> path
        !classic -> listOf(1) + path
        path.first() == 0 -> path
        path.first() == items.lastIndex -> listOf(2) + path.drop(1)
        else -> listOf(1, path.first() - 1) + path.drop(1)
    }
}

/** This night's areas: the root's first block, its last and what is between them; a root without them is all targets. */
fun Sequence.areas(): NightAreas {
    val items = root.items
    val start = items.firstOrNull() as? Block
    val end = items.lastOrNull() as? Block
    if (items.size < 2 || start == null || end == null) return NightAreas(Block(START), Block(TARGETS, items), Block(END), root.triggers)
    val middle = items.subList(1, items.size - 1)
    val own = (middle.singleOrNull() as? Block)?.takeIf { it.name == TARGETS && it.target == null }
    return NightAreas(start, own ?: Block(TARGETS, middle), end, root.triggers)
}

/**
 * The time [minute] of tonight's clock, from local midnight: tonight goes from noon to the
 * next noon, so an evening hour is the evening's and a morning hour the next morning's.
 */
fun tonightAt(minute: Int, now: Instant, zone: TimeZone): Instant {
    val local = now.toLocalDateTime(zone)
    val evening = if (local.hour >= NOON_HOUR) local.date else local.date.minus(1, DateTimeUnit.DAY)
    val day = if (minute >= NOON_HOUR * MINUTES_PER_HOUR) evening else evening.plus(1, DateTimeUnit.DAY)
    return LocalDateTime(day, LocalTime(minute / MINUTES_PER_HOUR % HOURS_PER_DAY, minute % MINUTES_PER_HOUR)).toInstant(zone)
}

private const val NOON_HOUR = 12

private const val AREAS = 3

private const val MINUTES_PER_HOUR = 60

private const val HOURS_PER_DAY = 24
