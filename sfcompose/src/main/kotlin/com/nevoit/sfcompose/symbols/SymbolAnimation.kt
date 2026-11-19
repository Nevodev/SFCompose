package com.nevoit.sfcompose.symbols

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/** Describes what plays when the symbol loads or the [AnimatedSFSymbol] replay key changes. */
data class SymbolAnimation(
    val effect: SymbolEffect = SymbolEffect.None,
    val drawEffect: SymbolDrawEffect? = null,
    val variableColor: SymbolVariableAnimation? = null,
    val playback: SymbolEffectPlayback = SymbolEffectPlayback.Discrete,
    val layerMode: SymbolEffectLayerMode = SymbolEffectLayerMode.ByLayer,
    val repeatCount: Int? = null,
    val drawReversed: Boolean = false,
    val drawReversesMotionGroups: Boolean = false,
    val startDelayMillis: Long = 0,
) {
    init {
        require(repeatCount == null || repeatCount > 0) { "Animation repeat count must be positive" }
        require(startDelayMillis >= 0) { "Animation start delay must not be negative" }
    }
}

internal fun SymbolAnimation.duration(symbol: SymbolInstance, mode: SymbolRenderingMode, pointSize: Float): Float {
    val resolvedMode = symbol.prepared.resolveMode(mode)
    val continuous = playback == SymbolEffectPlayback.Continuous
    val timing = SymbolMotionTiming(symbol.metadata.optString("sourceKind").startsWith("macOS-system"))
    val motion = if (continuous && effect.hasContinuousPlayback()) {
        if (repeatCount == null) return Float.POSITIVE_INFINITY
        val groups = symbolMotionGroups(symbol.nodes, resolvedMode).maxGroupIndex + 1
        timing.continuousDuration(effect, repeatCount, groups, wholeSymbol = layerMode == SymbolEffectLayerMode.WholeSymbol)
    } else effect.discreteDuration()
    val drawing = drawEffect?.let {
        SymbolDrawTiming(symbol, it == SymbolDrawEffect.On, reverse = drawReversed, layerMode = layerMode,
            mode = resolvedMode, drawReversesMotionGroups = drawReversesMotionGroups, pointSize = pointSize).durationSeconds
    } ?: 0f
    val variable = variableColor?.let { animation ->
        val count = symbol.prepared.mode(resolvedMode).variableThresholds.size
        (0 until count).maxOfOrNull { index ->
            val track = if (continuous && animation.iterative && !animation.bounces) {
                if (animation.willLoop) timing.loopingIterativeVariableColorTrack(index, count, repeatCount)
                else timing.nonLoopingContinuousIterativeVariableColorTrack(index, count, repeatCount)
            } else timing.variableColorTrack(index, count, animation.iterative, animation.bounces,
                animation.willLoop, repeatCount, continuous)
            track.durationSeconds
        } ?: 0f
    } ?: 0f
    return maxOf(motion, drawing, variable)
}

internal val StationarySymbolClock: () -> Float = { 0f }

@Composable
internal fun rememberSymbolAnimationTime(
    duration: Float, vararg replayKeys: Any?, startDelayMillis: Long = 0,
): () -> Float {
    if (duration <= 0f) return StationarySymbolClock
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(duration, startDelayMillis, *replayKeys) {
        // Reset the state already observed by drawing before suspending for a delayed replay.
        time.floatValue = 0f
        delay(startDelayMillis)
        val start = withFrameNanos { it }
        do {
            time.floatValue = withFrameNanos { ((it - start) / 1_000_000_000f).coerceAtMost(duration) }
        } while (time.floatValue < duration)
    }
    return remember(time) { { time.floatValue } }
}
