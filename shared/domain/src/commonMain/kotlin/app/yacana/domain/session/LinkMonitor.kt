// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tracks the link with an Alpaca server from the result of every call.
 *
 * A gap is the time between two successful calls, so it measures how long the app
 * went without any answer from the equipment. The first gap starts when monitoring starts.
 *
 * Not thread safe: call it from one coroutine.
 *
 * @param startMillis when monitoring started, in epoch milliseconds.
 * @param longGap gaps longer than this count as outages. The app uses 30 s.
 */
class LinkMonitor(startMillis: Long, private val longGap: Duration = 30.seconds) {
    private var lastSuccessMillis = startMillis
    private var stats = LinkStats()

    /** Records a call that got an answer, [latencyMillis] after it was sent. */
    fun success(atMillis: Long, latencyMillis: Long) {
        val gap = atMillis - lastSuccessMillis
        lastSuccessMillis = atMillis
        stats =
            stats.copy(
                calls = stats.calls + 1,
                longestGapMillis = maxOf(stats.longestGapMillis, gap),
                longGaps = stats.longGaps + if (gap > longGap.inWholeMilliseconds) 1 else 0,
                latencySumMillis = stats.latencySumMillis + latencyMillis,
                maxLatencyMillis = maxOf(stats.maxLatencyMillis, latencyMillis),
            )
    }

    /** Records a call that failed: timeout, connection error, HTTP error or Alpaca error. */
    fun failure() {
        stats = stats.copy(calls = stats.calls + 1, failures = stats.failures + 1)
    }

    /** Statistics so far. The gap still open at [nowMillis] counts towards the longest gap. */
    fun stats(nowMillis: Long): LinkStats {
        val openGap = nowMillis - lastSuccessMillis
        return stats.copy(openGapMillis = openGap, longestGapMillis = maxOf(stats.longestGapMillis, openGap))
    }
}

/** What [LinkMonitor] has seen so far. */
data class LinkStats(
    val calls: Int = 0,
    val failures: Int = 0,
    /** Longest time without a successful answer, including the gap still open. */
    val longestGapMillis: Long = 0,
    /** Closed gaps longer than the monitor's threshold. */
    val longGaps: Int = 0,
    /** Time since the last successful answer. */
    val openGapMillis: Long = 0,
    val latencySumMillis: Long = 0,
    val maxLatencyMillis: Long = 0,
) {
    val successes: Int get() = calls - failures

    /** Mean latency of successful calls, or 0 before the first one. */
    val meanLatencyMillis: Long get() = if (successes == 0) 0 else latencySumMillis / successes
}
