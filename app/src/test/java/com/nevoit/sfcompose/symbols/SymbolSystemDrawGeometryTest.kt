package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
class SymbolSystemDrawGeometryTest {
    @Test
    fun compiledSystemGlyphsMatchRenderBoxInFontCoordinates() = runBlocking {
        compare("/draw-system-bvg-renderbox-regular.json", "system-bvg-comparison", false)
    }

    @Test
    fun generateCanvas13ComparisonForReportedCaps() = runBlocking {
        compare("/draw-system-canvas13-in-font-coordinates.json", "system-canvas13-comparison", true)
    }

    @Test
    fun compiledSystemGlyphsMatchRenderBoxAt32Points() = runBlocking {
        compare("/draw-system-canvas13-point32-in-font-coordinates.json", "system-point32-comparison", true, 32f)
    }

    @Test
    fun compiledSystemGlyphsMatchRenderBoxAt160Points() = runBlocking {
        compare("/draw-system-canvas13-point160-in-font-coordinates.json", "system-point160-comparison", true, 160f)
    }

    private suspend fun compare(resource: String, reportDirectory: String, includesScale: Boolean, pointSize: Float = 13f) {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val records = javaClass.getResourceAsStream(resource)!!.use {
            JSONArray(it.bufferedReader(Charsets.UTF_8).readText())
        }
        val groups = (0 until records.length()).map(records::getJSONObject).groupBy {
            Triple(it.getString("name"), it.getBoolean("on"), it.getDouble("progress").toFloat())
        }
        val failures = ArrayList<String>()
        val report = StringBuilder()
        for ((key, entries) in groups) {
            val (fixtureName, on, progress) = key
            val scale = if (includesScale) SymbolScale.entries.single { fixtureName.endsWith("-$it") } else SymbolScale.Small
            val name = if (includesScale) fixtureName.removeSuffix("-$scale") else fixtureName.substringBefore(".svg.weight")
            val symbol = store.load(name, scale = scale)
            fun layers(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap {
                if (!it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome)) return@flatMap emptyList()
                when (it) {
                    is SymbolNode.Layer -> listOf(it)
                    is SymbolNode.Group -> layers(it.children)
                }
            }
            val selected = entries.map { entry ->
                val component = entry.getInt("part")
                layers(symbol.nodes).single { component in it.components }
            }.distinct()
            assertTrue("$name must use compiled system metadata", selected.all { it.source.has("systemClipStrokeKeyframes") })
            assertTrue("$name ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
            val full = Path().apply { selected.forEach { layer -> layer.components.forEach { addPath(symbol.contours[it]) } } }
            val bounds = RectF().also { full.computeBounds(it, true) }
            val native = Path()
            for (entry in entries) {
                val commands = entry.getJSONArray("commands")
                for (index in 0 until commands.length()) {
                    val command = commands.getJSONArray(index)
                    fun p(i: Int) = command.getDouble(i).toFloat()
                    when (command.getString(0)) {
                        "M" -> native.moveTo(p(1), p(2))
                        "L" -> native.lineTo(p(1), p(2))
                        "Q" -> native.quadTo(p(1), p(2), p(3), p(4))
                        "C" -> native.cubicTo(p(1), p(2), p(3), p(4), p(5), p(6))
                        "Z" -> native.close()
                    }
                }
            }
            val actual = Path().apply {
                selected.forEach { layer ->
                    val original = Path().apply { layer.components.forEach { addPath(symbol.contours[it]) } }
                    addPath(requireNotNull(symbol.draw.pathFor(layer.source, original, progress, on,
                        pointSize = if (includesScale) pointSize else symbol.unitsPerEm)))
                }
            }
            fun render(path: Path): Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    translate(16f, 16f)
                    val scale = 224f / maxOf(bounds.width(), bounds.height())
                    scale(scale, scale)
                    translate(-bounds.left, -bounds.top)
                    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
                }
            }
            val expectedImage = render(native)
            val actualImage = render(actual)
            fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            val expectedPixels = pixels(expectedImage)
            val actualPixels = pixels(actualImage)
            val error = expectedPixels.indices.sumOf { abs((expectedPixels[it] ushr 24) - (actualPixels[it] ushr 24)).toLong() }
            val coverage = expectedPixels.sumOf { (it ushr 24).toLong() }
            report.appendLine("$name-$scale on=$on progress=$progress alphaError=$error coverage=$coverage")
            if (error > coverage * 0.015 + 1024) failures += "$name-$scale on=$on progress=$progress alphaError=$error coverage=$coverage"
            val image = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            Canvas(image).apply { drawBitmap(expectedImage, 0f, 0f, null); drawBitmap(actualImage, 256f, 0f, null) }
            val output = File("build/reports/symbols/$reportDirectory/$name-$scale-$on-$progress.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File("build/reports/symbols/$reportDirectory.txt").writeText(report.toString(), Charsets.UTF_8)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
