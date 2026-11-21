package com.nevoit.sfcompose.symbols

/**
 * Motion and opacity effects for [SymbolAnimation] or caller-controlled [SFSymbol] rendering.
 *
 * Bounce, Pulse, Breathe, Rotate and Wiggle support continuous playback. Appear, Disappear and
 * Scale use their discrete timing even when Continuous is requested. Forward/Backward Wiggle
 * follows layout direction, while Left/Right is literal. Source annotations determine layer motion.
 * Draw On/Off and Variable Color are configured separately with [SymbolAnimation.drawEffect]
 * and [SymbolAnimation.variableColor]. None leaves this motion/opacity channel unchanged.
 */
enum class SymbolEffect {
    None,
    Pulse,
    Breathe,
    BreathePulse,
    Bounce,
    BounceDown,
    RotateClockwise,
    RotateCounterclockwise,
    Wiggle,
    WiggleUp,
    WiggleDown,
    WiggleLeft,
    WiggleRight,
    WiggleForward,
    WiggleBackward,
    WiggleClockwise,
    WiggleCounterclockwise,
    Appear,
    AppearDown,
    Disappear,
    DisappearUp,
    ScaleUp,
    ScaleDown,
}

/** Timing policy; lower-level renderers still require a caller-owned clock. */
enum class SymbolEffectPlayback {
    /** Plays one effect timeline, then keeps its final frame. */
    Discrete,
    /** Uses continuous timing for supported effects, with optional finite cycles and intro/outro. */
    Continuous,
}

internal object SymbolTiming {
    private val wiggleOffsets = floatArrayOf(0.06f, -0.04f, 0.03f, -0.025f, 0f)
    private val wiggleAngles = floatArrayOf(10f, -8f, 6f, -5f, 0f)

    fun appearanceFrame(
        elapsedSeconds: Float,
        appear: Boolean,
        down: Boolean,
        groupIndex: Int = 0,
        maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false,
    ): SymbolAppearanceFrame {
        val maximum = maxGroupIndex.coerceAtLeast(0)
        val index = groupIndex.coerceIn(0, maximum)
        val ordinal = if (!appear && down) maximum - index else index
        val delay = if (wholeSymbol) 0f else ordinal * minOf(
            (if (appear) .15f else .1f) / maximum.coerceAtLeast(1),
            if (appear) .05f else .0333333333f,
        )
        val duration = (if (appear) .25f else .166666667f) - delay
        // The downward Appear recipe shortens the segment without prepending a hold.
        val start = if (appear && down) 0f else delay
        val progress = ((elapsedSeconds - start) / duration).coerceIn(0f, 1f)
        val opacity = cubicBezier(progress, .33f, 0f, .67f, 1f)
        val scaleProgress = if (appear) cubicBezier(progress, .33f, 0f, .2f, 1f)
            else cubicBezier(progress, .33f, 0f, .83f, .83f)
        val endScale = if (down) .75f else 1.25f
        return if (appear) {
            val initialScale = if (down) 1.25f else .75f
            SymbolAppearanceFrame(opacity, initialScale + (1f - initialScale) * scaleProgress)
        } else SymbolAppearanceFrame(1f - opacity, 1f + (endScale - 1f) * scaleProgress)
    }

    fun scaleEffect(
        elapsedSeconds: Float,
        down: Boolean = false,
        groupIndex: Int = 0,
        maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false,
    ): Float {
        val maximum = maxGroupIndex.coerceAtLeast(0)
        val index = groupIndex.coerceIn(0, maximum)
        val delay = if (wholeSymbol) 0f else (maximum - index) * minOf(.2f / (maximum + 1), .05f)
        val duration = .25f - delay
        val target = if (down) .5f else 1.25f
        val riseStart = if (down) delay else 0f
        val fallStart = if (down) .75f else .75f + delay
        val time = elapsedSeconds.coerceAtLeast(0f)
        return when {
            time < riseStart -> 1f
            time < riseStart + duration -> {
                val progress = (time - riseStart) / duration
                val fraction = if (down) cubicBezier(progress, .75f, 0f, .65f, 1f)
                    else cubicBezier(progress, .33f, 0f, .67f, 1f)
                1f + (target - 1f) * fraction
            }
            time < fallStart -> target
            time < fallStart + duration -> {
                val progress = (time - fallStart) / duration
                val fraction = if (down) cubicBezier(progress, .35f, 0f, .25f, 1f)
                    else cubicBezier(progress, .33f, 0f, .67f, 1f)
                target + (1f - target) * fraction
            }
            else -> 1f
        }
    }

