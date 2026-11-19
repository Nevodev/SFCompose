package com.nevoit.sfcompose.symbols

internal object SymbolSystemVariableTiming {
    private const val third = 1f / 3f
    private const val twelfth = 1f / 12f
    private const val nativeNinth = .111111119389534f
    private data class OpacitySample(val activeFraction: Float, val interpolatesBaseline: Boolean = false)

    private fun builder() = VariableColorTrack.Builder(system = true)

    fun variableColorTrack(index: Int, count: Int, iterative: Boolean = false,
        bounces: Boolean = false, willLoop: Boolean = false, repeatCount: Int? = null,
        continuous: Boolean = false): VariableColorTrack {
        if (continuous) return continuousTrack(index, count, iterative, bounces, willLoop, repeatCount)
        val n = count.coerceAtLeast(1)
        val i = index.coerceIn(0, n - 1)
        if (!iterative) {
            val step = if (n > 3) (2f / 3f) / (n - 1) else third
            val delay = if (n > 1) step * i else 0f
            val holdDuration = if (n <= 1) third else if (bounces) 2f * step * (n - 1 - i) + third
                else (if (n == 2) 2f / 3f else 1f) - delay
            return builder().apply {
                hold(delay.toDouble())
                fade(1f, third.toDouble())
                hold(holdDuration.toDouble())
                fade(0f, third.toDouble())
            }.build()
        }
        val effectiveCount = n + if (willLoop) 1 else 0
        val base = sequentialTrack(i, effectiveCount, bounces)
        return if (willLoop && i == 0) base.withMaximum(sequentialTrack(effectiveCount - 1,
            effectiveCount, bounces)) else base
    }

    private fun sequentialTrack(index: Int, count: Int, bounces: Boolean): VariableColorTrack {
        val track = builder()
        if (!bounces) {
            val (rise, decay) = sequentialDurations(count)
            track.hold((index * rise).toDouble())
            track.fade(1f, rise.toDouble())
            track.fade(0f, decay.toDouble())
            return track.build()
        }
        if (count <= 2) {
            track.initial = if (index == 0) 1f else 0f
            track.fade(1f - track.initial, third.toDouble())
            track.fade(track.initial, third.toDouble())
        } else if (count == 3) {
            when (index) {
                0 -> {
                    track.initial = 1f
                    track.fade(0f, third.toDouble())
                    track.hold((2f / 3f).toDouble())
                    track.fade(1f, third.toDouble())
                }
                1 -> repeat(2) {
                    track.fade(1f, third.toDouble())
                    track.fade(0f, third.toDouble())
                }
                else -> {
                    track.hold(third.toDouble())
                    track.fade(1f, third.toDouble())
                    track.fade(0f, third.toDouble())
                }
            }
        } else {
            val baseDecay = (2 * count - 8) * (1f / 60f) + third
            val reciprocal = 1f / (2f * (baseDecay + (count - 2) * twelfth))
            val rise = (5f / 24f) * reciprocal
            val decay = baseDecay * 2.5f * reciprocal
            val hold = (5f / 12f) * reciprocal * (count - 2 - index)
            track.hold((maxOf(index - 1, 0) * rise).toDouble())
            if (index == 0) {
                track.initial = 1f
                track.fade(0f, decay.toDouble())
                track.hold(hold.toDouble())
                track.fade(1f, rise.toDouble())
            } else if (index == count - 1) {
                track.fade(1f, rise.toDouble())
                track.hold((decay - rise).toDouble())
                track.fade(0f, decay.toDouble())
            } else {
                track.fade(1f, rise.toDouble())
                track.fade(0f, decay.toDouble())
                track.hold(hold.toDouble())
                track.fade(1f, rise.toDouble())
                track.fade(0f, decay.toDouble())
            }
        }
        return track.build()
    }

    private fun sequentialDurations(count: Int): Pair<Float, Float> {
        if (count <= 3) return third to third
        val baseDecay = (2 * count - 8) * (1f / 60f) + third
        val reciprocal = SystemFloatTiming.reciprocal(baseDecay + count * twelfth)
        return (nativeNinth * reciprocal) to (baseDecay * (4f / 3f) * reciprocal)
    }

    fun loopingIterativeVariableColorTrack(index: Int, count: Int, repeatCount: Int? = null): VariableColorTrack {
        return continuousTrack(index, count, true, false, true, repeatCount)
    }

