package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolReplaceAttachmentTest {
    private fun frame(first: SymbolInstance, second: SymbolInstance, plan: SymbolReplacePlan,
        mode: SymbolRenderingMode, time: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        for ((symbol, incoming) in listOf(first to false, second to true)) {
            AndroidSymbolPainter(symbol).draw(Canvas(bitmap).apply { translate(0f, 104f) }, 640f, 192f,
                mode, Color.BLUE, Color.BLUE, Color.BLUE, false, SymbolEffect.None, time,
                replacementState = { plan.layerState(it, incoming, time) }, pointSize = 192f)
        }
        return bitmap
    }

    @Test
    fun incomingArrowHeadsDoNotAppearBeforeTheReplacementCutover() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val first = store.load("person.fill")
        val second = store.load("person.fill.and.arrow.left.and.arrow.right.outward")
        for (mode in SymbolRenderingMode.entries) {
            val plan = SymbolReplacePlan(first, second, mode = mode, pointSize = 192f)
            val actual = frame(first, second, plan, mode, .12f)
            val oldOnly = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
            AndroidSymbolPainter(first).draw(Canvas(oldOnly).apply { translate(0f, 104f) }, 640f, 192f,
                mode, Color.BLUE, Color.BLUE, Color.BLUE, false, SymbolEffect.None, .12f,
                replacementState = { plan.layerState(it, false, .12f) }, pointSize = 192f)
            var difference = 0L
            for (y in 0 until 400) for (x in 0 until 640) {
                difference += kotlin.math.abs(Color.alpha(actual.getPixel(x, y)) - Color.alpha(oldOnly.getPixel(x, y)))
            }
            val output = File("build/reports/symbols/replace-attachments/$mode-before.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("Incoming target leaked arrow heads at .12s / $mode: $difference", difference == 0L)
        }
    }

    @Test
    fun rejectedAttachmentStoreUsesRecoveredDrawInBothDirections() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val plain = store.load("person.fill")
        val arrows = store.load("person.fill.and.arrow.left.and.arrow.right.outward")
        for (mode in SymbolRenderingMode.entries) for (direction in SymbolReplaceDirection.entries) {
            val layer = arrows.nodes.single { node ->
                node.source.supportsSymbolMode(mode) && !SymbolDrawAttachments.systemRecords(
                    node.source, node.source.getString("systemRenderingMode")).isNullOrEmpty()
            }
            assertTrue(!arrows.draw.hasSystemAttachments(layer.source))
            assertTrue(arrows.draw.hasSystemAttachments(layer.source, recoverMissingAnchors = true))
            for (incoming in listOf(false, true)) {
                val first = if (incoming) plain else arrows
                val second = if (incoming) arrows else plain
                val plan = SymbolReplacePlan(first, second, direction, mode = mode, pointSize = 192f)
                assertEquals(SymbolReplaceKind.Draw, plan.kind)
                for (i in 1..29) {
                    val state = plan.layerState(layer.source, incoming, plan.durationSeconds * i / 30f)
                    if (incoming || direction != SymbolReplaceDirection.OffUp) {
                        assertTrue("Recovered attachment must animate with Draw", state.drawProgress != null)
                    }
                    assertNull(state.pathOverride)
                }
                assertPixelsEqual("Initial $mode / $direction / incoming=$incoming", staticFrame(first, mode),
                    frame(first, second, plan, mode, 0f))
                assertPixelsEqual("Final $mode / $direction / incoming=$incoming", staticFrame(second, mode),
                    frame(first, second, plan, mode, plan.durationSeconds))
            }
            val plan = SymbolReplacePlan(plain, arrows, direction, mode = mode, pointSize = 192f)
            val times = listOf(0f, .12f, .35f, .5f, .7f, plan.durationSeconds)
            val sheet = Bitmap.createBitmap(640 * times.size, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            times.forEachIndexed { index, time ->
                canvas.drawBitmap(frame(plain, arrows, plan, mode, time), index * 640f, 0f, null)
            }
            val output = File("build/reports/symbols/replace-attachments/$mode-$direction-sequence.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun validReplacementAttachmentsUseTheSameTimeStateAsStandaloneDraw() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val plain = store.load("person.fill")
        val attached = store.load("homepod.mini.arrow.forward")
        for (mode in SymbolRenderingMode.entries) for (on in listOf(false, true)) {
            val waiting = SymbolReplacePlan(plain, attached, mode = mode, pointSize = 192f)
            for (node in attached.nodes.filter { it.source.supportsSymbolMode(mode) }) {
                val state = waiting.layerState(node.source, true, .12f)
                assertEquals("Incoming Draw layers must wait for cutover / $mode", 0f, state.opacity, 0f)
            }
            val layer = attached.nodes.single { node ->
                node.source.supportsSymbolMode(mode) && attached.draw.hasSystemAttachments(node.source)
            }
            val plan = if (on) SymbolReplacePlan(plain, attached, SymbolReplaceDirection.OffUp,
                mode = mode, pointSize = 192f)
            else SymbolReplacePlan(attached, plain, mode = mode, pointSize = 192f)
            assertEquals(SymbolReplaceKind.Draw, plan.kind)
            val timing = SymbolDrawTiming(attached, on = on, mode = mode, pointSize = 192f)
            var visibleFrames = 0
            for (time in listOf(.1f, .25f, timing.drawDurationSeconds - .01f)) {
                fun render(replace: Boolean): Bitmap {
                    val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
                    AndroidSymbolPainter(attached).draw(Canvas(bitmap).apply { translate(0f, 104f) }, 640f, 192f,
                        mode, Color.BLUE, Color.BLUE, Color.BLUE, false, SymbolEffect.None, time,
                        drawEffect = if (replace) null else if (on) SymbolDrawEffect.On else SymbolDrawEffect.Off,
                        replacementState = { source ->
                            if (source !== layer.source) SymbolReplaceLayerState(opacity = 0f)
                            else if (replace) plan.layerState(source, on, time)
                            else SymbolReplaceLayerState(drawOn = on)
                        }, pointSize = 192f)
                    return bitmap
                }
                val state = plan.layerState(layer.source, on, time)
                val index = layer.source.getInt("sourceLayerIndex")
                assertEquals(timing.systemState(layer.source, time), state.systemDrawState?.invoke(index))
                val expected = render(false)
                if (pixels(expected).any { Color.alpha(it) > 0 }) visibleFrames++
                assertPixelsEqual("Valid attachment $mode / on=$on / time=$time", expected, render(true))
            }
            assertTrue("Fixture must exercise visible attachment pixels / $mode / on=$on", visibleFrames > 0)
        }
    }

    private fun staticFrame(symbol: SymbolInstance, mode: SymbolRenderingMode): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        AndroidSymbolPainter(symbol).draw(Canvas(bitmap).apply { translate(0f, 104f) }, 640f, 192f,
            mode, Color.BLUE, Color.BLUE, Color.BLUE, false, SymbolEffect.None, 0f, pointSize = 192f)
        return bitmap
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun assertPixelsEqual(message: String, expected: Bitmap, actual: Bitmap) {
        assertTrue(message, pixels(expected).contentEquals(pixels(actual)))
    }
}
