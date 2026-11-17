package com.nevoit.sfcompose.symbols

import kotlin.math.abs

/** Recipes emitted by the system RenderBox renderer, in seconds and font coordinates. */
internal object SymbolSystemTiming {
    private val bounceUp = floatArrayOf(1f, 1.25f, .909f, 1f)
    private val bounceDown = floatArrayOf(1f, .75f, 1.109f, 1f)
    private val bouncePresets = intArrayOf(3, 4, 5)
    private val wiggleOffsets = floatArrayOf(0f, .06f, -.04f, .03f, -.025f, 0f)
    private val wiggleRadians = floatArrayOf(0f, .1745329201221466f, -.13962633907794952f,
        .10471975803375244f, -.0872664600610733f, 0f)
    private val wigglePresets = intArrayOf(6, 7, 7, 7, 7)

    // The native table stores rounded polynomial coefficients, not just control points.
    private val coefficients = arrayOf(
        floatArrayOf(0f, 3f, -2f, 0f, 3f, -2f),
        floatArrayOf(.99f, .029999971389770508f, -.019999980926513672f, 0f, 3f, -2f),
        floatArrayOf(1.2000000476837158f, -.6000000238418579f, .3999999761581421f, 0f, 3f, -2f),
        floatArrayOf(1.6500000953674316f, -1.290000081062317f, .6399999856948853f, 0f, 3f, -2f),
        floatArrayOf(1.3499999046325684f, -.6899998188018799f, .3399999141693115f, 0f, 3f, -2f),
        floatArrayOf(.75f, .15000003576278687f, .09999996423721313f, 0f, 3f, -2f),
        floatArrayOf(1.6500000953674316f, -1.290000081062317f, .6399999856948853f, 0f, 3f, -2f),
        floatArrayOf(.99f, .029999971389770508f, -.019999980926513672f, 0f, 3f, -2f),
        floatArrayOf(.35999998450279236f, 1.290000081062317f, -.6500000953674316f,
            .5099999904632568f, 1.9800000190734863f, -1.4900000095367432f),
        floatArrayOf(.99f, .6599998474121094f, -.6499998569488525f, 0f, 2.490000009536743f, -1.4900000095367432f),
        floatArrayOf(.75f, -.75f, 1f, .75f, 1.5f, -1.25f),
        floatArrayOf(.99f, .6599998474121094f, -.6499998569488525f, 0f, 2.490000009536743f, -1.4900000095367432f),
        floatArrayOf(.5099999904632568f, 1.46999990940094f, -.9799998998641968f,
            .5099999904632568f, 1.46999990940094f, -.9799998998641968f),
        floatArrayOf(1.2000000476837158f, 0f, -.20000004768371582f, 1.5f, 0f, -.5f),
        floatArrayOf(.99f, .029999971389770508f, -.019999980926513672f, 0f, 3f, -2f),
        floatArrayOf(.99f, -1.380000114440918f, 1.3900001049041748f, 0f, 3f, -2f),
        floatArrayOf(.99f, .41999995708465576f, -.4099999666213989f, 0f, 2.0999999046325684f, -1.0999999046325684f),
    )

    internal fun curve(preset: Int, progress: Float): Float {
        val x = progress.coerceIn(0f, 1f)
        if (preset == 0) return x
        if (preset == 1 || preset == 7 || preset == 14) return x * x * (3f - 2f * x)
        val c = when (preset) {
            40 -> floatArrayOf(1.0499999523162842f, -1.3499999046325684f, 1.2999999523162842f, 0f, 3f, -2f)
            41 -> floatArrayOf(2.25f, -2.5500001907348633f, 1.3000001907348633f, 0f, 3f, -2f)
            else -> coefficients[preset]
        }
        val target = x.toDouble()
        val derivativeA = (3f * c[2]).toDouble()
        val derivativeB = (c[1] + c[1]).toDouble()
        var parameter = target
        fun value(t: Double): Float {
            val rounded = t.toFloat().toDouble()
            return (((c[5] * rounded + c[4]) * rounded + c[3]) * rounded).toFloat()
        }
        for (step in 0 until 8) {
            val error = ((parameter * c[2] + c[1]) * parameter + c[0]) * parameter - target
            if (abs(error) < .0010000000474974513) return value(parameter)
            val derivative = (parameter * derivativeA + derivativeB) * parameter + c[0]
            if (abs(derivative) < .000001) break
            parameter -= error / derivative
        }
        var low = 0.0
        var high = 1.0
        parameter = target
        repeat(1024) {
            val error = ((parameter * c[2] + c[1]) * parameter + c[0]) * parameter - target
            if (abs(error) < .0010000000474974513) return value(parameter)
            if (error < 0) low = parameter else high = parameter
            parameter = (low + high) * .5
        }
        return value(parameter)
    }

