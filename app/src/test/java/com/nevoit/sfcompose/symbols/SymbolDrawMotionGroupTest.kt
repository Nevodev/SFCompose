package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
class SymbolDrawMotionGroupTest {
    @Test
    fun sourceOrderToggleReversesStartsIndependentlyOfDrawOffReversed() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("cellularbars")
        val groups = symbolMotionGroups(symbol.nodes, SymbolRenderingMode.Monochrome)
        val nodes = symbol.nodes.filterIsInstance<SymbolNode.Layer>()
            .filter { it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome) }
            .sortedBy { groups.indices[it.source] }
        assertTrue(nodes.size > 1)
        val sourceReversed = symbol.metadata.optString("variableColorOrder") == "backToFront"
        for (on in listOf(true, false)) {
            for (reversed in listOf(true, false)) {
                val ordinary = SymbolDrawTiming(symbol, on, reverse = reversed)
                val toggled = SymbolDrawTiming(symbol, on, reverse = reversed, drawReversesMotionGroups = true)
                val first = if (sourceReversed xor (!on && reversed)) nodes.last() else nodes.first()
                val last = if (first === nodes.first()) nodes.last() else nodes.first()
                assertTrue(ordinary.progress(first.source, .001f)!! > 0f)
                assertEquals(0f, ordinary.progress(last.source, .001f)!!, 0f)
                assertEquals(0f, toggled.progress(first.source, .001f)!!, 0f)
                assertTrue(toggled.progress(last.source, .001f)!! > 0f)
                val counterOption = SymbolDrawTiming(symbol, on, reverse = !reversed)
                for (node in nodes) {
                    val expected = if (on) ordinary else toggled
                    assertEquals(expected.progress(node.source, .5f)!!,
                        counterOption.progress(node.source, .5f)!!, .00001f)
                }
            }
        }
    }

    @Test
    fun motionGroupToggleLeavesPathDirectionAndSingleGroupFramesUnchanged() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val multi = AndroidSymbolPainter(store.load("cellularbars"))
        for (on in listOf(true, false)) {
            for (reversed in listOf(true, false)) {
                assertArrayEquals(frame(multi, on, reversed, false, .4f), frame(multi, on, reversed, true, .4f))
            }
            assertTrue(!frame(multi, on, false, false).contentEquals(frame(multi, on, false, true)))
        }
        val circle = store.load("circle")
        assertEquals(1, symbolMotionGroups(circle.nodes, SymbolRenderingMode.Monochrome).indices.values.distinct().size)
        val single = AndroidSymbolPainter(circle)
        for (on in listOf(true, false)) {
            for (reversed in listOf(true, false)) {
                assertArrayEquals(frame(single, on, reversed, false), frame(single, on, reversed, true))
            }
        }
    }

    private fun frame(painter: AndroidSymbolPainter, on: Boolean, reversed: Boolean,
        reversesMotionGroups: Boolean, progress: Float? = null): IntArray {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(bitmap), 192f, 192f, SymbolRenderingMode.Monochrome,
            Color.RED, Color.GREEN, Color.BLUE, false, SymbolEffect.None, .2f,
            drawProgress = progress, drawOn = on,
            drawEffect = if (progress == null) if (on) SymbolDrawEffect.On else SymbolDrawEffect.Off else null,
            drawReversed = reversed, drawReversesMotionGroups = reversesMotionGroups)
        return IntArray(192 * 192).also { bitmap.getPixels(it, 0, 192, 0, 0, 192, 192) }
    }
}