    fun cubicBezier(
        progress: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Float {
        val x = progress.coerceIn(0f, 1f).toDouble()
        if (x == 0.0 || x == 1.0) return x.toFloat()
        val ax = 1.0 - 3.0 * x2 + 3.0 * x1
        val bx = 3.0 * x2 - 6.0 * x1
        val cx = 3.0 * x1
        var low = 0.0
        var high = 1.0
        var t = x
        repeat(24) {
            val estimate = t * (cx + t * (bx + t * ax))
            val error = estimate - x
            if (kotlin.math.abs(error) < 0.00001) {
                return bezierValue(t, y1.toDouble(), y2.toDouble()).toFloat()
            }
            if (error < 0.0) low = t else high = t
            val derivative = cx + t * (2.0 * bx + 3.0 * ax * t)
            val candidate = if (kotlin.math.abs(derivative) > 0.000001) {
                t - error / derivative
            } else {
                Double.NaN
            }
            t = if (candidate > low && candidate < high) candidate else (low + high) * 0.5
        }
        return bezierValue(t, y1.toDouble(), y2.toDouble()).toFloat()
    }

    fun bounceScale(
        elapsedSeconds: Float,
        down: Boolean = false,
        groupIndex: Int = 0,
        maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false,
    ): Float {
        val maxIndex = maxGroupIndex.coerceAtLeast(0)
        val index = groupIndex.coerceIn(0, maxIndex)
        val orderedIndex = if (down) maxIndex - index else index
        val delay = if (wholeSymbol) 0.0 else {
            orderedIndex * minOf(0.15 / maxIndex.coerceAtLeast(1), 0.05)
        }
        val time = elapsedSeconds.toDouble()
        val firstDuration = 13.0 / 60.0
        val secondDuration = 13.0 / 60.0
        val lastDuration = 0.266666667 - delay
        if (time <= delay || time >= 0.700000001) return 1f
        val firstTarget = if (down) 0.75 else 1.25
        val secondTarget = if (down) 1.109 else 0.909
        val local = time - delay
        return when {
            local < firstDuration -> interpolate(
                1.0, firstTarget, local / firstDuration, 0.55f, 0.67f,
            )
            local < firstDuration + secondDuration -> interpolate(
                firstTarget, secondTarget,
                (local - firstDuration) / secondDuration, 0.45f, 0.67f,
            )
            else -> interpolate(
                secondTarget, 1.0,
                (local - firstDuration - secondDuration) / lastDuration, 0.25f, 0.55f,
            )
        }
    }

    fun breatheScale(
        elapsedSeconds: Float,
        groupIndex: Int = 0,
        groupCount: Int = 1,
        wholeSymbol: Boolean = false,
    ): Float {
        val count = groupCount.coerceAtLeast(1)
        val index = groupIndex.coerceIn(0, count - 1)
        val step = if (count > 4) 0.5 / (count - 1) else 0.167
        val delay = if (wholeSymbol) 0.0 else index * step
        val halfDuration = if (wholeSymbol) 1.5 else {
            if (count > 4) 1.25 else (3.0 - (count - 1) * 0.167) * 0.5
        }
        val local = elapsedSeconds.toDouble() - delay
        return when {
            local <= 0.0 || local >= halfDuration * 2.0 -> 1f
            local < halfDuration -> interpolate(1.0, 1.2, local / halfDuration, 0.4f, 0.6f)
            else -> interpolate(1.2, 1.0, (local - halfDuration) / halfDuration, 0.4f, 0.6f)
        }
    }

    fun breathePulseAmount(
        elapsedSeconds: Float,
        groupIndex: Int = 0,
        groupCount: Int = 1,
        wholeSymbol: Boolean = false,
    ): Float {
        val count = groupCount.coerceAtLeast(1)
        val index = groupIndex.coerceIn(0, count - 1)
        val step = if (count > 4) .5f / (count - 1) else .167f
        val delay = if (wholeSymbol) 0f else index * step
        val halfDuration = if (wholeSymbol) 1.5f else if (count > 4) 1.25f
            else (3f - (count - 1) * .167f) * .5f
        val local = elapsedSeconds - delay
        return when {
            local <= 0f || local >= halfDuration * 2f -> 0f
            local < halfDuration -> cubicBezier(local / halfDuration, .33f, 0f, .67f, 1f)
            else -> 1f - cubicBezier((local - halfDuration) / halfDuration, .33f, 0f, .67f, 1f)
        }
    }

    fun pulseOpacity(elapsedSeconds: Float, durationSeconds: Float = 2f): Float {
        if (!durationSeconds.isFinite() || durationSeconds <= 0f) return 1f
        val halfDuration = durationSeconds.toDouble() * 0.5
        val time = elapsedSeconds.toDouble()
        return when {
            time <= 0.0 || time >= durationSeconds -> 1f
            time < halfDuration -> interpolate(1.0, 0.3, time / halfDuration, 0.33f, 0.67f)
            else -> interpolate(0.3, 1.0, (time - halfDuration) / halfDuration, 0.33f, 0.67f)
        }
    }

    fun rotateDegrees(
        elapsedSeconds: Float,
        clockwise: Boolean = true,
        groupIndex: Int = 0,
        groupCount: Int = 1,
        wholeSymbol: Boolean = false,
    ): Float {
        val count = groupCount.coerceAtLeast(1)
        val index = groupIndex.coerceIn(0, count - 1)
        val stagger = if (wholeSymbol) 0f else minOf(8f * (count - 1), 16f) / 60f
        val delay = stagger / count * index
        val progress = ((elapsedSeconds - delay) / (2f - stagger)).coerceIn(0f, 1f)
        val rotation = 360f * cubicBezier(progress, 0.25f, 0.25f, 0.25f, 1f)
        return if (clockwise) rotation else -rotation
    }

    fun continuousRotateDegrees(
        elapsedSeconds: Float,
        clockwise: Boolean = true,
        groupIndex: Int = 0,
        groupCount: Int = 1,
        wholeSymbol: Boolean = false,
    ): Float {
        val time = elapsedSeconds.toDouble().coerceAtLeast(0.0)
        val count = groupCount.coerceAtLeast(1)
        val index = groupIndex.coerceIn(0, count - 1)
        val step = if (count > 4) 0.25 / count else 0.0835
        val delay = if (wholeSymbol) 0.0 else index * step
        val startupDuration = if (wholeSymbol) 2.5 else 3.1665 - step
        val degrees = if (time <= startupDuration) {
            if (wholeSymbol) {
                360f * cubicBezier((time / 2.5).toFloat(), 0.2f, 0f, 0.6f, 0.5f)
            } else {
                val local = time - delay
                when {
                    local <= 0.0 -> 0f
                    local <= 0.6665 -> 60.7f * cubicBezier(
                        (local / 0.6665).toFloat(), 0.3f, 0f, 0.83f, 0.7f,
                    )
                    else -> 60.7f + 299.3f * cubicBezier(
                        ((local - 0.6665) / (2.5 - delay - step)).toFloat(),
                        0.17f, 0.15f, 0.85f, 0.82f,
                    )
                }
            }
        } else {
            val local = (time - startupDuration) % 2.0
            360f * cubicBezier((local / 2.0).toFloat(), 0.17f, 0.17f, 0.83f, 0.83f)
        }
        return if (clockwise) degrees else -degrees
    }

    fun wiggleOffsetFraction(
        elapsedSeconds: Float,
        groupIndex: Int = 0,
        maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false,
    ): Float = wiggleValue(
        elapsedSeconds, groupIndex, maxGroupIndex, wholeSymbol,
        wiggleOffsets,
    )

    fun wiggleDegrees(
        elapsedSeconds: Float,
        clockwise: Boolean = true,
        groupIndex: Int = 0,
        maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false,
    ): Float {
        val value = wiggleValue(
            elapsedSeconds, groupIndex, maxGroupIndex, wholeSymbol,
            wiggleAngles,
        )
        return if (clockwise) value else -value
    }

    fun variableThreshold(index: Int, count: Int, reversed: Boolean = false): Float {
        val size = count.coerceAtLeast(1)
        val position = index.coerceIn(0, size - 1)
        val ordered = if (reversed) size - 1 - position else position
        val fraction = ordered.toFloat() / size
        val rounded = kotlin.math.floor(fraction * 100f + 0.5f).toInt()
        return if (rounded == 0) fraction else (rounded + 1) / 100f
    }

    fun variableColorActive(value: Float, threshold: Float): Boolean =
        if (threshold == 0f) value > threshold else value >= threshold

    fun continuousBounceScale(
        elapsedSeconds: Float,
        down: Boolean = false,
        groupIndex: Int = 0,
        maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false,
    ): Float {
        val maxIndex = maxGroupIndex.coerceAtLeast(0)
        val index = groupIndex.coerceIn(0, maxIndex)
        val ordered = if (down) maxIndex - index else index
        val delay = if (wholeSymbol) 0.0 else ordered * minOf(0.15 / maxIndex.coerceAtLeast(1), 0.05)
        val time = (elapsedSeconds.toDouble() - delay).coerceAtLeast(0.0)
        val duration = 13.0 / 60.0
        val first = if (down) 0.959 else 1.05
        val second = if (down) 1.05 else 0.959
        val firstCycle = time <= duration * 2
        val local = if (firstCycle) time else (time - duration * 2) % (duration * 2)
        return if (local <= duration) {
            interpolate(if (firstCycle) 1.0 else second, first, local / duration, 0.55f, 0.67f)
        } else {
            interpolate(first, second, (local - duration) / duration, 0.45f, 0.67f)
        }
    }

    fun continuousWiggleMagnitude(
        elapsedSeconds: Float,
        groupIndex: Int = 0,
        maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false,
    ): Float {
        val maxIndex = maxGroupIndex.coerceAtLeast(0)
        val index = groupIndex.coerceIn(0, maxIndex)
        val delay = if (wholeSymbol) 0.0 else index * minOf(0.15 / maxIndex.coerceAtLeast(1), 0.05)
        val time = (elapsedSeconds.toDouble() - delay).coerceAtLeast(0.0)
        val cycle = 0.334
        val firstCycle = time <= cycle
        val local = if (firstCycle) time else (time - cycle) % cycle
        val from: Float
        val to: Float
        val progress: Double
        val x1: Float
        val y1: Float
        val x2: Float
        val y2: Float
        when {
            local <= 0.0835 -> {
                from = 0f
                to = 1f
                progress = local / 0.0835
                x1 = if (firstCycle) 0.33f else 0.12f
                y1 = if (firstCycle) 0f else 0.17f
                x2 = 0.67f
                y2 = 1f
            }
            local <= 0.2505 -> {
                from = 1f
                to = -1f
                progress = (local - 0.0835) / 0.167
                x1 = 0.33f
                y1 = 0f
                x2 = 0.67f
                y2 = 1f
            }
            else -> {
                from = -1f
                to = 0f
                progress = (local - 0.2505) / 0.0835
                x1 = 0.33f
                y1 = 0f
                x2 = 0.88f
                y2 = 0.83f
            }
        }
        return from + (to - from) * cubicBezier(progress.toFloat(), x1, y1, x2, y2)
    }

    fun variableColorTrack(
        index: Int,
        count: Int,
        iterative: Boolean = false,
        bounces: Boolean = false,
        willLoop: Boolean = false,
    ): VariableColorTrack {
        val n = count.coerceAtLeast(1)
        val i = index.coerceIn(0, n - 1)
        val track = VariableColorTrack.Builder()
        if (!iterative) {
            val step = minOf(0.6667 / n, 0.3333)
            val delay = step * i
            val hold = 0.6667 - step - delay
            track.hold(delay)
            track.fade(1f, 0.3333)
            track.hold(hold)
            track.hold(0.3333)
            if (bounces) track.hold(hold)
            track.fade(0f, 0.3333)
            if (bounces) track.hold(delay)
            track.hold(0.3333)
        } else if (!bounces) {
            val step = if (n <= 3) 0.3333 else 0.08333
            val decay = if (n <= 3) 0.3333 else (n - 4) * 0.0333 + 0.333
            track.hold(i * step)
            track.fade(1f, step)
            track.fade(0f, decay)
            track.padTo(if (n <= 3) (n + 1) * step else n * step + decay)
            if (n > 3) track.scaleTo(1.333)
        } else {
            when (n) {
                1 -> {
                    track.initial = 1f
                    track.fade(0f, 0.3333)
                    track.fade(1f, 0.3333)
                }
                2 -> {
                    track.initial = if (i == 0) 1f else 0f
                    track.fade(1f - track.initial, 0.3333)
                    track.fade(track.initial, 0.3333)
                }
                3 -> when (i) {
                    0 -> {
                        track.initial = 1f
                        track.fade(0f, 0.3333)
                        track.hold(0.6666)
                        track.fade(1f, 0.3333)
                    }
                    1 -> repeat(2) {
                        track.fade(1f, 0.3333)
                        track.fade(0f, 0.3333)
                    }
                    else -> {
                        track.hold(0.3333)
                        track.fade(1f, 0.3333)
                        track.fade(0f, 0.3333)
                        track.hold(0.3333)
                    }
                }
                else -> {
                    val effectiveCount = n + if (willLoop) 1 else 0
                    val decay = (effectiveCount - 4) * 0.0333 + 0.333
                    val half = (effectiveCount - 2) * 0.08333 + decay
                    if (i == 0) {
                        track.initial = 1f
                        track.fade(0f, decay)
                        if (willLoop) {
                            track.padTo(half - decay)
                            track.fade(1f, 0.08333)
                            track.padTo(half)
                            track.fade(0f, decay)
                        }
                        track.padTo(2 * half - decay)
                        track.fade(1f, 0.08333)
                    } else if (i == effectiveCount - 1) {
                        track.hold(half - decay)
                        track.fade(1f, 0.08333)
                        track.hold(decay - 0.08333)
                        track.fade(0f, decay)
                    } else {
                        track.padTo((i - 1) * 0.08333)
                        track.fade(1f, 0.08333)
                        track.fade(0f, decay)
                        val remaining = half - track.duration
                        track.padTo(half)
                        // The native recipe appends this hold after padding to half.
                        track.hold(remaining)
                        track.fade(1f, 0.08333)
                        track.fade(0f, decay)
                    }
                    track.padTo(2 * half)
                    track.scaleTo(2.5)
                }
            }
        }
        return track.build()
    }

    fun loopingIterativeVariableColorTrack(index: Int, count: Int): VariableColorTrack {
        val n = count.coerceAtLeast(1)
        val i = index.coerceIn(0, n - 1)
        val factor = if (n >= 4) 1.5 / (n * 0.08333) else 1.0
        val rise = if (n >= 4) factor * 0.08333 else 0.3333
        val decay = if (n >= 4) factor * ((n - 4) * 0.0333 + 0.333) else 0.3333
        val track = VariableColorTrack.Builder()
        track.fade(1f, rise)
        track.fade(0f, decay)
        track.padTo(rise * n)
        return track.build(delay = rise * i, repeats = true)
    }

    fun nonLoopingContinuousIterativeVariableColorTrack(index: Int, count: Int): VariableColorTrack {
        val n = count.coerceAtLeast(1)
        val i = index.coerceIn(0, n - 1)
        val effectiveCount = n + 1
        val baseDecay = (n - 3) * 0.0333 + 0.333
        val factor = if (effectiveCount >= 4) 1.5 / (effectiveCount * 0.08333 + baseDecay) else 1.0
        val rise = if (effectiveCount >= 4) factor * 0.08333 else 0.3333
        val decay = if (effectiveCount >= 4) factor * baseDecay else 0.3333
        val envelope = decay + rise * effectiveCount
        val track = VariableColorTrack.Builder()
        track.hold(rise * i)
        track.fade(1f, rise)
        track.fade(0f, decay)
        if (i == 0) {
            // This signed native hold deliberately overlaps the first layer's second pass.
            track.signedHold(envelope - 2 * (rise + decay))
            track.fade(1f, rise)
            track.fade(0f, decay)
        }
        track.padTo(envelope + rise * i)
        return track.build()
    }

    private fun wiggleValue(
        elapsedSeconds: Float,
        groupIndex: Int,
        maxGroupIndex: Int,
        wholeSymbol: Boolean,
        targets: FloatArray,
    ): Float {
        val maxIndex = maxGroupIndex.coerceAtLeast(0)
        val index = groupIndex.coerceIn(0, maxIndex)
        val delay = if (wholeSymbol) 0.0 else index * minOf(0.2 / (maxIndex + 1), 0.05)
        var time = elapsedSeconds.toDouble() - delay
        if (time <= 0.0 || elapsedSeconds >= 0.935f) return 0f
        var from = 0.0
        for (segment in targets.indices) {
            val duration = if (segment == targets.lastIndex) 0.267 - delay else 0.167
            val target = targets[segment].toDouble()
            if (time <= duration) {
                return interpolate(from, target, time / duration, if (segment == 0) 0.55f else 0.33f, 0.67f)
            }
            time -= duration
            from = target
        }
        return 0f
    }

    private fun interpolate(
        from: Double,
        to: Double,
        progress: Double,
        x1: Float,
        x2: Float,
    ): Float {
        val fraction = cubicBezier(progress.toFloat(), x1, 0f, x2, 1f)
        return (from + (to - from) * fraction).toFloat()
    }

    private fun bezierValue(t: Double, first: Double, second: Double): Double =
        t * (3.0 * first + t * (3.0 * second - 6.0 * first + t * (1.0 - 3.0 * second + 3.0 * first)))
}

internal data class SymbolAppearanceFrame(val opacity: Float, val scale: Float)

internal class VariableColorTrack private constructor(
    private val initial: Float,
    private val segments: List<Segment>,
    val durationSeconds: Float,
    private val delay: Double,
    private val repeats: Boolean,
    private val system: Boolean,
    private val maximum: VariableColorTrack? = null,
    private val sampler: ((Float) -> Float)? = null,
    private val interpolatesBaseline: ((Float) -> Boolean)? = null,
) {
    private val period = segments.sumOf { it.duration }

    fun activeFraction(elapsedSeconds: Float): Float =
        maxOf(sampler?.invoke(elapsedSeconds) ?: sample(elapsedSeconds),
            maximum?.activeFraction(elapsedSeconds) ?: Float.NEGATIVE_INFINITY)

    fun applyOpacity(channels: SymbolLayerOpacity, elapsedSeconds: Float, inactive: Float): SymbolLayerOpacity {
        val fraction = activeFraction(elapsedSeconds)
        // Native intro/outro uses property 10; the main tracks use property 8
        // followed by optional property 9, replacing both presentation channels.
        if (interpolatesBaseline?.invoke(elapsedSeconds) == true)
            return channels.lerpBothToward(inactive, 1f - fraction)
        val value = ((1f - inactive).toDouble() * fraction.toDouble() + inactive.toDouble()).toFloat()
        return channels.copy(drawing = value, erasing = value)
    }

    internal fun withMaximum(other: VariableColorTrack): VariableColorTrack =
        VariableColorTrack(initial, segments, maxOf(durationSeconds, other.durationSeconds), delay,
            repeats, system, maximum?.withMaximum(other) ?: other, sampler, interpolatesBaseline)

    companion object {
        internal fun systemSampler(durationSeconds: Float, interpolatesBaseline: ((Float) -> Boolean)? = null,
            sampler: (Float) -> Float): VariableColorTrack =
            VariableColorTrack(0f, emptyList(), durationSeconds, 0.0, false, true, sampler = sampler,
                interpolatesBaseline = interpolatesBaseline)
    }

    private fun sample(elapsedSeconds: Float): Float {
        var time = (elapsedSeconds.toDouble() - delay).coerceAtLeast(0.0)
        if (repeats) time %= period
        var value = initial
        for (segment in segments) {
            if (time <= segment.duration) {
                val target = segment.target ?: return value
                val progress = if (system) SymbolSystemTiming.curve(1,
                    SystemFloatTiming.progress(time.toFloat(), segment.duration.toFloat()))
                else SymbolTiming.cubicBezier((time / segment.duration).toFloat(), 0.33f, 0f, 0.67f, 1f)
                return value + (target - value) * progress
            }
            time -= segment.duration
            segment.target?.let { value = it }
        }
        return value
    }

    private data class Segment(val duration: Double, val target: Float?)

    class Builder(private val system: Boolean = false) {
        var initial: Float = 0f
        var duration: Double = 0.0
            private set
        private val segments = mutableListOf<Segment>()

        fun hold(seconds: Double) {
            if (seconds <= 0.0) return
            segments += Segment(seconds, null)
            duration += seconds
        }

        fun signedHold(seconds: Double) {
            segments += Segment(seconds, null)
            duration += seconds
        }

        fun fade(target: Float, seconds: Double) {
            segments += Segment(seconds, target)
            duration += seconds
        }

        fun padTo(seconds: Double) = hold(seconds - duration)

        fun scaleTo(seconds: Double) {
            val factor = seconds / duration
            for (i in segments.indices) segments[i] = segments[i].copy(duration = segments[i].duration * factor)
            duration = seconds
        }

        fun build(delay: Double = 0.0, repeats: Boolean = false): VariableColorTrack =
            VariableColorTrack(initial, segments.toList(), duration.toFloat(), delay, repeats, system)
    }
}
