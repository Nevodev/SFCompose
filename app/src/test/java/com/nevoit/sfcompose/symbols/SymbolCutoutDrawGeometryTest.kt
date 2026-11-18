package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.zip.GZIPInputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolCutoutDrawGeometryTest {
    @Test
    fun animatedClearBehindUsesTheOriginalNativeDrawAndAttachmentCommands() = runBlocking {
        val fixture = javaClass.getResourceAsStream("/symbols/system-draw-cutout.json.gz")!!.use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        }
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load(fixture.getString("name"))
        val base = layer(symbol, 0)
        val mask = layer(symbol, 1)
        assertTrue(mask.source.symbolErases(SymbolRenderingMode.Monochrome))
        assertTrue(symbol.draw.hasSystemAttachments(mask.source))
        val frames = fixture.getJSONArray("frames")
        fun nativeHeadBounds(progress: Double): RectF {
            val frame = (0 until frames.length()).map(frames::getJSONObject).single {
                it.getBoolean("on") && it.getDouble("progress") == progress
            }
            val commands = frame.getJSONArray("commands")
            val start = (0 until commands.length()).last { commands.getJSONArray(it).getString(0) == "M" }
            val head = JSONArray().apply { for (i in start until commands.length()) put(commands.getJSONArray(i)) }
            return RectF().also { path(head).computeBounds(it, true) }
        }
        val earlyHead = nativeHeadBounds(.25)
        val lateHead = nativeHeadBounds(.75)
        assertTrue("The native attachment must move the clear region right", lateHead.centerX() > earlyHead.centerX() + 30f)
        assertTrue("The native attachment must move the clear region up", lateHead.centerY() > earlyHead.centerY() + 30f)
        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            val nativeOn = frame.getBoolean("on")
            val nativeProgress = frame.getDouble("progress").toFloat()
            val state = SystemDrawState(if (nativeOn) 0f else nativeProgress,
                if (nativeOn) nativeProgress else 1f, .8f, 1f)
            for (reverse in listOf(false, true)) for (alpha in listOf(48, 144, 255)) {
                val on = nativeOn != reverse
                val progress = if (reverse) 1f - nativeProgress else nativeProgress
                val actual = render(symbol, alpha, reverse) { source ->
                    if (source !== mask.source) SymbolReplaceLayerState()
                    else SymbolReplaceLayerState(drawProgress = progress, drawOn = on, systemDrawState = { state })
                }
                val expected = reference(symbol, contourPath(symbol, base), path(frame.getJSONArray("commands")), alpha)
                assertPixelsClose("Native cutout on=$on reverse=$reverse p=$progress tintAlpha=$alpha", expected, actual)
                expected.recycle()
                actual.recycle()
            }
        }
    }

    @Test
    fun independentStaticClearBehindDoesNotInheritTheForegroundAttachmentPath() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        for (name in listOf("homepod.mini.arrow.forward", "rectangle.portrait.and.arrow.forward")) {
            val symbol = store.load(name)
            val base = layer(symbol, 0)
            val mask = layer(symbol, 1)
            val foreground = layer(symbol, 2)
            assertTrue(mask.source.symbolErases(SymbolRenderingMode.Monochrome))
            assertFalse(symbol.draw.hasGeometry(mask.source))
            assertFalse(symbol.draw.hasSystemAttachments(mask.source, true))
            assertTrue(symbol.draw.hasSystemAttachments(foreground.source, true))
            for (on in listOf(false, true)) for (reverse in listOf(false, true)) {
                for (progress in listOf(.1f, .5f, .9f)) for (alpha in listOf(48, 144, 255)) {
                    val actual = render(symbol, alpha, reverse) { source ->
                        when (source) {
                            foreground.source -> SymbolReplaceLayerState(opacity = 0f)
                            mask.source -> SymbolReplaceLayerState(drawProgress = progress, drawOn = on)
                            else -> SymbolReplaceLayerState()
                        }
                    }
                    val expected = reference(symbol, contourPath(symbol, base), contourPath(symbol, mask), alpha)
                    assertPixelsClose("Independent cutout $name on=$on reverse=$reverse p=$progress tintAlpha=$alpha", expected, actual)
                    expected.recycle()
                    actual.recycle()
                }
            }
        }
    }

    private fun layer(symbol: SymbolInstance, index: Int): SymbolNode.Layer =
        symbol.nodes.filterIsInstance<SymbolNode.Layer>().single {
            it.source.optString("systemRenderingMode") == "monochrome" &&
                it.source.optInt("systemNativeLayerIndex", -1) == index
        }

    private fun contourPath(symbol: SymbolInstance, layer: SymbolNode.Layer) = Path().apply {
        layer.components.forEach { addPath(symbol.contours[it]) }
    }

    private fun render(symbol: SymbolInstance, alpha: Int, reverse: Boolean = false,
        replacement: (JSONObject) -> SymbolReplaceLayerState): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        AndroidSymbolPainter(symbol, adaptiveCaps = false).draw(Canvas(bitmap), 256f, 256f,
            SymbolRenderingMode.Monochrome, Color.argb(alpha, 0, 0, 255), Color.BLUE, Color.BLUE,
            false, SymbolEffect.None, 0f, drawReversed = reverse, pointSize = 32f, replacementState = replacement)
        return bitmap
    }

    private fun reference(symbol: SymbolInstance, base: Path, mask: Path, alpha: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        Canvas(bitmap).apply {
            val checkpoint = saveLayer(null, null)
            translate(128f, 128f)
            scale(256f / symbol.unitsPerEm, -256f / symbol.unitsPerEm)
            translate(-symbol.metricCenterX, -symbol.metricCenterY)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(alpha, 0, 0, 255) }
            drawPath(base, paint)
            paint.color = Color.BLACK
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            drawPath(mask, paint)
            restoreToCount(checkpoint)
        }
        return bitmap
    }

    private fun assertPixelsClose(message: String, expected: Bitmap, actual: Bitmap) {
        fun pixels(bitmap: Bitmap) = IntArray(256 * 256).also { bitmap.getPixels(it, 0, 256, 0, 0, 256, 256) }
        val reference = pixels(expected)
        val result = pixels(actual)
        fun difference(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
        val error = reference.indices.sumOf { difference(reference[it], result[it]).toLong() }
        val coverage = reference.sumOf { difference(it, Color.GREEN).toLong() }
        assertTrue("$message error=$error coverage=$coverage", error <= coverage * .015 + 4096)
    }

    private fun path(commands: JSONArray): Path = Path().apply {
        for (index in 0 until commands.length()) {
            val command = commands.getJSONArray(index)
            fun v(i: Int) = command.getDouble(i).toFloat()
            when (command.getString(0)) {
                "M" -> moveTo(v(1), v(2))
                "L" -> lineTo(v(1), v(2))
                "Q" -> quadTo(v(1), v(2), v(3), v(4))
                "C" -> cubicTo(v(1), v(2), v(3), v(4), v(5), v(6))
                "Z" -> close()
            }
        }
    }
}
