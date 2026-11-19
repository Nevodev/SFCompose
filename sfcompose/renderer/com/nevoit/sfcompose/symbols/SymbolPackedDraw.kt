package com.nevoit.sfcompose.symbols

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Restores the selected sample, leaving the shared decoded document untouched. */
internal fun reconstructPackedSymbolDraw(data: JSONObject, sample: JSONObject): JSONObject {
    val seed = data.optJSONObject("drawMasters") ?: return sample
    val sampling = sample.getJSONObject("systemSampling")
    val coefficients = sampling.getJSONArray("coefficients")
    val light = coefficients.getDouble(0)
    val black = coefficients.getDouble(1)
    val scale = sampling.getDouble("sizeScale")
    fun mix(r: Double, u: Double, b: Double) = r + light * (u - r) + black * (b - r)

    val originalLayers = sample.getJSONArray("compiledLayers")
    val layerSeeds = seed.getJSONObject("layers")
    val layers = JSONArray()
    for (i in 0 until originalLayers.length()) {
        val layer = originalLayers.getJSONObject(i).copyFields()
        val masters = layerSeeds.optJSONArray(i.toString())
        val sets = JSONArray()
        if (masters != null) {
            val paths = List(3) { j ->
                val input = masters.getJSONObject(j).getJSONArray("paths")
                List(input.length()) { k ->
                    val contour = input.getJSONObject(k)
                    val points = contour.getJSONArray("points")
                    ClipContour(contour.getString("verbs"), DoubleArray(points.length(), points::getDouble))
                }
            }
            val target = paths[0].mapIndexed { j, regular ->
                ClipContour(regular.verbs, DoubleArray(regular.coordinates.size) { k ->
                    mix(regular.coordinates[k], paths[1][j].coordinates[k], paths[2][j].coordinates[k]) * scale
                })
            }
            val sourceLengths = paths.map(::clipLengths)
            val targetLengths = clipLengths(target)
            val sourceFrames = List(3) { masters.getJSONObject(it).getJSONArray("keyframes") }
            for (j in 0 until sourceFrames[0].length()) {
                val frames = sourceFrames.map { it.getJSONArray(j) }
                val output = JSONArray()
                for (k in 0 until frames[0].length()) {
                    output.put(interpolateFrame(frames.map { it.getJSONObject(k) }, sourceLengths, targetLengths, light, black))
                }
                sets.put(output)
            }
        }
        layer.put("clipStrokeKeyframes", sets.optJSONArray(0) ?: JSONArray())
        layer.put("clipStrokeKeyframeSets", sets)
        layers.put(layer)
    }

    val output = sample.copyFields().put("compiledLayers", layers)
    val anchors = JSONObject()
    val transform = sample.getJSONArray("systemTransform")
    val (a, b, c, d, e) = List(6, transform::getDouble)
    val f = transform.getDouble(5)
    val determinant = a * d - b * c
    fun fontPoint(x: Double, y: Double): JSONArray = JSONArray()
        .put((d / determinant) * x + (-c / determinant) * y + (c * f - d * e) / determinant)
        .put(-((-b / determinant) * x + (a / determinant) * y + (b * e - a * f) / determinant))
    fun coordinate(values: JSONArray, i: Int): Double = mix(
        values.getJSONArray(0).getDouble(i), values.getJSONArray(1).getDouble(i), values.getJSONArray(2).getDouble(i)) * scale
    seed.optJSONArray("rotation")?.let { values ->
        anchors.put("rotation", fontPoint(coordinate(values, 0), coordinate(values, 1)))
    }
    seed.optJSONArray("dynamic")?.let { values ->
        val frames = JSONArray()
        val nativeFrames = JSONArray()
        fun parameter(i: Int): Double {
            val r = values.getJSONArray(0).getDouble(i).toFloat()
            val u = values.getJSONArray(1).getDouble(i).toFloat()
            val h = values.getJSONArray(2).getDouble(i).toFloat()
            return (((u - r) * light.toFloat() + (h - r) * black.toFloat()) + r).toDouble()
        }
        for (i in 0 until values.getJSONArray(0).length() step 4) {
            val first = parameter(i)
            val second = parameter(i + 1)
            val x = coordinate(values, i + 2)
            val y = coordinate(values, i + 3)
            frames.put(JSONObject().put("sourceParameters", JSONArray().put(first).put(second)).put("point", fontPoint(x, y)))
            nativeFrames.put(JSONArray().put(first).put(second).put(x).put(y))
        }
        anchors.put("dynamicFrames", frames)
        if (frames.length() != 0) anchors.put("dynamic", frames.getJSONObject(0).getJSONArray("point"))
        output.put("systemDynamicAnchorSource", nativeFrames)
    }
    return output.put("systemAnchors", anchors)
}

