package com.nevoit.sfcompose.symbols

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.IdentityHashMap

enum class SymbolEffectLayerMode { WholeSymbol, ByLayer, Individually }
enum class SymbolDrawEffect { On, Off }

data class SymbolVariableAnimation(
    val iterative: Boolean = false,
    val bounces: Boolean = false,
    val hideInactive: Boolean = false,
    val willLoop: Boolean = false,
)

/** The clock is supplied by the caller so interruption and repeat policy remain explicit. */
@Composable
fun SFSymbol(
    symbol: SymbolInstance,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    tint: Color = Color.Black,
    secondary: Color = tint,
    tertiary: Color = secondary,
    rightToLeft: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    drawProgress: (() -> Float)? = null,
    drawOn: Boolean = true,
    drawEffect: SymbolDrawEffect? = null,
    gradients: Boolean = false,
    variableValue: (() -> Float)? = null,
    palette: List<Color>? = null,
    effect: SymbolEffect = SymbolEffect.None,
    playback: SymbolEffectPlayback = SymbolEffectPlayback.Discrete,
    effectLayerMode: SymbolEffectLayerMode = SymbolEffectLayerMode.ByLayer,
    appearance: SymbolAppearance = SymbolAppearance.Standard,
    variableAnimation: SymbolVariableAnimation? = null,
    elapsedSeconds: () -> Float = { 0f },
    variableDrawValue: (() -> Float)? = null,
    drawReversed: Boolean = false,
    drawReversesMotionGroups: Boolean = false,
    effectRepeatCount: Int? = null,
    displayMode: SymbolDisplayMode = SymbolDisplayMode.IntrinsicWidth,
) {
    require(effectRepeatCount == null || effectRepeatCount > 0) { "Effect repeat count must be positive" }
    val painter = remember(symbol) { AndroidSymbolPainter(symbol) }
    val semantics = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(modifier.then(semantics).height(32.dp)
        .aspectRatio(displayMode.layoutRatio(symbol.advance / symbol.unitsPerEm), matchHeightConstraintsFirst = true)) {
        val renderHeight = displayMode.renderHeight(size.width, size.height, symbol.horizontalInkRatio())
        drawIntoCanvas { canvas ->
            val checkpoint = canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(0f, (size.height - renderHeight) * .5f)
            try {
                painter.draw(
                    canvas.nativeCanvas,
                    size.width,
                    renderHeight,
                    mode,
                    tint.toArgb(),
                    secondary.toArgb(),
                    tertiary.toArgb(),
                    darkTheme,
                    effect,
                    elapsedSeconds(),
                    drawProgress?.invoke(),
                    drawOn,
                    gradients,
                    variableValue?.invoke(),
                    playback,
                    rightToLeft,
                    effectLayerMode,
                    appearance,
                    variableAnimation,
                    palette?.map { it.toArgb() }?.toIntArray(),
                    drawEffect = drawEffect,
                    variableDrawValue = variableDrawValue?.invoke(),
                    drawReversed = drawReversed,
                    drawReversesMotionGroups = drawReversesMotionGroups,
                    pointSize = renderHeight / density,
                    effectRepeatCount = effectRepeatCount,
                )
            } finally {
                canvas.nativeCanvas.restoreToCount(checkpoint)
            }
        }
    }
}

