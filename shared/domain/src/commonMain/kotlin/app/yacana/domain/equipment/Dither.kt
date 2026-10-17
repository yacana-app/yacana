// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import app.yacana.alpaca.GuideDirection
import app.yacana.domain.profile.DitherOptions
import app.yacana.ports.SkyPosition
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** The dither grid's 9 points. */
private val CELLS = (-1..1).flatMap { east -> (-1..1).map { north -> Dither.Cell(east, north) } }

/** How far each round shifts the grid, in thirds of a step: the centre first, then around it. */
private val FINES =
    listOf(
        Dither.Cell(0, 0),
        Dither.Cell(1, 0),
        Dither.Cell(1, -1),
        Dither.Cell(0, -1),
        Dither.Cell(-1, -1),
        Dither.Cell(-1, 0),
        Dither.Cell(-1, 1),
        Dither.Cell(0, 1),
        Dither.Cell(1, 1),
    )

/** The shifts of each round; only east ones for a dither in right ascension alone. */
private fun fines(raOnly: Boolean) = if (raOnly) FINES.filter { it.north == 0 } else FINES

/** Where a dither put the field, in pixels east and north of where it began; [goTos] it took, none for guide pulses. */
data class DitherOffset(val eastPixels: Double, val northPixels: Double, val goTos: Int = 0)

/** This position moved by [offset], of pixels of [pixelScaleArcsec], east and north on the sky. */
fun SkyPosition.moved(offset: DitherOffset, pixelScaleArcsec: Double): SkyPosition {
    val eastDeg = offset.eastPixels * pixelScaleArcsec / ARCSEC_PER_DEGREE
    val raHours = raHours + eastDeg / DEGREES_PER_HOUR / cos(decDeg * PI / HALF_TURN)
    return SkyPosition(raHours.mod(HOURS), decDeg + offset.northPixels * pixelScaleArcsec / ARCSEC_PER_DEGREE)
}

private const val ARCSEC_PER_DEGREE = 3600.0
private const val DEGREES_PER_HOUR = 15.0
private const val HALF_TURN = 180.0
private const val HOURS = 24.0

/**
 * Dithering without a guider, as N.I.N.A.'s direct guider (its `DirectGuider` and
 * `DitherOffsetSelector`): each dither moves the field to an offset drawn around where it began,
 * never wandering off from it, and the mount gets the guide pulses that take it there from the last
 * one; a mount that dithers by GoTo jumps between the points of a grid of grids instead. The sequence
 * dithers so between exposures, and so does Imaging's loop; [reset] makes where the field is now
 * the new centre, as after a GoTo.
 */
class Dither(private val random: Random = Random) {
    private var previous = DitherOffset(0.0, 0.0)

    /** Where the field began in the mount's coordinates, for dithers by GoTo; null until the first. */
    private var origin: SkyPosition? = null

    /** The grid point the field is at, the round, which says where the grid is shifted to, and the points it visited in it. */
    private var cell = Cell(0, 0)
    private var round = 0
    private val visited = mutableSetOf(Cell(0, 0))

    /** Where the last dither meant to put the field, around where it began. */
    val offset: DitherOffset get() = previous

    /** Where the field is now becomes the centre the next offsets are drawn around. */
    fun reset() {
        previous = DitherOffset(0.0, 0.0)
        origin = null
        cell = Cell(0, 0)
        round = 0
        visited.clear()
        visited += cell
    }

    /**
     * Moves [mount] to a new offset for [options], with [pixelScaleArcsec] of the camera: by guide
     * pulses, or by GoTo for a mount that dithers so ([MountMediator.dithersByPulses]), around
     * [centre] when given; then the settle time. Returns the new offset.
     */
    suspend fun move(mount: MountMediator, options: DitherOptions, pixelScaleArcsec: Double, centre: SkyPosition? = null): DitherOffset {
        val target =
            if (mount.dithersByPulses) pulse(mount, options, pixelScaleArcsec) else goTo(mount, options, pixelScaleArcsec, centre)
        delay(options.settleSeconds.seconds)
        previous = target
        return target
    }

