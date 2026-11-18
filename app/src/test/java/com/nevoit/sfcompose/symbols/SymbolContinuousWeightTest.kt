package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
class SymbolContinuousWeightTest {
    @Test
    fun arbitraryWeightInterpolatesThePathsMetricsAndCompiledDrawTogether() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val catalog = store.catalog()
        val lowPosition = catalog.weightPositions.getValue(SymbolWeight.Regular)
        val highPosition = catalog.weightPositions.getValue(SymbolWeight.Medium)
        val position = (lowPosition + highPosition) * .5f
        for (scale in SymbolScale.entries) {
            val low = store.load("arrow.right", SymbolWeight.Regular, scale)
            val high = store.load("arrow.right", SymbolWeight.Medium, scale)
            val middle = store.load("arrow.right", weightPosition = position, scale = scale)
            assertSame(middle, store.load("arrow.right", weightPosition = position, scale = scale))
            assertEquals(position, middle.weightPosition, 0f)
            assertEquals((low.advance + high.advance) * .5f, middle.advance, .001f)
            for (contour in middle.contourData.indices) for (point in middle.contourData[contour].points.indices) {
                assertEquals((low.contourData[contour].points[point] + high.contourData[contour].points[point]) * .5,
                    middle.contourData[contour].points[point], .001)
            }
            for (mode in SymbolRenderingMode.entries) {
                val node = middle.nodes.first { it.source.supportsSymbolMode(mode) }
                val lowNode = low.nodes.first { it.source.supportsSymbolMode(mode) }
                val highNode = high.nodes.first { it.source.supportsSymbolMode(mode) }
                assertTrue("$scale / $mode", middle.draw.hasSystemAttachments(node.source))
                val first = node.source.getJSONArray("systemClipStrokeKeyframes").getJSONObject(0)
                assertEquals((lowNode.source.getJSONArray("systemClipStrokeKeyframes").getJSONObject(0).getDouble("first") +
                    highNode.source.getJSONArray("systemClipStrokeKeyframes").getJSONObject(0).getDouble("first")) * .5,
                    first.getDouble("first"), .000001)
                val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
                AndroidSymbolPainter(middle).draw(Canvas(bitmap), 160f, 160f, mode, Color.BLUE, Color.BLUE, Color.BLUE,
                    false, SymbolEffect.None, 0f, drawProgress = .5f)
                val pixels = IntArray(160 * 160).also { bitmap.getPixels(it, 0, 160, 0, 0, 160, 160) }
                assertTrue(pixels.any { Color.alpha(it) > 0 })
            }
            assertTrue(middle.drawUnsupportedReasons.toString(), middle.drawUnsupportedReasons.isEmpty())
        }
    }

    @Test
    fun exactNamedPositionsKeepTheSameInstancesAndCacheIsBounded() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val catalog = store.catalog()
        for (weight in SymbolWeight.entries) {
            assertSame(store.load("shareplay", weight),
                store.load("shareplay", weightPosition = catalog.weightPositions.getValue(weight)))
        }
        val start = catalog.weightPositions.getValue(SymbolWeight.Regular)
        val end = catalog.weightPositions.getValue(SymbolWeight.Medium)
        val first = store.load("shareplay", weightPosition = start)
        repeat(52) { store.load("shareplay", weightPosition = start + (end - start) * (it + 1) / 54f) }
        assertFalse("Continuous drags must not retain an unbounded instance history",
            first === store.load("shareplay", weightPosition = start))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidContinuousWeightIsRejected() = runBlocking {
        SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("shareplay", weightPosition = Float.NaN)
        Unit
    }
}
