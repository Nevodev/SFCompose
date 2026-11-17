package com.nevoit.sfcompose.symbols

import android.graphics.Path
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
class SymbolPathBoundsTest {
    @Test
    fun quadraticAndCubicBoundsIncludeTheCurveRatherThanItsControls() {
        val quadratic = symbolContourBounds(SymbolContourData("MQZ", doubleArrayOf(0.0, 0.0, 100.0, 100.0, 200.0, 0.0)))
        assertEquals(50f, quadratic.bottom, 0.0001f)
        assertEquals(200f, quadratic.right, 0f)
        val cubic = symbolContourBounds(SymbolContourData("MCZ", doubleArrayOf(0.0, 0.0, 0.0, 100.0, 100.0, 100.0, 100.0, 0.0)))
        assertEquals(75f, cubic.bottom, 0.0001f)
        assertEquals(100f, cubic.right, 0f)
    }

    @Test
    fun retainedAppFontCurveMatchesIndependentFontToolsExtrema() {
        val fixture = javaClass.getResourceAsStream("/symbols/legacy-timemachine-bounds.json")!!
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val contours = fixture.getJSONArray("contours")
        val bounds = android.graphics.RectF().apply {
            for (index in 0 until contours.length()) {
                val contour = contours.getJSONObject(index)
                val points = contour.getJSONArray("points")
                union(symbolContourBounds(SymbolContourData(contour.getString("verbs"),
                    DoubleArray(points.length()) { points.getDouble(it) })))
            }
        }
        assertEquals(-219.8054757f, bounds.left, 0.0001f)
    }

    @Test
    fun resolvedAndroidPathUsesCurveExtremaAfterBooleanShapeOperations() {
        val path = Path().apply {
            moveTo(0f, 0f)
            cubicTo(0f, 100f, 100f, 100f, 100f, 0f)
            close()
        }
        assertEquals(75f, symbolPathBounds(path).bottom, 0.0001f)
        val cutout = Path().apply { addRect(-1f, 50f, 101f, 101f, Path.Direction.CW) }
        check(path.op(cutout, Path.Op.DIFFERENCE))
        val bounds = symbolPathBounds(path)
        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(0f, bounds.top, 0.0001f)
        assertEquals(100f, bounds.right, 0.0001f)
        assertEquals(50f, bounds.bottom, 0.0001f)
    }

    @Test
    fun platformConicSegmentsKeepTheirExactRationalCurveExtrema() {
        val path = Path().apply { addOval(-100f, -50f, 100f, 50f, Path.Direction.CW) }
        val bounds = symbolPathBounds(path)
        assertEquals(-100f, bounds.left, 0.0001f)
        assertEquals(-50f, bounds.top, 0.0001f)
        assertEquals(100f, bounds.right, 0.0001f)
        assertEquals(50f, bounds.bottom, 0.0001f)
        path.transform(android.graphics.Matrix().apply { setRotate(45f) })
        val rotated = symbolPathBounds(path)
        val extent = kotlin.math.sqrt(6250f)
        assertEquals(-extent, rotated.left, 0.0001f)
        assertEquals(-extent, rotated.top, 0.0001f)
        assertEquals(extent, rotated.right, 0.0001f)
        assertEquals(extent, rotated.bottom, 0.0001f)
    }
}
