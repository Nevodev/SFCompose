package com.nevoit.sfcompose.symbols

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.IntSize
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

/** Retains one icon's playback while its composable is absent. Hoist outside a lazy item to resume it. */
@Stable
class SymbolAnimationState {
    private var time by mutableFloatStateOf(0f)
    internal var replayGeneration by mutableIntStateOf(0)
        private set
    private var playbackKey: List<Any?>? = null
    private var remainingDelayNanos = 0L
    private var attached = false
    internal var loadedKey: Any? = null
    internal var loadedSymbol: SymbolInstance? = null
    internal var measuredSlot: IntSize? = null
    internal var hasStarted by mutableStateOf(false)
    internal var initialReplayKey: Any? = null
    internal var replayKeyInitialized = false

    val elapsedSeconds: Float get() = time

    /** Restarts this state's animation, including its start delay, on the next composition. */
    fun replay() { replayGeneration++ }

    internal suspend fun play(key: List<Any?>, duration: Float, startDelayMillis: Long) {
        check(!attached) { "A SymbolAnimationState can only be attached to one AnimatedSFSymbol at a time" }
        attached = true
        try {
            if (playbackKey != key) {
                playbackKey = key
                time = 0f
                remainingDelayNanos = startDelayMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000) * 1_000_000
            }
            if (duration <= 0f || time >= duration) return
            if (remainingDelayNanos > 0L) {
                val start = System.nanoTime()
                try {
                    delay(remainingDelayNanos / 1_000_000 + if (remainingDelayNanos % 1_000_000 > 0) 1 else 0)
                    remainingDelayNanos = 0L
                } finally {
                    remainingDelayNanos = (remainingDelayNanos - (System.nanoTime() - start)).coerceAtLeast(0L)
                }
            }
            val offset = time
            val start = withFrameNanos { it }
            do {
                time = withFrameNanos { (offset + (it - start) / 1_000_000_000f).coerceAtMost(duration) }
            } while (time < duration)
        } finally {
            attached = false
        }
    }
}

@Composable
fun rememberSymbolAnimationState(): SymbolAnimationState = remember { SymbolAnimationState() }

@Composable
internal fun rememberSymbolAnimationTime(
    duration: Float, vararg replayKeys: Any?, startDelayMillis: Long = 0,
    state: SymbolAnimationState = rememberSymbolAnimationState(),
): () -> Float {
    val generation = state.replayGeneration
    val key = listOf(duration, startDelayMillis, generation, *replayKeys)
    LaunchedEffect(state, key) {
        state.play(key, duration, startDelayMillis)
    }
    return remember(state) { { state.elapsedSeconds } }
}
