// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.profile.DitherOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A run at a glance, as "Night · sequence running" shows it: the target it is on, by
 * [name], and the field it points at; the [panel] of [panels] in a mosaic; the [exposures] and the
 * [frame] in progress; how far along it is by time, when it should [end], the [next] target and
 * when it starts, and when it [park]s. Times are estimates, as the night plan's.
 */
data class RunOverview(
    val name: String?,
    val target: SkyTarget?,
    val panel: Int?,
    val panels: Int,
    val exposures: Exposures?,
    val frame: Int,
    val progress: Double,
    val end: Instant,
    val next: Pair<String, Instant>?,
    val park: Instant?,
)

/** This run at a glance [now], each frame taking its exposure and a download, with the profile's [dither]. */
fun SequenceRun.overview(now: Instant, dither: DitherOptions): RunOverview {
    // The blocks down to the item in progress, the root first.
    val chain = mutableListOf<Block>()
    var item: SequenceItem? = sequence.root
    for (index in current) {
        val block = item as? Block ?: break
        chain += block
        item = block.items.getOrNull(index)
    }
    val depth = chain.indexOfLast { it.target != null }
    val field = chain.getOrNull(depth)
    // A panel's block sits in its mosaic's, which keeps the mosaic's own centre.
    val mosaic = chain.getOrNull(depth - 1)?.takeIf { depth >= 1 && it.target != null }
    val exposures = item as? Exposures
    val time = Times(this, dither)
    val span = time.visit(sequence.root, emptyList(), Duration.ZERO, inTarget = false)
    return RunOverview(
        name = (mosaic ?: field)?.name,
        target = field?.target,
        panel = mosaic?.let { it.items.take(current[depth - 1]).count { inner -> inner is Block } + 1 },
        panels = mosaic?.items?.count { it is Block } ?: 1,
        exposures = exposures,
        frame = exposures?.let { ((done[current] ?: 0) + 1).coerceAtMost(it.count) } ?: 0,
        progress = if (span.total > Duration.ZERO) 1 - span.left / span.total else 0.0,
        end = now + span.left,
        next = time.next?.let { (name, after) -> name to now + after },
        park = time.park?.let { now + it },
    )
}

/** How long something takes in all, and what is left of it. */
private data class Span(val total: Duration, val left: Duration) {
    /** This, then [other]. */
    fun then(other: Span) = Span(total + other.total, left + other.left)

    /** This and [other] at once. */
    fun alongside(other: Span) = Span(maxOf(total, other.total), maxOf(left, other.left))
}

/**
 * The run's time and what is left of it, and how long until the next target and the park. A
 * block repeated N times takes N rounds; one repeated until a time or while its target is high,
 * one round, as how long is not known. A parallel block takes as long as its longest item.
 */
private class Times(private val run: SequenceRun, private val dither: DitherOptions) {
    var next: Pair<String, Duration>? = null
    var park: Duration? = null

    /** [item] at [at], with [ahead] left before it starts. */
    fun visit(item: SequenceItem, at: List<Int>, ahead: Duration, inTarget: Boolean): Span =
        if (item is Block) block(item, at, ahead, inTarget) else instruction(item, at, ahead)

    private fun block(block: Block, at: List<Int>, ahead: Duration, inTarget: Boolean): Span {
        val holdsCurrent = run.current.size > at.size && run.current.take(at.size) == at
        // A target after the one in progress, not a panel of a mosaic.
        val later = run.current.isNotEmpty() && !holdsCurrent && !before(at, run.current)
        val field = block.target != null && !inTarget
        if (next == null && field && later) next = block.name to ahead
        var round = Span(Duration.ZERO, Duration.ZERO)
        block.items.forEachIndexed { index, inner ->
            val span = visit(inner, at + index, if (block.parallel) ahead else ahead + round.left, inTarget || block.target != null)
            round = if (block.parallel) round.alongside(span) else round.then(span)
        }
        val times = block.conditions.filterIsInstance<Condition.Loop>().minOfOrNull { it.times } ?: 1
        return Span(round.total * times, round.left + round.total * roundsAfter(at, times, holdsCurrent))
    }

    /** How many whole rounds of the block at [at] are still to come after the one it is in, of [times]. */
    private fun roundsAfter(at: List<Int>, times: Int, holdsCurrent: Boolean): Int = when {
        holdsCurrent -> (times - 1 - (run.rounds[at] ?: 0)).coerceAtLeast(0)
        before(at, run.current) -> 0
        else -> times - 1
    }

    private fun instruction(item: SequenceItem, at: List<Int>, ahead: Duration): Span {
        val whole = estimate(item)
        val done = before(at, run.current)
        if (item == Park && park == null && !done) park = ahead
        val left =
            when {
                done -> Duration.ZERO

                at == run.current && item is Exposures -> item.copy(
                    count = (item.count - (run.done[at] ?: 0)).coerceAtLeast(0),
                ).duration(dither)

                else -> whole
            }
        return Span(whole, left)
    }

    private fun estimate(item: SequenceItem): Duration = when (item) {
        is Exposures -> item.duration(dither)
        is GoToTarget -> CENTRING
        is WaitFor -> item.minutes.minutes
        else -> Duration.ZERO
    }
}
