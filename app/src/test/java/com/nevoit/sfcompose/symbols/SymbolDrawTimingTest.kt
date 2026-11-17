package com.nevoit.sfcompose.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolDrawTimingTest {
    @Test
    fun adaptiveDurationUsesNativeLengthCurveAndFrameTruncation() {
        assertEquals(8.0 / 60.0, DrawTimingSchedule.adaptiveDuration(0.0), 0.0)
        assertEquals(1.0, DrawTimingSchedule.adaptiveDuration(581.79), 0.0)
        assertEquals(36.0 / 60.0, DrawTimingSchedule.adaptiveDuration(177.44595), 0.0)
    }

    @Test
    fun byLayerOverlapUsesDifferentOnAndOffDelays() {
        val on = DrawTimingSchedule(mapOf(0 to 1.0, 1 to 1.0), emptySet(), true, false, SymbolEffectLayerMode.ByLayer)
        val off = DrawTimingSchedule(mapOf(0 to 1.0, 1 to 1.0), emptySet(), false, false, SymbolEffectLayerMode.ByLayer)
        assertEquals(0f, on.progress(1, 0.18f)!!, 0.00001f)
        assertEquals(0f, off.progress(1, 0.38f)!!, 0.00001f)
        assertEquals(1.18f, on.durationSeconds, 0.00001f)
        assertEquals(1.38f, off.durationSeconds, 0.00001f)
        assertTrue(on.progress(1, 0.38f)!! > 0f)
    }

    @Test
    fun individualGroupsSliceOneGlobalCurveAndKeepItsTotalDuration() {
        val schedule = DrawTimingSchedule(mapOf(0 to 1.0, 1 to 1.0), emptySet(), true, false, SymbolEffectLayerMode.Individually)
        assertEquals(2f, schedule.durationSeconds, 0.00001f)
        assertEquals(1f, schedule.progress(0, 0.5875f)!!, 0.00001f)
        assertEquals(0f, schedule.progress(1, 0.5875f)!!, 0.00001f)
        val global = SymbolTiming.cubicBezier(0.5f, 0.2f, 0f, 0.25f, 1f)
        assertEquals((global - 0.5f) / 0.5f, schedule.progress(1, 1f)!!, 0.0001f)
    }

    @Test
    fun wholeAndReversedOrdersRetainTheirOwnStartTimes() {
        val whole = DrawTimingSchedule(mapOf(0 to 1.0, 1 to 1.0), emptySet(), true, true, SymbolEffectLayerMode.WholeSymbol)
        assertEquals(whole.progress(0, 0.3f)!!, whole.progress(1, 0.3f)!!, 0f)
        assertEquals(1f, whole.durationSeconds, 0f)
        val reversed = DrawTimingSchedule(mapOf(0 to 1.0, 1 to 1.0), emptySet(), true, true, SymbolEffectLayerMode.ByLayer)
        assertEquals(0f, reversed.progress(0, 0.18f)!!, 0.00001f)
        assertTrue(reversed.progress(1, 0.18f)!! > 0f)
    }

    @Test
    fun fallbackGroupsParticipateInSchedulingAndDuration() {
        val schedule = DrawTimingSchedule(mapOf(0 to null, 1 to 1.0), setOf(0), true, false, SymbolEffectLayerMode.ByLayer)
        assertEquals(null, schedule.progress(0, 0.1f))
        assertEquals(0.5f, schedule.fallbackProgress(0, 0.125f), 0f)
        assertEquals(0f, schedule.progress(1, 0.045f)!!, 0.00001f)
        assertEquals(0.25f, schedule.fallbackDurationSeconds, 0f)
        assertEquals(1.045f, schedule.drawDurationSeconds, 0.00001f)
    }
}
