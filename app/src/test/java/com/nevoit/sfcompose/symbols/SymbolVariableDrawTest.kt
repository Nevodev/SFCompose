package com.nevoit.sfcompose.symbols

import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolVariableDrawTest {
    @Test
    fun nativeCanvasVariableDrawPreservesInactiveAndSemitransparentActiveMaterial() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("0.circle")
        val full = frame(symbol, null)
        assertArrayEquals(full, frame(symbol, 1f))
        val zero = frame(symbol, 0f)
        val middle = frame(symbol, .5f)
        assertTrue(zero.any { Color.alpha(it) == 38 })
        assertTrue(middle.any { Color.alpha(it) == 128 })
        assertTrue(middle.any { Color.alpha(it) == 38 })
        assertTrue(middle.none { Color.alpha(it) > 128 })
    }

    @Test
    fun nativeCanvasUnannotatedSymbolRemainsStatic() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("square")
        val full = frame(symbol, null)
        assertArrayEquals(full, frame(symbol, 0f))
        assertArrayEquals(full, frame(symbol, .5f))
    }

    @Test
    fun systemStyleRangesDriveAllRenderingModes() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("cellularbars")
        for (mode in SymbolRenderingMode.entries) {
            val draw = SymbolVariableDraw(symbol.nodes, false, mode, symbol.draw::hasGeometry)
            val layers = symbol.nodes.filter { it.source.supportsSymbolMode(mode) }
            assertEquals(4, layers.size)
            layers.forEach { assertNotNull("Missing system Variable Draw range / $mode", draw.progress(it.source, .125f)) }
            assertEquals(.5f, draw.progress(layers[0].source, .125f)!!, 0f)
            assertEquals(0f, draw.progress(layers[1].source, .125f)!!, 0f)
            assertEquals(.5f, draw.progress(layers[2].source, .625f)!!, 0f)
            assertEquals(0f, draw.progress(layers[3].source, .625f)!!, 0f)
            val full = frame(symbol, null, mode)
            assertArrayEquals("Full Variable Draw / $mode", full, frame(symbol, 1f, mode))
            val empty = frame(symbol, 0f, mode)
            val middle = frame(symbol, .125f, mode)
            assertTrue("Slider zero has no effect / $mode", !full.contentEquals(empty))
            assertTrue("Slider middle has no effect / $mode", !middle.contentEquals(empty))
            assertTrue("Slider middle is already complete / $mode", !middle.contentEquals(full))
        }
    }

    @Test
    fun variableDrawSurvivesAFontSizeChangeAndCachedSymbolReload() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val symbol = store.load("cellularbars")
        SymbolDrawTiming(symbol, on = true, pointSize = 112f)
        assertTrue(symbol === store.load("cellularbars"))
        for (mode in SymbolRenderingMode.entries) {
            val zero = frame(symbol, 0f, mode)
            val middle = frame(symbol, .625f, mode)
            val full = frame(symbol, 1f, mode)
            assertTrue("Cached symbol ignores zero / $mode", !zero.contentEquals(full))
            assertTrue("Cached symbol ignores intermediate progress / $mode", !middle.contentEquals(full))
            assertTrue("Cached symbol does not advance / $mode", !zero.contentEquals(middle))
        }
    }

    @Test
    fun drawAnnotationsAloneDoNotCreateNativeVariableRanges() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        for (name in listOf("rainbow", "airplay.audio", "plus")) {
            val symbol = store.load(name)
            for (mode in SymbolRenderingMode.entries) {
                val draw = SymbolVariableDraw(symbol.nodes, false, mode, symbol.draw::hasGeometry)
                assertTrue("Unexpected native Variable Draw support: $name / $mode", !draw.isAvailable)
                symbol.nodes.filter { it.source.supportsSymbolMode(mode) }.forEach {
                    assertNull(draw.progress(it.source, .5f))
                }
                assertArrayEquals("Unannotated native symbol must remain unchanged: $name / $mode",
                    frame(symbol, null, mode), frame(symbol, .5f, mode))
            }
        }
    }

    private fun frame(symbol: SymbolInstance, value: Float?, mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome): IntArray {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 192f, 192f, mode,
            0x804488cc.toInt(), 0x804488cc.toInt(), 0x804488cc.toInt(), false, SymbolEffect.None, 0f,
            variableDrawValue = value)
        return IntArray(192 * 192).also { bitmap.getPixels(it, 0, 192, 0, 0, 192, 192) }
    }

    @Test
    fun enabledLayersReceiveEqualUnroundedVariableRanges() {
        val layers = List(3) { layer() }
        val draw = SymbolVariableDraw(layers, true, SymbolRenderingMode.Monochrome)
        assertEquals(1f, draw.progress(layers[0].source, .5f)!!, 0f)
        assertEquals(.5f, draw.progress(layers[1].source, .5f)!!, 0f)
        assertEquals(0f, draw.progress(layers[2].source, .5f)!!, 0f)
        assertEquals(.99f, draw.progress(layers[0].source, .33f)!!, .00001f)
    }

    @Test
    fun frontToBackReversesOnlyEligibleLayers() {
        val back = layer()
        val disabled = layer().also { it.source.put("enableVariableDraw", false) }
        val empty = layer().also { it.source.remove("drawRailRoadTies") }
        val front = layer()
        val draw = SymbolVariableDraw(listOf(back, disabled, empty, front), false, SymbolRenderingMode.Monochrome)
        assertEquals(1f, draw.progress(front.source, .5f)!!, 0f)
        assertEquals(0f, draw.progress(back.source, .5f)!!, 0f)
        assertNull(draw.progress(disabled.source, .5f))
        assertNull(draw.progress(empty.source, .5f))
    }

    @Test
    fun modeVisibilityAndBooleanShapeGroupsMatchResolvedLayers() {
        val child = layer()
        val shape = SymbolNode.Group(listOf(child), layer().source.put("type", "shapeGroup"))
        val hidden = layer().also { it.source.put("multicolor", JSONObject().put("color", "hidden")) }
        val draw = SymbolVariableDraw(listOf(shape, hidden), true, SymbolRenderingMode.Multicolor)
        assertEquals(.5f, draw.progress(shape.source, .5f)!!, 0f)
        assertNull(draw.progress(child.source, .5f))
        assertNull(draw.progress(hidden.source, .5f))
        assertNull(draw.progress(shape.source, Float.NaN))
        assertEquals(0f, draw.progress(shape.source, -1f)!!, 0f)
        assertEquals(1f, draw.progress(shape.source, 2f)!!, 0f)
    }

    private fun layer() = SymbolNode.Layer(intArrayOf(), JSONObject()
        .put("monochrome", "draw")
        .put("multicolor", JSONObject().put("color", "tintColor"))
        .put("enableVariableDraw", true)
        .put("drawRailRoadTies", JSONObject().put("1", JSONObject().put("ties", JSONArray().put(0).put(1)))))
}
