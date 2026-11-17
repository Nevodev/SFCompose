package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolDrawNativeGeometryTest {
    private val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
    private val legacyStore = SymbolAssetStore { path ->
        if (path == "symbols/index.json") RuntimeEnvironment.getApplication().assets.open(path)
        else java.util.zip.GZIPInputStream(javaClass.getResourceAsStream("/legacy-app/${path.substringAfterLast('/')}.gz")!!)
    }

    private fun frame(symbol: SymbolInstance, progress: Float?, on: Boolean = true): Bitmap {
        val result = Bitmap.createBitmap(640, 384, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result).apply { translate(0f, 96f) }
        AndroidSymbolPainter(symbol).draw(canvas, 640f, 192f, SymbolRenderingMode.Monochrome,
            Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
            drawProgress = progress, drawOn = on)
        return result
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun coverage(bitmap: Bitmap): Long = pixels(bitmap).sumOf { (it ushr 24).toLong() }

    private fun save(name: String, progress: Float, bitmap: Bitmap) {
        val output = File("build/reports/symbols/draw-frames/$name-$progress.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun attachedArrowHeadsMoveWithTheDrawnShaft() = runBlocking {
        for (name in listOf("arrow.right", "arrow.clockwise", "arrow.down.left")) {
            val symbol = store.load(name)
            assertTrue("$name ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
            assertEquals("$name empty on endpoint", 0L, coverage(frame(symbol, 0f)))
            val complete = frame(symbol, 1f)
            assertTrue("$name complete endpoint", pixels(complete).contentEquals(pixels(frame(symbol, null))))
            val completeCoverage = coverage(complete)
            val middleFrames = listOf(0.2f, 0.5f, 0.8f).map { progress ->
                val bitmap = frame(symbol, progress)
                save(name, progress, bitmap)
                val count = coverage(bitmap)
                assertTrue("$name progress=$progress coverage=$count/$completeCoverage", count > 0 && count < completeCoverage)
                pixels(bitmap)
            }
            assertTrue("$name attachment frames must advance", !middleFrames[0].contentEquals(middleFrames[1]))
            assertTrue("$name attachment frames must advance", !middleFrames[1].contentEquals(middleFrames[2]))
        }
    }

    @Test
    fun multipleFlowsAndArtificialEndTiesRenderIntermediateGeometry() = runBlocking {
        for (name in listOf("heart", "command", "airpods.pro.chargingcase.wireless.radiowaves.left.and.right")) {
            val symbol = store.load(name)
            assertTrue("$name ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
            val complete = coverage(frame(symbol, 1f))
            for (progress in listOf(0.25f, 0.5f, 0.75f)) {
                val bitmap = frame(symbol, progress)
                save(name, progress, bitmap)
                val count = coverage(bitmap)
                assertTrue("$name progress=$progress coverage=$count/$complete", count > 0 && count < complete)
            }
        }
    }

    @Test
    fun mismatchedAuthoringMastersUseTheOriginalExportFallback() = runBlocking {
        for (name in listOf("wind.circle.fill", "kashida.arabic", "square.and.pencil")) {
            val symbol = legacyStore.load(name)
            assertTrue("$name unsupported: ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
            fun layers(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap {
                if (!it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome)) return@flatMap emptyList()
                when (it) {
                    is SymbolNode.Layer -> listOf(it)
                    is SymbolNode.Group -> layers(it.children)
                }
            }
            val nativeFallbacks = layers(symbol.nodes).filter { symbol.draw.nativeFallbackReason(it.source) != null }
            assertTrue("$name must retain the native authoring rejection", nativeFallbacks.isNotEmpty())
            for (layer in nativeFallbacks) {
                assertTrue(!symbol.draw.hasGeometry(layer.source))
                val original = android.graphics.Path().apply { layer.components.forEach { index -> symbol.contours.getOrNull(index)?.let(::addPath) } }
                val actual = symbol.draw.pathFor(layer.source, original, 0.5f)!!
                val first = android.graphics.RectF()
                val second = android.graphics.RectF()
                original.computeBounds(first, true)
                actual.computeBounds(second, true)
                assertEquals(first, second)
            }
        }
    }

    @Test
    fun reverseDrawUsesTheOppositeNativeClipInterval() = runBlocking {
        for (name in listOf("circle", "plus")) {
            val symbol = store.load(name)
            fun layers(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap {
                if (!it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome)) return@flatMap emptyList()
                when (it) {
                    is SymbolNode.Layer -> listOf(it)
                    is SymbolNode.Group -> layers(it.children)
                }
            }
            val selected = layers(symbol.nodes).filter { it.source.optString("systemRenderingMode", "monochrome") == "monochrome" }
            val full = Path().apply { symbol.contours.forEach(::addPath) }
            val bounds = RectF().also { full.computeBounds(it, true) }
            fun render(progress: Float, on: Boolean, reverse: Boolean): Bitmap {
                val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.translate(16f, 16f)
                canvas.scale(160f / bounds.width(), 160f / bounds.height())
                canvas.translate(-bounds.left, -bounds.top)
                val paint = Paint().apply { color = Color.BLUE; isAntiAlias = true }
                for (layer in selected) {
                    val original = Path().apply { layer.components.forEach { addPath(symbol.contours[it]) } }
                    val path = requireNotNull(symbol.draw.pathFor(layer.source, original, progress, on, reverse))
                    canvas.drawPath(path, paint)
                }
                return bitmap
            }
            for (on in listOf(true, false)) {
                for (progress in listOf(0.25f, 0.5f, 0.75f)) {
                    val reverse = render(progress, on, true)
                    val opposite = render(1f - progress, !on, false)
                    assertTrue("$name on=$on progress=$progress", pixels(reverse).contentEquals(pixels(opposite)))
                }
            }
        }
    }

    @Test
    fun independentDrawPartsKeepSeparateNativeTimingSamples() = runBlocking {
        val symbol = store.load("plus")
        symbol.drawUnsupportedReasons
        fun layers(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap {
            if (!it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome)) return@flatMap emptyList()
            when (it) {
                is SymbolNode.Layer -> listOf(it)
                is SymbolNode.Group -> layers(it.children)
            }
        }
        val layer = layers(symbol.nodes).single { it.source.optString("systemRenderingMode", "monochrome") == "monochrome" }
        val samples = requireNotNull(symbol.draw.timingLengthSamples(layer.source, layer.components))
        assertEquals(2, samples.size)
        assertTrue(samples.all { it.isFinite() && it > 0.0 })
    }

    @Test
    fun nativeRemoveOtherSubpathsDropsOnlyUnannotatedParts() = runBlocking {
        val symbol = legacyStore.load("square.and.arrow.up.fill")
        symbol.drawUnsupportedReasons
        fun layers(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap {
            when (it) {
                is SymbolNode.Layer -> listOf(it)
                is SymbolNode.Group -> layers(it.children)
            }
        }
        val layer = layers(symbol.nodes).single { it.source.has("drawRailRoadTies") }
        val original = Path().apply { layer.components.forEach { addPath(symbol.contours[it]) } }
        for (on in listOf(true, false)) for (progress in listOf(0f, 0.5f, 1f)) {
            assertTrue("on=$on progress=$progress", symbol.draw.pathFor(layer.source, original, progress, isOn = on)!!.isEmpty)
        }
        val originalBounds = RectF().also { original.computeBounds(it, true) }
        val retainedBounds = RectF().also {
            symbol.draw.pathFor(layer.source, original, 1f, removeOtherSubpaths = false)!!.computeBounds(it, true)
        }
        assertEquals(originalBounds, retainedBounds)
    }

    @Test
    fun originalClipperAndAndroidPathsUseTheSameCanvas() = runBlocking {
        val records = listOf("/draw-native-actual-plans-dense.json").flatMap { resource ->
            javaClass.getResourceAsStream(resource)!!.use {
                val array = JSONArray(it.bufferedReader(Charsets.UTF_8).readText())
                (0 until array.length()).map(array::getJSONObject)
            }
        }
        val groups = records.groupBy {
            Triple(it.getString("name"), it.getBoolean("on"), it.getDouble("progress").toFloat())
        }
        val report = StringBuilder()
        val failures = ArrayList<String>()
        for ((key, entries) in groups) {
            val (name, on, progress) = key
            val scale = SymbolScale.entries.first { name.endsWith("-$it") }
            val baseName = name.removeSuffix("-$scale")
            val symbol = legacyStore.load(baseName, scale = scale)
            fun layers(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap {
                when (it) {
                    is SymbolNode.Layer -> listOf(it)
                    is SymbolNode.Group -> layers(it.children)
                }
            }
            val components = when (baseName) {
                "airplay.audio" -> intArrayOf(0, 1, 2)
                "cellularbars" -> intArrayOf(0, 1, 2, 3)
                else -> error("Unexpected native fixture: $name")
            }
            val selected = layers(symbol.nodes).filter { layer -> layer.components.all { it in components } }
            val original = Path().apply { components.forEach { addPath(symbol.contours[it]) } }
            val bounds = RectF().also { original.computeBounds(it, true) }
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
            fun render(path: Path): Bitmap {
                val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.translate(16f, 16f)
                val scale = 160f / maxOf(bounds.width(), bounds.height())
                canvas.scale(scale, scale)
                canvas.translate(-bounds.left, -bounds.top)
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
                return bitmap
            }
            val expected = render(native)
            val actualPath = Path().apply {
                for (layer in selected) {
                    val originalLayer = Path().apply { layer.components.forEach { addPath(symbol.contours[it]) } }
                    addPath(requireNotNull(symbol.draw.pathFor(layer.source, originalLayer, progress, on)))
                }
            }
            val actual = render(actualPath)
            val first = pixels(expected)
            val second = pixels(actual)
            val alphaError = first.indices.sumOf { kotlin.math.abs((first[it] ushr 24) - (second[it] ushr 24)).toLong() }
            report.appendLine("$name on=$on progress=$progress alphaError=$alphaError native=${coverage(expected)} android=${coverage(actual)}")
            if (alphaError > coverage(expected) * 0.015 + 1024) failures += "$name on=$on progress=$progress alphaError=$alphaError"
            val image = Bitmap.createBitmap(384, 192, Bitmap.Config.ARGB_8888)
            Canvas(image).apply { drawBitmap(expected, 0f, 0f, null); drawBitmap(actual, 192f, 0f, null) }
            val output = File("build/reports/symbols/native-comparison/$name-$on-$progress.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File("build/reports/symbols/native-comparison.txt").writeText(report.toString(), Charsets.UTF_8)
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
    }

}