    private fun groupDelay(index: Int, maximum: Int, wholeSymbol: Boolean, total: Float = .15f): Float =
        if (wholeSymbol || maximum <= 0) 0f else index.coerceIn(0, maximum) * minOf(total / maximum, .05f)

    private fun interpolate(from: Float, to: Float, time: Float, duration: Float, preset: Int): Float =
        from + (to - from) * curve(preset, SystemFloatTiming.progress(time, duration))

    private fun sequence(time: Float, start: Float, values: FloatArray, presets: IntArray,
        duration: Float, lastDuration: Float): Float {
        var local = time - start
        if (local <= 0f) return values[0]
        for (index in presets.indices) {
            val segmentDuration = if (index == presets.lastIndex) lastDuration else duration
            if (local < segmentDuration) return interpolate(values[index], values[index + 1],
                local, segmentDuration, presets[index])
            local -= segmentDuration
        }
        return values.last()
    }

    fun appearanceFrame(elapsedSeconds: Float, appear: Boolean, down: Boolean, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false): SymbolAppearanceFrame {
        val maximum = maxGroupIndex.coerceAtLeast(0)
        val index = groupIndex.coerceIn(0, maximum)
        val step = if (wholeSymbol || maximum == 0) 0f else minOf(
            (if (appear) .15f else .1f) / maximum, if (appear) .05f else 1f / 30f)
        val forward = !down
        val delay = (if (forward) index else maximum - index) * step
        val duration = (if (appear) .25f else 1f / 6f) - delay
        val from = if (appear) { if (down) 1.25f else .75f } else 1f
        val to = if (appear) 1f else if (down) .75f else 1.25f
        return SymbolAppearanceFrame(
            interpolate(if (appear) 0f else 1f, if (appear) 1f else 0f,
                elapsedSeconds - delay, duration, 14),
            interpolate(from, to, elapsedSeconds - delay, duration, if (appear) 15 else 16))
    }

    fun bounceScale(elapsedSeconds: Float, down: Boolean = false, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false): Float {
        val delay = groupDelay(if (down) maxGroupIndex - groupIndex else groupIndex,
            maxGroupIndex, wholeSymbol)
        return sequence(elapsedSeconds, delay, if (down) bounceDown else bounceUp,
            bouncePresets, 13f / 60f, .2666666507720947f - delay)
    }

    fun scaleEffect(elapsedSeconds: Float, down: Boolean = false, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false): Float {
        val delay = groupDelay(maxGroupIndex - groupIndex, maxGroupIndex, wholeSymbol)
        val duration = .25f - delay
        val target = if (down) .5f else 1.25f
        val riseStart = if (down) delay else 0f
        val fallStart = .75f + if (down) 0f else delay
        return when {
            elapsedSeconds < riseStart -> 1f
            elapsedSeconds < riseStart + duration -> interpolate(1f, target,
                elapsedSeconds - riseStart, duration, if (down) 41 else 1)
            elapsedSeconds < fallStart -> target
            else -> interpolate(target, 1f, elapsedSeconds - fallStart, duration, if (down) 40 else 1)
        }
    }

    private fun breatheHalfDuration(count: Int, wholeSymbol: Boolean): Float {
        val maximum = count.coerceAtLeast(1) - 1
        val step = if (wholeSymbol || maximum == 0) 0f else minOf(.25f / maximum, .16667f)
        return (3f - step * maximum) * .5f
    }

    private fun breatheDelay(index: Int, count: Int, wholeSymbol: Boolean): Float {
        val maximum = count.coerceAtLeast(1) - 1
        return if (wholeSymbol || maximum == 0) 0f else
            index.coerceIn(0, maximum) * minOf(.25f / maximum, .16667f)
    }

    fun breatheScale(elapsedSeconds: Float, groupIndex: Int = 0, groupCount: Int = 1,
        wholeSymbol: Boolean = false): Float {
        val half = breatheHalfDuration(groupCount, wholeSymbol)
        val local = elapsedSeconds - breatheDelay(groupIndex, groupCount, wholeSymbol)
        return if (local < half) interpolate(1f, 1.2f, local, half, 2)
            else interpolate(1.2f, 1f, local - half, half, 2)
    }