private fun JSONObject.copyFields() = JSONObject().also { copy -> keys().forEach { copy.put(it, get(it)) } }

private data class ClipContour(val verbs: String, val coordinates: DoubleArray)
private data class ClipPoint(val x: Double, val y: Double) {
    fun midpoint(other: ClipPoint) = ClipPoint(x + (other.x - x) * .5, y + (other.y - y) * .5)
}
private data class ClipElement(val contour: Int, val prefix: Float, val length: Float)
private data class ClipLengths(val contours: FloatArray, val elements: List<ClipElement>)

private fun clipLengths(contours: List<ClipContour>): ClipLengths {
    val lengths = FloatArray(contours.size)
    val elements = ArrayList<ClipElement>()
    contours.forEachIndexed { i, contour ->
        var prefix = 0f
        var cursor = 0
        fun point() = ClipPoint(contour.coordinates[cursor++], contour.coordinates[cursor++])
        var current = ClipPoint(0.0, 0.0)
        var start = current
        for (verb in contour.verbs) {
            var last = current
            val length = when (verb) {
                'M' -> { last = point(); start = last; 0.0 }
                'L' -> { last = point(); lineLength(current, last) }
                'Q' -> { val control = point(); last = point(); quadraticLength(current, control, last) }
                'C' -> { val first = point(); val second = point(); last = point(); cubicLength(current, first, second, last) }
                'Z' -> { last = start; lineLength(current, last) }
                else -> error("Unrecognized clip contour command $verb")
            }.toFloat()
            elements += ClipElement(i, prefix, length)
            prefix += length
            current = last
        }
        check(cursor == contour.coordinates.size)
        lengths[i] = prefix
    }
    return ClipLengths(lengths, elements)
}

private fun lineLength(first: ClipPoint, second: ClipPoint): Double {
    val x = second.x - first.x
    val y = second.y - first.y
    return sqrt(Math.fma(x, x, y * y))
}

private fun quadraticLength(p0: ClipPoint, p1: ClipPoint, p2: ClipPoint, depth: Int = 0): Double {
    val x = (p2.x - p1.x) + (p0.x - p1.x)
    val y = (p2.y - p1.y) + (p0.y - p1.y)
    if (depth > 32 || Math.fma(x, x, y * y) < 4.0) return lineLength(p0, p2)
    val left = p0.midpoint(p1)
    val right = p1.midpoint(p2)
    val center = left.midpoint(right)
    return quadraticLength(p0, left, center, depth + 1) + quadraticLength(center, right, p2, depth + 1)
}

