package com.nevoit.sfcompose.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolTimingTest {
    @Test
    fun breathePulseUsesTheScaleEnvelopeWithItsOwnOpacityCurve() {
        assertEquals(0f, SymbolTiming.breathePulseAmount(0f), 0f)
        assertEquals(1f, SymbolTiming.breathePulseAmount(1.5f, wholeSymbol = true), 0f)
        assertEquals(0f, SymbolTiming.breathePulseAmount(3f, wholeSymbol = true), 0f)
        assertEquals(0f, SymbolTiming.breathePulseAmount(.49f, 5, 6), 0f)
        assertEquals(1f, SymbolTiming.breathePulseAmount(1.75f, 5, 6), 0f)
        assertEquals(.5f, SymbolTiming.breathePulseAmount(.75f, wholeSymbol = true), .00001f)
    }
    @Test
    fun appearanceKeepsNativeEndpointsAndDirectionSpecificLayerHolds() {
        assertEquals(SymbolAppearanceFrame(0f, .75f), SymbolTiming.appearanceFrame(0f, true, false))
        assertEquals(SymbolAppearanceFrame(1f, 1f), SymbolTiming.appearanceFrame(.25f, true, false))
        assertEquals(SymbolAppearanceFrame(0f, 1.25f), SymbolTiming.appearanceFrame(0f, true, true))
        assertEquals(SymbolAppearanceFrame(0f, .75f), SymbolTiming.appearanceFrame(1f, false, true))
        assertEquals(SymbolAppearanceFrame(0f, 1.25f), SymbolTiming.appearanceFrame(1f, false, false))
        assertEquals(0f, SymbolTiming.appearanceFrame(.1f, true, false, 2, 3).opacity, 0f)
        assertTrue(SymbolTiming.appearanceFrame(.1f, true, true, 2, 3).opacity > .5f)
        assertEquals(1f, SymbolTiming.appearanceFrame(.05f, false, true, 0, 3).opacity, 0f)
        assertEquals(0f, SymbolTiming.appearanceFrame(.166666667f, false, true, 0, 3).opacity, .00001f)
    }

    @Test
    fun scaleUsesQuarterSecondSegmentsAndHalfSecondHold() {
        assertEquals(1f, SymbolTiming.scaleEffect(0f), 0f)
        assertEquals(1.25f, SymbolTiming.scaleEffect(.25f), 0f)
        assertEquals(1.25f, SymbolTiming.scaleEffect(.75f), 0f)
        assertEquals(1f, SymbolTiming.scaleEffect(1f), 0f)
        assertEquals(.5f, SymbolTiming.scaleEffect(.25f, down = true), 0f)
        assertEquals(.5f, SymbolTiming.scaleEffect(.75f, down = true), 0f)
        assertEquals(1f, SymbolTiming.scaleEffect(1f, down = true), 0f)
        assertEquals(1f, SymbolTiming.scaleEffect(.1f, down = true, groupIndex = 0, maxGroupIndex = 3), 0f)
        assertEquals(.5f, SymbolTiming.scaleEffect(.25f, down = true, groupIndex = 0, maxGroupIndex = 3), 0f)
        assertEquals(1.25f, SymbolTiming.scaleEffect(.1f, groupIndex = 0, maxGroupIndex = 3), .00001f)
    }
    @Test
    fun bounceUsesRecoveredTargetsAndReturnsToRest() {
        assertEquals(1.25f, SymbolTiming.bounceScale(13f / 60f), 0.00001f)
        assertEquals(0.909f, SymbolTiming.bounceScale(26f / 60f), 0.00001f)
        assertEquals(0.75f, SymbolTiming.bounceScale(13f / 60f, down = true), 0.00001f)
        assertEquals(1.109f, SymbolTiming.bounceScale(26f / 60f, down = true), 0.00001f)
        assertEquals(1f, SymbolTiming.bounceScale(0.7f), 0.00001f)
        assertEquals(1f, SymbolTiming.bounceScale(10f), 0f)
    }

    @Test
    fun delayedBounceKeepsTheNativeEndTime() {
        assertEquals(1f, SymbolTiming.bounceScale(0.14f, groupIndex = 3, maxGroupIndex = 3), 0f)
        assertEquals(
            1.25f,
            SymbolTiming.bounceScale(0.15f + 13f / 60f, groupIndex = 3, maxGroupIndex = 3),
            0.00001f,
        )
        assertEquals(1f, SymbolTiming.bounceScale(0.7f, groupIndex = 3, maxGroupIndex = 3), 0.00001f)
        assertEquals(1f, SymbolTiming.bounceScale(0.14f, down = true, groupIndex = 0, maxGroupIndex = 3), 0f)
    }

    @Test
    fun wholeSymbolIgnoresGroupStagger() {
        assertEquals(
            SymbolTiming.bounceScale(0.2f, wholeSymbol = true),
            SymbolTiming.bounceScale(0.2f, groupIndex = 7, maxGroupIndex = 8, wholeSymbol = true),
            0f,
        )
        assertEquals(
            SymbolTiming.breatheScale(1.5f, wholeSymbol = true),
            SymbolTiming.breatheScale(1.5f, groupIndex = 7, groupCount = 9, wholeSymbol = true),
            0f,
        )
    }

    @Test
    fun breatheUsesGroupCountToKeepTheLastGroupWithinThreeSeconds() {
        assertEquals(1.2f, SymbolTiming.breatheScale(1.5f, wholeSymbol = true), 0.00001f)
        assertEquals(1f, SymbolTiming.breatheScale(0.49f, groupIndex = 5, groupCount = 6), 0f)
        assertEquals(1.2f, SymbolTiming.breatheScale(1.75f, groupIndex = 5, groupCount = 6), 0.00001f)
        assertEquals(1f, SymbolTiming.breatheScale(3f, groupIndex = 5, groupCount = 6), 0f)
        assertEquals(1.2f, SymbolTiming.breatheScale(1.667f, groupIndex = 2, groupCount = 3), 0.00001f)
    }

    @Test
    fun pulseMultipliesTheOriginalOpacityAndCompletesOneCycle() {
        assertEquals(1f, SymbolTiming.pulseOpacity(0f), 0f)
        assertEquals(0.3f, SymbolTiming.pulseOpacity(1f), 0.00001f)
        assertEquals(1f, SymbolTiming.pulseOpacity(2f), 0f)
        assertEquals(1f, SymbolTiming.pulseOpacity(3f), 0f)
        assertEquals(0.12f, 0.4f * SymbolTiming.pulseOpacity(1f), 0.00001f)
        assertTrue(SymbolTiming.pulseOpacity(0.5f) in 0.3f..1f)
    }

    @Test
    fun rotateCompletesExactlyOneTurnInTwoSeconds() {
        assertEquals(0f, SymbolTiming.rotateDegrees(0f), 0f)
        assertEquals(360f, SymbolTiming.rotateDegrees(2f), 0f)
        assertEquals(-360f, SymbolTiming.rotateDegrees(2f, clockwise = false), 0f)
        assertEquals(360f, SymbolTiming.rotateDegrees(10f), 0f)
        assertEquals(0f, SymbolTiming.rotateDegrees(-1f), 0f)
        assertTrue(SymbolTiming.rotateDegrees(1f) > 180f)
        val stagger = 16f / 60f
        val delay = stagger / 3f * 2f
        assertEquals(0f, SymbolTiming.rotateDegrees(delay, groupIndex = 2, groupCount = 3), 0f)
        assertEquals(360f, SymbolTiming.rotateDegrees(delay + 2f - stagger, groupIndex = 2, groupCount = 3), 0.0001f)
        assertEquals(SymbolTiming.rotateDegrees(1f), SymbolTiming.rotateDegrees(1f, groupIndex = 2, groupCount = 3, wholeSymbol = true), 0f)
    }

    @Test
    fun wiggleFollowsRecoveredAlternatingTargetsAndDuration() {
        assertEquals(0.06f, SymbolTiming.wiggleOffsetFraction(0.167f), 0.00001f)
        assertEquals(-0.04f, SymbolTiming.wiggleOffsetFraction(0.334f), 0.00001f)
        assertEquals(0.03f, SymbolTiming.wiggleOffsetFraction(0.501f), 0.00001f)
        assertEquals(-0.025f, SymbolTiming.wiggleOffsetFraction(0.668f), 0.00001f)
        assertEquals(0f, SymbolTiming.wiggleOffsetFraction(0.935f), 0f)
        assertEquals(10f, SymbolTiming.wiggleDegrees(0.167f), 0.00001f)
        assertEquals(-10f, SymbolTiming.wiggleDegrees(0.167f, clockwise = false), 0.00001f)
        assertEquals(0f, SymbolTiming.wiggleOffsetFraction(0.15f, groupIndex = 4, maxGroupIndex = 4), 0f)
        assertEquals(0.06f, SymbolTiming.wiggleOffsetFraction(0.327f, groupIndex = 4, maxGroupIndex = 4), 0.00001f)
    }

    @Test
    fun variableThresholdKeepsNativeRoundingAndZeroBoundary() {
        assertEquals(0f, SymbolTiming.variableThreshold(0, 3), 0f)
        assertEquals(0.34f, SymbolTiming.variableThreshold(1, 3), 0f)
        assertEquals(0.68f, SymbolTiming.variableThreshold(2, 3), 0f)
        assertEquals(0.68f, SymbolTiming.variableThreshold(0, 3, reversed = true), 0f)
        assertEquals(false, SymbolTiming.variableColorActive(0f, 0f))
        assertEquals(true, SymbolTiming.variableColorActive(0.001f, 0f))
        assertEquals(true, SymbolTiming.variableColorActive(0.34f, 0.34f))
        assertEquals(false, SymbolTiming.variableColorActive(0.339f, 0.34f))
    }

    @Test
    fun continuousEffectsUseTheirOwnRecipes() {
        assertEquals(1.05f, SymbolTiming.continuousBounceScale(13f / 60f), 0.00001f)
        assertEquals(0.959f, SymbolTiming.continuousBounceScale(26f / 60f), 0.00001f)
        assertEquals(1.05f, SymbolTiming.continuousBounceScale(39f / 60f), 0.00001f)
        assertEquals(0.959f, SymbolTiming.continuousBounceScale(13f / 60f, down = true), 0.00001f)
        assertEquals(1f, SymbolTiming.continuousWiggleMagnitude(0.0835f), 0.00001f)
        assertEquals(-1f, SymbolTiming.continuousWiggleMagnitude(0.2505f), 0.00001f)
        assertEquals(0f, SymbolTiming.continuousWiggleMagnitude(0.334f), 0.00001f)
        assertEquals(1f, SymbolTiming.continuousWiggleMagnitude(0.4175f), 0.00001f)
    }

    @Test
    fun continuousRotatePreservesStartupAndLayerStagger() {
        assertEquals(0f, SymbolTiming.continuousRotateDegrees(0f, wholeSymbol = true), 0f)
        assertEquals(360f, SymbolTiming.continuousRotateDegrees(2.5f, wholeSymbol = true), 0.0001f)
        assertEquals(180f, SymbolTiming.continuousRotateDegrees(3.5f, wholeSymbol = true), 0.0001f)
        assertEquals(0f, SymbolTiming.continuousRotateDegrees(0.16f, groupIndex = 2, groupCount = 3), 0f)
        assertEquals(60.7f, SymbolTiming.continuousRotateDegrees(0.8335f, groupIndex = 2, groupCount = 3), 0.0001f)
        assertEquals(360f, SymbolTiming.continuousRotateDegrees(3.083f, groupIndex = 2, groupCount = 3), 0.0001f)
        assertEquals(-180f, SymbolTiming.continuousRotateDegrees(3.5f, clockwise = false, wholeSymbol = true), 0.0001f)
    }

    @Test
    fun cumulativeVariableColorFadesLayersInOrderAndRetainsEarlierLayers() {
        val first = SymbolTiming.variableColorTrack(0, 3)
        val last = SymbolTiming.variableColorTrack(2, 3)
        assertEquals(1f, first.activeFraction(0.3333f), 0.00001f)
        assertEquals(0f, last.activeFraction(0.3333f), 0f)
        assertEquals(1f, first.activeFraction(0.7777667f), 0f)
        assertEquals(1f, last.activeFraction(0.7777667f), 0.00001f)
        assertEquals(0f, first.activeFraction(first.durationSeconds), 0.00001f)
        assertEquals(first.durationSeconds, last.durationSeconds, 0.00001f)
    }

    @Test
    fun iterativeVariableColorKeepsNativeSmallAndLargeGroupTiming() {
        val small = SymbolTiming.variableColorTrack(1, 3, iterative = true)
        assertEquals(1.3332f, small.durationSeconds, 0.00001f)
        assertEquals(1f, small.activeFraction(0.6666f), 0.00001f)
        assertEquals(0f, small.activeFraction(0.9999f), 0.00001f)
        val large = SymbolTiming.variableColorTrack(4, 5, iterative = true)
        assertEquals(1.333f, large.durationSeconds, 0f)
        assertEquals(0f, large.activeFraction(large.durationSeconds), 0.00001f)
    }

    @Test
    fun bouncingVariableColorStartsAndEndsAtTheNativeActiveLayer() {
        val first = SymbolTiming.variableColorTrack(0, 3, iterative = true, bounces = true)
        val middle = SymbolTiming.variableColorTrack(1, 3, iterative = true, bounces = true)
        assertEquals(1f, first.activeFraction(0f), 0f)
        assertEquals(0f, first.activeFraction(0.3333f), 0.00001f)
        assertEquals(1f, first.activeFraction(first.durationSeconds), 0.00001f)
        assertEquals(1f, middle.activeFraction(0.3333f), 0.00001f)
        assertEquals(1f, middle.activeFraction(0.9999f), 0.00001f)
        for (index in 0..5) {
            val track = SymbolTiming.variableColorTrack(index, 6, iterative = true, bounces = true, willLoop = true)
            assertEquals(2.5f, track.durationSeconds, 0f)
            assertEquals(if (index == 0) 1f else 0f, track.activeFraction(track.durationSeconds), 0.00001f)
        }
    }

    @Test
    fun continuousIterativeVariableColorHasItsOwnPeriodAndGroupDelay() {
        val first = SymbolTiming.loopingIterativeVariableColorTrack(0, 3)
        val last = SymbolTiming.loopingIterativeVariableColorTrack(2, 3)
        assertEquals(0.9999f, first.durationSeconds, 0.00001f)
        assertEquals(1f, first.activeFraction(0.3333f), 0.00001f)
        assertEquals(0f, last.activeFraction(0.3333f), 0f)
        assertEquals(1f, last.activeFraction(0.9999f), 0.00001f)
        assertEquals(first.activeFraction(0.1f), first.activeFraction(1.0999f), 0.00001f)
        val large = SymbolTiming.loopingIterativeVariableColorTrack(0, 8)
        assertEquals(1.5f, large.durationSeconds, 0.00001f)
        assertEquals(1f, large.activeFraction(0.1875f), 0.00001f)
    }

    @Test
    fun nonLoopingContinuousVariableColorUsesItsSeparateEnvelope() {
        val first = SymbolTiming.nonLoopingContinuousIterativeVariableColorTrack(0, 3)
        val last = SymbolTiming.nonLoopingContinuousIterativeVariableColorTrack(2, 3)
        assertEquals(1.5f, first.durationSeconds, 0.00001f)
        assertEquals(0f, first.activeFraction(0f), 0f)
        assertEquals(0f, first.activeFraction(first.durationSeconds), 0.00001f)
        assertEquals(0f, last.activeFraction(0.3f), 0f)
        assertEquals(0f, last.activeFraction(last.durationSeconds), 0.00001f)
        assertEquals(0f, first.activeFraction(first.durationSeconds + 10f), 0f)
    }
}
