package com.nevoit.sfcompose.symbols

import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import java.util.IdentityHashMap

/**
 * Scale/visibility recipe for [SFSymbolReplace], rather than a spatial translation direction.
 *
 * DownUp shrinks the outgoing symbol before growing the incoming one. UpUp grows the outgoing
 * symbol away before the incoming symbol grows in. OffUp removes the outgoing symbol while the
 * incoming one grows in. Semantic Magic Replace recipes can use source Draw or layer-specific
 * behavior instead of the standard scale recipe.
 */
enum class SymbolReplaceDirection { DownUp, UpUp, OffUp }

internal enum class SymbolReplaceKind {
    Standard, EnclosureMatch, BadgeAppear, BadgeDisappear, BadgeReplace,
    SlashAppear, SlashDisappear, Draw,
}

internal data class SymbolReplaceLayerState(
    val opacity: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = scaleX,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val drawProgress: Float? = null,
    val drawOn: Boolean = true,
    val pathOverride: Path? = null,
    val drawModeFrom: JSONObject? = null,
    val drawModeTo: JSONObject? = null,
    val drawModeProgress: Float = 1f,
    val systemDrawState: ((Int) -> SystemDrawState?)? = null,
    val commonOpacity: Float = 1f,
)

/** Recipes use font coordinates. The caller owns the clock and interrupt/repeat policy. */
internal class SymbolReplacePlan(
    private val first: SymbolInstance,
    private val second: SymbolInstance,
    private val direction: SymbolReplaceDirection = SymbolReplaceDirection.DownUp,
    preferMagic: Boolean = true,
    private val layered: Boolean = true,
    private val mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    pointSize: Float = 13f,
) {
    private val firstReader = ReplaceTagReader(first, mode)
    private val secondReader = ReplaceTagReader(second, mode)
    val kind: SymbolReplaceKind = if (preferMagic) classify(firstReader, secondReader) else SymbolReplaceKind.Standard
    private val offUpDraw = direction == SymbolReplaceDirection.OffUp &&
        kind in setOf(SymbolReplaceKind.Draw, SymbolReplaceKind.EnclosureMatch)
    private val firstDrawTiming by lazy {
        SymbolDrawTiming(first, on = false, mode = mode, layerFilter = { source ->
            kind != SymbolReplaceKind.EnclosureMatch || firstReader.layers[source] !in firstReader.enclosures.values.flatten()
        }, pointSize = pointSize, fallbackInvalidAttachments = true, recoverMissingAttachmentAnchors = true)
    }
    private val secondDrawTiming by lazy {
        SymbolDrawTiming(second, on = true, mode = mode, layerFilter = { source ->
            kind != SymbolReplaceKind.EnclosureMatch || secondReader.layers[source] !in secondReader.enclosures.values.flatten()
        }, pointSize = pointSize, fallbackInvalidAttachments = true, recoverMissingAttachmentAnchors = true)
    }
    private val firstFallbackFinish by lazy {
        val hasFallback = firstReader.layers.values.any { layer ->
            (kind != SymbolReplaceKind.EnclosureMatch || layer !in firstReader.enclosures.values.flatten()) &&
                firstDrawTiming.progress(layer.source, 0f) == null
        }
        if (hasFallback) if (direction == SymbolReplaceDirection.DownUp) 0.35f else 0.25f else 0f
    }
    private val secondFallbackFinish by lazy {
        val hasFallback = secondReader.layers.values.any { layer ->
            (kind != SymbolReplaceKind.EnclosureMatch || layer !in secondReader.enclosures.values.flatten()) &&
                secondDrawTiming.progress(layer.source, 0f) == null
        }
        if (hasFallback) if (direction == SymbolReplaceDirection.OffUp) 0.25f else 0.5f else 0f
    }
    private val cutover by lazy {
        if (offUpDraw) 0f
        else if (kind == SymbolReplaceKind.EnclosureMatch && firstFallbackFinish > 0f) firstFallbackFinish
        else maxOf(firstFallbackFinish, firstDrawTiming.drawDurationSeconds,
            if (kind == SymbolReplaceKind.EnclosureMatch) 0.083f else 0f)
    }
    private val interiorDurationSeconds by lazy {
        val incoming = maxOf(secondDrawTiming.drawDurationSeconds, secondFallbackFinish)
        if (offUpDraw) maxOf(0.083f, incoming)
        else maxOf(firstDrawTiming.drawDurationSeconds, firstFallbackFinish,
            if (incoming > 0f) cutover + incoming else 0f)
    }
    val durationSeconds: Float = when (kind) {
        SymbolReplaceKind.Standard -> if (direction == SymbolReplaceDirection.OffUp) 0.25f else 0.5f
        SymbolReplaceKind.BadgeAppear, SymbolReplaceKind.BadgeDisappear -> 0.7f
        SymbolReplaceKind.BadgeReplace -> 0.75f
        SymbolReplaceKind.SlashAppear, SymbolReplaceKind.SlashDisappear -> 0.71f
        SymbolReplaceKind.EnclosureMatch -> maxOf(interiorDurationSeconds, cutover +
            if (firstReader.enclosures.keys.single().filled != secondReader.enclosures.keys.single().filled) 0.083f else 0.05f)
        SymbolReplaceKind.Draw -> if (offUpDraw) maxOf(0.083f, secondDrawTiming.drawDurationSeconds, secondFallbackFinish)
            else cutover + maxOf(secondDrawTiming.drawDurationSeconds, secondFallbackFinish)
    }

    fun layerState(source: JSONObject, second: Boolean, elapsedSeconds: Float): SymbolReplaceLayerState {
        val reader = if (second) secondReader else firstReader
        val layer = reader.layers[source] ?: return SymbolReplaceLayerState()
        val time = elapsedSeconds.coerceAtLeast(0f)
        if (time >= durationSeconds) return SymbolReplaceLayerState(opacity = if (second) 1f else 0f)
        return when (kind) {
            SymbolReplaceKind.Standard -> standard(reader, layer, second, time)
            SymbolReplaceKind.BadgeAppear, SymbolReplaceKind.BadgeDisappear,
            SymbolReplaceKind.BadgeReplace -> badge(reader, layer, second, time)
            SymbolReplaceKind.SlashAppear, SymbolReplaceKind.SlashDisappear -> slash(reader, layer, second, time)
            SymbolReplaceKind.EnclosureMatch -> enclosure(reader, layer, second, time)
            SymbolReplaceKind.Draw -> draw(reader, layer, second, time)
        }
    }

    private fun standard(reader: ReplaceTagReader, layer: ReplaceLayer, incoming: Boolean, time: Float): SymbolReplaceLayerState {
        val reverseDelay = if (layered) {
            (reader.maxGroupIndex - layer.groupIndex) * minOf(0.15f / reader.maxGroupIndex.coerceAtLeast(1), 0.05f)
        } else 0f
        val scale: Float
        val opacity: Float
        when (direction) {
            SymbolReplaceDirection.DownUp -> {
                opacity = if (incoming == (time >= 0.25f)) 1f else 0f
                scale = if (incoming) {
                    tween(0.5f, 1f, time - 0.25f, 0.25f - reverseDelay, 0.2f, 0f, 0.25f, 1f)
                } else {
                    tween(1f, 0.5f, time - reverseDelay, 0.25f - reverseDelay, 0.75f, 0f, 0.8f, 1f)
                }
            }
            SymbolReplaceDirection.UpUp -> {
                if (incoming) {
                    val delay = if (layered) 0.166666667f + layer.groupIndex *
                        minOf(0.15f / reader.maxGroupIndex.coerceAtLeast(1), 0.05f) else 0.1667f
                    opacity = tween(0f, 1f, time - delay, 0.5f - delay, 0.17f, 0.17f, 0.67f, 1f)
                    scale = if (layered) tween(0.4f, 1f, time - delay, 0.5f - delay, 0.17f, 0.17f, 0.67f, 1f)
                        else tween(0.4f, 1f, time - delay, 0.3333f, 0.2f, 0f, 0.25f, 1f)
                } else if (layered) {
                    val firstDuration = 0.25f - reverseDelay
                    val firstScale = tween(1f, 1.25f, time, firstDuration, 0.33f, 0f, 0.82f, 0.83f)
                    val overshoot = reverseDelay * (0.25f / firstDuration) + 1.25f
                    scale = if (time <= firstDuration) firstScale else {
                        tween(1.25f, overshoot, time - firstDuration, reverseDelay, 1f, 1f, 1f, 1f)
                    }
                    opacity = tween(1f, 0f, time, firstDuration, 0.33f, 0f, 0.82f, 0.83f)
                } else {
                    opacity = tween(1f, 0f, time, 0.25f, 0.33f, 0f, 0.83f, 0.83f)
                    scale = tween(1f, 1.25f, time, 0.25f, 0.75f, 0f, 0.8f, 1f)
                }
            }
            SymbolReplaceDirection.OffUp -> {
                val duration = 0.25f - reverseDelay
                opacity = if (incoming) tween(0f, 1f, time, duration, 0.33f, 0f, 0.67f, 1f) else 0f
                scale = if (incoming) tween(0.5f, 1f, time, duration, 0.2f, 0f, 0.25f, 1f) else 1f
            }
        }
        return SymbolReplaceLayerState(opacity, scale)
    }

    private fun badge(reader: ReplaceTagReader, layer: ReplaceLayer, incoming: Boolean, time: Float): SymbolReplaceLayerState {
        val isBadge = layer in reader.badgeLayers
        val appearing = kind == SymbolReplaceKind.BadgeAppear
        val disappearing = kind == SymbolReplaceKind.BadgeDisappear
        if (isBadge) {
            val opacity: Float
            val scale: Float
            when {
                appearing -> {
                    opacity = tween(0f, 1f, time, 0.25f, 0.33f, 0f, 0.67f, 1f)
                    scale = tween(0.75f, 1f, time, 0.25f, 0.33f, 0f, 0.2f, 1f)
                }
                disappearing -> {
                    opacity = tween(1f, 0f, time, 0.167f, 0.33f, 0f, 0.67f, 1f)
                    scale = tween(1f, 0.75f, time, 0.25f, 0.33f, 0f, 0.8f, 0.7f)
                }
                incoming -> {
                    opacity = if (time >= 0.25f) 1f else 0f
                    scale = tween(0.5f, 1f, time - 0.25f, 0.25f, 0.2f, 0f, 0.25f, 1f)
                }
                else -> {
                    opacity = if (time >= 0.25f) 0f else 1f
                    scale = tween(1f, 0.5f, time, 0.25f, 0.75f, 0f, 0.8f, 1f)
                }
            }
            val bounds = reader.smallestBounds(reader.badgeLayers)
            return SymbolReplaceLayerState(opacity, scale, pivotX = bounds.centerX(), pivotY = bounds.centerY())
        }
        val switch = if (appearing) 0f else if (disappearing) 0.167f else 0.25f
        val scale = when {
            appearing -> bounce(time, 1.05f, 0.959f)
            disappearing -> bounce(time, 0.95f, 1.059f)
            else -> bounce(time - 0.05f, 0.95f, 1.059f)
        }
        return SymbolReplaceLayerState(if (incoming == (time >= switch)) 1f else 0f, scale)
    }

    private fun slash(reader: ReplaceTagReader, layer: ReplaceLayer, incoming: Boolean, time: Float): SymbolReplaceLayerState {
        val appearing = kind == SymbolReplaceKind.SlashAppear
        if (layer in reader.slashLayers) {
            val amount = if (appearing) {
                if (time <= 0.3333f) tween(0f, 1.08f, time, 0.3333f, 0.33f, 0f, 0.2f, 1f)
                else tween(1.08f, 1f, time - 0.3333f, 0.3333f, 0.17f, 0.17f, 0.2f, 1f)
            } else tween(1f, 0f, time, 0.25f, 0.33f, 0f, 0.67f, 1f)
            val line = layer.slashLine
            if (line != null) return SymbolReplaceLayerState(pathOverride = line.path(amount))
            return SymbolReplaceLayerState(drawProgress = if (appearing) amount.coerceIn(0f, 1f) else 1f - amount, drawOn = appearing)
        }
        val visible = incoming == (time >= if (appearing) 0f else 0.25f)
        return SymbolReplaceLayerState(if (visible) 1f else 0f, bounce(time, 1.05f, 0.97f, 0.22f, 0.27f))
    }

    private fun enclosure(reader: ReplaceTagReader, layer: ReplaceLayer, incoming: Boolean, time: Float): SymbolReplaceLayerState {
        val other = if (incoming) firstReader else secondReader
        val match = reader.enclosures.entries.singleOrNull() ?: return standard(reader, layer, incoming, time)
        val otherMatch = other.enclosures.entries.singleOrNull() ?: return standard(reader, layer, incoming, time)
        if (layer in match.value) {
            val switch = cutover
            val opacity = if (match.key.filled == otherMatch.key.filled) {
                if (incoming == (time >= switch)) 1f else 0f
            } else if (offUpDraw) {
                val amount = SymbolSystemTiming.curve(1, SystemFloatTiming.progress(time, 0.083f))
                if (incoming) amount else 1f - amount
            } else if (incoming) tween(0f, 1f, time - (switch - 0.083f), 0.083f, 0.33f, 0f, 0.67f, 1f)
            else tween(1f, 0f, time - switch, 0.083f, 0.33f, 0f, 0.67f, 1f)
            val index = match.value.indexOf(layer)
            val otherLayer = otherMatch.value.getOrNull(index)
            val from = if (incoming) otherLayer else layer
            val to = if (incoming) layer else otherLayer
            val nativeDescriptors = from?.source?.systemStyle(mode) != null && to?.source?.systemStyle(mode) != null
            if (nativeDescriptors && match.key.filled != otherMatch.key.filled) {
                return SymbolReplaceLayerState(opacity = opacity)
            }
            val normalizedTime = if (interiorDurationSeconds <= 0f) 1f
                else (time / interiorDurationSeconds).coerceIn(0f, 1f)
            // Native enclosure records use Animation::time directly, without a Keyframes curve.
            val materialProgress = if (nativeDescriptors) normalizedTime else
                SymbolTiming.cubicBezier(normalizedTime, 0.33f, 0f, 0.67f, 1f)
            if (nativeDescriptors && !from.source.symbolMaterialMatches(to.source, mode)) {
                return SymbolReplaceLayerState(commonOpacity = if (incoming) materialProgress else 1f - materialProgress)
            }
            return SymbolReplaceLayerState(
                opacity = opacity,
                drawModeFrom = from?.source,
                drawModeTo = to?.source,
                drawModeProgress = materialProgress,
            )
        }
        return draw(reader, layer, incoming, time)
    }

    private fun draw(reader: ReplaceTagReader, layer: ReplaceLayer, incoming: Boolean, time: Float): SymbolReplaceLayerState {
        if (offUpDraw && !incoming) {
            // The system OffUp Draw recipe fades every outgoing layer, including unannotated paths.
            return SymbolReplaceLayerState(opacity = 1f - SymbolSystemTiming.curve(1, SystemFloatTiming.progress(time, 0.083f)))
        }
        val timing = if (incoming) secondDrawTiming else firstDrawTiming
        val local = time - if (incoming) cutover else 0f
        val progress = timing.progress(layer.source, local) ?: return standard(reader, layer, incoming, local)
        return SymbolReplaceLayerState(
            opacity = if (incoming) { if (local < 0f) 0f else 1f }
                else if (time < firstDrawTiming.drawDurationSeconds) 1f else 0f,
            drawProgress = progress,
            drawOn = incoming,
            systemDrawState = { index -> reader.systemLayers[index]?.let { timing.systemState(it, local) } },
        )
    }

    companion object {
        private fun classify(a: ReplaceTagReader, b: ReplaceTagReader): SymbolReplaceKind {
            if (a.hasBadge && !b.hasBadge && a.baseTag != null && a.baseTag == b.wholeTag) return SymbolReplaceKind.BadgeDisappear
            if (!a.hasBadge && b.hasBadge && a.wholeTag != null && a.wholeTag == b.baseTag) return SymbolReplaceKind.BadgeAppear
            if (a.hasBadge && b.hasBadge && a.baseTag != null && a.baseTag == b.baseTag) {
                return if (a.badgeType != b.badgeType) SymbolReplaceKind.BadgeReplace else SymbolReplaceKind.Standard
            }
            if (!a.hasSlash && b.hasSlash && a.wholeTag != null && a.wholeTag == b.baseTag) return SymbolReplaceKind.SlashAppear
            if (a.hasSlash && !b.hasSlash && a.baseTag != null && a.baseTag == b.wholeTag) return SymbolReplaceKind.SlashDisappear
            if (a.enclosures.size == 1 && b.enclosures.size == 1 && a.enclosures.keys.single().tag == b.enclosures.keys.single().tag) {
                return SymbolReplaceKind.EnclosureMatch
            }
            return if (a.hasDrawPath || b.hasDrawPath) SymbolReplaceKind.Draw else SymbolReplaceKind.Standard
        }

        private fun tween(from: Float, to: Float, time: Float, duration: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            if (time <= 0f) return from
            if (duration <= 0f || time >= duration) return to
            return from + (to - from) * SymbolTiming.cubicBezier(time / duration, x1, y1, x2, y2)
        }

        private fun bounce(time: Float, first: Float, second: Float, segment: Float = 0.217f, last: Float = 0.266f): Float = when {
            time <= segment -> tween(1f, first, time, segment, 0.55f, 0f, 0.67f, 1f)
            time <= segment * 2 -> tween(first, second, time - segment, segment, 0.45f, 0f, 0.67f, 1f)
            else -> tween(second, 1f, time - segment * 2, last, 0.25f, 0f, 0.55f, 1f)
        }
    }
}

