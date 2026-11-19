package com.nevoit.sfcompose.symbols

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

internal class DrawOutline private constructor(private val segments: List<Segment>, private val tolerance: Double = 0.5) {
    class Cut internal constructor(val outline: DrawOutline, private val mappings: List<DoubleArray>, private val originalLength: Double) {
        fun map(distance: Float): Float? {
            fun locate(value: Double): Float? {
                for (mapping in mappings) {
                    val offset = value - mapping[0]
                    if (offset >= -0.0001 && offset <= mapping[1] + 0.0001) {
                        return (mapping[2] + offset.coerceIn(0.0, mapping[1]) / mapping[1] * mapping[3]).toFloat()
                    }
                }
                return null
            }
            return locate(distance.toDouble()) ?: if (distance == 0f) locate(originalLength) else null
        }
    }
    private class Segment(val points: List<DrawPoint>, private val tolerance: Double = 0.5) {
        val start get() = points.first()
        val end get() = points.last()
        private val samples by lazy { cubicSamples() }
        val length: Double by lazy {
            when (points.size) {
                2 -> (end - start).length()
                3 -> quadraticLength(points)
                4 -> samples.zipWithNext().sumOf { (first, second) -> (second - first).length() }
                else -> error("Invalid Draw segment")
            }
        }

        private fun quadraticLength(points: List<DrawPoint>, depth: Int = 0): Double {
            val bend = points[2] - points[1] + (points[0] - points[1])
            if (depth > 32 || bend.squaredLength() < 16.0 * tolerance * tolerance) return (points[2] - points[0]).length()
            val halves = splitPoints(points, 0.5)
            return quadraticLength(halves.first, depth + 1) + quadraticLength(halves.second, depth + 1)
        }

        private fun cubicSamples(): List<DrawPoint> {
            val first = points[0] + points[2] - points[1] * 2.0
            val second = points[1] + points[3] - points[2] * 2.0
            val bend = DrawPoint(maxOf(abs(first.x), abs(second.x)), maxOf(abs(first.y), abs(second.y)))
            val countValue = ceil(sqrt(0.75 * bend.length() / tolerance))
            val count = if (countValue < 1.0) {
                if (start == end) 0 else 1
            } else countValue.toInt().coerceAtMost(1024)
            if (count == 0) return listOf(start)
            val step = 1f / count
            val square = (step * step).toDouble()
            val cube = (step * (step * step)).toDouble()
            val a = (points[1] - points[0]) * 3.0
            val b = (points[2] - points[1]) * 3.0 - a
            val c = points[3] - points[0] - a - b
            var point = points[0]
            var delta = b * square + c * cube + a * step.toDouble()
            val third = c * (6.0 * cube)
            var secondDelta = third + b * (2.0 * square)
            return buildList(count + 1) {
                add(point)
                repeat(count) {
                    point += delta
                    delta += secondDelta
                    secondDelta += third
                    add(point)
                }
            }
        }

        fun index(distance: Double): Double {
            if (distance <= 0.0) return 0.0
            if (distance >= length) return 1.0
            return when (points.size) {
                2 -> distance / length
                3 -> {
                    var lower = 0.0
                    var upper = 1.0
                    while (upper - lower > 0.0001) {
                        val middle = (lower + upper) * 0.5
                        if (distance >= quadraticLength(splitPoints(points, middle).first)) lower = middle
                        else upper = middle
                    }
                    (lower + upper) * 0.5
                }
                else -> {
                    var prefix = 0.0
                    for (index in 0 until samples.lastIndex) {
                        val next = prefix + (samples[index + 1] - samples[index]).length()
                        if (distance < next) return (index + (distance - prefix) / (next - prefix)) / samples.lastIndex
                        prefix = next
                    }
                    1.0
                }
            }
        }

        fun at(parameter: Double): DrawPoint {
            var values = points
            while (values.size > 1) values = values.zipWithNext().map { (a, b) -> a + (b - a) * parameter }
            return values.single()
        }

