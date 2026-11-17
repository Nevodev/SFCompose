package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemEffectTimingNativeTest {
    private val oracle by lazy {
        javaClass.getResourceAsStream("/system-effects-native.json")!!.bufferedReader().use {
            JSONObject(it.readText())
        }
    }

    @Test
    fun nativeReciprocalRetainsArmRefinementRounding() {
        assertEquals(.535714328289032f, SystemFloatTiming.reciprocal(1.8666666746139526f), 0f)
        assertEquals(5.98802375793457f, SystemFloatTiming.reciprocal(.16699999570846558f), 0f)
        assertEquals(.5769230723381042f, SystemFloatTiming.reciprocal(1.7333333492279053f), 0f)
    }

    @Test
    fun systemPresetEvaluatorMatchesOriginalArm64Samples() {
        val curves = oracle.getJSONArray("curves")
        for (index in 0 until curves.length()) {
            val curve = curves.getJSONObject(index)
            val preset = curve.getInt("preset")
            if (preset in 17..39) continue
            val samples = curve.getJSONArray("samples")
            for (sample in 0 until samples.length()) {
                val expected = samples.getJSONObject(sample)
                assertEquals("preset=$preset/sample=$sample", expected.getDouble("progress").toFloat(),
                    SymbolSystemTiming.curve(preset, expected.getDouble("time").toFloat()), .000002f)
            }
        }
    }

    @Test
    fun effectFramesMatchOriginalSystemEmittedTracks() {
        val records = oracle.getJSONArray("records")
        for (index in 0 until records.length()) {
            val case = records.getJSONObject(index)
            val effect = case.getString("effect")
            val count = case.getInt("count")
            val whole = !case.getBoolean("byLayer")
            val direction = case.getBoolean("direction")
            val continuous = case.getBoolean("continuous")
            val phase = case.getInt("phase")
            // Public playback is start + loop; native stop phases need an explicit stop transition.
            if (phase == 2) continue
            val tracks = case.getJSONArray("tracks")
            for (trackIndex in 0 until tracks.length()) {
                val track = tracks.getJSONObject(trackIndex)
                val group = track.getInt("layer")
                val property = track.getInt("flags") and 31
                val samples = track.getJSONArray("samples")
                for (sampleIndex in 0 until samples.length()) {
                    val sample = samples.getJSONObject(sampleIndex)
                    var time = sample.getDouble("time").toFloat()
                    if (phase == 1) time += when (effect) {
                        "bounce" -> 26f / 60f
                        "wiggle" -> 1f / 3f
                        "rotate" -> 2.5f
                        "scale" -> .75f
                        else -> 0f
                    }
                    if (continuous && effect in setOf("bounce", "wiggle")) {
                        time += SymbolSystemTiming.continuousGroupDelay(
                            if (effect == "bounce" && direction) count - 1 - group else group,
                            count - 1, whole)
                    }
                    var expected = sample.getDouble("value").toFloat()
                    val actual = when (effect) {
                        "pulse" -> {
                            expected = .3f + .7f * expected
                            SymbolSystemTiming.pulseOpacity(time, if (direction) 1f else 2f)
                        }
                        "scale" -> {
                            val target = if (direction) .5f else 1.25f
                            expected = if (phase == 0) 1f + (target - 1f) * expected
                                else target + (1f - target) * expected
                            SymbolSystemTiming.scaleEffect(time, direction, group, count - 1, whole)
                        }
                        "breathe" -> if (property == 2) SymbolSystemTiming.breatheScale(time, group, count, whole)
                            else {
                                expected = 1f - expected
                                SymbolSystemTiming.breathePulseAmount(time, group, count, whole)
                            }
                        "bounce" -> if (continuous) SymbolSystemTiming.continuousBounceScale(time, direction, group, count - 1, whole)
                            else SymbolSystemTiming.bounceScale(time, direction, group, count - 1, whole)
                        "wiggle" -> if (continuous) {
                            expected /= if (direction) .0872664600610733f else .32500001788139343f
                            SymbolSystemTiming.continuousWiggleMagnitude(time, group, count - 1, whole)
                        } else if (direction) {
                            expected *= (180.0 / Math.PI).toFloat()
                            SymbolSystemTiming.wiggleDegrees(time, true, group, count - 1, whole)
                        } else {
                            expected /= 13f
                            SymbolSystemTiming.wiggleOffsetFraction(time, group, count - 1, whole)
                        }
                        "rotate" -> {
                            expected *= 360f / 6.2831854820251465f
                            if (continuous) SymbolSystemTiming.continuousRotateDegrees(time, !direction, group, count, whole)
                            else SymbolSystemTiming.rotateDegrees(time, !direction, group, count, whole)
                        }
                        "appear", "disappear" -> {
                            val appear = effect == "appear"
                            val frame = SymbolSystemTiming.appearanceFrame(time, appear,
                                if (appear) direction else !direction, group, count - 1, whole)
                            if (property == 2) frame.scale else frame.opacity
                        }
                        else -> error(effect)
                    }
                    val tolerance = if (effect == "rotate") .0003f else .00002f
                    assertEquals("$effect/n=$count/whole=$whole/direction=$direction/phase=$phase/group=$group/property=$property/t=$time",
                        expected, actual, tolerance)
                }
            }
        }
    }

    @Test
    fun variableColorTracksMatchOriginalSystemSchedulesIncludingWrappedMaximum() {
        val records = oracle.getJSONArray("variable")
        for (index in 0 until records.length()) {
            val case = records.getJSONObject(index)
            if (case.getBoolean("continuous")) continue
            val count = case.getInt("count")
            val iterative = case.getBoolean("iterative")
            val bounces = case.getBoolean("bounces")
            val wrap = case.getBoolean("wrap")
            val tracks = (0 until count).map {
                SymbolSystemVariableTiming.variableColorTrack(it, count, iterative, bounces, wrap)
            }
            val samples = case.getJSONArray("samples")
            for (sampleIndex in 0 until samples.length()) {
                val sample = samples.getJSONObject(sampleIndex)
                val group = sample.getInt("group")
                val time = sample.getDouble("time").toFloat()
                val value = (sample.getDouble("value").toFloat() - .3f) / .7f
                assertEquals("variable/n=$count/iterative=$iterative/bounces=$bounces/wrap=$wrap/group=$group/t=$time",
                    value, tracks[group - 1].activeFraction(time), .00002f)
            }
        }
    }

    @Test
    fun continuousVariableColorMatchesOriginalDefaultTimeDispatchEndToEnd() {
        val records = javaClass.getResourceAsStream("/system-variable-time-native.json")!!.bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("records")
        }
        for (index in 0 until records.length()) {
            val case = records.getJSONObject(index)
            val count = case.getInt("count")
            val group = case.getInt("group")
            val looping = case.getBoolean("looping")
            val track = if (looping) SymbolSystemVariableTiming.loopingIterativeVariableColorTrack(group - 1, count)
                else SymbolSystemVariableTiming.nonLoopingContinuousIterativeVariableColorTrack(group - 1, count)
            val samples = case.getJSONArray("samples")
            for (sampleIndex in 0 until samples.length()) {
                val sample = samples.getJSONObject(sampleIndex)
                val time = sample.getDouble("time").toFloat()
                val expected = (sample.getDouble("nativeValue").toFloat() - .3f) / .7f
                assertEquals("continuous variable/n=$count/group=$group/looping=$looping/t=$time",
                    expected, track.activeFraction(time), .00002f)
            }
        }
    }

    @Test
    fun continuousMotionMatchesOriginalDefaultTimeDispatchEndToEnd() {
        val records = javaClass.getResourceAsStream("/system-motion-time-native.json")!!.bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("records")
        }
        for (index in 0 until records.length()) {
            val case = records.getJSONObject(index)
            val count = case.getInt("count")
            val group = case.getInt("group")
            val effect = case.getString("effect")
            val direction = case.getBoolean("direction")
            val whole = !case.getBoolean("byLayer")
            val samples = case.getJSONArray("samples")
            for (sampleIndex in 0 until samples.length()) {
                val sample = samples.getJSONObject(sampleIndex)
                val time = sample.getDouble("time").toFloat()
                var expected = sample.getDouble("nativeValue").toFloat()
                val actual = when (effect) {
                    "pulse" -> {
                        expected = .3f + .7f * expected
                        val period = if (direction) 1f else 2f
                        SymbolSystemTiming.pulseOpacity(SymbolSystemTiming.cycleTime(maxOf(time, .0001f), period), period)
                    }
                    "breathe" -> SymbolSystemTiming.breatheScale(
                        SymbolSystemTiming.cycleTime(maxOf(time, .0001f), 3f), group, count, whole)
                    "bounce" -> SymbolSystemTiming.continuousBounceScale(time, direction, group, count - 1, whole)
                    "wiggle" -> {
                        expected /= if (direction) .0872664600610733f else .32500001788139343f
                        SymbolSystemTiming.continuousWiggleMagnitude(time, group, count - 1, whole)
                    }
                    "rotate" -> {
                        expected *= 360f / 6.2831854820251465f
                        SymbolSystemTiming.continuousRotateDegrees(time, !direction, group, count, whole)
                    }
                    else -> error(effect)
                }
                assertEquals("continuous $effect/n=$count/group=$group/whole=$whole/direction=$direction/t=$time",
                    expected, actual, if (effect == "rotate") .0003f else .00002f)
            }
        }
    }
}