private class ReplaceLayer(
    val source: JSONObject,
    val tags: Set<String>,
    val bounds: RectF,
    val groupIndex: Int,
    val slashLine: ReplaceSlashLine?,
)

private class ReplaceSlashLine(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val width: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    fun path(amount: Float): Path {
        if (amount <= 0f) return Path()
        val line = Path().apply {
            moveTo(startX, startY)
            lineTo(startX + (endX - startX) * amount, startY + (endY - startY) * amount)
        }
        return Path().also { paint.getFillPath(line, it) }
    }

    companion object {
        // The original F1DF0 recognizer collects straight-segment endpoints, excluding cap curves.
        fun from(data: SymbolContourData): ReplaceSlashLine? {
            val endpoints = mutableListOf<Pair<Float, Float>>()
            var cursor = 0
            var current = 0f to 0f
            var initial = current
            fun point(): Pair<Float, Float> = data.points[cursor++].toFloat() to data.points[cursor++].toFloat()
            for (verb in data.verbs) when (verb) {
                'M' -> { current = point(); initial = current }
                'L' -> { endpoints += current; current = point(); endpoints += current }
                'Q' -> { point(); current = point() }
                'C' -> { point(); point(); current = point() }
                'Z' -> { endpoints += current; endpoints += initial; current = initial }
            }
            val points = endpoints.distinct()
            if (points.size != 4) return null
            fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
                val x = a.first - b.first
                val y = a.second - b.second
                return x * x + y * y
            }
            val a = points.first()
            val b = points.drop(1).minBy { distance(a, it) }
            val rest = points - setOf(a, b)
            val first = (a.first + b.first) * 0.5f to (a.second + b.second) * 0.5f
            val last = (rest[0].first + rest[1].first) * 0.5f to (rest[0].second + rest[1].second) * 0.5f
            val start = if (first.first < last.first) first else last
            val end = if (first.first < last.first) last else first
            if (start.first >= end.first || start.second <= end.second) return null
            return ReplaceSlashLine(start.first, start.second, end.first, end.second, kotlin.math.sqrt(distance(a, b)))
        }
    }
}
private data class TaggedEnclosure(val tag: String, val filled: Boolean)