private fun cubicLength(p0: ClipPoint, p1: ClipPoint, p2: ClipPoint, p3: ClipPoint): Double {
    fun curvature(a: Double, b: Double, c: Double, d: Double) =
        maxOf(abs(a + Math.fma(-2.0, b, c)), abs(b + Math.fma(-2.0, c, d)))
    val x = curvature(p0.x, p1.x, p2.x, p3.x)
    val y = curvature(p0.y, p1.y, p2.y, p3.y)
    val steps = ceil(sqrt(1.5 * sqrt(x * x + y * y))).toInt().coerceAtMost(1024)
    if (steps < 1) return if (p0 == p3) 0.0 else lineLength(p0, p3)
    val h = (1.0 / steps).toFloat()
    val h2 = h * h
    val h3 = h * h2
    fun axis(p: Double, q: Double, r: Double, s: Double): DoubleArray {
        val c = (q - p) * 3.0
        val b = Math.fma(3.0, r - q, -c)
        val a = ((s - p) - c) - b
        val third = (a * 6.0) * h3
        return doubleArrayOf(p, Math.fma(c, h.toDouble(), Math.fma(a, h3.toDouble(), b * h2)),
            Math.fma(b + b, h2.toDouble(), third), third)
    }
    val horizontal = axis(p0.x, p1.x, p2.x, p3.x)
    val vertical = axis(p0.y, p1.y, p2.y, p3.y)
    fun step(axis: DoubleArray): Double {
        val next = axis[0] + axis[1]
        axis[1] += axis[2]
        axis[2] += axis[3]
        val delta = next - axis[0]
        axis[0] = next
        return delta
    }
    var length = 0.0
    repeat(steps) {
        val dx = step(horizontal)
        val dy = step(vertical)
        length += sqrt(dx * dx + dy * dy)
    }
    return length
}

private const val FLOAT_EPSILON = 0.00000011920928955078125f

private fun interpolateFrame(
    frames: List<JSONObject>, sources: List<ClipLengths>, target: ClipLengths, light: Double, black: Double,
): JSONObject {
    val endpointNames = listOf("first", "second")
    val subpaths = endpointNames.map { floor(frames[0].getDouble(it).toFloat()).toInt() }
    val firstContour = minOf(subpaths[0], subpaths[1])
    val lastContour = maxOf(subpaths[0], subpaths[1])
    val projections = sources.mapIndexed { index, data ->
        val distances = endpointNames.mapIndexed { endpoint, name ->
            val value = frames[index].getDouble(name).toFloat()
            (value - floor(value)) * data.contours[subpaths[endpoint]]
        }
        val mapped = FloatArray(2) { Float.NaN }
        for ((elementIndex, element) in data.elements.withIndex()) {
            if (element.contour < firstContour) continue
            val end = element.prefix + element.length
            for (endpoint in 0..1) if (mapped[endpoint].isNaN() && end > distances[endpoint]) {
                val fraction = if (abs(element.length) < FLOAT_EPSILON) 0f else
                    (distances[endpoint] - element.prefix) / element.length
                val projected = target.elements[elementIndex]
                mapped[endpoint] = Math.fma(projected.length, fraction, projected.prefix)
            }
            if (element.contour > lastContour) break
        }
        check(mapped.none(Float::isNaN)) { "Unable to project packed Draw keyframe" }
        mapped
    }
    val output = JSONObject().put("flags", frames[0].getInt("flags"))
    for ((endpoint, name) in endpointNames.withIndex()) {
        val subpath = subpaths[endpoint]
        val total = target.contours[subpath]
        val base = projections[0][endpoint]
        fun delta(variant: Int): Float {
            val value = projections[variant][endpoint]
            val difference = value - base
            val distance = abs(difference)
            if (distance <= total - distance) return difference
            return (value + if (difference > 0f) -total else total) - base
        }
        var value = Math.fma(black, delta(2).toDouble(), Math.fma(light, delta(1).toDouble(), base.toDouble())).toFloat()
        if (value < 0f) value += total else if (value >= total) value -= total
        val fraction = if (abs(total) < FLOAT_EPSILON) 0f else value / total
        output.put(name, (fraction + subpath).toDouble())
    }
    val progress = frames.map { it.getDouble("progress").toFloat() }
    val first = progress[0] + (light * (progress[1] - progress[0])).toFloat()
    return output.put("progress", (first + (black * (progress[2] - progress[0])).toFloat()).toDouble())
}
