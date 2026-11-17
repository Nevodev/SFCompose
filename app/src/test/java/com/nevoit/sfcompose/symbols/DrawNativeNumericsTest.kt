package com.nevoit.sfcompose.symbols

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawNativeNumericsTest {
    private fun cases(): JSONArray = javaClass.getResourceAsStream("/draw-native-numerics.json")!!.use {
        JSONArray(it.bufferedReader(Charsets.UTF_8).readText())
    }

    @Test
    fun authoringProjectionMatchesOriginalArm64Functions() {
        val cases = cases()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val points = case.getJSONArray("points")
            val contour = DrawContour(SymbolContourData("MC", DoubleArray(points.length(), points::getDouble)))
            assertEquals(case.getString("name"), case.getDouble("sharedLength"), contour.length, 1e-8)
            val projections = case.getJSONArray("nearest")
            for (pointIndex in 0 until projections.length()) {
                val projection = projections.getJSONObject(pointIndex)
                val target = projection.getJSONArray("target")
                val actual = contour.project(DrawPoint(target.getDouble(0), target.getDouble(1)))
                assertEquals("${case.getString("name")}/$pointIndex", projection.getDouble("projectionFraction"), actual.fraction, 1e-8)
            }
        }
    }

    @Test
    fun primitiveLengthsAndCutPositionsMatchOriginalArm64Functions() {
        val cases = cases()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val points = case.getJSONArray("points")
            val outline = DrawOutline.from(SymbolContourData("MC", DoubleArray(points.length(), points::getDouble)))
            assertEquals(case.getString("name"), case.getDouble("coreLength"), outline.length.toDouble(), 0.0001)
            val cuts = case.getJSONArray("indices")
            for (cutIndex in 0 until cuts.length()) {
                val cut = cuts.getJSONObject(cutIndex)
                val expected = cut.getJSONArray("position")
                val position = FloatArray(2)
                assertTrue(outline.getPosTan(cut.getDouble("distance").toFloat(), position, null))
                assertEquals("${case.getString("name")}/$cutIndex/x", expected.getDouble(0), position[0].toDouble(), 0.0001)
                assertEquals("${case.getString("name")}/$cutIndex/y", expected.getDouble(1), position[1].toDouble(), 0.0001)
            }
        }
    }

    @Test
    fun wrappedBisectionRetainsContourTraversalOrder() {
        val outline = DrawOutline.from(SymbolContourData("MLLLZ", doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)))
        val cut = outline.cut(35f, 5f)
        val point = FloatArray(2)
        assertTrue(cut.outline.getPosTan(0f, point, null))
        assertEquals(0f, point[0], 0f)
        assertEquals(5f, point[1], 0f)
        assertTrue(cut.outline.getPosTan(7f, point, null))
        assertEquals(2f, point[0], 0f)
        assertEquals(0f, point[1], 0f)
        assertEquals(7f, cut.map(2f)!!, 0f)
    }
}
