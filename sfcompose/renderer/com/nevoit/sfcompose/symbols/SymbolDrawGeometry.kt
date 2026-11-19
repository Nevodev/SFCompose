package com.nevoit.sfcompose.symbols

import kotlin.math.abs
import kotlin.math.sqrt

internal data class DrawPoint(val x: Double, val y: Double) {
    operator fun plus(other: DrawPoint) = DrawPoint(x + other.x, y + other.y)
    operator fun minus(other: DrawPoint) = DrawPoint(x - other.x, y - other.y)
    operator fun times(value: Double) = DrawPoint(x * value, y * value)
    operator fun div(value: Double) = DrawPoint(x / value, y / value)
    fun squaredLength() = x * x + y * y
    fun length() = sqrt(squaredLength())
}

/** Numerical routines recovered from SFSymbolsShared's authoring-to-ClipStroke conversion. */
internal class DrawContour(data: SymbolContourData) {
    private data class Cubic(val p0: DrawPoint, val p1: DrawPoint, val p2: DrawPoint, val p3: DrawPoint) {
        fun split(t: Double): Pair<Cubic, Cubic> {
            fun mix(first: DrawPoint, second: DrawPoint) =
                if (t == 0.5) (first + second) * 0.5 else first + (second - first) * t
            val a = mix(p0, p1)
            val b = mix(p1, p2)
            val c = mix(p2, p3)
            val d = mix(a, b)
            val e = mix(b, c)
            val f = mix(d, e)
            return Cubic(p0, a, d, f) to Cubic(f, e, c, p3)
        }

        fun point(t: Double): DrawPoint {
            val first = p1 - p0
            val second = p2 - p1
            val third = p3 - p2
            val a = first * 3.0
            val b = (second - first) * 3.0
            val c = first + (third - second * 2.0)
            return p0 + (a + (b + c * t) * t) * t
        }

        fun length(): Double {
            data class Work(val cubic: Cubic, val span: Double, val depth: Int)
            val pending = ArrayDeque<Work>()
            pending.addLast(Work(this, 1.0, 0))
            var total = 0.0
            var work = 0
            while (pending.isNotEmpty()) {
                if (work++ == 1024) return 1.0
                val item = pending.removeLast()
                val curve = item.cubic
                val polygon = (curve.p1 - curve.p0).length() + (curve.p2 - curve.p1).length() +
                    (curve.p3 - curve.p2).length()
                val chord = (curve.p3 - curve.p0).length()
                if (polygon - chord > 0.25 * item.span && item.depth < 29) {
                    val (first, second) = curve.split(0.5)
                    pending.addLast(Work(second, item.span * 0.5, item.depth + 1))
                    pending.addLast(Work(first, item.span * 0.5, item.depth + 1))
                } else total += (polygon + chord) * 0.5
            }
            return total
        }

        fun nearest(target: DrawPoint): Double {
            fun search(initial: Cubic, minimum: Double, maximum: Double): Pair<Double, Double> {
                var curve = initial
                var lower = minimum
                var upper = maximum
                var first = (target - curve.p0).squaredLength()
                var last = (target - curve.p3).squaredLength()
                // The original tests endpoint-distance equality, not spatial flatness.
                while (abs(first - last) >= 1.0) {
                    val middle = (lower + upper) * 0.5
                    if (middle == lower || middle == upper) break
                    val halves = curve.split(0.5)
                    if (first >= last) {
                        lower = middle
                        curve = halves.second
                    } else {
                        upper = middle
                        curve = halves.first
                    }
                    first = (target - curve.p0).squaredLength()
                    last = (target - curve.p3).squaredLength()
                }
                return (lower + upper) * 0.5 to (first + last) * 0.5
            }
            val halves = split(0.5)
            val first = search(halves.first, 0.0, 0.5)
            val second = search(halves.second, 0.5, 1.0)
            return if (first.second < second.second) first.first else second.first
        }
    }

    data class Projection(val fraction: Double, val squaredDistance: Double)
    private val curves: List<Cubic>
    private val lengths: DoubleArray
    val length: Double

    init {
        val output = ArrayList<Cubic>()
        var cursor = 0
        var current = DrawPoint(0.0, 0.0)
        var start = current
        fun point(): DrawPoint = DrawPoint(data.points[cursor++], data.points[cursor++])
        fun line(end: DrawPoint) {
            val delta = (end - current) / 3.0
            output += Cubic(current, current + delta, end - delta, end)
            current = end
        }
        for (verb in data.verbs) when (verb) {
            'M' -> {
                current = point()
                start = current
                output += Cubic(current, current, current, current)
            }
            'L' -> line(point())
            'Q' -> {
                val control = point()
                val end = point()
                output += Cubic(current, control - (control - current) / 3.0,
                    control + (end - control) / 3.0, end)
                current = end
            }
            'C' -> {
                val first = point()
                val second = point()
                val end = point()
                output += Cubic(current, first, second, end)
                current = end
            }
            'Z' -> if (current != start) line(start)
            else -> error("Unsupported Draw contour verb: $verb")
        }
        require(cursor == data.points.size) { "Unexpected Draw contour coordinates" }
        curves = output
        lengths = DoubleArray(curves.size) { curves[it].length() }
        length = lengths.sum()
    }

    fun project(target: DrawPoint): Projection {
        var best = Double.POSITIVE_INFINITY
        var distance = 0.0
        var prefix = 0.0
        for ((index, curve) in curves.withIndex()) {
            val parameter = curve.nearest(target)
            val squared = (curve.point(parameter) - target).squaredLength()
            if (squared < best) {
                best = squared
                distance = prefix + curve.split(parameter).first.length()
            }
            prefix += lengths[index]
        }
        return Projection(if (length > 0.0) distance / length else 0.0, best)
    }
}