        fun tangent(parameter: Double): DrawPoint {
            val derivative = points.zipWithNext().map { (first, second) -> second - first }
            var values = derivative
            while (values.size > 1) values = values.zipWithNext().map { (a, b) -> a + (b - a) * parameter }
            var tangent = values.single()
            if (tangent.squaredLength() == 0.0) {
                tangent = if (parameter <= 0.5) derivative.firstOrNull { it.squaredLength() > 0.0 }
                    ?: (end - start) else derivative.lastOrNull { it.squaredLength() > 0.0 } ?: (end - start)
            }
            val magnitude = tangent.length()
            return if (magnitude > 0.0) tangent / magnitude else DrawPoint(0.0, 0.0)
        }

        fun slice(from: Double, to: Double): Segment {
            val first = if (from <= 0.0001) 0.0 else index(from)
            val last = if (to >= length - 0.0001) 1.0 else index(to)
            if (first <= 0.0001 && last >= 0.9999) return this
            var result = points
            if (first > 0.0001) result = splitPoints(result, first).second
            if (last < 0.9999) result = splitPoints(result, (last - first) / (1.0 - first)).first
            return Segment(result, tolerance)
        }

        fun append(path: Path) = when (points.size) {
            2 -> path.lineTo(end.x.toFloat(), end.y.toFloat())
            3 -> path.quadTo(points[1].x.toFloat(), points[1].y.toFloat(), end.x.toFloat(), end.y.toFloat())
            4 -> path.cubicTo(points[1].x.toFloat(), points[1].y.toFloat(), points[2].x.toFloat(), points[2].y.toFloat(), end.x.toFloat(), end.y.toFloat())
            else -> error("Invalid Draw segment")
        }
    }

    val preciseLength: Double = segments.sumOf { it.length }
    val length: Float = preciseLength.toFloat()
    val isClosed: Boolean = segments.isNotEmpty() && segments.first().start == segments.last().end



    data class ElementPosition(val index: Int, val fraction: Double)

    fun elementAt(fraction: Float): ElementPosition? {
        val distance = preciseLength * fraction
        var prefix = 0.0
        for ((index, segment) in segments.withIndex()) {
            if (distance + Math.ulp(1.0) < prefix + segment.length) {
                return ElementPosition(index, if (segment.length == 0.0) 0.0 else (distance - prefix) / segment.length)
            }
            prefix += segment.length
        }
        return null
    }

    fun distanceAt(position: ElementPosition): Double? {
        if (position.index !in segments.indices) return null
        var prefix = 0.0
        for (index in 0 until position.index) prefix += segments[index].length
        return prefix + position.fraction * segments[position.index].length
    }

    fun getPosTan(distance: Float, position: FloatArray, tangent: FloatArray?, outgoing: Boolean = false): Boolean {
        if (segments.isEmpty()) return false
        var prefix = 0.0
        val clamped = distance.toDouble().coerceIn(0.0, length.toDouble())
        val tolerance = maxOf(0.0001, Math.ulp(distance).toDouble() * 2.0)
        for ((index, segment) in segments.withIndex()) {
            if ((if (outgoing) clamped < prefix + segment.length - tolerance else clamped <= prefix + segment.length + tolerance) || index == segments.lastIndex) {
                val parameter = segment.index((clamped - prefix).coerceIn(0.0, segment.length))
                val point = segment.at(parameter)
                position[0] = point.x.toFloat()
                position[1] = point.y.toFloat()
                tangent?.let {
                    val direction = segment.tangent(parameter)
                    it[0] = direction.x.toFloat()
                    it[1] = direction.y.toFloat()
                }
                return true
            }
            prefix += segment.length
        }
        return false
    }

    private fun sliced(from: Float, to: Float): List<Segment> {
        val result = ArrayList<Segment>()
        var prefix = 0.0
        for (segment in segments) {
            val start = maxOf(from.toDouble(), prefix)
            val end = minOf(to.toDouble(), prefix + segment.length)
            if (end - start >= 0.0001) result += segment.slice(start - prefix, end - prefix)
            prefix += segment.length
        }
        return result
    }

