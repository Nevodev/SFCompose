package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolAdaptiveCapTest {
    @Test
    fun productionNaturalCapsMatchTheWebsiteRotationReference() = runBlocking {
        val fixture = javaClass.getResourceAsStream("/symbols/site-adaptive-cap-on.json")!!
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }.getJSONArray("frames")
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val failures = mutableListOf<String>()
        fun path(commands: JSONArray) = Path().apply {
            for (index in 0 until commands.length()) {
                val command = commands.getJSONArray(index)
                fun p(i: Int) = command.getDouble(i).toFloat()
                when (command.getString(0)) {
                    "M" -> moveTo(p(1), p(2))
                    "L" -> lineTo(p(1), p(2))
                    "Q" -> quadTo(p(1), p(2), p(3), p(4))
                    "C" -> cubicTo(p(1), p(2), p(3), p(4), p(5), p(6))
                    "Z" -> close()
                }
            }
        }
        for (index in 0 until fixture.length()) {
            val frame = fixture.getJSONObject(index)
            val symbol = store.load(frame.getString("name"), scale = SymbolScale.Small)
            val layer = symbol.nodes.filterIsInstance<SymbolNode.Layer>().single {
                it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome) &&
                    it.source.getInt("systemNativeLayerIndex") == frame.getInt("part")
            }
            val regular = (0 until symbol.sourceMasters.length()).map(symbol.sourceMasters::getJSONObject)
                .single { it.getDouble("weight") == 400.0 }
            val outer = regular.getJSONArray("systemTransform")
            val toFont = Matrix().apply { setValues(floatArrayOf(
                1f / outer.getDouble(0).toFloat(), 0f, (-outer.getDouble(4) / outer.getDouble(0)).toFloat(),
                0f, -1f / outer.getDouble(3).toFloat(), (outer.getDouble(5) / outer.getDouble(3)).toFloat(),
                0f, 0f, 1f)) }
            val expectedPath = path(frame.getJSONArray("commands")).apply { transform(toFont) }
            val capPath = path(frame.getJSONArray("capCommands")).apply { transform(toFont) }
            val expected = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            val pixelScale = 160f / symbol.unitsPerEm
            val toScreen = Matrix().apply {
                setScale(pixelScale, -pixelScale)
                postTranslate(256f - pixelScale * symbol.metricCenterX, 192f + pixelScale * symbol.metricCenterY)
            }
            Canvas(expected).apply {
                concat(toScreen)
                drawPath(expectedPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
            }
            val actual = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
            AndroidSymbolPainter(symbol).draw(Canvas(actual).apply { translate(0f, 112f) }, 512f, 160f,
                SymbolRenderingMode.Monochrome, Color.BLUE, Color.BLUE, Color.BLUE, false, SymbolEffect.None, 0f,
                drawProgress = frame.getDouble("progress").toFloat(),
                replacementState = { SymbolReplaceLayerState(opacity = if (it === layer.source) 1f else 0f) })
            capPath.transform(toScreen)
            val roi = RectF().also { capPath.computeBounds(it, true); it.inset(-2f, -2f) }
            var error = 0L
            var coverage = 0L
            for (y in roi.top.toInt().coerceAtLeast(0)..roi.bottom.toInt().coerceAtMost(383)) {
                for (x in roi.left.toInt().coerceAtLeast(0)..roi.right.toInt().coerceAtMost(511)) {
                    val alpha = Color.alpha(expected.getPixel(x, y))
                    coverage += alpha
                    error += abs(alpha - Color.alpha(actual.getPixel(x, y)))
                }
            }
            if (error > coverage * .08 + 2 * 255) failures += "${symbol.name}/${frame.getInt("part")}/${frame.getDouble("progress")}: $error/$coverage"
            val output = File("build/reports/symbols/site-adaptive/${symbol.name}-${frame.getInt("part")}-${frame.getDouble("progress")}.png")
            output.parentFile!!.mkdirs()
            val comparison = Bitmap.createBitmap(1024, 384, Bitmap.Config.ARGB_8888)
            Canvas(comparison).apply { drawBitmap(expected, 0f, 0f, null); drawBitmap(actual, 512f, 0f, null) }
            output.outputStream().use { comparison.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun cellularBarCrownsStayCenteredDuringDrawOnAndOff() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        for (opticalScale in SymbolScale.entries) for (weight in listOf(SymbolWeight.Ultralight, SymbolWeight.Regular, SymbolWeight.Black)) {
            val symbol = store.load("cellularbars", weight = weight, scale = opticalScale)
            for (layer in symbol.nodes.filterIsInstance<SymbolNode.Layer>()
                .filter { it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome) }) {
                val original = Path().apply { layer.components.forEach { addPath(symbol.contours[it]) } }
                val bounds = RectF().also { original.computeBounds(it, true) }
                for ((on, progress) in listOf(true to .98f, false to .02f)) {
                    val path = symbol.draw.pathFor(layer.source, original, progress, isOn = on, pointSize = 320f,
                        adaptiveCaps = true)!!
                    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply {
                        translate(256f, 256f)
                        scale(320f / symbol.unitsPerEm, -320f / symbol.unitsPerEm)
                        translate(-bounds.centerX(), -bounds.centerY())
                        drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
                    }
                    val rows = (0 until 512).filter { y -> (0 until 512).any { x -> Color.alpha(bitmap.getPixel(x, y)) >= 200 } }
                    assertTrue(rows.isNotEmpty())
                    val crownY = if (on) rows.first() else rows.last()
                    val crownX = (0 until 512).filter { Color.alpha(bitmap.getPixel(it, crownY)) >= 200 }.average()
                    assertTrue("Tilted cap: $opticalScale/$weight on=$on part=${layer.components.toList()} crown=$crownX",
                        abs(crownX - 256.0) <= 2.0)
                }
            }
        }
    }

}
