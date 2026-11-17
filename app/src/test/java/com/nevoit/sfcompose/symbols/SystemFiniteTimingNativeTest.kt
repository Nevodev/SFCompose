package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemFiniteTimingNativeTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/$name.json")!!.bufferedReader().use {
        JSONObject(it.readText()).getJSONArray("records")
    }

    @Test
    fun finiteMotionMatchesOriginalTimeDispatchAndAnimationLifetime() {
        val records = fixture("system-finite-motion-time-native")
        val adapter = SymbolMotionTiming(true)
        for (index in 0 until records.length()) {
            val case = records.getJSONObject(index)
            val count = case.getInt("count")
            val group = case.getInt("group")
            val direction = case.getBoolean("direction")
            val whole = !case.getBoolean("byLayer")
            val repeats = case.getInt("repeatCount")
            val ending = case.getDouble("endTime").toFloat()
            val effect = when (case.getString("effect")) {
                "pulse" -> SymbolEffect.Pulse
                "breathe" -> SymbolEffect.Breathe
                "bounce" -> if (direction) SymbolEffect.BounceDown else SymbolEffect.Bounce
                "wiggle" -> SymbolEffect.Wiggle
                "rotate" -> if (direction) SymbolEffect.RotateCounterclockwise else SymbolEffect.RotateClockwise
                else -> error("Unknown effect")
            }
            if (effect != SymbolEffect.Pulse || !direction) {
                assertEquals("duration/$effect/$count/$whole/$repeats", ending,
                    adapter.continuousDuration(effect, repeats, count, whole), .000002f)
            }
            val samples = case.getJSONArray("samples")
            for (sampleIndex in 0 until samples.length()) {
                val sample = samples.getJSONObject(sampleIndex)
                val time = sample.getDouble("time").toFloat()
                var expected = sample.getDouble("nativeValue").toFloat()
                val actual = when (case.getString("effect")) {
                    "pulse" -> {
                        val period = if (direction) 1f else 2f
                        expected = if (time >= ending) 1f else .3f + .7f * expected
                        adapter.pulseOpacity(adapter.periodTime(time, period, repeats), period)
                    }
                    "breathe" -> {
                        if (time >= ending) expected = 1f
                        adapter.breatheScale(adapter.periodTime(time, 3f, repeats), group, count, whole)
                    }
                    "bounce" -> {
                        if (time >= ending) expected = 1f
                        adapter.continuousBounceScale(time, direction, group, count - 1, whole, repeats)
                    }
                    "wiggle" -> {
                        expected = if (time >= ending) 0f else expected /
                            if (direction) .0872664600610733f else .32500001788139343f
                        adapter.continuousWiggleMagnitude(time, group, count - 1, whole, repeats)
                    }
                    "rotate" -> {
                        expected = if (time >= ending) 0f else expected * (360f / 6.2831854820251465f)
                        adapter.continuousRotateDegrees(time, !direction, group, count, whole, repeats)
                    }
                    else -> error("Unknown effect")
                }
                assertEquals("finite/$effect/n=$count/group=$group/whole=$whole/repeats=$repeats/t=$time",
                    expected, actual, if (case.getString("effect") == "rotate") .0003f else .00002f)
            }
        }
    }

    @Test
    fun variableColorFiniteAndInfinitePlaybackMatchesOriginalTimeDispatch() {
        val records = fixture("system-finite-variable-time-native")
        val adapter = SymbolMotionTiming(true)
        for (index in 0 until records.length()) {
            val case = records.getJSONObject(index)
            val count = case.getInt("count")
            val group = case.getInt("group")
            val iterative = case.getBoolean("iterative")
            val bounces = case.getBoolean("bounces")
            val willLoop = case.getBoolean("willLoop")
            val repeats = if (case.isNull("repeatCount")) null else case.getInt("repeatCount")
            val ending = if (case.isNull("endTime")) Float.POSITIVE_INFINITY else case.getDouble("endTime").toFloat()
            val track = adapter.variableColorTrack(group - 1, count, iterative, bounces,
                willLoop = willLoop, repeatCount = repeats, continuous = true)
            assertEquals("variable/duration/$count/$iterative/$bounces/$repeats", ending, track.durationSeconds, .000003f)
            val samples = case.getJSONArray("samples")
            for (sampleIndex in 0 until samples.length()) {
                val sample = samples.getJSONObject(sampleIndex)
                val time = sample.getDouble("time").toFloat()
                val expected = if (time >= ending) 1f else (sample.getDouble("nativeValue").toFloat() - .3f) / .7f
                assertEquals("variable/n=$count/group=$group/iterative=$iterative/bounces=$bounces/repeats=$repeats/t=$time",
                    expected, track.activeFraction(time), .00002f)
            }
        }
    }
}
