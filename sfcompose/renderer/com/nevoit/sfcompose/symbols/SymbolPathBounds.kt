package com.nevoit.sfcompose.symbols

import android.graphics.RectF
import android.graphics.Path
import android.os.Build
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.asComposePath
import kotlin.math.abs
import kotlin.math.sqrt

internal fun symbolPathBounds(path: Path): RectF {
    val verbs = StringBuilder()
    val points = mutableListOf<Double>()
    val buffer = FloatArray(8)
    val conicBounds = mutableListOf<RectF>()
    val hasNext: () -> Boolean
    val next: (FloatArray) -> PathSegment.Type
    if (Build.VERSION.SDK_INT >= 34) {
        val iterator = path.pathIterator
        hasNext = iterator::hasNext
        next = { values -> when (iterator.next(values, 0)) {
            android.graphics.PathIterator.VERB_MOVE -> PathSegment.Type.Move
            android.graphics.PathIterator.VERB_LINE -> PathSegment.Type.Line
            android.graphics.PathIterator.VERB_QUAD -> PathSegment.Type.Quadratic
            android.graphics.PathIterator.VERB_CUBIC -> PathSegment.Type.Cubic
            android.graphics.PathIterator.VERB_CONIC -> PathSegment.Type.Conic
            android.graphics.PathIterator.VERB_CLOSE -> PathSegment.Type.Close
            else -> PathSegment.Type.Done
        } }
    } else {
        val iterator = path.asComposePath().iterator(PathIterator.ConicEvaluation.AsQuadratics, tolerance = 0.001f)
        hasNext = iterator::hasNext
        next = { values -> iterator.next(values) }
    }
    while (hasNext()) {
        val type = next(buffer)
        if (type == PathSegment.Type.Conic) {
            conicBounds += symbolConicBounds(buffer)
            verbs.append('L')
            points += buffer[4].toDouble()
            points += buffer[5].toDouble()
            continue
        }
        val end = when (type) {
            PathSegment.Type.Move -> { verbs.append('M'); 2 }
            PathSegment.Type.Line -> { verbs.append('L'); 4 }
            PathSegment.Type.Quadratic -> { verbs.append('Q'); 6 }
            PathSegment.Type.Cubic -> { verbs.append('C'); 8 }
            PathSegment.Type.Close -> { verbs.append('Z'); 0 }
            PathSegment.Type.Done -> break
            PathSegment.Type.Conic -> error("Path iterator did not evaluate its conic")
        }
        val start = if (type == PathSegment.Type.Move) 0 else 2
        for (index in start until end) points += buffer[index].toDouble()
    }
    return symbolContourBounds(SymbolContourData(verbs.toString(), points.toDoubleArray())).apply {
        conicBounds.forEach(::union)
    }
}

private fun symbolConicBounds(points: FloatArray): RectF {
    val weight = points[6].toDouble()
    val d = 2.0 * (1.0 - weight)
    val e = 2.0 * (weight - 1.0)
    fun coefficients(offset: Int): DoubleArray {
        val start = points[offset].toDouble()
        val control = points[offset + 2].toDouble()
        val end = points[offset + 4].toDouble()
        return doubleArrayOf(start - 2.0 * weight * control + end, 2.0 * (weight * control - start), start)
    }
    val x = coefficients(0)
    val y = coefficients(1)
    fun extrema(values: DoubleArray): List<Double> {
        val (a, b, c) = values
        return symbolQuadraticRoots(a * e - b * d, 2.0 * (a - c * d), b - c * e)
    }
    val times = listOf(0.0, 1.0) + (extrema(x) + extrema(y)).filter { it > 0.0 && it < 1.0 }
    fun evaluate(values: DoubleArray, t: Double): Double =
        ((values[0] * t + values[1]) * t + values[2]) / ((d * t + e) * t + 1.0)
    return RectF(times.minOf { evaluate(x, it).toFloat() }, times.minOf { evaluate(y, it).toFloat() },
        times.maxOf { evaluate(x, it).toFloat() }, times.maxOf { evaluate(y, it).toFloat() })
}

private fun symbolQuadraticRoots(a: Double, b: Double, c: Double): List<Double> {
    if (abs(a) < 1e-12) return if (abs(b) < 1e-12) emptyList() else listOf(-c / b)
    val discriminant = b * b - 4.0 * a * c
    if (discriminant < 0.0) return emptyList()
    val squareRoot = sqrt(discriminant)
    return listOf((-b + squareRoot) / (2.0 * a), (-b - squareRoot) / (2.0 * a))
}

// CGPathGetPathBoundingBox uses curve extrema; Android computeBounds includes off-curve controls.
internal fun symbolContourBounds(data: SymbolContourData): RectF {
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY
    var cursor = 0
    var x = 0.0
    var y = 0.0
    var initialX = 0.0
    var initialY = 0.0
    fun include(px: Double, py: Double) {
        left = minOf(left, px)
        right = maxOf(right, px)
        top = minOf(top, py)
        bottom = maxOf(bottom, py)
    }
    fun cubic(start: Double, first: Double, second: Double, end: Double, t: Double): Double {
        val u = 1.0 - t
        return u * u * u * start + 3.0 * u * u * t * first + 3.0 * u * t * t * second + t * t * t * end
    }
    fun roots(start: Double, first: Double, second: Double, end: Double): List<Double> {
        val a = -start + 3.0 * first - 3.0 * second + end
        val b = 2.0 * (start - 2.0 * first + second)
        val c = first - start
        return symbolQuadraticRoots(a, b, c)
    }
    for (verb in data.verbs) when (verb) {
        'M', 'L' -> {
            x = data.points[cursor++]
            y = data.points[cursor++]
            if (verb == 'M') { initialX = x; initialY = y }
            include(x, y)
        }
        'Q', 'C' -> {
            val controlX = data.points[cursor++]
            val controlY = data.points[cursor++]
            val secondX = data.points[cursor++]
            val secondY = data.points[cursor++]
            val endX: Double
            val endY: Double
            val firstX: Double
            val firstY: Double
            val lastX: Double
            val lastY: Double
            if (verb == 'Q') {
                endX = secondX
                endY = secondY
                firstX = x + (controlX - x) * (2.0 / 3.0)
                firstY = y + (controlY - y) * (2.0 / 3.0)
                lastX = endX + (controlX - endX) * (2.0 / 3.0)
                lastY = endY + (controlY - endY) * (2.0 / 3.0)
            } else {
                firstX = controlX
                firstY = controlY
                lastX = secondX
                lastY = secondY
                endX = data.points[cursor++]
                endY = data.points[cursor++]
            }
            for (t in roots(x, firstX, lastX, endX) + roots(y, firstY, lastY, endY)) {
                if (t > 0.0 && t < 1.0) include(cubic(x, firstX, lastX, endX, t), cubic(y, firstY, lastY, endY, t))
            }
            x = endX
            y = endY
            include(x, y)
        }
        'Z' -> { x = initialX; y = initialY }
    }
    return if (left.isFinite()) RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()) else RectF()
}
