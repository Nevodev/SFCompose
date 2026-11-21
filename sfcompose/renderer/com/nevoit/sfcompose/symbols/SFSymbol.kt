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
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.IdentityHashMap

/**
 * Selects a whole-symbol or source-layer animation recipe.
 *
 * WholeSymbol applies motion to the entire glyph; ByLayer uses source motion groups and their
 * anchors. Individually selects the effect's individual-layer recipe. Source annotations and the
 * selected effect determine which layers move; layers are not inferred from visible contours.
 */
enum class SymbolEffectLayerMode { WholeSymbol, ByLayer, Individually }

/** Timed drawing direction: On reveals source strokes; Off removes them. Requires source Draw data. */
enum class SymbolDrawEffect { On, Off }

/**
 * Configures animated Variable Color for symbols with source variable-layer thresholds.
 *
 * Use [SymbolAnimation.variableColor] for managed playback, or pass this to [SFSymbol] together
 * with a caller-controlled clock. For a static signal-strength value, use `variableValue` instead.
 *
 * @property iterative Highlights successive variable layers rather than accumulating active layers.
 * @property bounces Adds the source bounce timing when activating variable layers.
 * @property hideInactive Hides inactive variable layers instead of drawing their inactive appearance.
 * @property willLoop Selects the source's looping Variable Color recipe. The playback mode and
 *   repeat count also participate in determining whether playback is finite.
 */
data class SymbolVariableAnimation(
    val iterative: Boolean = false,
    val bounces: Boolean = false,
    val hideInactive: Boolean = false,
    val willLoop: Boolean = false,
)

/**
 * Draws an already loaded [SymbolInstance] with optional caller-controlled effects.
 *
 * Prefer the catalog-entry overloads for automatic loading and [AnimatedSFSymbol] for a managed
 * clock. Obtain [symbol] with [rememberSFSymbol] or [SymbolAssetStore.load]; load an `.Animated`
 * entry when animation data is needed. This overload neither loads resources nor starts a clock.
 * Values supplied by lambdas are read during drawing, so snapshot state can update frames without
 * recomposing this component. A constant [elapsedSeconds] displays a single frame.
 *
 * @param symbol Loaded glyph with its weight, optical scale and resource variant already selected.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param rightToLeft Enables source-declared RTL mirroring and logical effect direction. Resource
 *   selection must be done when loading; an instance already selected for RTL retains that mirroring.
 * @param darkTheme Selects light/dark semantic fill colors; defaults to the current system theme.
 * @param drawProgress Optional raw Draw geometry progress in `0f..1f`. Timed [drawEffect] progress
 *   takes priority where available; `null` leaves geometry unchanged when no Draw effect is active.
 * @param drawOn Whether raw [drawProgress] reveals (`true`) or removes (`false`) strokes.
 * @param drawEffect Optional timed Draw On/Off driven by [elapsedSeconds]; requires source Draw data.
 * @param gradients Enables source gradient styling where supported.
 * @param variableValue Optional Variable Color value in `0f..1f`, such as signal strength.
 * @param palette Layer colors in Palette mode; a nonempty list overrides the three colors.
 * @param effect Motion or opacity effect; defaults to None.
 * @param playback Discrete or continuous timing for supported effects; does not create a clock.
 * @param effectLayerMode Whole-symbol or source-layer animation recipe; defaults to ByLayer.
 * @param appearance Semantic color appearance, independent of resource-variant selection.
 * @param variableAnimation Optional animated Variable Color recipe driven by [elapsedSeconds].
 * @param elapsedSeconds Animation time in seconds from the caller's clock, read during drawing.
 * @param variableDrawValue Optional Variable Draw value in `0f..1f`, requiring source support. Used
 *   only when no raw or timed Draw progress has supplied geometry for the layer.
 * @param drawReversed Selects the source's reverse Draw geometry and ordering.
 * @param drawReversesMotionGroups Reverses timed Draw group choreography independently of geometry.
 * @param effectRepeatCount Positive continuous cycle count; `null` allows unbounded repetition.
 * @param displayMode Slot sizing and overflow policy; defaults to SquareOverflow without clipping.
 * @param pointSize Requested em/slot height in dp, before internal [padding]; defaults to 32 dp.
 *   Parent constraints and [displayMode] can change the actual rendered size.
 * @param padding Insets the drawing area on all sides without increasing the requested slot size.
 * @throws IllegalArgumentException If [effectRepeatCount] is nonpositive.
 */
