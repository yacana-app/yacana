// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import app.yacana.ports.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * A saved frame as the session's history keeps it: its number, when it started, what it
 * was, its stars and their HFR (0 without stars), its background (the median of its pixels, and
 * their MAD, in ADU), a small [thumbnail], and whether it is to be discarded.
 */
data class HistoryFrame(
    val number: Int,
    val at: Instant,
    val type: FrameType,
    val filter: String?,
    val exposureSeconds: Double,
    val gain: Int?,
    val binning: Int,
    val hfr: Double,
    val stars: Int,
    val background: Double,
    val mad: Double,
    val savedAs: String,
    val thumbnail: Preview? = null,
    val discarded: DiscardReason? = null,
    /** The target named in its headers, if any. */
    val target: String? = null,
)

/** Why a frame is marked as discarded: fewer stars and a brighter sky, as clouds lit by a town give; or one of the two. */
enum class DiscardReason { Clouds, FewStars, BrightBackground }

/** The frames saved this session, oldest first. */
class SessionHistory {
    private val mutableFrames = MutableStateFlow<List<HistoryFrame>>(emptyList())

    val frames: StateFlow<List<HistoryFrame>> = mutableFrames.asStateFlow()

    fun add(frame: HistoryFrame) = mutableFrames.update { judged(it + frame) }
}

/**
 * [frames] with the light frames that have far fewer stars or more background than the median of
 * the frames like them (the same filter, exposure, gain and binning) marked: under half the stars,
 * or the background more than 5 MADs above. Each kind of frame is judged from its fifth frame on,
 * and again as more come, since the median moves with them.
 */
internal fun judged(frames: List<HistoryFrame>): List<HistoryFrame> {
    val references =
        frames
            .filter { it.type == FrameType.Light }
            .groupBy { it.kind }
            .filterValues { it.size >= MIN_FRAMES }
            .mapValues { (_, group) ->
                Reference(median(group.map { it.stars.toDouble() }), median(group.map { it.background }), median(group.map { it.mad }))
            }
    return frames.map { frame -> frame.copy(discarded = references[frame.kind]?.reasonFor(frame)) }
}

private data class Kind(val type: FrameType, val filter: String?, val exposureSeconds: Double, val gain: Int?, val binning: Int)

private val HistoryFrame.kind get() = Kind(type, filter, exposureSeconds, gain, binning)

private class Reference(val stars: Double, val background: Double, val mad: Double) {
    fun reasonFor(frame: HistoryFrame): DiscardReason? {
        val few = frame.stars < stars * FEW_STARS
        val bright = frame.background > background + BRIGHT_MADS * mad
        return when {
            few && bright -> DiscardReason.Clouds
            few -> DiscardReason.FewStars
            bright -> DiscardReason.BrightBackground
            else -> null
        }
    }
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

private const val MIN_FRAMES = 5
private const val FEW_STARS = 0.5
private const val BRIGHT_MADS = 5.0