    fun nonLoopingContinuousIterativeVariableColorTrack(index: Int, count: Int, repeatCount: Int? = null): VariableColorTrack =
        continuousTrack(index, count, true, false, true, repeatCount ?: 1)

    private fun continuousTrack(index: Int, count: Int, iterative: Boolean, bounces: Boolean,
        willLoop: Boolean, repeatCount: Int?): VariableColorTrack {
        val n = count.coerceAtLeast(1)
        val group = index.coerceIn(0, n - 1) + 1
        val denominator = n * twelfth + third + (2 * n - 8) * (1f / 60f)
        val reciprocal = SystemFloatTiming.reciprocal(denominator)
        val delay = if (iterative && !bounces && willLoop) {
            if (n <= 3) group * third else (nativeNinth * group) * reciprocal
        } else 0f
        val period = continuousPeriod(n, iterative, bounces, willLoop)
        val repeatDelay = if (!iterative || !bounces && !willLoop) third else 0f
        val mainTrack = if (iterative && !bounces && willLoop) {
            val (rise, decay) = sequentialDurations(n)
            builder().apply { fade(1f, rise.toDouble()); fade(0f, decay.toDouble()) }.build()
        } else variableColorTrack(index, n, iterative, bounces, willLoop)
        val maximumDelay = if (iterative && !bounces && willLoop) {
            if (n <= 3) n * third else nativeNinth * n * reciprocal
        } else 0f
        val duration = if (repeatCount == null) Float.POSITIVE_INFINITY else
            (third.toDouble() + maximumDelay + (period * repeatCount).toDouble() +
                (repeatDelay * (repeatCount - 1)).toDouble() + third).toFloat()
        fun sample(elapsed: Float): OpacitySample {
            if (repeatCount != null && elapsed >= duration) return OpacitySample(1f, true)
            val time = maxOf(elapsed, .0001f)
            return if (time < third) {
                OpacitySample(1f - SymbolSystemTiming.curve(1, SystemFloatTiming.progress(time, third)), true)
            } else {
                val local = maxOf(time - third - delay, .0001f)
                val span = period + repeatDelay
                val outroTime = if (repeatCount == null) Float.NEGATIVE_INFINITY else local - span * repeatCount
                if (outroTime > 0f) {
                    OpacitySample(SymbolSystemTiming.curve(1, SystemFloatTiming.progress(outroTime, third)), true)
                } else {
                    val sampleTime = if (repeatCount == 1) local.coerceAtMost(period) else {
                        val cycle = local * SystemFloatTiming.reciprocal(span)
                        val iteration = kotlin.math.floor(minOf(cycle, repeatCount?.toFloat() ?: Float.POSITIVE_INFINITY))
                        ((cycle - iteration) * span).coerceIn(0f, period)
                    }
                    OpacitySample(mainTrack.activeFraction(sampleTime))
                }
            }
        }
        return VariableColorTrack.systemSampler(duration, interpolatesBaseline = { sample(it).interpolatesBaseline }) {
            sample(it).activeFraction
        }
    }

    private fun continuousPeriod(count: Int, iterative: Boolean, bounces: Boolean, willLoop: Boolean): Float {
        if (!iterative) {
            val capped = count.coerceIn(1, 3).toFloat()
            return if (bounces) (third.toDouble() + capped.toDouble() * (2f / 3f)).toFloat()
                else ((2f / 3f).toDouble() + capped.toDouble() * third).toFloat()
        }
        if (bounces) {
            val effective = count + if (willLoop) 1 else 0
            return if (effective <= 2) 2f / 3f else if (effective == 3) 4f / 3f else 2.5f
        }
        if (!willLoop) return (count.coerceIn(1, 3) + 1) * third
        if (count <= 3) return count * third
        val denominator = count * twelfth + third + (2 * count - 8) * (1f / 60f)
        return nativeNinth * count * SystemFloatTiming.reciprocal(denominator)
    }

    fun continuousDuration(count: Int, iterative: Boolean, bounces: Boolean, willLoop: Boolean,
        repeatCount: Int): Float = (0 until count.coerceAtLeast(1)).maxOf {
        continuousTrack(it, count, iterative, bounces, willLoop, repeatCount).durationSeconds
    }
}