    /** N.I.N.A.'s dither: the guide pulses at half the sidereal rate from the last offset to the next. */
    private suspend fun pulse(mount: MountMediator, options: DitherOptions, pixelScaleArcsec: Double): DitherOffset {
        val target = offset(previous, options)
        val east = (target.eastPixels - previous.eastPixels) * pixelScaleArcsec / GUIDE_RATE
        val north = (target.northPixels - previous.northPixels) * pixelScaleArcsec / GUIDE_RATE
        if (east != 0.0) mount.pulseGuide(if (east > 0) GuideDirection.East else GuideDirection.West, abs(east).seconds)
        if (north != 0.0 && !options.raOnly) {
            mount.pulseGuide(if (north > 0) GuideDirection.North else GuideDirection.South, abs(north).seconds)
        }
        return target
    }

    /**
     * A dither by GoTo: to another point of a 3 × 3 grid around where the field began, [GRID_ARCSEC]
     * apart, or the dither's pixels if more. A Seestar leaves its GoTos of under about 25″ undone,
     * so N.I.N.A.'s small offsets would mostly not move it; one step of the grid always does. Each
     * round visits the grid's 9 points; then the grid shifts by a third of its step to the next of
     * [FINES], so 9 rounds put the field at 81 places, as evenly apart as they can be, in little
     * more room than one grid: on a Seestar at home, 74 of the 81 were told apart, its right
     * ascension going by whole seconds of time. Each GoTo goes to where the field began plus the
     * point, so where one lands does not add up with the next; one after which the mount says it
     * has not moved tries another point, [GOTO_TRIES] at most. Given a [centre] in the mount's
     * coordinates, as the sequence's target once the mount is synced on a plate solve, the grid is
     * around it, so the drift since the last dither is undone too.
     */
    private suspend fun goTo(mount: MountMediator, options: DitherOptions, pixelScaleArcsec: Double, centre: SkyPosition?): DitherOffset {
        val pixels = options.pixels.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val step = maxOf(GRID_ARCSEC / pixelScaleArcsec, pixels)
        var here = mount.position()
        // A mount moved elsewhere since, as by a GoTo of its own, dithers around where it is now.
        val start =
            centre ?: origin?.takeIf {
                separationArcsec(it.moved(previous, pixelScaleArcsec), here) <= 2 * step * pixelScaleArcsec + GOTO_SLACK_ARCSEC
            }
                ?: here.moved(DitherOffset(-previous.eastPixels, -previous.northPixels), pixelScaleArcsec)
        origin = start
        val tried = mutableSetOf<Cell>()
        var moved: DitherOffset? = null
        var goTos = 0
        while (moved == null && goTos < GOTO_TRIES) {
            val point = nextPoint(tried, options.raOnly) ?: break
            tried += point.cell
            goTos++
            val target = point.offset(step, options.raOnly).copy(goTos = goTos)
            mount.goToAndWait(start.moved(target, pixelScaleArcsec))
            val now = mount.position()
            if (separationArcsec(now, here) >= step * pixelScaleArcsec / 2) {
                moved = target
                visit(point)
            }
            here = now
        }
        // Left where it was, the field's offset is the last one.
        return moved ?: previous.copy(goTos = goTos)
    }

    /** A point of the dither grid: -1, 0 or 1 steps east and north of where the field began. */
    internal data class Cell(val east: Int, val north: Int)

    /** A [cell] of the grid as shifted in [round]. */
    internal data class Point(val cell: Cell, val round: Int) {
        /** Its offset, with grid steps of [step] pixels; the grid shifted only east with [raOnly]. */
        fun offset(step: Double, raOnly: Boolean): DitherOffset {
            val shifts = fines(raOnly)
            val fine = shifts[round % shifts.size]
            return DitherOffset((cell.east + fine.east / FINE_PARTS) * step, (cell.north + fine.north / FINE_PARTS) * step)
        }
    }