internal class AndroidSymbolPainter(private val symbol: SymbolInstance, private val adaptiveCaps: Boolean = true) {
    private val motionTiming = SymbolMotionTiming(symbol.metadata.optString("sourceKind").startsWith("macOS-system"))
    private val variableDraw = SymbolRenderingMode.entries.associateWith {
        SymbolVariableDraw(symbol.nodes, symbol.metadata.optString("variableColorOrder") == "backToFront", it, symbol.draw::hasGeometry)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val erase = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val copy = PorterDuffXfermode(PorterDuff.Mode.SRC)
    private val compiled = compile(symbol.nodes)
    private val systemLayers = SymbolRenderingMode.entries.associateWith { mode -> buildMap<Int, JSONObject> {
        fun visit(nodes: List<SymbolNode>) {
            for (node in nodes.filter { it.source.supportsSymbolMode(mode) }) when (node) {
                is SymbolNode.Layer -> if (node.source.has("sourceLayerIndex")) {
                    put(node.source.getInt("sourceLayerIndex"), node.source)
                }
                is SymbolNode.Group -> visit(node.children)
            }
        }
        visit(symbol.nodes)
    } }
    private val drawableModes = SymbolRenderingMode.entries.associateWith { mode ->
        compiled.any { hasMode(it, mode) }
    }
    private val motionGroups = SymbolRenderingMode.entries.associateWith { symbolMotionGroups(symbol.nodes, it) }
    private val hasPulseLayers = motionGroups.mapValues { (mode, groups) ->
        groups.indices.keys.any { it.symbolBoolean(mode, "-sfsymbols-always-pulses", "canPulse") }
    }

    private class Node(
        val source: JSONObject,
        val path: Path,
        val children: List<Node>?,
    ) {
        val bounds: RectF by lazy { symbolPathBounds(path) }
    }
    private val gradientShaders = HashMap<String, Shader>()
    private data class DrawScheduleKey(val mode: SymbolRenderingMode, val layerMode: SymbolEffectLayerMode,
        val effect: SymbolDrawEffect, val reversed: Boolean, val reversesMotionGroups: Boolean, val pointSize: Float)
    private val drawSchedules = HashMap<DrawScheduleKey, SymbolDrawTiming>()
    private data class VariableTrackKey(val mode: SymbolRenderingMode, val animation: SymbolVariableAnimation,
        val continuous: Boolean, val repeatCount: Int?)
    private val variableTracks = HashMap<VariableTrackKey, IdentityHashMap<JSONObject, VariableColorTrack>>()
    private val variableThresholds = SymbolRenderingMode.entries.associateWith { mode ->
        val eligible = buildList {
            fun visit(nodes: List<Node>) {
                for (node in nodes) {
                    if (node.children != null) visit(node.children)
                    else if ((node.source.systemStyle(mode)?.has("-sfsymbols-variable-threshold")
                            ?: node.source.optBoolean("autovariable")) && hasMode(node, mode) && node.source.supportsSymbolMode(mode) &&
                        (mode != SymbolRenderingMode.Multicolor ||
                            node.source.optJSONObject("multicolor")?.optString("color") != "hidden")
                    ) add(node)
                }
            }
            visit(compiled)
        }
        IdentityHashMap<JSONObject, Float>().apply {
            eligible.forEachIndexed { index, node ->
                val compiledThreshold = node.source.systemStyle(mode)?.optDouble("-sfsymbols-variable-threshold", Double.NaN)
                    ?: node.source.optDouble("variableThreshold", Double.NaN)
                put(node.source, if (compiledThreshold.isFinite()) compiledThreshold.toFloat() else
                    SymbolTiming.variableThreshold(index, eligible.size,
                        reversed = symbol.metadata.optString("variableColorOrder") == "frontToBack"))
            }
        }
    }
    private val minimumHierarchy = run {
        fun levels(nodes: List<Node>): List<Int> = nodes.flatMap { node ->
            node.children?.let { levels(it) } ?: listOfNotNull(
                node.source.optInt("hierarchical", -1).takeIf { it >= 0 },
            )
        }
        levels(compiled).minOrNull() ?: 0
    }

    private fun compile(nodes: List<SymbolNode>): List<Node> = nodes.map { node ->
        when (node) {
            is SymbolNode.Layer -> Node(node.source, Path().apply {
                // Glyph.contours(at:) skips stale source indices while preserving valid contours.
                for (component in node.components) symbol.contours.getOrNull(component)?.let { addPath(it) }
            }, null)
            is SymbolNode.Group -> {
                val children = compile(node.children)
                if (node.source.optString("type") == "shapeGroup") {
                    val shape = Path()
                    for (child in children) {
                        val operation = if (child.source.optString("shapeGroupBehavior", "draw") == "erase")
                            Path.Op.DIFFERENCE else Path.Op.UNION
                        check(shape.op(child.path, operation)) { "Unable to resolve original shape group" }
                    }
                    Node(node.source, shape, null)
                } else Node(node.source, Path().apply { children.forEach { addPath(it.path) } }, children)
            }
        }
    }

    private fun hasMode(node: Node, mode: SymbolRenderingMode): Boolean =
        node.children?.any { hasMode(it, mode) } ?: node.source.has(when (mode) {
            SymbolRenderingMode.Monochrome -> "monochrome"
            SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette -> "hierarchical"
            SymbolRenderingMode.Multicolor -> "multicolor"
        })

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        mode: SymbolRenderingMode,
        tint: Int,
        secondary: Int,
        tertiary: Int,
        darkTheme: Boolean,
        effect: SymbolEffect,
        time: Float,
        drawProgress: Float? = null,
        drawOn: Boolean = true,
        gradients: Boolean = false,
        variableValue: Float? = null,
        playback: SymbolEffectPlayback = SymbolEffectPlayback.Discrete,
        rightToLeft: Boolean = false,
        effectLayerMode: SymbolEffectLayerMode = SymbolEffectLayerMode.ByLayer,
        appearance: SymbolAppearance = SymbolAppearance.Standard,
        variableAnimation: SymbolVariableAnimation? = null,
        palette: IntArray? = null,
        replacementState: ((JSONObject) -> SymbolReplaceLayerState)? = null,
        drawEffect: SymbolDrawEffect? = null,
        variableDrawValue: Float? = null,
        drawReversed: Boolean = false,
        drawReversesMotionGroups: Boolean = false,
        pointSize: Float = height,
        effectRepeatCount: Int? = null,
    ) {
        require(effectRepeatCount == null || effectRepeatCount > 0) { "Effect repeat count must be positive" }
        if (symbol.bounds.isEmpty || width <= 0f || height <= 0f) return
        val scale = height / symbol.unitsPerEm
        val layer = canvas.saveLayer(null, null)
        canvas.translate(width * 0.5f, height * 0.5f)
        canvas.scale(scale, -scale)
        canvas.translate(-symbol.metricCenterX, -symbol.metricCenterY)
        val pivotX = symbol.metricCenterX
        val pivotY = symbol.metricCenterY
        val transforms = SymbolFillTransform(canvas,
            skipAnimationRotation = symbol.metadata.optString("sourceKind").startsWith("macOS-system"))
        val continuous = playback == SymbolEffectPlayback.Continuous
        val globalEffect = if (effectLayerMode == SymbolEffectLayerMode.WholeSymbol) effect else SymbolEffect.None
        if (rightToLeft && symbol.metadata.optBoolean("rtl")) {
            transforms.scale(-1f, 1f, pivotX, pivotY)
        }
        if (isLinearWiggle(globalEffect)) {
            val offset = (if (continuous) motionTiming.continuousWiggleMagnitude(time, wholeSymbol = true, repeatCount = effectRepeatCount) * 0.025f
                else motionTiming.wiggleOffsetFraction(time, wholeSymbol = true)) * symbol.unitsPerEm
            val direction = wiggleDirection(globalEffect)
            val forwardSign = if (continuous || rightToLeft) -1f else 1f
            if (direction == "clockwise" || direction == "counterclockwise") {
                val clockwise = direction == "clockwise"
                val degrees = if (continuous) motionTiming.continuousWiggleMagnitude(time, wholeSymbol = true, repeatCount = effectRepeatCount) * if (clockwise) 5f else -5f
                    else motionTiming.wiggleDegrees(time, clockwise, wholeSymbol = true)
                transforms.rotate(-degrees, pivotX, pivotY)
            }
            val delta = when (direction) {
                "up" -> 0f to offset
                "down" -> 0f to -offset
                "left" -> -offset to 0f
                "right" -> offset to 0f
                "backward" -> -offset * forwardSign to 0f
                "clockwise", "counterclockwise" -> 0f to 0f
                "custom" -> {
                    val radians = Math.toRadians(symbol.metadata.optDouble("customWiggleAngle", 0.0))
                    (kotlin.math.cos(radians) * offset).toFloat() to (-kotlin.math.sin(radians) * offset).toFloat()
                }
                else -> offset * forwardSign to 0f
            }
            transforms.translate(delta.first, delta.second)
        }
        if (globalEffect == SymbolEffect.RotateClockwise || globalEffect == SymbolEffect.RotateCounterclockwise) {
            transforms.rotate(
                -(if (continuous) motionTiming.continuousRotateDegrees(time, clockwise = effect == SymbolEffect.RotateClockwise, wholeSymbol = true, repeatCount = effectRepeatCount)
                else motionTiming.rotateDegrees(time, clockwise = effect == SymbolEffect.RotateClockwise)),
                pivotX, pivotY,
            )
        }
        if (globalEffect == SymbolEffect.WiggleClockwise || globalEffect == SymbolEffect.WiggleCounterclockwise) {
            val sign = if (effect == SymbolEffect.WiggleClockwise) 1f else -1f
            val angle = if (continuous) motionTiming.continuousWiggleMagnitude(time, wholeSymbol = true, repeatCount = effectRepeatCount) * 5f * sign
                else motionTiming.wiggleDegrees(time, clockwise = effect == SymbolEffect.WiggleClockwise, wholeSymbol = true)
            transforms.rotate(-angle, pivotX, pivotY)
        }
        val effectScale = when (globalEffect) {
            SymbolEffect.Appear, SymbolEffect.AppearDown, SymbolEffect.Disappear, SymbolEffect.DisappearUp ->
                appearanceFrame(globalEffect, time, wholeSymbol = true).scale
            SymbolEffect.ScaleUp, SymbolEffect.ScaleDown -> motionTiming.scaleEffect(time,
                down = globalEffect == SymbolEffect.ScaleDown, wholeSymbol = true)
            SymbolEffect.Bounce -> if (continuous) motionTiming.continuousBounceScale(time, wholeSymbol = true, repeatCount = effectRepeatCount)
                else motionTiming.bounceScale(time, wholeSymbol = true)
            SymbolEffect.BounceDown -> if (continuous) motionTiming.continuousBounceScale(time, down = true, wholeSymbol = true, repeatCount = effectRepeatCount)
                else motionTiming.bounceScale(time, down = true, wholeSymbol = true)
            SymbolEffect.Breathe, SymbolEffect.BreathePulse -> motionTiming.breatheScale(if (continuous) motionTiming.periodTime(time, 3f, effectRepeatCount) else time, wholeSymbol = true)
            else -> 1f
        }
        transforms.scale(effectScale, effectScale, pivotX, pivotY)
        val opacity = when (globalEffect) {
            SymbolEffect.Appear, SymbolEffect.AppearDown, SymbolEffect.Disappear, SymbolEffect.DisappearUp ->
                appearanceFrame(globalEffect, time, wholeSymbol = true).opacity
            SymbolEffect.Pulse -> motionTiming.pulseOpacity(if (continuous) motionTiming.periodTime(time, 2f, effectRepeatCount) else time)
            SymbolEffect.BreathePulse -> 1f - .7f * motionTiming.breathePulseAmount(if (continuous) motionTiming.periodTime(time, 3f, effectRepeatCount) else time, wholeSymbol = true)
            else -> 1f
        }
        val resolvedMode = if (drawableModes.getValue(mode)) mode else SymbolRenderingMode.Monochrome
        for (node in compiled) drawNode(canvas, node, resolvedMode, tint, secondary, tertiary, darkTheme, opacity, drawProgress, drawEffect?.let { it == SymbolDrawEffect.On } ?: drawOn, gradients, variableValue,
            if (effectLayerMode == SymbolEffectLayerMode.WholeSymbol) SymbolEffect.None else effect, time, continuous, rightToLeft, appearance, variableAnimation, palette, replacementState,
            drawEffect?.let { drawSchedules.getOrPut(DrawScheduleKey(resolvedMode, effectLayerMode, it, drawReversed, drawReversesMotionGroups, pointSize)) {
                SymbolDrawTiming(symbol, it == SymbolDrawEffect.On, reverse = drawReversed, layerMode = effectLayerMode, mode = resolvedMode,
                    drawReversesMotionGroups = drawReversesMotionGroups, pointSize = pointSize)
            } },
            continuous || effectLayerMode == SymbolEffectLayerMode.ByLayer, variableDrawValue, drawReversed, pointSize, height / pointSize, transforms, effectRepeatCount)
        canvas.restoreToCount(layer)
    }

