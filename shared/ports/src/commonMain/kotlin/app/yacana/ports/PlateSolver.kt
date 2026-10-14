// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ports

import kotlin.time.Duration

/** Plate solving with ASTAP on the phone. The platform runs the solver. */
interface PlateSolver {
    /** The star databases on the phone, by name, such as `D50`. */
    fun installedDatabases(): Set<String>

    /**
     * Where [frame] points, written as FITS with [headers] for the solver to read; null when the
     * solver finds no solution within the request's time.
     * @throws SolverException when it cannot run, such as without the request's database.
     */
    suspend fun solve(frame: Frame, headers: List<HeaderCard>, request: SolveRequest): Solution?
}

/**
 * One solve: with [database], for a frame [fieldHeightDeg] high (0 lets the solver find out),
 * searching [radiusDeg] around [hint] (180 and no hint search the whole sky), with ASTAP's
 * [downsample] factor (0 is automatic), within [timeout].
 */
data class SolveRequest(
    val database: String,
    val fieldHeightDeg: Double,
    val hint: SkyPosition?,
    val radiusDeg: Double,
    val downsample: Int,
    val timeout: Duration,
)

/** A position on the sky: right ascension in hours and declination in degrees. */
data class SkyPosition(val raHours: Double, val decDeg: Double)

/**
 * Where a frame points: the centre in J2000, the rotation of its y axis from north in degrees,
 * as ASTAP gives it, and the scale in arcseconds per pixel. [yAxisDeg] is where the frame's y axis
 * points, from north through east, from the solution's CD matrix: unlike the rotation, it does not
 * depend on whether the frame is mirrored. [mirrored] when, with its first row at the top, the frame
 * has east to the right of north, as optics with one mirror give it.
 */
data class Solution(
    val centre: SkyPosition,
    val rotationDeg: Double,
    val scaleArcsec: Double,
    val yAxisDeg: Double? = null,
    val mirrored: Boolean = false,
)

/** The solver could not run; [message] says why. */
class SolverException(message: String) : Exception(message)