    /**
     * The next point, at random: one of the round's that it has not visited, but the one it is at
     * and those [tried]; once all are visited, one of the next round's. In the same row with
     * [raOnly]. Where it can, every axis that changes moves a whole step at least, past a
     * Seestar's dead zone, as when the grid shifts at the start of a round. Null if none is left.
     */
    internal fun nextPoint(tried: Set<Cell>, raOnly: Boolean): Point? {
        fun open(seen: Set<Cell>) = CELLS.filter { it != cell && it !in seen && it !in tried && (!raOnly || it.north == cell.north) }
        val left = open(visited)
        val inRound = if (left.isNotEmpty()) round else (round + 1) % fines(raOnly).size
        val cells = left.ifEmpty { open(emptySet()) }
        val now = Point(cell, round).offset(1.0, raOnly)
        val whole =
            cells.filter { candidate ->
                val there = Point(candidate, inRound).offset(1.0, raOnly)
                listOf(there.eastPixels - now.eastPixels, there.northPixels - now.northPixels).all { abs(it) < SAME || abs(it) >= 1 - SAME }
            }
        return whole.ifEmpty { cells }.randomOrNull(random)?.let { Point(it, inRound) }
    }

    /** The field is at [point] now. */
    internal fun visit(point: Point) {
        if (point.round != round) visited.clear()
        round = point.round
        cell = point.cell
        visited += cell
    }

    /**
     * The next offset, as N.I.N.A.'s `DitherOffsetSelector`: at a random angle and a distance of a
     * normal distribution with a standard deviation of [DitherOptions.pixels], within three times
     * that, around where the field began; in right ascension alone with [DitherOptions.raOnly]; and
     * at least [DitherOptions.minimumPixels] from [from], or else, after 100 tries, that far towards
     * the centre.
     */
    internal fun offset(from: DitherOffset, options: DitherOptions): DitherOffset {
        val pixels = options.pixels.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val minimum = options.minimumPixels.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(pixels) ?: 0.0
        repeat(TRIES) {
            val angle = random.nextDouble() * PI
            val distance = gaussian(pixels).coerceIn(-SPREAD * pixels, SPREAD * pixels)
            val drawn = DitherOffset(distance * cos(angle), distance * sin(angle))
            val candidate = if (options.raOnly) drawn.copy(northPixels = from.northPixels) else drawn
            val moved =
                if (options.raOnly) {
                    abs(candidate.eastPixels - from.eastPixels)
                } else {
                    hypot(candidate.eastPixels - from.eastPixels, candidate.northPixels - from.northPixels)
                }
            if (moved >= minimum) return candidate
        }
        return fallback(from, minimum, options.raOnly)
    }

    /** N.I.N.A.'s step when no draw moved far enough: [minimum] towards the centre, or east from it. */
    private fun fallback(from: DitherOffset, minimum: Double, raOnly: Boolean): DitherOffset {
        val distance = hypot(from.eastPixels, from.northPixels)
        return when {
            minimum <= 0.0 -> from
            raOnly -> from.copy(eastPixels = from.eastPixels + if (from.eastPixels > 0) -minimum else minimum)
            distance == 0.0 -> DitherOffset(minimum, 0.0)
            else -> DitherOffset(from.eastPixels * (1 - minimum / distance), from.northPixels * (1 - minimum / distance))
        }
    }

    /** A normal deviate of mean 0 and [deviation], by Box and Muller. */
    private fun gaussian(deviation: Double): Double {
        val u = 1.0 - random.nextDouble()
        return deviation * sqrt(2 * -ln(u)) * cos(2 * PI * random.nextDouble())
    }

    private companion object {
        /** Half the sidereal rate, in arcseconds per second, as a guide pulse moves. */
        const val GUIDE_RATE = 7.52
        const val TRIES = 100

        /** Offsets stay within three standard deviations of the centre. */
        const val SPREAD = 3.0

        /** How far from where it was sent a GoTo may land, and the mount still be where it dithers: a Seestar's lands within about 15″. */
        const val GOTO_SLACK_ARCSEC = 60.0

        /** The dither grid's step: a Seestar's GoTos of 25″ or more were all done, in a test at home. */
        const val GRID_ARCSEC = 27.0

        /** GoTos to dither before giving up, when the mount says one left it where it was. */
        const val GOTO_TRIES = 3

        /** Thirds of a step: how far each round shifts the grid. */
        const val FINE_PARTS = 3.0

        /** Steps closer than this are the same. */
        const val SAME = 1e-6
    }
}
