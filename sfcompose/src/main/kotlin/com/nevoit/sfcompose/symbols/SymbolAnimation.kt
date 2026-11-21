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

/**
 * Effects played by [AnimatedSFSymbol] after loading or an explicit replay trigger.
 *
 * The default configuration is stationary. Motion, Draw and Variable Color may be combined;
 * playback lasts until the longest configured effect finishes. For example:
 *
 * ```kotlin
 * SymbolAnimation(effect = SymbolEffect.Bounce)
 * SymbolAnimation(drawEffect = SymbolDrawEffect.On, startDelayMillis = 500)
 * SymbolAnimation(effect = SymbolEffect.Pulse, playback = SymbolEffectPlayback.Continuous)
 * ```
 *
 * @property effect Motion or opacity effect; None disables this channel.
 * @property drawEffect Timed Draw On/Off, or `null` to disable. Requires source Draw data.
 * @property variableColor Variable Color animation options, or `null` to disable this channel.
 * @property playback Discrete plays once; Continuous uses repeatable timing for supported effects.
 * @property layerMode How motion and Draw are applied to the symbol's source layers.
 * @property repeatCount Positive continuous cycle count, or `null` for unbounded continuous playback.
 *   Finite continuous motion includes its intro and outro. This applies to continuous motion and
 *   Variable Color; it does not repeat discrete motion or timed Draw On/Off.
 * @property drawReversed Selects the source's reverse Draw geometry and ordering.
 * @property drawReversesMotionGroups Reverses Draw motion-group choreography independently of geometry.
 * @property startDelayMillis Nonnegative delay after loading or replay. Holds the time-zero animation
 *   frame, which may be invisible for Draw On. Repeated cycles do not repeat this delay. Use
 *   `autoPlay = false` on [AnimatedSFSymbol] to show the resting symbol until explicitly triggered.
 * @throws IllegalArgumentException If [repeatCount] is nonpositive or [startDelayMillis] is negative.
 */
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

/**
 * Playback state for one [AnimatedSFSymbol], including its clock, start delay and last loaded symbol.
 *
 * Create with [rememberSymbolAnimationState] and hoist above a lazy item to pause while the icon
 * is absent and resume on reentry. Completed playback keeps its final frame. [replay] starts again,
 * including when requested while the icon is absent. One state may be attached to only one icon
 * at a time; use a separate state for each icon.
 *
 * State lasts for its owner's composition lifetime and is not restored after process death.
 */
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

    /** Animation time in seconds, excluding the start delay; pauses while the icon is absent. */
    val elapsedSeconds: Float get() = time

    /**
     * Requests playback from time zero, including the configured start delay, on the next composition.
     *
     * Also triggers an icon with `autoPlay = false`. If the icon is absent, the request is retained
     * until it reenters composition. Calling this does not synchronously reset [elapsedSeconds].
     */
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

/**
 * Remembers playback state for one [AnimatedSFSymbol] for the lifetime of this composition.
 *
 * Call above a lazy list item to retain playback when that item leaves composition. Pass the result
 * as the icon's `state` and call [SymbolAnimationState.replay] from a click handler to replay it.
 */
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
