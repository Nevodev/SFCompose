package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolFontSizingTest {
    private val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)

    private fun frame(symbol: SymbolInstance, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height + 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { translate(0f, 160f) }
        AndroidSymbolPainter(symbol).draw(canvas, width.toFloat(), height.toFloat(),
            SymbolRenderingMode.Monochrome, Color.BLUE, Color.BLUE, Color.BLUE, false, SymbolEffect.None, 0f)
        return bitmap
    }

    private fun ink(symbol: SymbolInstance, width: Int, height: Int): Rect {
        val bitmap = frame(symbol, width, height)
        var left = width
        var top = bitmap.height
        var right = 0
        var bottom = 0
        for (y in 0 until bitmap.height) for (x in 0 until width) if (Color.alpha(bitmap.getPixel(x, y)) > 127) {
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        return Rect(left, top, right, bottom)
    }

    @Test
    fun glyphMetricsDetermineSizeAndBaselineForEveryOpticalScale() = runBlocking {
        for (name in listOf("minus", "circle", "arrow.left.and.right")) for (scale in SymbolScale.entries) {
            val symbol = store.load(name, scale = scale)
            val bounds = android.graphics.RectF()
            for (contour in symbol.contourData) bounds.union(symbolContourBounds(contour))
            val actual = ink(symbol, 512, 160)
            val unit = 160f / symbol.unitsPerEm
            assertEquals("$name/$scale top", 240f - (bounds.bottom - symbol.metricCenterY) * unit, actual.top.toFloat(), 1.1f)
            assertEquals("$name/$scale bottom", 240f - (bounds.top - symbol.metricCenterY) * unit, actual.bottom.toFloat(), 1.1f)
            assertEquals("$name/$scale left", 256f + (bounds.left - symbol.metricCenterX) * unit, actual.left.toFloat(), 1.1f)
            assertEquals("$name/$scale right", 256f + (bounds.right - symbol.metricCenterX) * unit, actual.right.toFloat(), 1.1f)
        }
    }

    @Test
    fun narrowCanvasDoesNotShrinkWideGlyphs() = runBlocking {
        for (name in listOf("circle", "cellularbars", "airplay.audio")) {
            val symbol = store.load(name)
            val narrow = frame(symbol, 80, 160)
            val wide = frame(symbol, 512, 160)
            var referenceCoverage = 0L
            var difference = 0L
            for (y in 0 until narrow.height) for (x in 0 until narrow.width) {
                val expected = Color.alpha(wide.getPixel(x + 216, y))
                referenceCoverage += expected
                difference += kotlin.math.abs(expected - Color.alpha(narrow.getPixel(x, y)))
            }
            assertTrue("$name must keep its font scale: alpha difference $difference/$referenceCoverage",
                difference < referenceCoverage * 0.01 + 4 * 255)
        }
        val minus = store.load("minus")
        assertTrue("A minus must retain its source stroke height rather than filling the box", ink(minus, 512, 160).height() < 30)
    }
}
