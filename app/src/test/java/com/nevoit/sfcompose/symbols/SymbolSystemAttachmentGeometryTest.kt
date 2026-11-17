package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolSystemAttachmentGeometryTest {
    @Test
    fun nativeParserDiscardsAllAttachmentsWhenOneDynamicAnchorIsMissing() = runBlocking {
        val store = SymbolAssetStore { name ->
            if (name == "symbols/index.json") RuntimeEnvironment.getApplication().assets.open(name)
            else GZIPInputStream(javaClass.getResourceAsStream("/system-logical/${name.substringAfterLast('/')}.gz")!!)
        }
        for (name in listOf("arrow.trianglehead.turn.up.right.circle.fill", "arrow.trianglehead.turn.up.right.diamond.fill",
            "person.fill.and.arrow.left.and.arrow.right.outward", "rectangle.portrait.and.arrow.forward", "rectangle.portrait.and.arrow.right")) {
            val symbol = store.load(name, scale = SymbolScale.Medium)
            assertTrue("$name ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
            fun visit(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap {
                when (it) {
                    is SymbolNode.Layer -> listOf(it)
                    is SymbolNode.Group -> visit(it.children)
                }
            }
            val attached = visit(symbol.nodes).filter { !SymbolDrawAttachments.systemRecords(it.source,
                it.source.getString("systemRenderingMode")).isNullOrEmpty() }
            assertTrue("$name must retain all three original mode stores", attached.size == 3)
            for (node in attached) {
                assertTrue("$name must retain the original malformed two-record store",
                    SymbolDrawAttachments.systemRecords(node.source, node.source.getString("systemRenderingMode"))!!.size == 2)
                assertTrue("$name must discard the entire native attachment store", !symbol.draw.hasSystemAttachments(node.source))
                assertTrue("$name must keep its Draw clip geometry", symbol.draw.hasGeometry(node.source))
            }
        }
    }

    @Test
    fun importedLogicalModeLayersMatchTheNativeMergedAttachment() = runBlocking {
        val store = SymbolAssetStore { name ->
            if (name == "symbols/index.json") RuntimeEnvironment.getApplication().assets.open(name)
            else GZIPInputStream(javaClass.getResourceAsStream("/system-logical/${name.substringAfterLast('/')}.gz")!!)
        }
        val symbol = store.load("homepod.mini.arrow.forward", scale = SymbolScale.Medium)
        assertTrue(symbol.drawUnsupportedReasons.toString(), symbol.drawUnsupportedReasons.isEmpty())
        val fixture = javaClass.getResourceAsStream("/draw-system-attachments.json.gz")!!.use { input ->
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        }
        val frames = fixture.getJSONArray("frames")
        fun leaves(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap {
            when (it) {
                is SymbolNode.Layer -> listOf(it)
                is SymbolNode.Group -> leaves(it.children)
            }
        }
        for (mode in listOf("monochrome", "multicolor", "hierarchical")) {
            val node = leaves(symbol.nodes).single { it.source.optString("systemRenderingMode") == mode &&
                it.source.optInt("systemNativeLayerIndex", -1) == 2 }
            assertTrue(symbol.draw.hasSystemAttachments(node.source))
            val original = Path().apply { node.components.forEach { addPath(symbol.contours[it]) } }
            val bounds = RectF().also { original.computeBounds(it, true) }
            fun pixels(path: Path): IntArray {
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    translate(16f, 16f)
                    val scale = 224f / maxOf(bounds.width(), bounds.height())
                    scale(scale, scale)
                    translate(-bounds.left, -bounds.top)
                    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
                }
                return IntArray(256 * 256).also { bitmap.getPixels(it, 0, 256, 0, 0, 256, 256) }
            }
            for (i in 0 until frames.length()) {
                val frame = frames.getJSONObject(i)
                if (frame.getString("name") != symbol.name) continue
                val on = frame.getBoolean("on")
                val progress = frame.getDouble("progress").toFloat()
                val state = SystemDrawState(if (on) 0f else progress, if (on) progress else 1f, 0.8f, 1f)
                val expected = pixels(path(frame.getJSONArray("commands")))
                val actual = pixels(requireNotNull(symbol.draw.pathFor(node.source, original, progress, on,
                    pointSize = 32f, systemDrawState = { state }, systemMode = mode)))
                val error = expected.indices.sumOf { abs((expected[it] ushr 24) - (actual[it] ushr 24)).toLong() }
                val coverage = expected.sumOf { (it ushr 24).toLong() }
                assertTrue("$mode ${symbol.name} on=$on p=$progress error=$error coverage=$coverage",
                    error <= coverage * 0.015 + 1024)
            }
        }
    }

    @Test
    fun roundedSubpathIndexConsumesTheOriginalOrderedPrefix() {
        val fixture = javaClass.getResourceAsStream("/draw-system-integer-seam.json.gz")!!.use { input ->
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        }
        val layer = fixture.getJSONObject("layer")
        val array = layer.getJSONArray("contours")
        val contours = List(array.length()) { contour(array.getJSONObject(it)) }
        val components = contours.indices.toList().toIntArray()
        val source = JSONObject().put("sourceLayerIndex", 1)
            .put("systemClipStrokeKeyframes", layer.getJSONArray("clipStrokeKeyframes"))
        val original = Path().apply { contours.forEach { addPath(it.first) } }
        val bounds = RectF().also { original.computeBounds(it, true) }
        val master = fixture.getJSONObject("master")
        val symbol = SymbolInstance(fixture.getString("name"), SymbolWeight.Light, SymbolScale.Small,
            master.getDouble("weight").toFloat(), master.getDouble("advance").toFloat(),
            fixture.getDouble("capHeight").toFloat(), fixture.getDouble("upm").toFloat(),
            contours.map { it.first }, contours.map { it.second }, JSONArray().put(master), JSONArray(),
            doubleArrayOf(1.0 / 3.0, 0.0), 28.929993f, bounds, listOf(SymbolNode.Layer(components, source)), JSONObject())
        assertTrue(symbol.drawUnsupportedReasons.toString(), symbol.drawUnsupportedReasons.isEmpty())
        fun pixels(path: Path): IntArray {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                translate(16f, 16f)
                val scale = 224f / maxOf(bounds.width(), bounds.height())
                scale(scale, scale)
                translate(-bounds.left, -bounds.top)
                drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
            }
            return IntArray(256 * 256).also { bitmap.getPixels(it, 0, 256, 0, 0, 256, 256) }
        }
        val frames = fixture.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            val expected = pixels(path(frame.getJSONArray("commands")))
            val actual = pixels(requireNotNull(symbol.draw.pathFor(source, original,
                frame.getDouble("progress").toFloat(), frame.getBoolean("on"),
                pointSize = frame.getDouble("pointSize").toFloat())))
            val error = expected.indices.sumOf { abs((expected[it] ushr 24) - (actual[it] ushr 24)).toLong() }
            val coverage = expected.sumOf { (it ushr 24).toLong() }
            assertTrue("${symbol.name} on=${frame.getBoolean("on")} p=${frame.getDouble("progress")} size=${frame.getInt("pointSize")} error=$error coverage=$coverage",
                error <= coverage * 0.015 + 1024)
        }
    }

    private fun path(commands: JSONArray): Path = Path().apply {
        for (index in 0 until commands.length()) {
            val row = commands.getJSONArray(index)
            fun v(i: Int) = row.getDouble(i).toFloat()
            when (row.getString(0)) {
                "M" -> moveTo(v(1), v(2))
                "L" -> lineTo(v(1), v(2))
                "Q" -> quadTo(v(1), v(2), v(3), v(4))
                "C" -> cubicTo(v(1), v(2), v(3), v(4), v(5), v(6))
                "Z" -> close()
            }
        }
    }

    private fun contour(source: JSONObject): Pair<Path, SymbolContourData> {
        val verbs = source.getString("verbs")
        val array = source.getJSONArray("points")
        val points = DoubleArray(array.length()) { array.getDouble(it) }
        var position = 0
        val commands = JSONArray()
        for (verb in verbs) {
            val row = JSONArray().put(verb.toString())
            repeat(when (verb) { 'M', 'L' -> 2; 'Q' -> 4; 'C' -> 6; else -> 0 }) { row.put(points[position++]) }
            commands.put(row)
        }
        return path(commands) to SymbolContourData(verbs, points)
    }

    @Test
    fun systemArrowAttachmentsMatchOriginalResolverAndLayerTransforms() {
        val fixture = javaClass.getResourceAsStream("/draw-system-attachments.json.gz")!!.use { input ->
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        }
        val frames = fixture.getJSONArray("frames")
        val symbols = fixture.getJSONArray("symbols")
        for (symbolIndex in 0 until symbols.length()) for (flags in 0..3) {
            val record = symbols.getJSONObject(symbolIndex)
            val master = record.getJSONObject("master")
            val selectedIndices = record.optJSONArray("layerIndices")?.let { array -> List(array.length()) { array.getInt(it) } } ?: listOf(0)
            val contours = ArrayList<Pair<Path, SymbolContourData>>()
            val nodes = selectedIndices.map { layerIndex ->
                val compiled = master.getJSONArray("compiledLayers").getJSONObject(layerIndex)
                val csv = compiled.getJSONObject("systemAttachmentSource").getString("monochrome").split(',').joinToString(",") { csv ->
                    csv.split(' ').toMutableList().apply { set(1, (flags or 4).toString()) }.joinToString(" ")
                }
                val source = JSONObject().put("sourceLayerIndex", layerIndex)
                    .put("systemClipStrokeKeyframes", compiled.getJSONArray("clipStrokeKeyframes"))
                    .put("systemAttachmentSource", JSONObject().put("monochrome", csv))
                compiled.optJSONObject("sourceModeStyles")?.let { source.put("sourceModeStyles", it) }
                val array = compiled.getJSONArray("contours")
                val offset = contours.size
                repeat(array.length()) { contours += contour(array.getJSONObject(it)) }
                SymbolNode.Layer(IntArray(array.length()) { offset + it }, source)
            }
            val source = nodes.first().source
            val original = Path().apply { contours.forEach { addPath(it.first) } }
            val bounds = RectF().also { original.computeBounds(it, true) }
            val symbol = SymbolInstance(record.getString("name"), SymbolWeight.Regular, SymbolScale.Medium,
                400f, master.getDouble("advance").toFloat(), record.getDouble("capHeight").toFloat(),
                record.getDouble("upm").toFloat(), contours.map { it.first }, contours.map { it.second },
                JSONArray().put(master), JSONArray(), doubleArrayOf(0.0, 0.0), 28.929993f, bounds,
                nodes, JSONObject())
            assertTrue("${symbol.name}: ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
            assertTrue(symbol.draw.hasSystemAttachments(source))
            fun pixels(path: Path): IntArray {
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    translate(16f, 16f)
                    val scale = 224f / maxOf(bounds.width(), bounds.height())
                    scale(scale, scale)
                    translate(-bounds.left, -bounds.top)
                    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
                }
                return IntArray(256 * 256).also { bitmap.getPixels(it, 0, 256, 0, 0, 256, 256) }
            }
            for (i in 0 until frames.length()) {
                val frame = frames.getJSONObject(i)
                if (frame.getString("name") != symbol.name || frame.getInt("flags") != flags) continue
                val on = frame.getBoolean("on")
                val progress = frame.getDouble("progress").toFloat()
                val state = SystemDrawState(if (on) 0f else progress, if (on) progress else 1f, 0.8f, 1f)
                val expected = pixels(path(frame.getJSONArray("commands")))
                val actual = pixels(Path().apply {
                    for (node in nodes) {
                        val nodePath = Path().apply { node.components.forEach { addPath(contours[it].first) } }
                        addPath(requireNotNull(symbol.draw.pathFor(node.source, nodePath, progress, on,
                            pointSize = 32f, systemDrawState = { state })))
                    }
                })
                val error = expected.indices.sumOf { abs((expected[it] ushr 24) - (actual[it] ushr 24)).toLong() }
                val coverage = expected.sumOf { (it ushr 24).toLong() }
                if (error > coverage * 0.015 + 1024) {
                    val report = java.io.File("build/reports/symbols/system-attachments").apply { mkdirs() }
                    fun save(name: String, pixels: IntArray) {
                        val bitmap = Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
                        java.io.File(report, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    val prefix = "${symbol.name}-$flags-$on-$progress"
                    save("$prefix-native.png", expected)
                    save("$prefix-port.png", actual)
                    val field = SymbolDraw::class.java.getDeclaredField("systemReferences").apply { isAccessible = true }
                    val references = field.get(symbol.draw) as Map<*, *>
                    val method = SymbolDraw::class.java.getDeclaredMethod("systemAttachmentSlice", SymbolDrawAttachments.Entry::class.java,
                        SystemDrawState::class.java).apply { isAccessible = true }
                    for (entry in references.keys) {
                        val slice = method.invoke(symbol.draw, entry, state) as? SymbolDrawAttachments.Slice
                        val values = FloatArray(9)
                        slice?.transform?.getValues(values)
                        println("$prefix reference=${references[entry]} slice=${values.toList()} origin=${slice?.originX},${slice?.originY}")
                    }
                }
                assertTrue("${symbol.name} flags=$flags on=$on p=$progress error=$error coverage=$coverage",
                    error <= coverage * 0.015 + 1024)
            }
        }
    }
}
