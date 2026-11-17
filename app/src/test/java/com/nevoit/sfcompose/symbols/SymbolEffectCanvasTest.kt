package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
class SymbolEffectCanvasTest {
    @Test
    fun publicPainterReversedDrawUsesTheOppositeNativeClipInterval() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("circle")
        val painter = AndroidSymbolPainter(symbol)
        fun draw(on: Boolean, reversed: Boolean, progress: Float): IntArray {
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            painter.draw(Canvas(bitmap), 192f, 192f, SymbolRenderingMode.Monochrome,
                Color.RED, Color.GREEN, Color.BLUE, false, SymbolEffect.None, 0f,
                drawProgress = progress, drawOn = on, drawReversed = reversed)
            return IntArray(192 * 192).also { bitmap.getPixels(it, 0, 192, 0, 0, 192, 192) }
        }
        val on = draw(true, false, .5f)
        val off = draw(false, false, .5f)
        assertTrue(!on.contentEquals(off))
        assertArrayEquals(off, draw(true, true, .5f))
        assertArrayEquals(on, draw(false, true, .5f))
        assertArrayEquals(draw(true, false, 1f), draw(true, true, 1f))
        assertEquals(0L, coverage(draw(true, true, 0f)))
        assertEquals(0L, coverage(draw(false, true, 1f)))
    }
    @Test
    fun appearAndDisappearActuallyChangeCanvasCoverage() = runBlocking {
        val symbol = symbol()
        assertEquals(0L, coverage(frame(symbol, SymbolEffect.Appear, 0f)))
        assertEquals(coverage(frame(symbol)), coverage(frame(symbol, SymbolEffect.Appear, 1f)))
        assertEquals(coverage(frame(symbol)), coverage(frame(symbol, SymbolEffect.Disappear, 0f)))
        assertEquals(0L, coverage(frame(symbol, SymbolEffect.Disappear, 1f)))
        assertTrue(coverage(frame(symbol, SymbolEffect.AppearDown, .125f)) > 0L)
    }

    @Test
    fun scaleReturnsToItsOriginalCanvasCoverageAfterTheNativeHold() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("circle.fill")
        val base = coverage(frame(symbol))
        assertTrue(coverage(frame(symbol, SymbolEffect.ScaleUp, .5f)) > base)
        assertTrue(coverage(frame(symbol, SymbolEffect.ScaleDown, .5f)) < base)
        assertEquals(base, coverage(frame(symbol, SymbolEffect.ScaleUp, 1f)))
        assertEquals(base, coverage(frame(symbol, SymbolEffect.ScaleDown, 1f)))
    }

    @Test
    fun breathePulseDistinguishesWholeOpacityFromAnnotatedHierarchyAndPalette() = runBlocking {
        val symbol = symbol()
        for (node in symbol.nodes) {
            node.source.put("canPulse", true).put("hierarchical", 0)
            for (mode in SymbolRenderingMode.entries) node.source.systemStyle(mode)
                ?.put("-sfsymbols-always-pulses", "true")
        }
        val whole = frame(symbol, SymbolEffect.BreathePulse, 1.5f)
        assertEquals(76, whole.maxOf { Color.alpha(it) })
        val layered = frame(symbol, SymbolEffect.BreathePulse, 1.5f,
            SymbolEffectLayerMode.ByLayer, SymbolRenderingMode.Hierarchical)
        assertEquals(127, layered.maxOf { Color.alpha(it) })
        val palette = frame(symbol, SymbolEffect.BreathePulse, 1.5f,
            SymbolEffectLayerMode.ByLayer, SymbolRenderingMode.Palette)
        assertTrue(palette.any { it == Color.GREEN })
        assertTrue(palette.none { it == Color.RED })
    }

    @Test
    fun pulseUsesTheOriginalHierarchyMaterialTransition() = runBlocking {
        val symbol = symbol()
        for (node in symbol.nodes) {
            node.source.put("canPulse", true).put("hierarchical", 0)
            for (mode in SymbolRenderingMode.entries) node.source.systemStyle(mode)
                ?.put("-sfsymbols-always-pulses", "true")
        }
        val hierarchy = frame(symbol, SymbolEffect.Pulse, 1f,
            SymbolEffectLayerMode.ByLayer, SymbolRenderingMode.Hierarchical)
        assertEquals(127, hierarchy.maxOf { Color.alpha(it) })
        val palette = frame(symbol, SymbolEffect.Pulse, 1f,
            SymbolEffectLayerMode.ByLayer, SymbolRenderingMode.Palette)
        assertTrue(palette.any { it == Color.GREEN })
        assertTrue(palette.none { it == Color.RED })
    }

    @Test
    fun explicitWiggleDirectionsMoveTheCanvasInTheRequestedDirection() = runBlocking {
        val symbol = symbol()
        val rest = center(frame(symbol))
        val right = center(frame(symbol, SymbolEffect.WiggleRight, .167f))
        val left = center(frame(symbol, SymbolEffect.WiggleLeft, .167f))
        val up = center(frame(symbol, SymbolEffect.WiggleUp, .167f))
        val down = center(frame(symbol, SymbolEffect.WiggleDown, .167f))
        assertTrue(right.first > rest.first && left.first < rest.first)
        assertTrue(up.second < rest.second && down.second > rest.second)
    }

    private suspend fun symbol(): SymbolInstance = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("square")

    private fun frame(symbol: SymbolInstance, effect: SymbolEffect = SymbolEffect.None, time: Float = 0f,
        layerMode: SymbolEffectLayerMode = SymbolEffectLayerMode.WholeSymbol,
        mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome): IntArray {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 192f, 192f, mode,
            Color.RED, Color.GREEN, Color.BLUE, false, effect, time, effectLayerMode = layerMode)
        return IntArray(192 * 192).also { bitmap.getPixels(it, 0, 192, 0, 0, 192, 192) }
    }

    private fun coverage(pixels: IntArray): Long = pixels.sumOf { Color.alpha(it).toLong() }

    private fun center(pixels: IntArray): Pair<Double, Double> {
        val total = coverage(pixels).toDouble()
        return pixels.indices.sumOf { (it % 192) * Color.alpha(pixels[it]).toDouble() } / total to
            pixels.indices.sumOf { (it / 192) * Color.alpha(pixels[it]).toDouble() } / total
    }
}