@Composable
fun SFSymbol(
    symbol: SymbolInstance,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    tint: Color = LocalSFSymbolColor.current,
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
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    pointSize: Dp = 32.dp,
    padding: Dp = 0.dp,
) {
    require(effectRepeatCount == null || effectRepeatCount > 0) { "Effect repeat count must be positive" }
    val painter = remember(symbol) { AndroidSymbolPainter(symbol) }
    val tintArgb = tint.toArgb()
    val secondaryArgb = secondary.toArgb()
    val tertiaryArgb = tertiary.toArgb()
    val paletteArgb = remember(palette) { palette?.map { it.toArgb() }?.toIntArray() }
    val layoutRatio = remember(symbol, displayMode) { displayMode.layoutRatio(symbol.advance / symbol.unitsPerEm) }
    val inkRatio = remember(symbol) { symbol.horizontalInkRatio() }
    val semantics = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(modifier.then(semantics).symbolSlot(pointSize, layoutRatio, padding)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val renderHeight = displayMode.renderHeight(size.width, size.height, inkRatio)
        drawIntoCanvas { canvas ->
            val checkpoint = canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(0f, (size.height - renderHeight) * .5f)
            try {
                painter.draw(
                    canvas.nativeCanvas,
                    size.width,
                    renderHeight,
                    mode,
                    tintArgb,
                    secondaryArgb,
                    tertiaryArgb,
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
                    paletteArgb,
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

internal class AndroidSymbolPainter(
    private val symbol: SymbolInstance,
    private val adaptiveCaps: Boolean = true,
    private val recoverMissingAttachmentAnchors: Boolean = adaptiveCaps,
) {
    private val prepared = symbol.prepared
    private val systemMaterials = symbol.metadata.optString("sourceKind").startsWith("macOS-system")
    private val motionTiming by lazy { SymbolMotionTiming(systemMaterials) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val erase = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val copy = PorterDuffXfermode(PorterDuff.Mode.SRC)
    private val gradientShaders = LruCache<String, Shader>(24)
    private data class DrawScheduleKey(val mode: SymbolRenderingMode, val layerMode: SymbolEffectLayerMode,
        val effect: SymbolDrawEffect, val reversed: Boolean, val reversesMotionGroups: Boolean, val pointSize: Float)
    private val drawSchedules by lazy { LruCache<DrawScheduleKey, SymbolDrawTiming>(8) }
    private data class VariableTrackKey(val mode: SymbolRenderingMode, val animation: SymbolVariableAnimation,
        val continuous: Boolean, val repeatCount: Int?)
    private val variableTracks by lazy { LruCache<VariableTrackKey, Map<JSONObject, VariableColorTrack>>(8) }
    private val staticBounds = RectF()

    private fun baseColor(style: PreparedSymbol.Style, mode: SymbolRenderingMode, tint: Int, secondary: Int,
        tertiary: Int, darkTheme: Boolean, appearance: SymbolAppearance, palette: IntArray?): Int = when (mode) {
        SymbolRenderingMode.Monochrome, SymbolRenderingMode.Hierarchical -> tint
        SymbolRenderingMode.Palette -> if (palette != null && palette.isNotEmpty()) {
            palette[SymbolColors.paletteIndex(style.hierarchy, prepared.minimumHierarchy, palette.size).coerceAtLeast(0)]
        } else when (style.hierarchy) { 1 -> secondary; 2, 3 -> tertiary; else -> tint }
        SymbolRenderingMode.Multicolor -> SymbolColors.resolve(style.colorName, tint, darkTheme, appearance)
    }

    private fun drawStatic(canvas: Canvas, mode: PreparedSymbol.Mode, tint: Int, secondary: Int, tertiary: Int,
        darkTheme: Boolean, appearance: SymbolAppearance, palette: IntArray?) {
        paint.shader = null
        paint.xfermode = null
        for (node in mode.layers) {
            val style = node.style(mode.mode)
            val color = baseColor(style, mode.mode, tint, secondary, tertiary, darkTheme, appearance, palette)
            val hierarchy = if (mode.mode == SymbolRenderingMode.Hierarchical)
                SymbolColors.hierarchyOpacity(style.hierarchy) else 1f
            val alpha = if (style.native) AndroidColor.alpha(color) * (hierarchy * style.materialOpacity)
                else (AndroidColor.alpha(color) * hierarchy * style.opacity / 100.0).toFloat()
            if (alpha < 1f) continue
            paint.color = color
            paint.alpha = alpha.toInt().coerceIn(0, 255)
            canvas.drawPath(node.path, paint)
        }
    }

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
        val resolvedMode = prepared.resolveMode(mode)
        val modeData = prepared.mode(resolvedMode)
        val pivotX = symbol.metricCenterX
        val pivotY = symbol.metricCenterY
        val mirrored = symbol.mirroredForRightToLeft || rightToLeft && symbol.metadata.optBoolean("rtl")
        val staticGeometry = effect == SymbolEffect.None && drawEffect == null && drawProgress == null &&
            replacementState == null && variableDrawValue == null
        val needsIsolation = modeData.requiresIsolation || replacementState != null
        val layer = if (needsIsolation) {
            val bounds = if (staticGeometry) {
                val ink = modeData.inkBounds
                staticBounds.set(
                    width * .5f + (if (mirrored) pivotX - ink.right else ink.left - pivotX) * scale - 1f,
                    height * .5f + (pivotY - ink.bottom) * scale - 1f,
                    width * .5f + (if (mirrored) pivotX - ink.left else ink.right - pivotX) * scale + 1f,
                    height * .5f + (pivotY - ink.top) * scale + 1f,
                )
                staticBounds
            } else null
            canvas.saveLayer(bounds, null)
        } else canvas.save()
        canvas.translate(width * .5f, height * .5f)
        canvas.scale(if (mirrored) -scale else scale, -scale)
        canvas.translate(-pivotX, -pivotY)
        if (staticGeometry && !needsIsolation && !gradients && variableValue == null &&
            variableAnimation == null && (resolvedMode != SymbolRenderingMode.Multicolor || symbol.fillImages.isEmpty())) {
            drawStatic(canvas, modeData, tint, secondary, tertiary, darkTheme, appearance, palette)
            canvas.restoreToCount(layer)
            return
        }
        val transforms = SymbolFillTransform(canvas,
            skipAnimationRotation = systemMaterials)
        val continuous = playback == SymbolEffectPlayback.Continuous
        val globalEffect = if (effectLayerMode == SymbolEffectLayerMode.WholeSymbol) effect else SymbolEffect.None
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
        if (effectScale != 1f) transforms.scale(effectScale, effectScale, pivotX, pivotY)
        val opacity = when (globalEffect) {
            SymbolEffect.Appear, SymbolEffect.AppearDown, SymbolEffect.Disappear, SymbolEffect.DisappearUp ->
                appearanceFrame(globalEffect, time, wholeSymbol = true).opacity
            SymbolEffect.Pulse -> if (systemMaterials) 1f else
                motionTiming.pulseOpacity(if (continuous) motionTiming.periodTime(time, 2f, effectRepeatCount) else time)
            SymbolEffect.BreathePulse -> if (systemMaterials) 1f else
                1f - .7f * motionTiming.breathePulseAmount(if (continuous) motionTiming.periodTime(time, 3f, effectRepeatCount) else time, wholeSymbol = true)
            else -> 1f
        }
        val wholeSymbolPulseAmount = if (systemMaterials) when (globalEffect) {
            SymbolEffect.Pulse -> (1f - motionTiming.pulseOpacity(
                if (continuous) motionTiming.periodTime(time, 2f, effectRepeatCount) else time)) / .7f
            SymbolEffect.BreathePulse -> motionTiming.breathePulseAmount(
                if (continuous) motionTiming.periodTime(time, 3f, effectRepeatCount) else time, wholeSymbol = true)
            else -> null
        } else null
        val schedule = drawEffect?.let {
            val key = DrawScheduleKey(resolvedMode, effectLayerMode, it, drawReversed, drawReversesMotionGroups, pointSize)
            drawSchedules.getOrBuild(key) {
                SymbolDrawTiming(symbol, it == SymbolDrawEffect.On, reverse = drawReversed, layerMode = effectLayerMode, mode = resolvedMode,
                    drawReversesMotionGroups = drawReversesMotionGroups, pointSize = pointSize,
                    recoverMissingAttachmentAnchors = recoverMissingAttachmentAnchors)
            }
        }
        val systemState = schedule?.let { timing -> { index: Int ->
            modeData.systemLayers[index]?.let { timing.systemState(it, time) }
        } }
        for (node in prepared.roots) drawNode(canvas, node, resolvedMode, tint, secondary, tertiary, darkTheme, opacity, drawProgress, drawEffect?.let { it == SymbolDrawEffect.On } ?: drawOn, gradients, variableValue,
            if (effectLayerMode == SymbolEffectLayerMode.WholeSymbol) SymbolEffect.None else effect, time, continuous, rightToLeft, appearance, variableAnimation, palette, replacementState,
            schedule, continuous || effectLayerMode == SymbolEffectLayerMode.ByLayer, variableDrawValue, drawReversed,
            pointSize, height / pointSize, transforms, effectRepeatCount, wholeSymbolPulseAmount, systemState)
        canvas.restoreToCount(layer)
    }

    private fun drawNode(
        canvas: Canvas,
        node: PreparedSymbol.Node,
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
        wholeSymbolPulseAmount: Float?,
        systemDrawState: ((Int) -> SystemDrawState?)?,
    ) {
        val source = node.source
        val style = node.style(mode)
        if (!style.visible || node.children == null && !style.drawable) return
        val level = style.hierarchy
        val colorName = style.colorName
        var isErase = style.erases
        val nativeMaterial = style.native
        var clearBehind = style.clearBehind
        if (node.children != null) {
            for (child in node.children) drawNode(canvas, child, mode, tint, secondary, tertiary, darkTheme,
                inheritedOpacity, drawProgress, drawOn, gradients, variableValue, effect, time, continuous, rightToLeft,
                appearance, variableAnimation, palette, replacementState, drawSchedule, customRotationAnchors,
                variableDrawValue, drawReversed, pointSize, canvasScaleDensity, parentTransforms, effectRepeatCount,
                wholeSymbolPulseAmount, systemDrawState)
            return
        }
        val replacement = replacementState?.invoke(source)
        if (replacement != null && replacement.opacity <= 0f && !(nativeMaterial && clearBehind)) return
        val drawScale = drawSchedule?.scale(source, time) ?: 1f
        val transformsLayer = drawScale != 1f || effect != SymbolEffect.None && effect != SymbolEffect.Pulse ||
            replacement != null && (replacement.translateX != 0f || replacement.translateY != 0f ||
                replacement.scaleX != 1f || replacement.scaleY != 1f)
        val checkpoint = if (transformsLayer) canvas.save() else null
        val transforms = if (transformsLayer) parentTransforms.copy() else parentTransforms
        if (drawScale != 1f) transforms.scale(drawScale, drawScale, node.bounds.centerX(), node.bounds.centerY())
        if (replacement != null && transformsLayer) {
            transforms.translate(replacement.translateX, replacement.translateY)
            transforms.scale(replacement.scaleX, replacement.scaleY, replacement.pivotX ?: symbol.metricCenterX,
                replacement.pivotY ?: symbol.metricCenterY)
        }
        val modeData = prepared.mode(mode)
        val motion = if (effect == SymbolEffect.None) null else modeData.motion
        val motionIndex = motion?.indices?.get(source) ?: 0
        val motionMaximum = motion?.maxGroupIndex ?: 0
        val eligibleEffect = when {
            effect == SymbolEffect.Pulse && modeData.hasPulseLayers && !style.pulses -> SymbolEffect.None
            (effect == SymbolEffect.RotateClockwise || effect == SymbolEffect.RotateCounterclockwise) &&
                !style.rotates -> SymbolEffect.None
            else -> effect
        }
        val layerOpacity = if (eligibleEffect == SymbolEffect.None || eligibleEffect == SymbolEffect.Pulse) 1f
            else applyLayerEffect(transforms, eligibleEffect, time, continuous, motionIndex, motionMaximum,
                rightToLeft, customRotationAnchors, effectRepeatCount)
        var color = baseColor(style, mode, tint, secondary, tertiary, darkTheme, appearance, palette)
        var explicitOpacity = style.opacity
        var interpolatedMaterialOpacity: Float? = null
        var interpolatedDrawingOpacity: Float? = null
        val from = replacement?.drawModeFrom
        val to = replacement?.drawModeTo
        if (from != null && to != null) {
            val amount = replacement.drawModeProgress
            fun descriptorErases(value: JSONObject): Boolean = value.symbolErases(mode)
            val firstErases = descriptorErases(from)
            val secondErases = descriptorErases(to)
            isErase = secondErases
            clearBehind = to.symbolBoolean(mode, "-sfsymbols-clear-behind", "clearBehind")
            fun descriptorColor(value: JSONObject): Int = when (mode) {
                SymbolRenderingMode.Multicolor -> SymbolColors.resolve(value.optJSONObject("multicolor")?.optString("color", "tintColor") ?: "tintColor", tint, darkTheme, appearance)
                SymbolRenderingMode.Palette -> {
                    val hierarchy = value.optInt("hierarchical", 0)
                    if (palette != null && palette.isNotEmpty()) palette[SymbolColors.paletteIndex(hierarchy, prepared.minimumHierarchy, palette.size).coerceAtLeast(0)]
                    else when (hierarchy) { 1 -> secondary; 2, 3 -> tertiary; else -> tint }
                }
                else -> tint
            }
            val firstColor = descriptorColor(from)
            val secondColor = descriptorColor(to)
            val nativeMatchedDescriptors = nativeMaterial && from.symbolMaterialMatches(to, mode)
            color = if (firstErases != secondErases && !nativeMatchedDescriptors) secondColor else ((0..3).sumOf { channel ->
                val shift = channel * 8
                val old = (firstColor ushr shift) and 255
                val new = (secondColor ushr shift) and 255
                ((old + (new - old) * amount).toInt() shl shift)
            })
            fun descriptorOpacity(value: JSONObject): Double {
                val opacity = if (value.systemStyle(mode) != null) {
                    if (value.symbolErases(mode)) 0.0 else value.symbolOpacity(mode)
                } else if (mode == SymbolRenderingMode.Multicolor)
                    value.optJSONObject("multicolor")?.optDouble("opacity", value.optDouble("opacity", 100.0))
                        ?: value.optDouble("opacity", 100.0)
                else value.optDouble("opacity", 100.0)
                return opacity * if (mode == SymbolRenderingMode.Hierarchical)
                    SymbolColors.hierarchyOpacity(value.optInt("hierarchical", 0)) else 1f
            }
            val oldOpacity = descriptorOpacity(from)
            val newOpacity = descriptorOpacity(to)
            explicitOpacity = if (firstErases != secondErases) newOpacity else oldOpacity + (newOpacity - oldOpacity) * amount
            if (nativeMatchedDescriptors) {
                val oldMaterial = from.symbolMaterialOpacity(mode)
                val newMaterial = to.symbolMaterialOpacity(mode)
                val mixedMaterial = ((newMaterial - oldMaterial).toDouble() * amount + oldMaterial).toFloat()
                interpolatedMaterialOpacity = mixedMaterial
                isErase = clearBehind && mixedMaterial <= 0f
                val oldDrawing = if (mode == SymbolRenderingMode.Hierarchical)
                    SymbolColors.hierarchyOpacity(from.optInt("hierarchical", 0)) else 1f
                val newDrawing = if (mode == SymbolRenderingMode.Hierarchical)
                    SymbolColors.hierarchyOpacity(to.optInt("hierarchical", 0)) else 1f
                interpolatedDrawingOpacity = ((newDrawing - oldDrawing).toDouble() * amount + oldDrawing).toFloat()
            }
        }
        val threshold = if (variableValue != null) modeData.variableThresholds[source] else null
        val variableActive = if (threshold != null && variableValue != null) {
            SymbolTiming.variableColorActive(variableValue.coerceIn(0f, 1f), threshold)
        } else null
        val activeTrack = variableAnimation?.let { animation ->
            val repeatCount = effectRepeatCount.takeIf { continuous }
            variableTracks.getOrBuild(VariableTrackKey(mode, animation, continuous, repeatCount)) {
                val ordered = modeData.variableThresholds.entries.sortedBy { it.value }
                IdentityHashMap<JSONObject, VariableColorTrack>().apply {
                    ordered.forEachIndexed { index, entry ->
                        put(entry.key, if (continuous && animation.iterative && !animation.bounces) {
                            if (animation.willLoop) motionTiming.loopingIterativeVariableColorTrack(index, ordered.size, repeatCount)
                            else motionTiming.nonLoopingContinuousIterativeVariableColorTrack(index, ordered.size, repeatCount)
                        } else motionTiming.variableColorTrack(index, ordered.size, animation.iterative, animation.bounces, animation.willLoop,
                            repeatCount = repeatCount, continuous = continuous))
                    }
                }
            }[source]?.takeUnless { track -> continuous && time >= track.durationSeconds }
        }
        val activeFraction = if (nativeMaterial) null else activeTrack?.activeFraction(time)
        val pulseAmount = wholeSymbolPulseAmount ?: if ((effect == SymbolEffect.Pulse || effect == SymbolEffect.BreathePulse) &&
            (!modeData.hasPulseLayers || style.pulses)) {
            when (effect) {
                SymbolEffect.BreathePulse -> motionTiming.breathePulseAmount(if (continuous) motionTiming.periodTime(time, 3f, effectRepeatCount) else time, motionIndex, motionMaximum + 1)
                SymbolEffect.Pulse -> (1f - motionTiming.pulseOpacity(if (continuous) motionTiming.periodTime(time, 2f, effectRepeatCount) else time)) / .7f
            }
        } else 0f
        val semanticPulse = wholeSymbolPulseAmount == null && style.pulses && level >= 0
        val hierarchyOpacity = if (mode == SymbolRenderingMode.Hierarchical && (from == null || to == null)) {
            if (activeFraction != null) {
                val inactive = if (variableAnimation?.hideInactive == true) 0f else SymbolColors.hierarchyOpacity(2)
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
                palette[SymbolColors.paletteIndex(targetLevel, prepared.minimumHierarchy, palette.size).coerceAtLeast(0)]
            } else if (targetLevel == 1) secondary else tertiary
            color = (0..3).sumOf { channel ->
                val shift = channel * 8
                val old = (color ushr shift) and 255
                val new = (target ushr shift) and 255
                ((old + (new - old) * pulseAmount).toInt() shl shift)
            }
        }
        val pulseOpacity = if (semanticPulse && (mode == SymbolRenderingMode.Hierarchical || mode == SymbolRenderingMode.Palette)) 1f
            else 1f - .7f * pulseAmount
        val variableOpacity = if (mode == SymbolRenderingMode.Hierarchical) 1f else if (activeFraction != null) {
            val inactive = if (variableAnimation?.hideInactive == true) 0f else 0.3f
            inactive + (1f - inactive) * activeFraction
        } else if (variableActive == false) 0.3f else 1f
        paint.color = color
        val gradient = node.gradient
        val fillImage = if (mode == SymbolRenderingMode.Multicolor && !isErase) symbol.fillImages[colorName] else null
        paint.isFilterBitmap = fillImage != null
        paint.shader = if (fillImage != null) {
            val bounds = node.bounds
            val key = "image/$colorName/$darkTheme/${bounds.left}/${bounds.top}/${bounds.right}/${bounds.bottom}"
            gradientShaders.getOrBuild(key) {
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
            gradientShaders.getOrBuild(key) {
                val colors = SymbolColors.gradient(color)
                val points = SymbolColors.gradientPoints(angle, bounds.left, -bounds.bottom, bounds.right, -bounds.top)
                LinearGradient(points[0], -points[1], points[2], -points[3], colors, null, Shader.TileMode.CLAMP)
            }
        } else null
        val colorAlpha = if (paint.shader == null) AndroidColor.alpha(color) else 255
        val nativeOpacity = if (nativeMaterial) {
            var channels = SymbolLayerOpacity(common = replacement?.commonOpacity ?: 1f)
            if (variableActive != null) {
                channels = channels.multiplyBoth(if (variableActive) SymbolColors.hierarchyOpacity(0)
                    else if (mode == SymbolRenderingMode.Hierarchical) SymbolColors.hierarchyOpacity(2) else .3f)
            } else if (interpolatedDrawingOpacity != null) {
                channels = channels.multiplyDrawing(interpolatedDrawingOpacity)
            } else if (mode == SymbolRenderingMode.Hierarchical && (from == null || to == null)) {
                channels = channels.multiplyDrawing(SymbolColors.hierarchyOpacity(level))
            }
            if (activeTrack != null) {
                val inactive = if (variableAnimation.hideInactive) 0f
                    else if (mode == SymbolRenderingMode.Hierarchical) SymbolColors.hierarchyOpacity(2) else .3f
                channels = activeTrack.applyOpacity(channels, time, inactive)
            }
            channels
                .multiplyDrawing(inheritedOpacity, hideErasingAtZero = true)
                .multiplyDrawing(layerOpacity, hideErasingAtZero = true)
                .multiplyDrawing(replacement?.opacity ?: 1f, hideErasingAtZero = true)
                .multiplyDrawing(drawSchedule?.opacity(source, time) ?: 1f, hideErasingAtZero = true)
                .let { if (pulseAmount == 0f) it else it.pulse(1f - pulseAmount, byLayer = wholeSymbolPulseAmount == null,
                    hasExplicitPulseLayers = modeData.hasPulseLayers,
                    hierarchical = mode == SymbolRenderingMode.Hierarchical,
                    currentHierarchyOpacity = SymbolColors.hierarchyOpacity(level),
                    nextHierarchyOpacity = SymbolColors.hierarchyOpacity(if (level >= 2) level - 1 else level + 1)) }
        } else null
        val materialOpacity = if (nativeMaterial) interpolatedMaterialOpacity ?: style.materialOpacity
            else if (isErase) 0f else (explicitOpacity / 100.0).toFloat()
        val fillAlpha = if (nativeOpacity != null) {
            (colorAlpha * nativeOpacity.drawingOpacity(materialOpacity)).toInt().coerceIn(0, 255)
        } else {
            (colorAlpha * (replacement?.opacity ?: 1f) * (drawSchedule?.opacity(source, time) ?: 1f) * inheritedOpacity * layerOpacity * hierarchyOpacity * variableOpacity * pulseOpacity * explicitOpacity / 100.0)
                .toInt().coerceIn(0, 255)
        }
        paint.alpha = fillAlpha
        paint.xfermode = if (isErase) erase else if (style.clearBehind) copy else null
        val progress = replacement?.drawProgress ?: drawSchedule?.progress(source, time) ?: drawProgress
        val path = replacement?.pathOverride ?: if (progress == null || progress.isFinite() && !node.drawAnnotated) node.path else {
            symbol.draw.pathFor(source, node.path, progress, isOn = replacement?.drawOn ?: drawOn,
                isReverse = drawReversed, removeOtherSubpaths = !source.has("systemClipStrokeKeyframes"), pointSize = pointSize,
                systemDrawState = replacement?.systemDrawState ?: systemDrawState, systemMode = systemStyleMode(mode),
                adaptiveCaps = adaptiveCaps && source.has("systemClipStrokeKeyframes"),
                recoverMissingAttachmentAnchors = recoverMissingAttachmentAnchors, reuseSourcePath = true)
                ?: error("Unsupported Draw geometry for ${symbol.name}")
        }
        val variableProgress = if (progress == null && replacement?.pathOverride == null && variableDrawValue != null) {
            modeData.variableDraw.progress(source, variableDrawValue)
        } else null
        fun drawMaterial(path: Path, channelScale: Float = 1f) {
            if (nativeOpacity == null) {
                paint.alpha = (fillAlpha * channelScale).toInt()
                canvas.drawPath(path, paint)
                return
            }
            val channels = nativeOpacity.multiplyBoth(channelScale)
            val coverage = channels.erasingOpacity(materialOpacity, clearBehind).coerceIn(0f, 1f)
            val alpha = (colorAlpha * channels.drawingOpacity(materialOpacity)).toInt().coerceIn(0, 255)
            if (coverage == 1f) {
                paint.alpha = alpha
                paint.xfermode = copy
                canvas.drawPath(path, paint)
            } else {
                if (coverage > 0f) {
                    val shader = paint.shader
                    val fillColor = paint.color
                    paint.shader = null
                    paint.color = AndroidColor.BLACK
                    paint.alpha = (coverage * 255f).toInt()
                    paint.xfermode = erase
                    canvas.drawPath(path, paint)
                    paint.color = fillColor
                    paint.shader = shader
                }
                if (alpha > 0) {
                    paint.alpha = alpha
                    paint.xfermode = null
                    canvas.drawPath(path, paint)
                }
            }
        }
        transforms.withShader(paint.shader) {
            if (variableProgress != null) {
                val active = symbol.draw.pathFor(source, node.path, variableProgress, isOn = true,
                    removeOtherSubpaths = false, pointSize = pointSize, systemMode = systemStyleMode(mode),
                    adaptiveCaps = adaptiveCaps && source.has("systemClipStrokeKeyframes"),
                    recoverMissingAttachmentAnchors = recoverMissingAttachmentAnchors)
                    ?: error("Unsupported Variable Draw geometry for ${symbol.name}")
                val inactive = Path(node.path).apply { op(active, Path.Op.DIFFERENCE) }
                drawMaterial(inactive, .3f)
                drawMaterial(active)
            } else {
                drawMaterial(path)
            }
        }
        paint.xfermode = null
        if (checkpoint != null) canvas.restoreToCount(checkpoint)
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
        if (scale != 1f) canvas.scale(scale, scale, pivotX, pivotY)
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

internal inline fun <K : Any, V : Any> LruCache<K, V>.getOrBuild(key: K, build: () -> V): V =
    get(key) ?: build().also { put(key, it) }
