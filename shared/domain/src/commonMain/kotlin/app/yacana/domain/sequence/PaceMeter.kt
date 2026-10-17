// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

import app.yacana.domain.profile.Pace

/**
 * Tonight's pace as the night measures it: each light's time beyond its exposure, each dither,
 * each autofocus and each centring. Each one is tonight's once there are enough of it to trust,
 * the median of the frames and dithers, so a stalled download does not count for all; until
 * then, the profile's [known].
 */
internal class PaceMeter(private val known: Pace) {
    private val frames = mutableListOf<Double>()
    private val dithers = mutableListOf<Double>()
    private val autofocus = mutableListOf<Double>()
    private val centrings = mutableListOf<Double>()

    /** A light that took [seconds] beyond its exposure, with nothing else between it and the last. */
    fun frame(seconds: Double) {
        if (seconds >= 0) frames += seconds
    }

    fun dither(seconds: Double) {
        if (seconds >= 0) dithers += seconds
    }

    fun autofocus(seconds: Double) {
        if (seconds > 0) autofocus += seconds
    }

    fun centring(seconds: Double) {
        if (seconds > 0) centrings += seconds
    }

    /** Tonight's, where measured enough; the profile's for the rest. */
    val pace: Pace
        get() = Pace(
            frameSeconds = frames.takeIf { it.size >= FRAMES }?.median() ?: known.frameSeconds,
            ditherSeconds = dithers.takeIf { it.size >= DITHERS }?.median() ?: known.ditherSeconds,
            autofocusSeconds = autofocus.takeIf { it.isNotEmpty() }?.average() ?: known.autofocusSeconds,
            centringSeconds = centrings.takeIf { it.isNotEmpty() }?.average() ?: known.centringSeconds,
        )

    /** Whether tonight measured anything to keep for the next nights. */
    val measured: Boolean get() = frames.size >= FRAMES || dithers.size >= DITHERS || autofocus.isNotEmpty() || centrings.isNotEmpty()

    private fun List<Double>.median(): Double = sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    private companion object {
        /** Lights and dithers enough to trust their median. */
        const val FRAMES = 5
        const val DITHERS = 3
    }
}
