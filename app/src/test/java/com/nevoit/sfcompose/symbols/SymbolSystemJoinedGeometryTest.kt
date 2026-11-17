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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolSystemJoinedGeometryTest {
    private fun path(commands: JSONArray): Path = Path().apply {
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

    @Test
    fun compiledCompoundPathsMatchTheOriginalJoinAndClipInFontCoordinates() {
        val sources = javaClass.getResourceAsStream("/draw-system-joined-font-coordinates.json")!!.use {
            JSONArray(it.bufferedReader(Charsets.UTF_8).readText())
        }
        for (index in 0 until sources.length()) {
            val record = sources.getJSONObject(index)
            val commands = record.getJSONArray("source")
            val contourCommands = ArrayList<JSONArray>()
            for (commandIndex in 0 until commands.length()) {
                val command = commands.getJSONArray(commandIndex)
                if (command.getString(0) == "M") contourCommands += JSONArray()
                contourCommands.last().put(command)
            }
            val contours = contourCommands.map(::path)
            val contourData = contourCommands.map { array ->
                val verbs = StringBuilder()
                val points = ArrayList<Double>()
                for (i in 0 until array.length()) {
                    val command = array.getJSONArray(i)
                    verbs.append(command.getString(0))
                    for (j in 1 until command.length()) points += command.getDouble(j)
                }
                SymbolContourData(verbs.toString(), points.toDoubleArray())
            }
            val compiled = JSONArray()
            val pairs = record.getJSONArray("inputKeyframes")
            for (i in 0 until pairs.length()) {
                val pair = pairs.getJSONArray(i)
                compiled.put(JSONObject().put("first", pair.getDouble(0)).put("second", pair.getDouble(1))
                    .put("progress", pair.getDouble(2)).put("flags", pair.getInt(3)))
            }
            val source = JSONObject().put("systemClipStrokeKeyframes", compiled)
            val components = contours.indices.toList().toIntArray()
            val original = Path().apply { contours.forEach(::addPath) }
            val bounds = RectF().also { original.computeBounds(it, true) }
            val symbol = SymbolInstance(record.getString("name"), SymbolWeight.Regular, SymbolScale.Small,
                400f, bounds.width(), 1443f, 2048f, contours, contourData, JSONArray(), JSONArray(),
                doubleArrayOf(0.0, 0.0), 28.929993f, bounds,
                listOf(SymbolNode.Layer(components, source)), JSONObject())
            assertTrue("${symbol.name} ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
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
            val frames = record.getJSONArray("frames")
            for (i in 0 until frames.length()) {
                val frame = frames.getJSONObject(i)
                val expected = pixels(path(frame.getJSONArray("commands")))
                val actual = pixels(requireNotNull(symbol.draw.pathFor(source, original,
                    frame.getDouble("progress").toFloat(), frame.getBoolean("on"), pointSize = symbol.unitsPerEm)))
                val error = expected.indices.sumOf { abs((expected[it] ushr 24) - (actual[it] ushr 24)).toLong() }
                val coverage = expected.sumOf { (it ushr 24).toLong() }
                assertTrue("${symbol.name} on=${frame.getBoolean("on")} progress=${frame.getDouble("progress")} error=$error coverage=$coverage",
                    error <= coverage * 0.015 + 1024)
            }
        }
    }
}
