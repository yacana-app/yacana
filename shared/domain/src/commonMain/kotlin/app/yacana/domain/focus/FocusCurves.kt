// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Ported from N.I.N.A.'s autofocus fittings (Trendline.cs, TrendlineFitting.cs, HyperbolicFitting.cs
// and QuadraticFitting.cs), Copyright © 2016-2026 Stefan Berg and the N.I.N.A. contributors,
// MPL-2.0: https://github.com/isbeorn/nina. NINA fits the lines and the parabola with Accord.NET's
// weighted least squares; here they are solved in closed form.

package app.yacana.domain.focus

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/** A point of the focus curve: the focuser's [position], the frame's HFR and its standard error. */
data class FocusPoint(val position: Double, val hfr: Double, val error: Double)

/** A position and an HFR on the curve, such as its minimum. */
data class CurvePoint(val position: Double, val hfr: Double)

/** How the best focus is found from the points, as N.I.N.A.'s curve fittings. */
@Serializable
enum class FocusFitting { Trendlines, Parabolic, TrendParabolic, Hyperbolic, TrendHyperbolic }

/** A straight line fitted to points weighted by 1/error², as N.I.N.A.'s `Trendline`. */
class Trendline(val points: List<FocusPoint>) {
    var slope = 0.0
        private set
    var offset = 0.0
        private set
    var rSquared = 0.0
        private set

    init {
        if (points.size > 1) {
            val w = points.map { 1 / (it.error * it.error) }
            val sw = w.sum()
            val sx = points.indices.sumOf { w[it] * points[it].position }
            val sy = points.indices.sumOf { w[it] * points[it].hfr }
            val sxx = points.indices.sumOf { w[it] * points[it].position * points[it].position }
            val sxy = points.indices.sumOf { w[it] * points[it].position * points[it].hfr }
            slope = (sw * sxy - sx * sy) / (sw * sxx - sx * sx)
            offset = (sy - slope * sx) / sw
            rSquared = rSquared(points.map { it.hfr }, points.map { slope * it.position + offset }, w)
        }
    }

    fun hfrAt(position: Double) = slope * position + offset

    /** Where it meets [line], with the position rounded as NINA does; (0, 0) if they are parallel. */
    fun intersect(line: Trendline): CurvePoint {
        if (slope == line.slope) return CurvePoint(0.0, 0.0)
        val x = (line.offset - offset) / (slope - line.slope)
        return CurvePoint(round(x), slope * x + offset)
    }
}

/**
 * The two lines of the V, as N.I.N.A.'s `TrendlineFitting` for HFR: each through the points on
 * its side of the lowest one (counting its error) that are at least 0.1 above it.
 */
class TrendlineFitting(points: List<FocusPoint>) {
    val minimum: FocusPoint = points.reduce { l, r -> if (l.hfr + l.error < r.hfr + r.error) l else r }
    val left = Trendline(points.filter { it.position < minimum.position && it.hfr > minimum.hfr + MARGIN })
    val right = Trendline(points.filter { it.position > minimum.position && it.hfr > minimum.hfr + MARGIN })
    val intersection: CurvePoint = left.intersect(right)

    private companion object {
        const val MARGIN = 0.1
    }
}

/**
 * The hyperbola y = a·cosh(asinh((p − x)/b)) closest to the points, as N.I.N.A.'s
 * `HyperbolicFitting`: a search that halves the ranges of a, b and p each cycle. [minimum] is null
 * when the points cannot give one.
 */
class HyperbolicFitting(points: List<FocusPoint>) {
    var minimum: CurvePoint? = null
        private set
    var rSquared = 0.0
        private set
    private var a = 0.0
    private var b = 0.0
    private var p = 0.0

    init {
        fit(points.filter { it.hfr >= MIN_HFR })
    }

    /** The HFR the hyperbola gives at [position]; null without a fit. */
    fun hfrAt(position: Double): Double? = if (minimum == null) null else a * hcos(harcsin((p - position) / b))

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth") // As NINA writes it, so that it gives the same numbers.
    private fun fit(points: List<FocusPoint>) {
        if (points.isEmpty()) return
        val lowest = points.reduce { l, r -> if (l.hfr < r.hfr) l else r }
        val highest = points.reduce { l, r -> if (l.hfr > r.hfr) l else r }
        var highestPosition = highest.position
        val highestHfr = highest.hfr
        val lowestPosition = lowest.position
        val lowestHfr = lowest.hfr
        var oldError = Double.MAX_VALUE
        var lowestError = Double.MAX_VALUE
        // Always go up.
        if (highestPosition < lowestPosition) highestPosition = 2 * lowestPosition - highestPosition
        var a = lowestHfr
        var b = sqrt((highestPosition - lowestPosition) * (highestPosition - lowestPosition) * a * a / (highestHfr * highestHfr - a * a))
        var p = lowestPosition
        var aRange = a
        var bRange = b
        var pRange = highestPosition - lowestPosition
        // Not enough valid points for a curve.
        if (listOf(aRange, bRange, pRange).any { it.isNaN() || it == 0.0 }) return
        var cycles = 0
        do {
            val p0 = p
            val b0 = b
            val a0 = a
            aRange *= HALF
            bRange *= HALF
            pRange *= HALF
            var p1 = p0 - pRange
            while (p1 <= p0 + pRange) {
                var a1 = a0 - aRange
                while (a1 <= a0 + aRange) {
                    var b1 = b0 - bRange
                    while (b1 <= b0 + bRange) {
                        val error = scaledError(points, p1, a1, b1)
                        if (error < lowestError) {
                            oldError = lowestError
                            lowestError = error
                            a = a1
                            b = b1
                            p = p1
                        }
                        b1 += bRange * STEP
                    }
                    a1 += aRange * STEP
                }
                p1 += pRange * STEP
            }
            cycles++
        } while (oldError - lowestError >= TOLERANCE && lowestError > TOLERANCE && cycles < MAX_CYCLES)
        this.a = a
        this.b = b
        this.p = p
        minimum = CurvePoint(round(p), a)
        rSquared = rSquared(points.map { it.hfr }, points.map { a * hcos(harcsin((p - it.position) / b)) }, null)
    }

