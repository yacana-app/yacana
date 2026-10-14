// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.session

import app.yacana.domain.imaging.FrameType
import app.yacana.domain.imaging.HistoryFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The lights kept of a target with a filter and an exposure. */
data class TargetFrames(val target: String, val filter: String?, val count: Int, val exposureSeconds: Double)

/**
 * "Night · summary": when the night [ended] and whether the mount [parked]; the
 * [integration] of the lights kept, the lights taken and [discarded], their median HFR, the lights
 * kept by target, and what happened that was not to plan.
 */
data class NightSummary(
    val ended: Instant?,
    val parked: Boolean,
    val integration: Duration,
    val frames: Int,
    val discarded: Int,
    val medianHfr: Double?,
    val byTarget: List<TargetFrames>,
    val incidents: List<DiaryEvent>,
)

/** The summary of the night told by [diary], with the history's [frames] since [since]. */
fun nightSummary(diary: List<DiaryEvent>, frames: List<HistoryFrame>, since: Instant): NightSummary {
    val lights = frames.filter { it.type == FrameType.Light && it.at >= since }
    val kept = lights.filter { it.discarded == null }
    val hfrs = kept.map { it.hfr }.filter { it > 0 }.sorted()
    return NightSummary(
        ended = diary.lastOrNull { it.ends() }?.at,
        parked = diary.any { it is DiaryEvent.Parked || (it is DiaryEvent.SafeStopped && it.parked) },
        integration = kept.sumOf { it.exposureSeconds }.seconds,
        frames = lights.size,
        discarded = lights.size - kept.size,
        medianHfr = hfrs.getOrNull(hfrs.size / 2),
        byTarget =
        kept.groupBy { Triple(it.target.orEmpty(), it.filter, it.exposureSeconds) }.map { (key, list) ->
            TargetFrames(key.first, key.second, list.size, key.third)
        },
        incidents = diary.filter { it.incident() },
    )
}

private fun DiaryEvent.ends() =
    this is DiaryEvent.Finished || this is DiaryEvent.Stopped || this is DiaryEvent.SafeStopped || this is DiaryEvent.Failed

/** What did not go as planned: reconnections, autofocus a trigger asked for or that failed, discards, restarts, safe stops and failures. */
private fun DiaryEvent.incident() = when (this) {
    is DiaryEvent.Focused -> trigger != null

    is DiaryEvent.LinkBack, is DiaryEvent.FocusFailed, is DiaryEvent.Discarded, is DiaryEvent.Restored, is DiaryEvent.SafeStopped,
    is DiaryEvent.Failed,
    -> true

    else -> false
}
