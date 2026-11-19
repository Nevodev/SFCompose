package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import java.util.IdentityHashMap
import kotlin.math.abs

internal class SymbolDrawTiming(
    symbol: SymbolInstance,
    private val on: Boolean,
    private val reverse: Boolean = false,
    wholeSymbol: Boolean = false,
    layerMode: SymbolEffectLayerMode = if (wholeSymbol) SymbolEffectLayerMode.WholeSymbol else SymbolEffectLayerMode.ByLayer,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    layerFilter: (JSONObject) -> Boolean = { true },
    drawReversesMotionGroups: Boolean = false,
    pointSize: Float = 13f,
    fallbackInvalidAttachments: Boolean = false,
    recoverMissingAttachmentAnchors: Boolean = false,
) {
    private val groups = symbolMotionGroups(symbol.nodes, mode)
    private val sources = IdentityHashMap<JSONObject, Int>()
    private val drawSources = java.util.Collections.newSetFromMap(IdentityHashMap<JSONObject, Boolean>())
    private val sourceDurations = IdentityHashMap<JSONObject, Double>()
    private val attachmentSources = java.util.Collections.newSetFromMap(IdentityHashMap<JSONObject, Boolean>())
    private val systemTiming = symbol.metadata.optString("sourceKind").startsWith("macOS-system")
    private val schedule: DrawTimingSchedule

    init {
        val lengths = mutableMapOf<Int, MutableList<Double>>()
        val fallback = mutableSetOf<Int>()
        val allLengths = mutableListOf<Double>()
        fun visit(node: SymbolNode) {
            val group = groups.indices[node.source]
            if (group != null && layerFilter(node.source)) {
                sources[node.source] = group
                val components = when (node) {
                    is SymbolNode.Layer -> node.components
                    is SymbolNode.Group -> {
                        fun collect(nodes: List<SymbolNode>): List<Int> = nodes.flatMap {
                            when (it) {
                                is SymbolNode.Layer -> it.components.toList()
                                is SymbolNode.Group -> collect(it.children)
                            }
                        }
                        collect(node.children).toIntArray()
                    }
                }
                // Rejected stores leave unannotated heads static while their shafts draw. Replace
                // must transition that layer together rather than expose the detached heads.
                val invalidAttachments = fallbackInvalidAttachments && systemTiming &&
                    !SymbolDrawAttachments.systemRecords(node.source, node.source.optString("systemRenderingMode", "monochrome")).isNullOrEmpty() &&
                    !symbol.draw.hasSystemAttachments(node.source, recoverMissingAttachmentAnchors)
                val samples = if (invalidAttachments) null else symbol.draw.timingLengthSamples(node.source, components, pointSize)
                    ?.filter { it.isFinite() && it >= 0.0 }
                if (!samples.isNullOrEmpty()) {
                    drawSources += node.source
                    val operationLength = if (systemTiming) samples.maxOrNull()!! else samples.average()
                    lengths.getOrPut(group, ::mutableListOf).add(operationLength)
                    allLengths.add(operationLength)
                    if (systemTiming) {
                        sourceDurations[node.source] = DrawTimingSchedule.systemDuration(operationLength)
                        if (symbol.draw.hasSystemAttachments(node.source, recoverMissingAttachmentAnchors)) attachmentSources += node.source
                    }
                } else {
                    fallback += group
                }
            }
            if (node is SymbolNode.Group && node.source.optString("type") != "shapeGroup") {
                node.children.forEach(::visit)
            }
        }
        symbol.nodes.forEach(::visit)
        if (systemTiming) {
            val runtimeLayers = sources.keys.groupBy { source ->
                source.systemStyle(mode)?.optString("selector")?.takeIf { it.isNotEmpty() } ?: source
            }
            for (layerSources in runtimeLayers.values) {
                val duration = layerSources.mapNotNull(sourceDurations::get).maxOrNull() ?: continue
                val hasAttachments = layerSources.any { it in attachmentSources }
                for (source in layerSources) {
                    drawSources += source
                    sourceDurations[source] = duration
                    if (hasAttachments) attachmentSources += source
                }
            }
            fallback.clear()
            sources.forEach { (source, group) -> if (source !in drawSources) fallback += group }
        }
        val wholeMean = allLengths.takeIf { it.isNotEmpty() }?.average()
        val durations = sources.values.distinct().associateWith { group ->
            val samples = lengths[group]
            if (samples.isNullOrEmpty()) null else if (systemTiming) {
                samples.maxOf(DrawTimingSchedule::systemDuration)
            } else {
                DrawTimingSchedule.adaptiveDuration(
                    if (layerMode == SymbolEffectLayerMode.WholeSymbol) wholeMean!! else samples.average(),
                )
            }
        }
        val sourceReversed = if (systemTiming)
            symbol.metadata.optBoolean("systemDrawReversesMotionGroups", false)
        else symbol.metadata.optString("variableColorOrder") == "backToFront"
        val reverseOrder = sourceReversed xor
            drawReversesMotionGroups xor (!on && reverse)
        val tailEnds = if (!on) attachmentSources.groupBy { sources.getValue(it) }.mapValues { (_, values) ->
            values.maxOf { sourceDurations.getValue(it) + 1.0 / 30.0 }
        } else emptyMap()
        schedule = DrawTimingSchedule(durations, fallback, on, reverseOrder, layerMode, systemTiming, tailEnds)
    }

    val durationSeconds: Float get() = schedule.durationSeconds
    val drawDurationSeconds: Float get() = schedule.drawDurationSeconds
    val fallbackDurationSeconds: Float get() = schedule.fallbackDurationSeconds

    fun progress(source: JSONObject, elapsedSeconds: Float): Float? {
        if (source !in drawSources) return null
        return sources[source]?.let { schedule.progress(it, elapsedSeconds, sourceDurations[source]) }
    }

    fun systemState(source: JSONObject, elapsedSeconds: Float): SystemDrawState? {
        if (!systemTiming) return null
        val group = sources[source] ?: return null
        return schedule.systemState(group, elapsedSeconds, sourceDurations[source], reverse,
            source in attachmentSources)
    }

    fun opacity(source: JSONObject, elapsedSeconds: Float): Float {
        if (source in drawSources) return 1f
        val group = sources[source] ?: return 1f
        val local = schedule.fallbackProgress(group, elapsedSeconds)
        val eased = if (systemTiming) local * local * (3f - 2f * local)
        else SymbolTiming.cubicBezier(local, 0.33f, 0f, 0.67f, 1f)
        return if (on) eased else 1f - eased
    }

    fun scale(source: JSONObject, elapsedSeconds: Float): Float {
        if (source in drawSources) return 1f
        val group = sources[source] ?: return 1f
        val local = schedule.fallbackProgress(group, elapsedSeconds)
        val eased = if (systemTiming) SymbolSystemTiming.curve(if (on) 15 else 16, local)
        else if (on) SymbolTiming.cubicBezier(local, 0.33f, 0f, 0.2f, 1f)
        else SymbolTiming.cubicBezier(local, 0.33f, 0f, 0.8f, 0.7f)
        return if (on) 0.75f + 0.25f * eased else 1f - 0.25f * eased
    }
}

