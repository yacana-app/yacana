// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A framing: the profile's field of [fieldDeg] centred on [center], its long side
 * across at no rotation, as the map draws it, turned [rotationDeg] counterclockwise on the map,
 * from north through east; in [columns] along the long side by [rows] panels, each overlapping its
 * neighbour by [overlap] of the field.
 */
data class Framing(
    val center: SkyPosition,
    val rotationDeg: Double,
    val fieldDeg: Pair<Double, Double>,
    val columns: Int = 1,
    val rows: Int = 1,
    val overlap: Double = DEFAULT_OVERLAP,
) {
    val longDeg: Double get() = max(fieldDeg.first, fieldDeg.second)
    val shortDeg: Double get() = min(fieldDeg.first, fieldDeg.second)

    /** The whole mosaic across and down, in degrees: each panel after the first adds a field less the overlap. */
    val widthDeg: Double get() = span(longDeg, columns)
    val heightDeg: Double get() = span(shortDeg, rows)

    /**
     * Each panel, numbered from the left of the top row, with its centre and its own rotation: the
     * rotation of the framing where the panel is, as north turns a little across a mosaic.
     */
    val panels: List<Panel>
        get() = (0 until rows).flatMap { row ->
            (0 until columns).map { column ->
                val right = (column - (columns - 1) / 2.0) * longDeg * (1 - overlap)
                val up = ((rows - 1) / 2.0 - row) * shortDeg * (1 - overlap)
                val at = place(right, up)
                Panel(row * columns + column + 1, at, positionAngle(at, place(right, up + NUDGE_DEG)))
            }
        }

    private fun span(side: Double, panels: Int) = side * (1 + (panels - 1) * (1 - overlap))

    /**
     * The place [right] and [up] degrees from the centre in the frame, turned by the rotation, on
     * the plane that touches the sky at the centre: on the map right is west and up is north.
     */
    private fun place(right: Double, up: Double): SkyPosition {
        val turn = rotationDeg * RADIANS
        val west = (right * cos(turn) - up * sin(turn)) * RADIANS
        val north = (right * sin(turn) + up * cos(turn)) * RADIANS
        val xi = -west
        val ra0 = center.raHours * DEGREES_PER_HOUR * RADIANS
        val dec0 = center.decDeg * RADIANS
        val rho = sqrt(xi * xi + north * north)
        if (rho == 0.0) return center
        val c = atan(rho)
        val dec = asin(cos(c) * sin(dec0) + north * sin(c) * cos(dec0) / rho)
        val ra = ra0 + atan2(xi * sin(c), rho * cos(dec0) * cos(c) - north * sin(dec0) * sin(c))
        return SkyPosition((ra / RADIANS / DEGREES_PER_HOUR).mod(HOURS), dec / RADIANS)
    }

    companion object {
        /** NINA's mosaics overlap by a fifth; a little less keeps more sky. */
        const val DEFAULT_OVERLAP = 0.15

        /**
         * A framing of [item] for the profile's [field] turned [rotationDeg], the camera's: on it,
         * in as many columns and rows as take it with a tenth to spare, the object as turned against
         * the field, as large as it needs.
         */
        fun of(item: SkyObject, field: Pair<Double, Double>, rotationDeg: Double): Framing {
            val major = item.majorArcmin ?: 0.0
            val minor = item.minorArcmin ?: major
            // The object's long axis against the field's: the field's long side points to its rotation plus a right angle.
            val tilt = ((item.angleDeg ?: 0.0) - rotationDeg - RIGHT_ANGLE) * RADIANS
            val along = hypot(major * cos(tilt), minor * sin(tilt))
            val across = hypot(major * sin(tilt), minor * cos(tilt))
            val plain = Framing(item.position, rotationDeg, field)
            return plain.copy(columns = needed(along, plain.longDeg), rows = needed(across, plain.shortDeg))
        }

        /** How many panels along a side of [sideDeg] take [sizeArcmin] with a tenth to spare. */
        internal fun needed(sizeArcmin: Double, sideDeg: Double, overlap: Double = DEFAULT_OVERLAP): Int {
            val size = sizeArcmin * MARGIN / ARCMIN_PER_DEGREE
            var panels = 1
            while (sideDeg * (1 + (panels - 1) * (1 - overlap)) < size) panels++
            return panels
        }
    }
}

/**
 * The camera's rotation as the framing counts it, from where a solved frame's y axis points
 * ([yAxisDeg], from north through east) and its [width] and [height]: its long side's position
 * angle less a right angle, as a field turned half round is the same field.
 */
fun cameraRotation(yAxisDeg: Double, width: Int, height: Int): Double {
    val long = if (height >= width) yAxisDeg else yAxisDeg + RIGHT_ANGLE
    return (long - RIGHT_ANGLE).mod(HALF_TURN)
}

/** A panel of a framing: its number, its centre and its rotation, as the sequence takes it. */
data class Panel(val number: Int, val center: SkyPosition, val rotationDeg: Double)

/**
 * How many fields of [fieldDeg] (width × height, in degrees) the object takes, with a tenth to
 * spare around it, the field turned as it fits best and the panels overlapping as a framing's: 1
 * when it fits, the panels of a mosaic otherwise, as "fits" and "mosaic of 2" in the Sky Atlas; null
 * without its size or the field.
 */
fun SkyObject.panels(fieldDeg: Pair<Double, Double>?): Int? {
    val field = fieldDeg
    val major = majorArcmin
    if (field == null || major == null) return null
    val minor = minorArcmin ?: major
    return Framing.needed(major, max(field.first, field.second)) * Framing.needed(minor, min(field.first, field.second))
}

/** The position angle of [to] seen from [from], from north through east, in degrees. */
private fun positionAngle(from: SkyPosition, to: SkyPosition): Double {
    val dec1 = from.decDeg * RADIANS
    val dec2 = to.decDeg * RADIANS
    val dra = (to.raHours - from.raHours) * DEGREES_PER_HOUR * RADIANS
    val angle = atan2(sin(dra) * cos(dec2), cos(dec1) * sin(dec2) - sin(dec1) * cos(dec2) * cos(dra))
    return (angle / RADIANS).mod(FULL_TURN)
}

/** A tenth of the object's size to spare on each side of it, in all. */
private const val MARGIN = 1.1

/** A small step up the frame, to see where up points at a panel. */
private const val NUDGE_DEG = 1e-3
private const val ARCMIN_PER_DEGREE = 60.0
private const val DEGREES_PER_HOUR = 15.0
private const val HOURS = 24.0
private const val RIGHT_ANGLE = 90.0
private const val HALF_TURN = 180.0
private const val FULL_TURN = 360.0
private const val RADIANS = PI / 180.0
