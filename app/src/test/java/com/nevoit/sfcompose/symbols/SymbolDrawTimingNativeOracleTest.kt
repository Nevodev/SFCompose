package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolDrawTimingNativeOracleTest {
    @Test
    fun authoringFixtureScheduleMatchesOriginalSharedArm64Timelines() {
        val fixture = javaClass.getResourceAsStream("/draw-native-timing.json")!!.use {
            JSONObject(it.bufferedReader(Charsets.UTF_8).readText()).getJSONArray("symbols")
        }
        for (caseIndex in 0 until fixture.length()) {
            val case = fixture.getJSONObject(caseIndex)
            val name = case.getString("name")
            val on = case.getBoolean("on")
            val reverse = case.getBoolean("reverse")
            val timeline = case.getJSONArray("timeline")
            val drawDurations = (0 until timeline.length()).associate { index ->
                val layer = timeline.getJSONObject(index)
                layer.getInt("group") to if (layer.optDouble("lengthAt1000", Double.NaN).isFinite())
                    layer.getDouble("duration") else null
            }
            val fallback = drawDurations.filterValues { it == null }.keys
            val timing = DrawTimingSchedule(drawDurations, fallback, on, case.getBoolean("descending"),
                SymbolEffectLayerMode.ByLayer)
            var finish = 0.0
            for (layerIndex in 0 until timeline.length()) {
                val layer = timeline.getJSONObject(layerIndex)
                val group = layer.getInt("group")
                val label = "$name/on=$on/reverse=$reverse/layer=$layerIndex"
                val expectedLength = layer.optDouble("lengthAt1000", Double.NaN)
                if (expectedLength.isFinite()) {
                    assertEquals(label, layer.getDouble("duration"), DrawTimingSchedule.adaptiveDuration(expectedLength), 0.0)
                    val samples = layer.getJSONArray("samples")
                    for (sampleIndex in 0 until samples.length()) {
                        val sample = samples.getJSONObject(sampleIndex)
                        assertEquals(label, sample.getDouble("progress").toFloat(),
                            timing.progress(group, sample.getDouble("time").toFloat())!!, .0001f)
                    }
                } else {
                    assertEquals(label, 0f, timing.fallbackProgress(group, layer.getDouble("start").toFloat()), .0001f)
                    assertEquals(label, 1f, timing.fallbackProgress(group, layer.getDouble("finish").toFloat()), .0001f)
                }
                finish = maxOf(finish, layer.getDouble("finish"))
            }
            assertEquals("$name/on=$on/reverse=$reverse", finish.toFloat(), timing.durationSeconds, .0001f)
        }
    }
}