    private fun drawNode(
        canvas: Canvas,
        node: Node,
        mode: SymbolRenderingMode,
        tint: Int,
        secondary: Int,
        tertiary: Int,
        darkTheme: Boolean,
        inheritedOpacity: Float,
        drawProgress: Float?,
        drawOn: Boolean,
        gradients: Boolean,
        variableValue: Float?,
        effect: SymbolEffect,
        time: Float,
        continuous: Boolean,
        rightToLeft: Boolean,
        appearance: SymbolAppearance,
        variableAnimation: SymbolVariableAnimation?,
        palette: IntArray?,
        replacementState: ((JSONObject) -> SymbolReplaceLayerState)?,
        drawSchedule: SymbolDrawTiming?,
        customRotationAnchors: Boolean,
        variableDrawValue: Float?,
        drawReversed: Boolean,
        pointSize: Float,
        canvasScaleDensity: Float,
        parentTransforms: SymbolFillTransform,
        effectRepeatCount: Int?,
    ) {
        val source = node.source
        if (!source.supportsSymbolMode(mode)) return
        if (node.children == null && !hasMode(node, mode)) return
        val monochrome = source.optString("monochrome", "draw")
        val level = source.optInt("hierarchical", 0)
        val multicolor = source.optJSONObject("multicolor")
        val colorName = multicolor?.optString("color", "tintColor") ?: "tintColor"
        var isErase = source.symbolErases(mode)
        val hidden = when (mode) {
            SymbolRenderingMode.Monochrome -> monochrome == "hidden"
            SymbolRenderingMode.Multicolor -> colorName == "hidden"
            else -> false
        }
        if (hidden) return
        if (node.children != null) {
            if (source.optString("type") == "shapeGroup") {
                val checkpoint = canvas.saveLayer(null, null)
                for (child in node.children) drawNode(canvas, child, mode, tint, secondary, tertiary, darkTheme, inheritedOpacity, drawProgress, drawOn, gradients, variableValue, effect, time, continuous, rightToLeft, appearance, variableAnimation, palette, replacementState, drawSchedule, customRotationAnchors, variableDrawValue, drawReversed, pointSize, canvasScaleDensity, parentTransforms, effectRepeatCount)
                canvas.restoreToCount(checkpoint)
            } else {
                for (child in node.children) drawNode(canvas, child, mode, tint, secondary, tertiary, darkTheme, inheritedOpacity, drawProgress, drawOn, gradients, variableValue, effect, time, continuous, rightToLeft, appearance, variableAnimation, palette, replacementState, drawSchedule, customRotationAnchors, variableDrawValue, drawReversed, pointSize, canvasScaleDensity, parentTransforms, effectRepeatCount)
            }
            return
        }
        val replacement = replacementState?.invoke(source)
        if (replacement != null && replacement.opacity <= 0f) return
        val checkpoint = canvas.save()
        val transforms = parentTransforms.copy()
        val drawScale = drawSchedule?.scale(source, time) ?: 1f
        val localBounds = node.bounds
        transforms.scale(drawScale, drawScale, localBounds.centerX(), localBounds.centerY())
        if (replacement != null) {
            transforms.translate(replacement.translateX, replacement.translateY)
            transforms.scale(replacement.scaleX, replacement.scaleY, replacement.pivotX ?: symbol.metricCenterX,
                replacement.pivotY ?: symbol.metricCenterY)
        }
        val motion = motionGroups.getValue(mode)
        val motionIndex = motion.indices[source] ?: 0
        val eligibleEffect = when {
            effect == SymbolEffect.Pulse && hasPulseLayers.getValue(mode) && !source.symbolBoolean(mode, "-sfsymbols-always-pulses", "canPulse") -> SymbolEffect.None
            effect in setOf(SymbolEffect.RotateClockwise, SymbolEffect.RotateCounterclockwise) &&
                !source.symbolBoolean(mode, "-sfsymbols-always-rotates", "canRotate") -> SymbolEffect.None
            else -> effect
        }
        val layerOpacity = applyLayerEffect(transforms, eligibleEffect, time, continuous, motionIndex, motion.maxGroupIndex, rightToLeft, customRotationAnchors, effectRepeatCount)
        var color = when (mode) {
            SymbolRenderingMode.Monochrome, SymbolRenderingMode.Hierarchical -> tint
            SymbolRenderingMode.Palette -> if (palette != null && palette.isNotEmpty()) {
                palette[SymbolColors.paletteIndex(level, minimumHierarchy, palette.size).coerceAtLeast(0)]
            } else when (level) { 1 -> secondary; 2, 3 -> tertiary; else -> tint }
            SymbolRenderingMode.Multicolor -> SymbolColors.resolve(colorName, tint, darkTheme, appearance)
        }
        var explicitOpacity = source.symbolOpacity(mode)
        val from = replacement?.drawModeFrom
        val to = replacement?.drawModeTo
        if (from != null && to != null) {
            val amount = replacement.drawModeProgress
            fun descriptorErases(value: JSONObject): Boolean = value.symbolErases(mode)
            val firstErases = descriptorErases(from)
            val secondErases = descriptorErases(to)
            isErase = secondErases
            fun descriptorColor(value: JSONObject): Int = when (mode) {
                SymbolRenderingMode.Multicolor -> SymbolColors.resolve(value.optJSONObject("multicolor")?.optString("color", "tintColor") ?: "tintColor", tint, darkTheme, appearance)
                SymbolRenderingMode.Palette -> {
                    val hierarchy = value.optInt("hierarchical", 0)
                    if (palette != null && palette.isNotEmpty()) palette[SymbolColors.paletteIndex(hierarchy, minimumHierarchy, palette.size).coerceAtLeast(0)]
                    else when (hierarchy) { 1 -> secondary; 2, 3 -> tertiary; else -> tint }
                }
                else -> tint
            }
            val firstColor = descriptorColor(from)
            val secondColor = descriptorColor(to)
            color = if (firstErases != secondErases) secondColor else ((0..3).sumOf { channel ->
                val shift = channel * 8
                val old = (firstColor ushr shift) and 255
                val new = (secondColor ushr shift) and 255
                ((old + (new - old) * amount).toInt() shl shift)
            })
            fun descriptorOpacity(value: JSONObject): Double {
                val opacity = if (mode == SymbolRenderingMode.Multicolor)
                    value.optJSONObject("multicolor")?.optDouble("opacity", value.optDouble("opacity", 100.0))
                        ?: value.optDouble("opacity", 100.0)
                else value.optDouble("opacity", 100.0)
                return opacity * if (mode == SymbolRenderingMode.Hierarchical)
                    SymbolColors.hierarchyOpacity(value.optInt("hierarchical", 0)) else 1f
            }
            val oldOpacity = descriptorOpacity(from)
            val newOpacity = descriptorOpacity(to)
            explicitOpacity = if (firstErases != secondErases) newOpacity else oldOpacity + (newOpacity - oldOpacity) * amount
        }
        val threshold = variableThresholds.getValue(mode)[source]
        val variableActive = if (threshold != null && variableValue != null) {
            SymbolTiming.variableColorActive(variableValue.coerceIn(0f, 1f), threshold)
        } else null
        val activeFraction = variableAnimation?.let { animation ->
            val repeatCount = effectRepeatCount.takeIf { continuous }
            variableTracks.getOrPut(VariableTrackKey(mode, animation, continuous, repeatCount)) {
                val ordered = variableThresholds.getValue(mode).entries.sortedBy { it.value }
                IdentityHashMap<JSONObject, VariableColorTrack>().apply {
                    ordered.forEachIndexed { index, entry ->
                        put(entry.key, if (continuous && animation.iterative && !animation.bounces) {
                            if (animation.willLoop) motionTiming.loopingIterativeVariableColorTrack(index, ordered.size, repeatCount)
                            else motionTiming.nonLoopingContinuousIterativeVariableColorTrack(index, ordered.size, repeatCount)
                        } else motionTiming.variableColorTrack(index, ordered.size, animation.iterative, animation.bounces, animation.willLoop,
                            repeatCount = repeatCount, continuous = continuous))
                    }
                }
            }[source]?.let { track ->
                if (continuous && time >= track.durationSeconds) null else track.activeFraction(time)
            }
        }
        val pulseAmount = if (!hasPulseLayers.getValue(mode) || source.symbolBoolean(mode, "-sfsymbols-always-pulses", "canPulse")) {
            when (effect) {
                SymbolEffect.BreathePulse -> motionTiming.breathePulseAmount(if (continuous) motionTiming.periodTime(time, 3f, effectRepeatCount) else time, motionIndex, motion.maxGroupIndex + 1)
                SymbolEffect.Pulse -> (1f - motionTiming.pulseOpacity(if (continuous) motionTiming.periodTime(time, 2f, effectRepeatCount) else time)) / .7f
                else -> 0f
            }
        } else 0f
        val semanticPulse = source.symbolBoolean(mode, "-sfsymbols-always-pulses", "canPulse") && level >= 0
        val hierarchyOpacity = if (mode == SymbolRenderingMode.Hierarchical && (from == null || to == null)) {
            if (activeFraction != null) {
                val inactive = if (variableAnimation.hideInactive) 0f else SymbolColors.hierarchyOpacity(2)
                inactive + (1f - inactive) * activeFraction
            } else {
                val initial = SymbolColors.hierarchyOpacity(when (variableActive) { true -> 0; false -> 2; null -> level })
                val target = SymbolColors.hierarchyOpacity(if (level == 0 || level == 2) 1 else 2)
                if (semanticPulse) initial + (target - initial) * pulseAmount else initial
            }
        } else 1f
        if (mode == SymbolRenderingMode.Palette && semanticPulse && pulseAmount > 0f) {
            val targetLevel = if (level == 0 || level == 2) 1 else 2
            val target = if (palette != null && palette.isNotEmpty()) {
                palette[SymbolColors.paletteIndex(targetLevel, minimumHierarchy, palette.size).coerceAtLeast(0)]
            } else if (targetLevel == 1) secondary else tertiary
            color = (0..3).sumOf { channel ->
                val shift = channel * 8
                val old = (color ushr shift) and 255
                val new = (target ushr shift) and 255
                ((old + (new - old) * pulseAmount).toInt() shl shift)
            }
        }
        val pulseOpacity = if (semanticPulse && mode in setOf(SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette)) 1f
            else 1f - .7f * pulseAmount
        val variableOpacity = if (mode == SymbolRenderingMode.Hierarchical) 1f else if (activeFraction != null) {
            val inactive = if (variableAnimation.hideInactive) 0f else 0.3f
            inactive + (1f - inactive) * activeFraction
        } else if (variableActive == false) 0.3f else 1f
        paint.color = color
        val gradient = source.optJSONObject("gradient")
        val fillImage = if (mode == SymbolRenderingMode.Multicolor && !isErase) symbol.fillImages[colorName] else null
        paint.isFilterBitmap = fillImage != null
        paint.shader = if (fillImage != null) {
            val bounds = node.bounds
            val key = "image/$colorName/$darkTheme/${bounds.left}/${bounds.top}/${bounds.right}/${bounds.bottom}"
            gradientShaders.getOrPut(key) {
                val bitmap = if (darkTheme) fillImage.dark else fillImage.light
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    // The system maps the image to this layer's ink bounds; PNG rows run opposite to font Y.
                    setLocalMatrix(Matrix().apply {
                        setScale(bounds.width() / bitmap.width, -bounds.height() / bitmap.height)
                        postTranslate(bounds.left, bounds.bottom)
                    })
                }
            }
        } else if (gradients && !isErase && gradient?.optBoolean("optOut", false) != true) {
            val angle = gradient?.optInt("angle", 0) ?: 0
            val alignToLayer = gradient?.optBoolean("alignToLayer", false) == true
            val bounds = if (alignToLayer) node.bounds
                else symbol.gradientMetrics?.bounds(pointSize, (canvasScaleDensity).coerceAtLeast(.0001f))
                    ?: RectF(0f, 0f, symbol.advance, symbol.capHeight)
            val key = "$color/$angle/${bounds.left}/${bounds.top}/${bounds.right}/${bounds.bottom}"
            gradientShaders.getOrPut(key) {
                val colors = SymbolColors.gradient(color)
                val points = SymbolColors.gradientPoints(angle, bounds.left, -bounds.bottom, bounds.right, -bounds.top)
                LinearGradient(points[0], -points[1], points[2], -points[3], colors, null, Shader.TileMode.CLAMP)
            }
        } else null
        val colorAlpha = if (paint.shader == null) AndroidColor.alpha(color) else 255
        paint.alpha = (colorAlpha * (replacement?.opacity ?: 1f) * (drawSchedule?.opacity(source, time) ?: 1f) * inheritedOpacity * layerOpacity * hierarchyOpacity * variableOpacity * pulseOpacity * explicitOpacity / 100.0)
            .toInt().coerceIn(0, 255)
        paint.xfermode = if (isErase) erase else if (source.symbolBoolean(mode, "-sfsymbols-clear-behind", "clearBehind")) copy else null
        val progress = replacement?.drawProgress ?: drawSchedule?.progress(source, time) ?: drawProgress
        val path = replacement?.pathOverride ?: if (progress == null) node.path else {
            symbol.draw.pathFor(source, node.path, progress, isOn = replacement?.drawOn ?: drawOn,
                isReverse = drawReversed, removeOtherSubpaths = !source.has("systemClipStrokeKeyframes"), pointSize = pointSize,
                systemDrawState = drawSchedule?.let { schedule -> { index ->
                    systemLayers.getValue(mode)[index]?.let { schedule.systemState(it, time) }
                } }, systemMode = systemStyleMode(mode),
                adaptiveCaps = adaptiveCaps && source.has("systemClipStrokeKeyframes"))
                ?: error("Unsupported Draw geometry for ${symbol.name}")
        }
        val variableProgress = if (progress == null && replacement?.pathOverride == null && variableDrawValue != null) {
            variableDraw.getValue(mode).progress(source, variableDrawValue)
        } else null
        transforms.withShader(paint.shader) {
            if (variableProgress != null) {
                val active = symbol.draw.pathFor(source, node.path, variableProgress, isOn = true,
                    removeOtherSubpaths = false, pointSize = pointSize, systemMode = systemStyleMode(mode),
                    adaptiveCaps = adaptiveCaps && source.has("systemClipStrokeKeyframes"))
                    ?: error("Unsupported Variable Draw geometry for ${symbol.name}")
                val inactive = Path(node.path).apply { op(active, Path.Op.DIFFERENCE) }
                val originalAlpha = paint.alpha
                paint.alpha = (originalAlpha * 0.3f).toInt()
                canvas.drawPath(inactive, paint)
                paint.alpha = originalAlpha
                canvas.drawPath(active, paint)
            } else {
                canvas.drawPath(path, paint)
            }
        }
        paint.xfermode = null
        canvas.restoreToCount(checkpoint)
    }

    private fun applyLayerEffect(
        canvas: SymbolFillTransform,
        effect: SymbolEffect,
        time: Float,
        continuous: Boolean,
        index: Int,
        maximum: Int,
        rightToLeft: Boolean,
        customRotationAnchors: Boolean,
        effectRepeatCount: Int?,
    ): Float {
        val pivotX = symbol.metricCenterX
        val pivotY = symbol.metricCenterY
        val scale = when (effect) {
            SymbolEffect.Appear, SymbolEffect.AppearDown, SymbolEffect.Disappear, SymbolEffect.DisappearUp ->
                appearanceFrame(effect, time, index, maximum).scale
            SymbolEffect.ScaleUp, SymbolEffect.ScaleDown -> motionTiming.scaleEffect(time,
                down = effect == SymbolEffect.ScaleDown, groupIndex = index, maxGroupIndex = maximum)
            SymbolEffect.Bounce -> if (continuous) motionTiming.continuousBounceScale(time, groupIndex = index, maxGroupIndex = maximum, repeatCount = effectRepeatCount)
                else motionTiming.bounceScale(time, groupIndex = index, maxGroupIndex = maximum)
            SymbolEffect.BounceDown -> if (continuous) motionTiming.continuousBounceScale(time, true, index, maximum, repeatCount = effectRepeatCount)
                else motionTiming.bounceScale(time, true, index, maximum)
            SymbolEffect.Breathe, SymbolEffect.BreathePulse -> motionTiming.breatheScale(if (continuous) motionTiming.periodTime(time, 3f, effectRepeatCount) else time, index, maximum + 1)
            else -> 1f
        }
        canvas.scale(scale, scale, pivotX, pivotY)
        when (effect) {
            SymbolEffect.RotateClockwise, SymbolEffect.RotateCounterclockwise -> {
                val clockwise = effect == SymbolEffect.RotateClockwise
                val degrees = if (continuous) motionTiming.continuousRotateDegrees(time, clockwise, index, maximum + 1, repeatCount = effectRepeatCount)
                    else motionTiming.rotateDegrees(time, clockwise, index, maximum + 1)
                val anchor = if (customRotationAnchors) symbol.rotationAnchor() else pivotX to pivotY
                canvas.rotate(-degrees, anchor.first, anchor.second)
            }
            SymbolEffect.WiggleClockwise, SymbolEffect.WiggleCounterclockwise -> {
                val clockwise = effect == SymbolEffect.WiggleClockwise
                val degrees = if (continuous) motionTiming.continuousWiggleMagnitude(time, index, maximum, repeatCount = effectRepeatCount) * if (clockwise) 5f else -5f
                    else motionTiming.wiggleDegrees(time, clockwise, index, maximum)
                canvas.rotate(-degrees, pivotX, pivotY)
            }
            SymbolEffect.Wiggle, SymbolEffect.WiggleUp, SymbolEffect.WiggleDown, SymbolEffect.WiggleLeft,
            SymbolEffect.WiggleRight, SymbolEffect.WiggleForward, SymbolEffect.WiggleBackward -> {
                val offset = (if (continuous) motionTiming.continuousWiggleMagnitude(time, index, maximum, repeatCount = effectRepeatCount) * 0.025f
                    else motionTiming.wiggleOffsetFraction(time, index, maximum)) * symbol.unitsPerEm
                val forward = if (continuous || rightToLeft) -1f else 1f
                when (wiggleDirection(effect)) {
                    "up" -> canvas.translate(0f, offset)
                    "down" -> canvas.translate(0f, -offset)
                    "left" -> canvas.translate(-offset, 0f)
                    "right" -> canvas.translate(offset, 0f)
                    "backward" -> canvas.translate(-forward * offset, 0f)
                    "clockwise", "counterclockwise" -> {
                        val clockwise = symbol.metadata.optString("preferredWiggleDirection") == "clockwise"
                        val degrees = if (continuous) motionTiming.continuousWiggleMagnitude(time, index, maximum, repeatCount = effectRepeatCount) * if (clockwise) 5f else -5f
                            else motionTiming.wiggleDegrees(time, clockwise, index, maximum)
                        canvas.rotate(-degrees, pivotX, pivotY)
                    }
                    "custom" -> {
                        val radians = Math.toRadians(symbol.metadata.optDouble("customWiggleAngle", 0.0))
                        canvas.translate((kotlin.math.cos(radians) * offset).toFloat(), (-kotlin.math.sin(radians) * offset).toFloat())
                    }
                    else -> canvas.translate(forward * offset, 0f)
                }
            }
            else -> Unit
        }
        return when (effect) {
            SymbolEffect.Appear, SymbolEffect.AppearDown, SymbolEffect.Disappear, SymbolEffect.DisappearUp ->
                appearanceFrame(effect, time, index, maximum).opacity
            else -> 1f
        }
    }

    private fun appearanceFrame(effect: SymbolEffect, time: Float, index: Int = 0,
        maximum: Int = 0, wholeSymbol: Boolean = false): SymbolAppearanceFrame = motionTiming.appearanceFrame(
        time, appear = effect == SymbolEffect.Appear || effect == SymbolEffect.AppearDown,
        down = effect == SymbolEffect.AppearDown || effect == SymbolEffect.Disappear,
        groupIndex = index, maxGroupIndex = maximum, wholeSymbol = wholeSymbol,
    )

    private fun systemStyleMode(mode: SymbolRenderingMode): String = when (mode) {
        SymbolRenderingMode.Monochrome -> "monochrome"
        SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette -> "hierarchical"
        SymbolRenderingMode.Multicolor -> "multicolor"
    }

    private fun isLinearWiggle(effect: SymbolEffect): Boolean = when (effect) {
        SymbolEffect.Wiggle, SymbolEffect.WiggleUp, SymbolEffect.WiggleDown, SymbolEffect.WiggleLeft,
        SymbolEffect.WiggleRight, SymbolEffect.WiggleForward, SymbolEffect.WiggleBackward -> true
        else -> false
    }

    private fun wiggleDirection(effect: SymbolEffect): String = when (effect) {
        SymbolEffect.WiggleUp -> "up"
        SymbolEffect.WiggleDown -> "down"
        SymbolEffect.WiggleLeft -> "left"
        SymbolEffect.WiggleRight -> "right"
        SymbolEffect.WiggleForward -> "forward"
        SymbolEffect.WiggleBackward -> "backward"
        else -> symbol.metadata.optString("preferredWiggleDirection", "forward")
    }
}