    private fun scaledError(points: List<FocusPoint>, focus: Double, a: Double, b: Double): Double =
        sqrt(points.sumOf { ((a * hcos(harcsin((focus - it.position) / b)) - it.hfr) / it.error).pow(2) })

    private companion object {
        const val MIN_HFR = 0.1
        const val HALF = 0.5
        const val STEP = 0.1
        const val TOLERANCE = 0.0001
        const val MAX_CYCLES = 30

        /** NINA's MathHelper.HCos and HArcsin, written as it writes them. */
        fun hcos(x: Double) = (exp(x) + exp(-x)) / 2

        fun harcsin(x: Double) = ln(x + sqrt(x * x + 1))
    }
}

/** The parabola closest to the points weighted by 1/error², as N.I.N.A.'s `QuadraticFitting`. */
class QuadraticFitting(points: List<FocusPoint>) {
    /** The coefficients of x², of x and the constant. */
    val coefficients: Triple<Double, Double, Double>
    val rSquared: Double
    val minimum: CurvePoint

    init {
        val w = points.map { 1 / (it.error * it.error) }
        // The normal equations of weighted least squares for x², x and 1.
        val m = Array(TERMS) { DoubleArray(TERMS + 1) }
        points.forEachIndexed { i, point ->
            val basis = doubleArrayOf(point.position * point.position, point.position, 1.0)
            for (r in 0 until TERMS) {
                for (c in 0 until TERMS) m[r][c] += w[i] * basis[r] * basis[c]
                m[r][TERMS] += w[i] * basis[r] * point.hfr
            }
        }
        val (c2, c1, c0) = solve3(m)
        coefficients = Triple(c2, c1, c0)
        rSquared = rSquared(points.map { it.hfr }, points.map { c2 * it.position * it.position + c1 * it.position + c0 }, w)
        val x = round(c1 / (2 * c2) * -1)
        minimum = CurvePoint(x, hfrAt(x))
    }

    fun hfrAt(position: Double) = coefficients.first * position * position + coefficients.second * position + coefficients.third
}

/** Gaussian elimination with partial pivoting of a 3 × 4 augmented matrix. */
private fun solve3(m: Array<DoubleArray>): Triple<Double, Double, Double> {
    val last = TERMS - 1
    for (col in 0..last) {
        val pivot = (col..last).maxBy { abs(m[it][col]) }
        val swap = m[col]
        m[col] = m[pivot]
        m[pivot] = swap
        for (row in col + 1..last) {
            val f = m[row][col] / m[col][col]
            for (k in col..TERMS) m[row][k] -= f * m[col][k]
        }
    }
    val x = DoubleArray(TERMS)
    for (row in last downTo 0) {
        var sum = m[row][TERMS]
        for (k in row + 1..last) sum -= m[row][k] * x[k]
        x[row] = sum / m[row][row]
    }
    return Triple(x[0], x[1], x[2])
}

/** The terms of a parabola: x², x and 1. */
private const val TERMS = 3

/** Accord.NET's coefficient of determination, weighted when [weights] are given. */
private fun rSquared(expected: List<Double>, actual: List<Double>, weights: List<Double>?): Double {
    val w = weights ?: List(expected.size) { 1.0 }
    val mean = expected.indices.sumOf { w[it] * expected[it] } / w.sum()
    var errors = 0.0
    var total = 0.0
    expected.indices.forEach {
        errors += w[it] * (expected[it] - actual[it]).pow(2)
        total += w[it] * (expected[it] - mean).pow(2)
    }
    return 1 - errors / total
}

/**
 * The best focus with [fitting], as N.I.N.A.'s `DetermineFinalFocusPoint` for HFR; the trend
 * combinations average the lines' crossing with the curve's minimum.
 */
fun finalFocus(points: List<FocusPoint>, fitting: FocusFitting): CurvePoint? {
    val trend = { TrendlineFitting(points).intersection }
    return when (fitting) {
        FocusFitting.Trendlines -> trend()
        FocusFitting.Hyperbolic -> HyperbolicFitting(points).minimum
        FocusFitting.Parabolic -> QuadraticFitting(points).minimum
        FocusFitting.TrendParabolic -> average(trend(), QuadraticFitting(points).minimum)
        FocusFitting.TrendHyperbolic -> HyperbolicFitting(points).minimum?.let { average(trend(), it) }
    }
}

private fun average(a: CurvePoint, b: CurvePoint) = CurvePoint(round((a.position + b.position) / 2), (a.hfr + b.hfr) / 2)