    fun breathePulseAmount(elapsedSeconds: Float, groupIndex: Int = 0, groupCount: Int = 1,
        wholeSymbol: Boolean = false): Float {
        val half = breatheHalfDuration(groupCount, wholeSymbol)
        val local = elapsedSeconds - breatheDelay(groupIndex, groupCount, wholeSymbol)
        return if (local < half) curve(1, SystemFloatTiming.progress(local, half))
            else 1f - curve(1, SystemFloatTiming.progress(local - half, half))
    }

    fun pulseOpacity(elapsedSeconds: Float, durationSeconds: Float = 2f): Float {
        if (!durationSeconds.isFinite() || durationSeconds <= 0f) return 1f
        val half = durationSeconds * .5f
        return if (elapsedSeconds < half) interpolate(1f, .3f, elapsedSeconds, half, 1)
            else interpolate(.3f, 1f, elapsedSeconds - half, half, 1)
    }

    fun rotateDegrees(elapsedSeconds: Float, clockwise: Boolean = true, groupIndex: Int = 0,
        groupCount: Int = 1, wholeSymbol: Boolean = false): Float {
        val maximum = if (wholeSymbol) 0 else groupCount.coerceAtLeast(1) - 1
        val step = if (maximum == 0) 0f else minOf((4f / 15f) / maximum, 2f / 15f)
        val duration = 2f - minOf(maximum * (2f / 15f), 4f / 15f)
        val degrees = 360f * curve(10, SystemFloatTiming.progress(elapsedSeconds - step * groupIndex.coerceIn(0, maximum), duration))
        return if (clockwise) degrees else -degrees
    }

    fun continuousRotateDegrees(elapsedSeconds: Float, clockwise: Boolean = true, groupIndex: Int = 0,
        groupCount: Int = 1, wholeSymbol: Boolean = false, repeatCount: Int? = null): Float {
        if (repeatCount != null) {
            val outroTime = elapsedSeconds - 2.5f - 2f * repeatCount
            if (outroTime >= 2.5f) return 0f
            if (outroTime > 0f) {
                val degrees = 360f * curve(13, SystemFloatTiming.progress(outroTime, 2.5f))
                return if (clockwise) degrees else -degrees
            }
        }
        val maximum = if (wholeSymbol) 0 else groupCount.coerceAtLeast(1) - 1
        val degrees = if (elapsedSeconds < 2.5f) {
            if (maximum == 0) 360f * curve(11, SystemFloatTiming.progress(elapsedSeconds, 2.5f))
            else {
                val delay = groupIndex.coerceIn(0, maximum) * minOf(.25f / maximum, 1f / 12f)
                val local = elapsedSeconds - delay
                val first = .6666666865348816f
                val midpoint = 1.0594148635864258f * (180.0 / Math.PI).toFloat()
                if (local < first) interpolate(0f, midpoint, local, first, 11)
                else interpolate(midpoint, 360f, local - first, 1.8333332538604736f - delay, 12)
            }
        } else {
            val local = maxOf(elapsedSeconds - 2.5f, .0001f)
            360f * curve(12, SystemFloatTiming.progress(if (repeatCount == 1) local.coerceAtMost(2f)
                else cycleTime(local, 2f), 2f))
        }
        return if (clockwise) degrees else -degrees
    }

    fun wiggleOffsetFraction(elapsedSeconds: Float, groupIndex: Int = 0, maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false): Float {
        val delay = groupDelay(groupIndex, maxGroupIndex, wholeSymbol)
        return sequence(elapsedSeconds, delay, wiggleOffsets, wigglePresets, .167f, .2670000195503235f - delay)
    }

    fun wiggleDegrees(elapsedSeconds: Float, clockwise: Boolean = true, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false): Float {
        val delay = groupDelay(groupIndex, maxGroupIndex, wholeSymbol)
        val radians = sequence(elapsedSeconds, delay, wiggleRadians, wigglePresets, .167f, .2670000195503235f - delay)
        val degrees = radians * (180.0 / Math.PI).toFloat()
        return if (clockwise) degrees else -degrees
    }

