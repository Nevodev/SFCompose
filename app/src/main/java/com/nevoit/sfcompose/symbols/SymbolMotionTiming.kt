package com.nevoit.sfcompose.symbols

internal class SymbolMotionTiming(private val system: Boolean) {
    fun periodTime(elapsedSeconds: Float, period: Float, repeatCount: Int? = null): Float = when {
        repeatCount != null && elapsedSeconds >= period * repeatCount -> period
        system && repeatCount == 1 -> maxOf(elapsedSeconds, .0001f).coerceAtMost(period)
        system -> SymbolSystemTiming.cycleTime(maxOf(elapsedSeconds, .0001f), period)
        else -> elapsedSeconds % period
    }

    fun appearanceFrame(elapsedSeconds: Float, appear: Boolean, down: Boolean, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false): SymbolAppearanceFrame =
        if (system) SymbolSystemTiming.appearanceFrame(elapsedSeconds, appear, down, groupIndex, maxGroupIndex, wholeSymbol)
        else SymbolTiming.appearanceFrame(elapsedSeconds, appear, down, groupIndex, maxGroupIndex, wholeSymbol)

    fun bounceScale(elapsedSeconds: Float, down: Boolean = false, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false): Float =
        if (system) SymbolSystemTiming.bounceScale(elapsedSeconds, down, groupIndex, maxGroupIndex, wholeSymbol)
        else SymbolTiming.bounceScale(elapsedSeconds, down, groupIndex, maxGroupIndex, wholeSymbol)

    fun breatheScale(elapsedSeconds: Float, groupIndex: Int = 0, groupCount: Int = 1,
        wholeSymbol: Boolean = false): Float =
        if (system) SymbolSystemTiming.breatheScale(elapsedSeconds, groupIndex, groupCount, wholeSymbol)
        else SymbolTiming.breatheScale(elapsedSeconds, groupIndex, groupCount, wholeSymbol)

    fun breathePulseAmount(elapsedSeconds: Float, groupIndex: Int = 0, groupCount: Int = 1,
        wholeSymbol: Boolean = false): Float =
        if (system) SymbolSystemTiming.breathePulseAmount(elapsedSeconds, groupIndex, groupCount, wholeSymbol)
        else SymbolTiming.breathePulseAmount(elapsedSeconds, groupIndex, groupCount, wholeSymbol)

    fun pulseOpacity(elapsedSeconds: Float, durationSeconds: Float = 2f): Float =
        if (system) SymbolSystemTiming.pulseOpacity(elapsedSeconds, durationSeconds)
        else SymbolTiming.pulseOpacity(elapsedSeconds, durationSeconds)

    fun rotateDegrees(elapsedSeconds: Float, clockwise: Boolean = true, groupIndex: Int = 0,
        groupCount: Int = 1, wholeSymbol: Boolean = false): Float =
        if (system) SymbolSystemTiming.rotateDegrees(elapsedSeconds, clockwise, groupIndex, groupCount, wholeSymbol)
        else SymbolTiming.rotateDegrees(elapsedSeconds, clockwise, groupIndex, groupCount, wholeSymbol)

    fun continuousRotateDegrees(elapsedSeconds: Float, clockwise: Boolean = true, groupIndex: Int = 0,
        groupCount: Int = 1, wholeSymbol: Boolean = false, repeatCount: Int? = null): Float =
        if (system) SymbolSystemTiming.continuousRotateDegrees(elapsedSeconds, clockwise, groupIndex, groupCount, wholeSymbol, repeatCount)
        else SymbolTiming.continuousRotateDegrees(elapsedSeconds, clockwise, groupIndex, groupCount, wholeSymbol)

    fun wiggleOffsetFraction(elapsedSeconds: Float, groupIndex: Int = 0, maxGroupIndex: Int = 0,
        wholeSymbol: Boolean = false): Float =
        if (system) SymbolSystemTiming.wiggleOffsetFraction(elapsedSeconds, groupIndex, maxGroupIndex, wholeSymbol)
        else SymbolTiming.wiggleOffsetFraction(elapsedSeconds, groupIndex, maxGroupIndex, wholeSymbol)

    fun wiggleDegrees(elapsedSeconds: Float, clockwise: Boolean = true, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false): Float =
        if (system) SymbolSystemTiming.wiggleDegrees(elapsedSeconds, clockwise, groupIndex, maxGroupIndex, wholeSymbol)
        else SymbolTiming.wiggleDegrees(elapsedSeconds, clockwise, groupIndex, maxGroupIndex, wholeSymbol)

    fun continuousBounceScale(elapsedSeconds: Float, down: Boolean = false, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false, repeatCount: Int? = null): Float =
        if (system) SymbolSystemTiming.continuousBounceScale(elapsedSeconds, down, groupIndex, maxGroupIndex, wholeSymbol, repeatCount)
        else SymbolTiming.continuousBounceScale(elapsedSeconds, down, groupIndex, maxGroupIndex, wholeSymbol)

    fun continuousWiggleMagnitude(elapsedSeconds: Float, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false, repeatCount: Int? = null): Float =
        if (system) SymbolSystemTiming.continuousWiggleMagnitude(elapsedSeconds, groupIndex, maxGroupIndex, wholeSymbol, repeatCount)
        else SymbolTiming.continuousWiggleMagnitude(elapsedSeconds, groupIndex, maxGroupIndex, wholeSymbol)

    fun scaleEffect(elapsedSeconds: Float, down: Boolean = false, groupIndex: Int = 0,
        maxGroupIndex: Int = 0, wholeSymbol: Boolean = false): Float =
        if (system) SymbolSystemTiming.scaleEffect(elapsedSeconds, down, groupIndex, maxGroupIndex, wholeSymbol)
        else SymbolTiming.scaleEffect(elapsedSeconds, down, groupIndex, maxGroupIndex, wholeSymbol)

    fun variableColorTrack(index: Int, count: Int, iterative: Boolean = false,
        bounces: Boolean = false, willLoop: Boolean = false, repeatCount: Int? = null,
        continuous: Boolean = false): VariableColorTrack =
        if (system) SymbolSystemVariableTiming.variableColorTrack(index, count, iterative, bounces, willLoop, repeatCount, continuous)
        else SymbolTiming.variableColorTrack(index, count, iterative, bounces, willLoop)

    fun loopingIterativeVariableColorTrack(index: Int, count: Int, repeatCount: Int? = null): VariableColorTrack =
        if (system) SymbolSystemVariableTiming.loopingIterativeVariableColorTrack(index, count, repeatCount)
        else SymbolTiming.loopingIterativeVariableColorTrack(index, count)

    fun nonLoopingContinuousIterativeVariableColorTrack(index: Int, count: Int, repeatCount: Int? = null): VariableColorTrack =
        if (system) SymbolSystemVariableTiming.nonLoopingContinuousIterativeVariableColorTrack(index, count, repeatCount)
        else SymbolTiming.nonLoopingContinuousIterativeVariableColorTrack(index, count)

    fun continuousDuration(effect: SymbolEffect, repeatCount: Int, groupCount: Int,
        wholeSymbol: Boolean = false): Float =
        SymbolSystemTiming.continuousDuration(effect, repeatCount, groupCount, wholeSymbol)

    fun variableContinuousDuration(count: Int, iterative: Boolean, bounces: Boolean,
        willLoop: Boolean, repeatCount: Int): Float =
        SymbolSystemVariableTiming.continuousDuration(count, iterative, bounces, willLoop, repeatCount)
}