private class ReplaceTagReader(private val symbol: SymbolInstance, mode: SymbolRenderingMode) {
    val layers = IdentityHashMap<JSONObject, ReplaceLayer>()
    val systemLayers = HashMap<Int, JSONObject>()
    private val ordered = mutableListOf<ReplaceLayer>()
    val maxGroupIndex: Int
    val wholeTag: String?
    val baseTag: String?
    val badgeLayers: List<ReplaceLayer>
    val slashLayers: List<ReplaceLayer>
    val badgeType: String?
    val enclosures: Map<TaggedEnclosure, List<ReplaceLayer>>
    val hasDrawPath: Boolean
    val hasBadge get() = badgeLayers.isNotEmpty() && badgeType != null
    val hasSlash get() = slashLayers.isNotEmpty()

    init {
        val motion = symbolMotionGroups(symbol.nodes, mode)
        fun resolvedPath(node: SymbolNode): Path = when (node) {
            is SymbolNode.Layer -> Path().apply {
                node.components.forEach { symbol.contours.getOrNull(it)?.let(::addPath) }
            }
            is SymbolNode.Group -> Path().apply {
                for (child in node.children) {
                    val path = resolvedPath(child)
                    if (node.source.optString("type") == "shapeGroup") {
                        val operation = if (child.source.optString("shapeGroupBehavior", "draw") == "erase")
                            Path.Op.DIFFERENCE else Path.Op.UNION
                        check(op(path, operation)) { "Unable to resolve original shape group" }
                    } else addPath(path)
                }
            }
        }
        fun addLayer(node: SymbolNode, inheritedGroup: Int?) {
            val bounds = if (node is SymbolNode.Layer) RectF().apply {
                node.components.forEach { component ->
                    symbol.contourData.getOrNull(component)?.let { union(symbolContourBounds(it)) }
                }
            } else symbolPathBounds(resolvedPath(node))
            val tags = node.source.systemStyle(mode)?.let { style ->
                style.optString("-sfsymbols-layer-tags").split(Regex("\\s+")).filter(String::isNotEmpty).toSet()
            } ?: node.source.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).map { tags.getString(it) }.toSet()
            }.orEmpty()
            val slashLine = (node as? SymbolNode.Layer)?.components?.singleOrNull()
                ?.let { symbol.contourData.getOrNull(it) }?.let(ReplaceSlashLine::from)
            val layer = ReplaceLayer(node.source, tags, bounds,
                motion.indices[node.source] ?: inheritedGroup ?: 0, slashLine)
            layers[node.source] = layer
            if (node.source.has("sourceLayerIndex")) systemLayers[node.source.getInt("sourceLayerIndex")] = node.source
            ordered += layer
        }
        fun visit(nodes: List<SymbolNode>, inheritedGroup: Int? = null) {
            for (node in nodes.filter { it.source.supportsSymbolMode(mode) }) when (node) {
                is SymbolNode.Layer -> addLayer(node, inheritedGroup)
                is SymbolNode.Group -> {
                    if (node.source.optString("type") == "shapeGroup") addLayer(node, inheritedGroup)
                    else visit(node.children, motion.indices[node.source] ?: inheritedGroup)
                }
            }
        }
        visit(symbol.nodes)
        maxGroupIndex = motion.maxGroupIndex
        fun commonTags(items: List<ReplaceLayer>): Set<String> = items.firstOrNull()?.tags?.let { first ->
            items.drop(1).fold(first) { acc, layer -> acc.intersect(layer.tags) }
        }.orEmpty()
        val common = commonTags(ordered) - setOf("_enclosure.fill", "_enclosure.stroke")
        wholeTag = when {
            common.size == 1 -> common.single()
            common.size == 2 -> common.singleOrNull { it.endsWith(".fill") && it.removeSuffix(".fill") in common }
            else -> null
        }
        badgeLayers = ordered.filter { it.tags.size == 3 && "_badge" in it.tags && wholeTag in it.tags }
        slashLayers = ordered.filter { "_slash" in it.tags && wholeTag in it.tags }
        badgeType = (commonTags(badgeLayers) - setOfNotNull("_badge", wholeTag)).singleOrNull()
        val base = ordered.filter { it !in badgeLayers && it !in slashLayers }
        baseTag = (commonTags(base) - setOfNotNull(wholeTag)).singleOrNull()
        enclosures = if (wholeTag == null) emptyMap() else buildMap {
            for (layer in ordered) {
                val filled = "_enclosure.fill" in layer.tags
                val stroke = "_enclosure.stroke" in layer.tags
                if (!filled && !stroke) continue
                val candidates = layer.tags - setOfNotNull(if (filled) "_enclosure.fill" else "_enclosure.stroke", wholeTag)
                val tag = if (candidates.isEmpty()) wholeTag else candidates.singleOrNull()
                if (tag != null) {
                    val key = TaggedEnclosure(tag, filled)
                    put(key, get(key).orEmpty() + layer)
                }
            }
        }
        hasDrawPath = ordered.any { symbol.draw.hasGeometry(it.source) }
    }

    fun unionBounds(items: List<ReplaceLayer>): RectF = RectF().apply { items.forEach { union(it.bounds) } }
    fun smallestBounds(items: List<ReplaceLayer>): RectF = items.minByOrNull { it.bounds.width() * it.bounds.height() }?.bounds
        ?: RectF(0f, 0f, symbol.advance, symbol.capHeight)
}