internal class DrawTimingSchedule(
    drawDurations: Map<Int, Double?>,
    fallbackGroups: Set<Int>,
    private val on: Boolean,
    reverseOrder: Boolean,
    layerMode: SymbolEffectLayerMode,
    private val systemTiming: Boolean = false,
    drawEndOffsets: Map<Int, Double> = emptyMap(),
) {
    private data class Track(val start: Double, val duration: Double, val xStart: Double, val xEnd: Double, val yStart: Double, val yEnd: Double)
    private val drawTracks = mutableMapOf<Int, Track>()
    private val fallbackStarts = mutableMapOf<Int, Double>()
    private val fallbackDuration = if (on) 0.25 else if (systemTiming) 1.0 / 6.0 else 0.167
    private val usesLocalDuration = layerMode != SymbolEffectLayerMode.Individually
    val drawDurationSeconds: Float
    val fallbackDurationSeconds: Float
    val durationSeconds: Float

    init {
        val ordered = drawDurations.keys.sorted().let { if (reverseOrder) it.reversed() else it }
        val total = drawDurations.values.filterNotNull().sum()
        var fraction = 0.0
        var delay = 0.0
        for (group in ordered) {
            val original = drawDurations[group]
            var duration = original ?: 0.0
            var xStart = 0.0
            var xEnd = 1.0
            var yStart = 0.0
            var yEnd = 1.0
            if (original != null && layerMode == SymbolEffectLayerMode.Individually && total > 0.0) {
                yStart = fraction / total
                yEnd = (fraction + original) / total
                xStart = inverseCurve(yStart)
                xEnd = inverseCurve(yEnd)
                duration = (xEnd - xStart) * total
                fraction += original
            }
            val start = if (layerMode == SymbolEffectLayerMode.WholeSymbol) 0.0 else delay
            if (original != null) drawTracks[group] = Track(start, duration, xStart, xEnd, yStart, yEnd)
            if (group in fallbackGroups) fallbackStarts[group] = start
            val maximum = maxOf(duration, if (group in fallbackGroups) fallbackDuration else 0.0)
            delay += when (layerMode) {
                SymbolEffectLayerMode.WholeSymbol -> 0.0
                SymbolEffectLayerMode.ByLayer -> maximum * if (on) 0.18 else 0.38
                SymbolEffectLayerMode.Individually -> maximum
            }
        }
        drawDurationSeconds = (drawTracks.maxOfOrNull { (group, track) ->
            track.start + maxOf(track.duration, drawEndOffsets[group] ?: 0.0)
        } ?: 0.0).toFloat()
        fallbackDurationSeconds = (fallbackStarts.values.maxOfOrNull { it + fallbackDuration } ?: 0.0).toFloat()
        durationSeconds = maxOf(drawDurationSeconds, fallbackDurationSeconds)
    }

    fun progress(group: Int, elapsedSeconds: Float, sourceDuration: Double? = null): Float? = drawTracks[group]?.let { track ->
        val duration = sourceDuration.takeIf { usesLocalDuration } ?: track.duration
        val progress = if (systemTiming) SystemFloatTiming.progress(
            elapsedSeconds - track.start.toFloat(), duration.toFloat()).toDouble()
        else ((elapsedSeconds - track.start) / duration).coerceIn(0.0, 1.0)
        if (progress == 0.0 || progress == 1.0) progress.toFloat() else {
            val globalX = track.xStart + progress * (track.xEnd - track.xStart)
            val value = curve(globalX)
            ((value - track.yStart) / (track.yEnd - track.yStart)).toFloat().coerceIn(0f, 1f)
        }
    }

    fun fallbackProgress(group: Int, elapsedSeconds: Float): Float = if (systemTiming) {
        SystemFloatTiming.progress(elapsedSeconds - (fallbackStarts[group] ?: 0.0).toFloat(), fallbackDuration.toFloat())
    } else (((elapsedSeconds - (fallbackStarts[group] ?: 0.0)) / fallbackDuration).toFloat()).coerceIn(0f, 1f)

    fun systemState(group: Int, elapsedSeconds: Float, sourceDuration: Double? = null,
        reverse: Boolean = false, hasAttachments: Boolean = false): SystemDrawState? {
        val track = drawTracks[group] ?: return null
        val duration = sourceDuration ?: track.duration
        val progress = progress(group, elapsedSeconds, sourceDuration) ?: return null
        val localTime = elapsedSeconds - track.start
        val tail = !on && hasAttachments
        val tailDuration = (1f / 30f).toDouble()
        val completed = tail && localTime >= duration + tailDuration
        val clipped = if (tail && !completed) progress * .9999f else progress
        val from = if (on || reverse) 0f else clipped
        val to = if (on) progress else if (reverse) {
            // The native reversed hold stores 0.0001 directly; subtracting rounded
            // 0.9999 from one changes the endpoint before the attachment vanishes.
            if (tail && !completed && localTime >= duration) .0001f else 1f - clipped
        } else 1f
        val scale = when {
            !tail || localTime <= duration -> 1f
            completed -> 0f
            else -> (1.0 - .5 * (localTime - duration) / tailDuration).toFloat()
        }
        return SystemDrawState(from, to, duration.toFloat(), scale)
    }

    private fun curve(progress: Double): Double = if (systemTiming) {
        systemCurve(progress.toFloat(), on).toDouble()
    } else if (on) {
        SymbolTiming.cubicBezier(progress.toFloat(), 0.2f, 0f, 0.25f, 1f).toDouble()
    } else {
        SymbolTiming.cubicBezier(progress.toFloat(), 0.4f, 0f, 0.8f, 1f).toDouble()
    }

    private fun inverseCurve(progress: Double): Double = if (on) {
        SymbolTiming.cubicBezier(progress.toFloat(), 0f, 0.2f, 1f, 0.25f).toDouble()
    } else {
        SymbolTiming.cubicBezier(progress.toFloat(), 0f, 0.4f, 1f, 0.8f).toDouble()
    }

    companion object {
        // RenderBox solves these presets with float coefficients and a 0.001 X tolerance.
        // A more accurate Bezier solver changes its sampled progress near the start.
        internal fun systemCurve(progress: Float, on: Boolean): Float {
            val target = progress.coerceIn(0f, 1f).toDouble()
            val c = if (on) .6000000238418579f else 1.2000000476837158f
            val b = if (on) -.4500000476837158f else 0f
            val a = if (on) .8500000238418579f else -.20000004768371582f
            val derivativeA = (3f * a).toDouble()
            val derivativeB = (b + b).toDouble()
            var parameter = target
            repeat(8) {
                val error = ((parameter * a + b) * parameter + c) * parameter - target
                if (abs(error) < .0010000000474974513) {
                    val t = parameter.toFloat().toDouble()
                    return ((-2.0 * t + 3.0) * t * t).toFloat()
                }
                val derivative = (parameter * derivativeA + derivativeB) * parameter + c
                if (abs(derivative) < .000001) return@repeat
                parameter -= error / derivative
            }
            var low = 0.0
            var high = 1.0
            parameter = target
            repeat(1023) {
                val error = ((parameter * a + b) * parameter + c) * parameter - target
                if (abs(error) < .0010000000474974513) {
                    val t = parameter.toFloat().toDouble()
                    return ((-2.0 * t + 3.0) * t * t).toFloat()
                }
                if (error < 0.0) low = parameter else high = parameter
                parameter = (low + high) * .5
            }
            val t = parameter.toFloat().toDouble()
            return ((-2.0 * t + 3.0) * t * t).toFloat()
        }

        // RenderBox normalizes the scaled layer length by the requested point size.
        fun systemDuration(lengthAtPointSize1000: Double): Double {
            val normalized = (lengthAtPointSize1000.toFloat() * 0.001f * 0.17182131111621857f).coerceIn(0f, 1f)
            return (0.13333334028720856 + 0.8666666746139526f * normalized * (2f - normalized)).toFloat().toDouble()
        }

        fun adaptiveDuration(lengthAtPointSize1000: Double): Double {
            val target = lengthAtPointSize1000 / 581.79
            var parameter = target
            var converged = false
            repeat(8) {
                val error = parameter * (0.57 + parameter * (-0.27 + 0.7 * parameter)) - target
                if (abs(error) < 0.00001) {
                    converged = true
                    return@repeat
                }
                val derivative = 0.57 + parameter * (-0.54 + 2.1 * parameter)
                if (abs(derivative) >= 0.000001) parameter -= error / derivative
            }
            if (!converged) {
                var low = 0.0
                var high = 1.0
                parameter = target.coerceIn(0.0, 1.0)
                repeat(32) {
                    val value = parameter * (0.57 + parameter * (-0.27 + 0.7 * parameter))
                    if (value < target) low = parameter else high = parameter
                    if (abs(value - target) >= 0.00001) parameter = (low + high) * 0.5
                }
            }
            val frames = (8.0 + 52.0 * parameter * (0.81 + parameter * (0.96 - 0.77 * parameter))).toLong()
            return frames / 60.0
        }
    }
}
