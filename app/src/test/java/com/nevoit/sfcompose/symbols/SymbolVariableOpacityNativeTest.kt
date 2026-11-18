package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolVariableOpacityNativeTest {
    @Test
    fun variableColorAppliesTheOriginalPhaseSpecificTwoChannelOperations() {
        val fixture = javaClass.getResourceAsStream("/symbols/system-variable-opacity-native.json")!!
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val rows = fixture.getJSONArray("records")
        var checks = 0
        var expiredChecks = 0
        val properties = mutableSetOf<Int>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val track = SymbolSystemVariableTiming.variableColorTrack(row.getInt("group") - 1, row.getInt("count"),
                row.getBoolean("iterative"), row.getBoolean("bounces"), row.getBoolean("willLoop"),
                repeatCount = if (row.isNull("repeatCount")) null else row.getInt("repeatCount"),
                continuous = row.getBoolean("continuous"))
            val operations = row.getJSONArray("operations")
            for (op in 0 until operations.length()) properties += operations.getJSONObject(op).getInt("property")
            val outputs = row.getJSONArray("outputs")
            for (sample in 0 until outputs.length()) {
                val expected = outputs.getJSONObject(sample)
                val initial = SymbolLayerOpacity(expected.getDouble("drawing").toFloat(),
                    expected.getDouble("erasing").toFloat(), .6f)
                val actual = track.applyOpacity(initial, row.getDouble("time").toFloat(), row.getDouble("inactive").toFloat())
                val message = "record=$index phase=${row.getInt("phase")} time=${row.getDouble("time")} baseline=$initial"
                // The fixture also evaluates raw Animation::time after end_time; the animator
                // removes those tracks, so their raw outro must not override the static model.
                if (row.getBoolean("continuous") && row.getDouble("time").toFloat() >= track.durationSeconds) {
                    assertEquals(initial, actual)
                    expiredChecks++
                    continue
                }
                assertEquals("drawing $message", expected.getDouble("resultDrawing").toFloat(), actual.drawing, .00003f)
                assertEquals("erasing $message", expected.getDouble("resultErasing").toFloat(), actual.erasing, .00003f)
                assertEquals(.6f, actual.common, 0f)
                checks++
            }
        }
        assertEquals(setOf(8, 9, 10), properties)
        assertEquals(8280, checks + expiredChecks)
        assertTrue(checks > 6000)
        assertTrue(expiredChecks > 0)
    }

    @Test
    fun mainVariableColorReplacesTheBaselineWhileIntroPreservesItsTwoChannels() {
        val baseline = SymbolLayerOpacity(.5f, 1f)
        val track = SymbolSystemVariableTiming.variableColorTrack(0, 3, continuous = true, repeatCount = 2)
        val intro = track.applyOpacity(baseline, .1f, .3f)
        assertTrue(intro.drawing < intro.erasing)
        val main = track.applyOpacity(baseline, .5f, .3f)
        assertEquals(main.drawing, main.erasing, 0f)
        assertEquals(.65f, main.drawing, .00003f)
    }
}
