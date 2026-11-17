package com.nevoit.sfcompose.symbols

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolGradientBoundsTest {
    @Test
    fun staticGradientBoundsUseTheOriginalPixelRoundedAlignmentSize() = runBlocking {
        val records = javaClass.getResourceAsStream("/system-gradient-metrics-native.json")!!.use {
            JSONObject(it.bufferedReader(Charsets.UTF_8).readText()).getJSONArray("records")
        }
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val scale = SymbolScale.entries.single { it.assetName == record.getString("scale") }
            val symbol = store.load(record.getString("name"), scale = scale)
            val bounds = requireNotNull(symbol.gradientMetrics).bounds(record.getDouble("pointSize").toFloat(),
                record.getDouble("density").toFloat())
            val expected = record.getJSONArray("hostMappedFontRect")
            val actual = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            for (coordinate in actual.indices) assertEquals("case=$index coordinate=$coordinate",
                expected.getDouble(coordinate).toFloat(), actual[coordinate], .0002f)
            val native = record.getJSONArray("originalFillRect")
            val transform = record.getJSONArray("systemTransform")
            val ratio = record.getDouble("pointSize") / 13.0
            assertEquals("native width case=$index", native.getDouble(2),
                bounds.width().toDouble() * transform.getDouble(0) * ratio, .00001)
            assertEquals("native height case=$index", native.getDouble(3),
                bounds.height().toDouble() * transform.getDouble(3) * ratio, .00001)
        }
    }
}
