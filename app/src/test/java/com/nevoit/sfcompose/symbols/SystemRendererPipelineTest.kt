package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemRendererPipelineTest {
    @Test
    fun nativeCapModeUsesTheSystemClipAtItsActualFontSize() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val failures = ArrayList<String>()
        for ((pointSize, density) in listOf(32 to 1, 32 to 3, 160 to 1)) {
            val pixelHeight = pointSize * density
            val records = javaClass.getResourceAsStream("/draw-system-canvas13-point$pointSize-in-font-coordinates.json")!!.use {
                JSONArray(it.bufferedReader(Charsets.UTF_8).readText())
            }
            val frames = (0 until records.length()).map(records::getJSONObject).groupBy {
                Triple(it.getString("name"), it.getBoolean("on"), it.getDouble("progress").toFloat())
            }
            for ((key, entries) in frames) {
                val (fixtureName, on, progress) = key
                val opticalScale = SymbolScale.entries.single { fixtureName.endsWith("-$it") }
                val symbol = store.load(fixtureName.removeSuffix("-$opticalScale"), scale = opticalScale)
                val expected = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
                val nativePath = Path()
                val animatedParts = entries.map { it.getInt("part") }.toSet()
                symbol.contours.forEachIndexed { index, path -> if (index !in animatedParts) nativePath.addPath(path) }
                for (entry in entries) {
                    val commands = entry.getJSONArray("commands")
                    for (i in 0 until commands.length()) {
                        val command = commands.getJSONArray(i)
                        fun p(index: Int) = command.getDouble(index).toFloat()
                        when (command.getString(0)) {
                            "M" -> nativePath.moveTo(p(1), p(2))
                            "L" -> nativePath.lineTo(p(1), p(2))
                            "Q" -> nativePath.quadTo(p(1), p(2), p(3), p(4))
                            "C" -> nativePath.cubicTo(p(1), p(2), p(3), p(4), p(5), p(6))
                            "Z" -> nativePath.close()
                        }
                    }
                }
                Canvas(expected).apply {
                    translate(256f, 192f)
                    val scale = pixelHeight / symbol.unitsPerEm
                    scale(scale, -scale)
                    translate(-symbol.metricCenterX, -symbol.metricCenterY)
                    drawPath(nativePath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
                }
                val actual = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(actual).apply { translate(0f, (384f - pixelHeight) * 0.5f) }
                AndroidSymbolPainter(symbol, adaptiveCaps = false).draw(canvas, 512f, pixelHeight.toFloat(), SymbolRenderingMode.Monochrome,
                    Color.BLUE, Color.BLUE, Color.BLUE, false, SymbolEffect.None, 0f,
                    drawProgress = progress, drawOn = on, pointSize = pointSize.toFloat())
                val first = IntArray(512 * 384).also { expected.getPixels(it, 0, 512, 0, 0, 512, 384) }
                val second = IntArray(first.size).also { actual.getPixels(it, 0, 512, 0, 0, 512, 384) }
                val error = first.indices.sumOf { abs((first[it] ushr 24) - (second[it] ushr 24)).toLong() }
                val coverage = first.sumOf { (it ushr 24).toLong() }
                if (error > coverage * 0.015 + 4 * 255) failures += "$fixtureName size=$pointSize density=$density on=$on p=$progress error=$error/$coverage"
                val output = File("build/reports/symbols/system-painter/$fixtureName-$pointSize-${density}x-$on-$progress.png")
                output.parentFile!!.mkdirs()
                val comparison = Bitmap.createBitmap(1024, 384, Bitmap.Config.ARGB_8888)
                Canvas(comparison).apply { drawBitmap(expected, 0f, 0f, null); drawBitmap(actual, 512f, 0f, null) }
                output.outputStream().use { comparison.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
