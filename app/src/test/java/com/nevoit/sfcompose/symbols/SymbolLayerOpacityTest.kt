package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolLayerOpacityTest {
    private val fixture = JSONObject(checkNotNull(javaClass.getResource(
        "/symbols/system-layer-opacity-native.json")).readText())

    @Test
    fun drawingAndErasingGettersMatchOriginalInstructions() {
        val rows = fixture.getJSONArray("getters")
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val state = SymbolLayerOpacity(row.float("drawing"), row.float("erasing"), row.float("common"))
            assertEquals("drawing $index", row.float("drawingResult").toRawBits(),
                state.drawingOpacity(row.float("material")).toRawBits())
            assertEquals("erasing $index", row.float("erasingResult").toRawBits(),
                state.erasingOpacity(row.float("material"), row.getBoolean("clearBehind")).toRawBits())
        }
    }

    @Test
    fun pulseMaterialAndExplicitLayerGatesMatchOriginalInstructions() {
        val hierarchy = fixture.getJSONArray("hierarchy")
        val rows = fixture.getJSONArray("pulses")
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val level = row.getInt("level")
            val next = if (level >= 2) level - 1 else level + 1
            val state = SymbolLayerOpacity(row.float("drawing"), row.float("erasing"), row.float("common"))
                .pulse(row.float("value"), row.getBoolean("byLayer"), row.getBoolean("explicitPulse"),
                    row.getInt("mode") == 3, hierarchy.getDouble(level).toFloat(), hierarchy.getDouble(next).toFloat())
            assertEquals("drawing $index", row.float("drawingResult").toRawBits(), state.drawing.toRawBits())
            assertEquals("erasing $index", row.float("erasingResult").toRawBits(), state.erasing.toRawBits())
            assertEquals("common $index", row.float("commonResult").toRawBits(), state.common.toRawBits())
        }
    }

    @Test
    fun staticVariableAndDrawVisibilityKeepSeparateChannels() {
        val base = SymbolLayerOpacity(.5f, .8f, .6f)
        assertEquals(SymbolLayerOpacity(.25f, .4f, .6f), base.multiplyBoth(.5f))
        assertEquals(SymbolLayerOpacity(.25f, .4f, .6f), base.variableColor(.5f, 0f, 1f))
        assertEquals(SymbolLayerOpacity(.25f, .8f, .6f), base.multiplyDrawing(.5f, true))
        assertEquals(SymbolLayerOpacity(0f, 0f, .6f), base.multiplyDrawing(0f, true))
        assertEquals(.6f, base.multiplyDrawing(0f, true).erasingOpacity(.35f, true), 0f)
    }

    private fun JSONObject.float(name: String): Float = getDouble(name).toFloat()
}
