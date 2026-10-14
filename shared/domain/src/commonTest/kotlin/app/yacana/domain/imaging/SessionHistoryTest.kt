// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Which frames the history marks as discarded. */
class SessionHistoryTest {
    private val start = Instant.parse("2026-09-20T00:02:51Z")

    private fun frame(
        number: Int,
        stars: Int,
        background: Double,
        mad: Double = 64.0,
        filter: String? = "LP",
        type: FrameType = FrameType.Light,
        exposure: Double = 20.0,
    ) = HistoryFrame(
        number = number,
        at = start + (number * 24).seconds,
        type = type,
        filter = filter,
        exposureSeconds = exposure,
        gain = 80,
        binning = 1,
        hfr = 1.88,
        stars = stars,
        background = background,
        mad = mad,
        savedAs = "frame $number",
    )

    /**
     * Nine frames of C 20 in a row, as the engine measures them: the fifth caught a glow, such
     * as a torch or a car's lights, with a quarter of the stars and the sky 11 MADs brighter.
     */
    private val c20 =
        listOf(
            frame(1, 1035, 2134.0, 66.0),
            frame(2, 1266, 2131.0, 65.0),
            frame(3, 1410, 2129.0),
            frame(4, 1238, 2131.0),
            frame(5, 287, 2865.0, 260.0),
            frame(6, 1277, 2130.0),
            frame(7, 1191, 2129.0),
            frame(8, 1296, 2130.0),
            frame(9, 1203, 2129.0),
        )

    private fun discarded(frames: List<HistoryFrame>) =
        judged(frames).filter { it.discarded != null }.associate { it.number to it.discarded }

    @Test
    fun aGlowOnAClearNightIsTheOnlyOneMarked() {
        assertEquals(mapOf(5 to DiscardReason.Clouds), discarded(c20))
    }

    @Test
    fun fewerStarsOrABrighterSkyOnTheirOwn() {
        // The median is then 1238 stars and a background of 2131 ADU with a MAD of 64.
        val frames = c20 + frame(10, 590, 2131.0) + frame(11, 1250, 2500.0)

        assertEquals(
            mapOf(5 to DiscardReason.Clouds, 10 to DiscardReason.FewStars, 11 to DiscardReason.BrightBackground),
            discarded(frames),
        )
    }

    @Test
    fun eachKindOfFrameIsJudgedFromItsFifthFrame() {
        assertEquals(emptyMap(), discarded(c20.take(3) + c20[4]), "4 frames are too few to judge")
        assertEquals(mapOf(5 to DiscardReason.Clouds), discarded(c20.take(4) + c20[4]))
    }

    @Test
    fun framesAreComparedWithTheirOwnKind() {
        // With another filter or exposure a frame is a kind of its own, too few to judge; darks are not judged.
        val others =
            listOf(
                frame(10, 300, 2131.0, filter = "IR"),
                frame(11, 300, 2131.0, exposure = 10.0),
                frame(12, 0, 1500.0, type = FrameType.Dark),
            )

        assertEquals(mapOf(5 to DiscardReason.Clouds), discarded(c20 + others))
    }

    @Test
    fun theHistoryJudgesAgainAsFramesCome() {
        val history = SessionHistory()
        c20.take(3).forEach(history::add)
        history.add(c20[4])
        assertEquals(null, history.frames.value.last().discarded, "too soon to judge")

        history.add(c20[3])

        assertEquals(DiscardReason.Clouds, history.frames.value.single { it.number == 5 }.discarded)
    }
}
