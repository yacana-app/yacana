// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.session

import app.yacana.domain.imaging.DiscardReason
import app.yacana.domain.imaging.FrameType
import app.yacana.domain.imaging.HistoryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class NightDiaryTest {
    private val evening = Instant.parse("2026-10-02T12:00:00Z")

    /** The journal of the design's night, as JournalFiles writes it, with a line from the day before and one that is not JSON. */
    private val lines =
        listOf(
            """{"t":"2026-10-01T23:00:00Z","event":"sequence_started","name":"Yesterday"}""",
            """{"t":"2026-10-02T18:58:00Z","event":"sequence_started","name":"Tonight"}""",
            """{"t":"2026-10-02T18:59:00Z","event":"sequence_item","item":"GoToTarget(centre=true)","target":"M 31 Panel 1"}""",
            """{"t":"2026-10-02T19:00:00Z","event":"centring_done","centred":true,"attempts":2,"offArcsec":8.2}""",
            """{"t":"2026-10-02T19:02:00Z","event":"autofocus_done","position":1518,"hfr":2.0,"points":9,"temperature":9.4}""",
            """not json""",
            """{"t":"2026-10-03T00:14:00Z","event":"link_lost","running":true}""",
            """{"t":"2026-10-03T00:14:00Z","event":"sequence_paused","reason":"link lost"}""",
            """{"t":"2026-10-03T00:14:51Z","event":"link_back","attempts":5}""",
            """{"t":"2026-10-03T00:14:51Z","event":"sequence_resumed","at":[1,1]}""",
            """{"t":"2026-10-03T01:04:00Z","event":"sequence_autofocus_trigger","triggers":"AfterTemperatureChange"}""",
            """{"t":"2026-10-03T01:05:00Z","event":"autofocus_done","position":1524,"hfr":2.1,"points":9,"temperature":8.1}""",
            """{"t":"2026-10-03T02:50:00Z","event":"sequence_item","item":"Park","target":null}""",
            """{"t":"2026-10-03T02:50:30Z","event":"sequence_finished"}""",
        )

    private fun light(minute: Int, target: String, discarded: DiscardReason? = null) = HistoryFrame(
        number = minute,
        at = Instant.parse("2026-10-03T01:00:00Z") + minute.minutes,
        type = FrameType.Light,
        filter = "IR-cut",
        exposureSeconds = 60.0,
        gain = 80,
        binning = 1,
        hfr = 2.0 + minute % 3 * 0.1,
        stars = 400,
        background = 1000.0,
        mad = 40.0,
        savedAs = "frame $minute",
        discarded = discarded,
        target = target,
    )

    /** M 31 then M 33, three of M 33's in a row through clouds, then one more. */
    private val frames =
        (1..4).map { light(it, "M 31") } +
            (5..7).map { light(it, "M 33", DiscardReason.Clouds) } +
            light(8, "M 33") +
            light(9, "M 33", DiscardReason.Clouds)

    @Test
    fun theDiaryTellsEachStepWithItsTimeAndReason() {
        val diary = nightDiary(lines, frames, evening)

        assertEquals(DiaryEvent.Started(Instant.parse("2026-10-02T18:58:00Z"), "Tonight"), diary.first())
        val centred = diary.filterIsInstance<DiaryEvent.Centred>().single()
        assertEquals("M 31 Panel 1", centred.target)
        assertEquals(2, centred.attempts)
        val (first, extra) = diary.filterIsInstance<DiaryEvent.Focused>()
        assertEquals(null, first.trigger)
        assertEquals("AfterTemperatureChange", extra.trigger)
        assertEquals(-1.3, extra.change!!, 1e-9)
        assertEquals(51.seconds, diary.filterIsInstance<DiaryEvent.LinkBack>().single().after)
        assertEquals("link lost", diary.filterIsInstance<DiaryEvent.Paused>().single().reason)
        // Clouds in a row are one event; the one after a kept frame, another.
        val clouds = diary.filterIsInstance<DiaryEvent.Discarded>()
        assertEquals(listOf(3, 1), clouds.map { it.count })
        assertEquals("M 33", clouds.first().target)
        assertIs<DiaryEvent.Finished>(diary.last())
        assertEquals(diary.sortedBy { it.at }, diary)
    }

    @Test
    fun theSummaryCountsWhatWasKeptAndWhatHappened() {
        val summary = nightSummary(nightDiary(lines, frames, evening), frames, evening)

        assertEquals(Instant.parse("2026-10-03T02:50:30Z"), summary.ended)
        assertTrue(summary.parked)
        assertEquals(9, summary.frames)
        assertEquals(4, summary.discarded)
        assertEquals(5.minutes, summary.integration)
        assertEquals(listOf(TargetFrames("M 31", "IR-cut", 4, 60.0), TargetFrames("M 33", "IR-cut", 1, 60.0)), summary.byTarget)
        // The reconnection, the extra autofocus and the two runs of clouds; not the first autofocus.
        assertEquals(4, summary.incidents.size)
        assertTrue(summary.incidents.none { it is DiaryEvent.Focused && it.trigger == null })
        assertTrue(summary.ended!! - evening < 15.hours)
    }
}