    fun continuousBounceScale(elapsedSeconds: Float, down: Boolean = false, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false, repeatCount: Int? = null): Float {
        val duration = 13f / 60f
        val delay = continuousGroupDelay(if (down) maxGroupIndex - groupIndex else groupIndex,
            maxGroupIndex, wholeSymbol)
        val time = maxOf(elapsedSeconds - delay, .0001f)
        val first = if (down) .95f else 1.05f
        val second = if (down) 1.059f else .95f
        if (repeatCount != null) {
            val outroTime = time - 2 * duration - (2 * duration) * repeatCount
            if (outroTime > 0f) return interpolate(second, 1f, outroTime, 4f / 15f, 5)
        }
        val firstCycle = time < 2 * duration
        val mainTime = maxOf(time - 2 * duration, .0001f)
        val local = if (firstCycle) time else if (repeatCount == 1) mainTime.coerceAtMost(2 * duration)
            else cycleTime(mainTime, 2 * duration)
        return if (local < duration) interpolate(if (firstCycle) 1f else second, first, local, duration, 3)
            else interpolate(first, second, local - duration, duration, 4)
    }

    fun continuousWiggleMagnitude(elapsedSeconds: Float, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false, repeatCount: Int? = null): Float {
        val time = maxOf(elapsedSeconds - continuousGroupDelay(groupIndex, maxGroupIndex, wholeSymbol), .0001f)
        if (repeatCount != null) {
            val outroTime = time - 1f / 3f - (1f / 3f) * repeatCount
            if (outroTime > 0f) return when {
                outroTime < 1f / 12f -> interpolate(0f, 1f, outroTime, 1f / 12f, 8)
                outroTime < .25f -> interpolate(1f, -1f, outroTime - 1f / 12f, 1f / 6f, 7)
                else -> interpolate(-1f, 0f, outroTime - .25f, 1f / 6f, 7)
            }
        }
        val firstCycle = time < 1f / 3f
        val mainTime = maxOf(time - 1f / 3f, .0001f)
        val local = if (firstCycle) time else if (repeatCount == 1) mainTime.coerceAtMost(1f / 3f)
            else cycleTime(mainTime, 1f / 3f)
        return when {
            local < 1f / 12f -> interpolate(0f, 1f, local, 1f / 12f, if (firstCycle) 7 else 8)
            local < .25f -> interpolate(1f, -1f, local - 1f / 12f, 1f / 6f, 7)
            else -> interpolate(-1f, 0f, local - .25f, 1f / 12f, 9)
        }
    }

    internal fun continuousGroupDelay(groupIndex: Int, maxGroupIndex: Int, wholeSymbol: Boolean): Float {
        if (wholeSymbol) return 0f
        val step = if (maxGroupIndex >= 4) .15f * SystemFloatTiming.reciprocal(maxGroupIndex.toFloat()) else .05f
        return groupIndex.coerceIn(0, maxGroupIndex.coerceAtLeast(0)) * step
    }

    internal fun cycleTime(time: Float, period: Float): Float {
        val cycles = time * SystemFloatTiming.reciprocal(period)
        return ((cycles - kotlin.math.floor(cycles)) * period).coerceIn(0f, period)
    }

    fun continuousDuration(effect: SymbolEffect, repeatCount: Int, groupCount: Int,
        wholeSymbol: Boolean = false): Float {
        val delay = if (effect in setOf(SymbolEffect.Bounce, SymbolEffect.BounceDown,
                SymbolEffect.Wiggle, SymbolEffect.WiggleUp, SymbolEffect.WiggleDown,
                SymbolEffect.WiggleLeft, SymbolEffect.WiggleRight, SymbolEffect.WiggleForward,
                SymbolEffect.WiggleBackward, SymbolEffect.WiggleClockwise, SymbolEffect.WiggleCounterclockwise))
            continuousGroupDelay(groupCount - 1, groupCount - 1, wholeSymbol).toDouble() else 0.0
        val duration = when (effect) {
            SymbolEffect.Pulse -> (2f * repeatCount).toDouble()
            SymbolEffect.Breathe, SymbolEffect.BreathePulse -> (3f * repeatCount).toDouble()
            SymbolEffect.Bounce, SymbolEffect.BounceDown -> (26f / 60f).toDouble() +
                ((26f / 60f) * repeatCount).toDouble() + (4f / 15f).toDouble()
            SymbolEffect.RotateClockwise, SymbolEffect.RotateCounterclockwise -> 2.5 + (2f * repeatCount).toDouble() + 2.5
            SymbolEffect.None, SymbolEffect.Appear, SymbolEffect.AppearDown,
            SymbolEffect.Disappear, SymbolEffect.DisappearUp, SymbolEffect.ScaleUp, SymbolEffect.ScaleDown -> 0.0
            else -> (1f / 3f).toDouble() + ((1f / 3f) * repeatCount).toDouble() + (5f / 12f).toDouble()
        }
        return (duration + delay).toFloat()
    }
}