    fun getSegment(from: Float, to: Float, path: Path, moveTo: Boolean): Boolean {
        val selected = sliced(from, to)
        if (selected.isEmpty()) return false
        val first = selected.first().start
        if (moveTo) path.moveTo(first.x.toFloat(), first.y.toFloat())
        else path.lineTo(first.x.toFloat(), first.y.toFloat())
        selected.forEach { it.append(path) }
        return true
    }

    fun rotated(cut: Float): DrawOutline = DrawOutline(sliced(cut, length) + sliced(0f, cut), tolerance)

    fun range(from: Float, to: Float): DrawOutline {
        val end = minOf(to, length)
        return DrawOutline(sliced(from, end) + if (to > length) sliced(0f, to - length) else emptyList(), tolerance)
    }

    fun cut(from: Float, to: Float): Cut {
        val selected = ArrayList<Segment>()
        val mappings = ArrayList<DoubleArray>()
        var destination = 0.0
        fun append(minimum: Double, maximum: Double) {
            var prefix = 0.0
            for (segment in segments) {
                val first = maxOf(minimum, prefix)
                val last = minOf(maximum, prefix + segment.length)
                if (last - first >= 0.0001) {
                    val piece = segment.slice(first - prefix, last - prefix)
                    selected += piece
                    mappings += doubleArrayOf(first, last - first, destination, piece.length)
                    destination += piece.length
                }
                prefix += segment.length
            }
        }
        val end = if (to == 0f) length else to
        if (end <= from) {
            append(from.toDouble(), length.toDouble())
            append(0.0, end.toDouble())
        } else append(from.toDouble(), end.toDouble())
        if (selected.isNotEmpty() && selected.last().end != selected.first().start) {
            selected += Segment(listOf(selected.last().end, selected.first().start), tolerance)
        }
        return Cut(DrawOutline(selected, tolerance), mappings, length.toDouble())
    }

    fun appendTransformed(path: Path, a: Double, b: Double, tx: Double, ty: Double) {
        fun transform(point: DrawPoint) = DrawPoint(a * point.x - b * point.y + tx, b * point.x + a * point.y + ty)
        for ((index, segment) in segments.withIndex()) {
            val transformed = Segment(segment.points.map(::transform))
            if (index == 0) path.lineTo(transformed.start.x.toFloat(), transformed.start.y.toFloat())
            transformed.append(path)
        }
    }

    fun joined(other: DrawOutline): DrawOutline {
        val first = segments.last().end
        val second = other.segments.first().start
        return DrawOutline(segments + Segment(listOf(first, second), tolerance) + other.segments + Segment(listOf(other.segments.last().end, segments.first().start), tolerance), tolerance)
    }

    companion object {
        private fun splitPoints(points: List<DrawPoint>, parameter: Double): Pair<List<DrawPoint>, List<DrawPoint>> {
            val first = ArrayList<DrawPoint>(points.size)
            val second = ArrayList<DrawPoint>(points.size)
            var values = points
            while (values.isNotEmpty()) {
                first += values.first()
                second += values.last()
                values = values.zipWithNext().map { (a, b) -> a + (b - a) * parameter }
            }
            second.reverse()
            return first to second
        }

        fun from(data: SymbolContourData, tolerance: Double = 0.5): DrawOutline {
            require(tolerance > 0.0 && tolerance.isFinite())
            val segments = ArrayList<Segment>()
            var cursor = 0
            var current = DrawPoint(0.0, 0.0)
            var start = current
            fun point() = DrawPoint(data.points[cursor++], data.points[cursor++])
            for (verb in data.verbs) when (verb) {
                'M' -> { current = point(); start = current }
                'L' -> { val end = point(); segments += Segment(listOf(current, end), tolerance); current = end }
                'Q' -> { val control = point(); val end = point(); segments += Segment(listOf(current, control, end), tolerance); current = end }
                'C' -> { val first = point(); val second = point(); val end = point(); segments += Segment(listOf(current, first, second, end), tolerance); current = end }
                'Z' -> if (current != start) { segments += Segment(listOf(current, start), tolerance); current = start }
                else -> error("Unsupported Draw outline verb: $verb")
            }
            return DrawOutline(segments, tolerance)
        }
    }
}
