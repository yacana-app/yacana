// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.session

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkMonitorTest {
    @Test
    fun steadyPollingHasShortGapsAndNoOutages() {
        val monitor = LinkMonitor(startMillis = 0)
        for (t in 2_000L..60_000L step 2_000L) monitor.success(t, latencyMillis = 40)

        val stats = monitor.stats(nowMillis = 61_000)

        assertEquals(30, stats.calls)
        assertEquals(0, stats.failures)
        assertEquals(2_000, stats.longestGapMillis)
        assertEquals(0, stats.longGaps)
        assertEquals(1_000, stats.openGapMillis)
        assertEquals(40, stats.meanLatencyMillis)
    }

    @Test
    fun aGapLongerThanTheThresholdCountsAsAnOutage() {
        val monitor = LinkMonitor(startMillis = 0)
        monitor.success(2_000, latencyMillis = 10)
        repeat(10) { monitor.failure() }
        monitor.success(34_001, latencyMillis = 90)

        val stats = monitor.stats(nowMillis = 34_001)

        assertEquals(12, stats.calls)
        assertEquals(10, stats.failures)
        assertEquals(32_001, stats.longestGapMillis)
        assertEquals(1, stats.longGaps)
        assertEquals(50, stats.meanLatencyMillis)
        assertEquals(90, stats.maxLatencyMillis)
    }

    @Test
    fun aGapOfExactlyTheThresholdIsNotAnOutage() {
        val monitor = LinkMonitor(startMillis = 0)
        monitor.success(30_000, latencyMillis = 5)

        assertEquals(0, monitor.stats(nowMillis = 30_000).longGaps)
    }

    @Test
    fun theOpenGapCountsAsTheLongestButNotYetAsAnOutage() {
        val monitor = LinkMonitor(startMillis = 0)
        monitor.success(1_000, latencyMillis = 5)
        monitor.failure()

        val stats = monitor.stats(nowMillis = 95_000)

        assertEquals(94_000, stats.longestGapMillis)
        assertEquals(94_000, stats.openGapMillis)
        assertEquals(0, stats.longGaps)
    }

    @Test
    fun noAnswerSinceTheStartIsOneOpenGap() {
        val monitor = LinkMonitor(startMillis = 10_000)
        monitor.failure()

        assertEquals(50_000, monitor.stats(nowMillis = 60_000).longestGapMillis)
    }
}
