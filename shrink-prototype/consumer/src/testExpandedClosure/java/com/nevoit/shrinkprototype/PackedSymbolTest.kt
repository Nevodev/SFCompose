package com.nevoit.shrinkprototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.nevoit.sfcompose.symbols.AndroidSymbolPainter
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolEffect
import com.nevoit.sfcompose.symbols.SymbolInstance
import com.nevoit.sfcompose.symbols.SymbolRenderingMode
import com.nevoit.sfcompose.symbols.SymbolResourceConfiguration
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.shrinkprototype.catalog.CompactSymbols
import com.nevoit.shrinkprototype.catalog.ExpandedSymbols
import com.nevoit.shrinkprototype.catalog.LiveDrawSymbols
import com.nevoit.shrinkprototype.catalog.PackedSymbolRef
import com.nevoit.sfcompose.symbols.SymbolWeight
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackedSymbolTest {
    private fun store(entry: PackedSymbolRef): SymbolAssetStore =
        SymbolAssetStore.fromResourceClosure(RuntimeEnvironment.getApplication().resources, entry.resources)

    @Test
    fun separateResourceClosuresResolveLocaleDirectionAndMirroring() = runBlocking {
        val arabic = ExpandedSymbols.ArabicZeroRegular
        val localeStore = store(arabic)
        val arabicId = localeStore.variants(arabic.name).single { it.localeTag == "ar" }.id
        val urduId = localeStore.variants(arabic.name).single { it.localeTag == "ur" }.id
        assertEquals(arabicId, localeStore.load(arabic.name,
            configuration = SymbolResourceConfiguration(listOf("ar-EG"), true)).variantId)
        assertEquals(urduId, localeStore.load(arabic.name,
            configuration = SymbolResourceConfiguration(listOf("ur-PK"), true)).variantId)

        val rtl = SymbolResourceConfiguration(rightToLeft = true)
        val airtag = ExpandedSymbols.AirtagForwardRegular
        val loaded = store(airtag).load(airtag.name, configuration = rtl)
        assertEquals("${airtag.name}.rtl", loaded.resourceName)
        assertEquals("36081d0e98005a592e929c86", loaded.variantId)
        val arrow = ExpandedSymbols.ArrowForwardRegular
        assertTrue(store(arrow).load(arrow.name, configuration = rtl).mirroredForRightToLeft)
    }

    @Test
    fun masterOutlinesProduceTheSameDrawFramesAtEveryNamedScale() = runBlocking {
        val pairs = listOf(
            Triple(ExpandedSymbols.AirplayAudioRegular, CompactSymbols.AirplayAudioRegular, LiveDrawSymbols.AirplayAudioRegular),
            Triple(ExpandedSymbols.CellularBarsRegular, CompactSymbols.CellularBarsRegular, LiveDrawSymbols.CellularBarsRegular),
            Triple(ExpandedSymbols.HomepodMiniArrowForwardRegular, CompactSymbols.HomepodMiniArrowForwardRegular, LiveDrawSymbols.HomepodMiniArrowForwardRegular),
            Triple(ExpandedSymbols.ArrowClockwiseRegular, CompactSymbols.ArrowClockwiseRegular, LiveDrawSymbols.ArrowClockwiseRegular),
        )
        for ((expanded, compact, live) in pairs) {
            val a = store(expanded)
            val b = store(compact)
            val c = store(live)
            for (scale in SymbolScale.entries) {
                val original = a.load(expanded.name, scale = scale)
                val reconstructed = b.load(compact.name, scale = scale)
                val liveDraw = c.load(live.name, scale = scale)
                for (pointSize in listOf(13f, 32f, 160f)) for (on in listOf(true, false)) {
                    val expected = render(original, pointSize, on)
                    val context = "${expanded.name} $scale $pointSize drawOn=$on"
                    assertArrayEquals(context, expected, render(reconstructed, pointSize, on))
                    assertArrayEquals("Live $context", expected, render(liveDraw, pointSize, on))
                }
            }
        }
    }

    @Test
    fun runtimeKeyframesAndAnchorsMatchPackedSamples() = runBlocking {
        val entries = listOf(
            CompactSymbols.WifiRegular to LiveDrawSymbols.WifiRegular,
            CompactSymbols.WifiBold to LiveDrawSymbols.WifiBold,
            CompactSymbols.ArabicZeroRegular to LiveDrawSymbols.ArabicZeroRegular,
            CompactSymbols.AirtagForwardRegular to LiveDrawSymbols.AirtagForwardRegular,
            CompactSymbols.ArrowForwardRegular to LiveDrawSymbols.ArrowForwardRegular,
            CompactSymbols.AirplayAudioRegular to LiveDrawSymbols.AirplayAudioRegular,
            CompactSymbols.CellularBarsRegular to LiveDrawSymbols.CellularBarsRegular,
            CompactSymbols.HomepodMiniArrowForwardRegular to LiveDrawSymbols.HomepodMiniArrowForwardRegular,
            CompactSymbols.ArrowClockwiseRegular to LiveDrawSymbols.ArrowClockwiseRegular,
            CompactSymbols.PersonFillBold to LiveDrawSymbols.PersonFillBold,
        )
        for ((expected, actual) in entries) {
            val compiled = store(expected)
            val runtime = store(actual)
            val weight = SymbolWeight.valueOf(expected.weight)
            for (name in compiled.catalog().names) for (variant in compiled.variants(name)) for (scale in SymbolScale.entries) {
                val a = compiled.load(name, weight, scale, variant.id).sourceMasters.getJSONObject(0)
                val b = runtime.load(name, weight, scale, variant.id).sourceMasters.getJSONObject(0)
                val context = "$name/${variant.id}/$weight/$scale"
                assertJsonEquals("$context anchors", a.getJSONObject("systemAnchors"), b.getJSONObject("systemAnchors"))
                assertJsonEquals("$context dynamic anchors", a.optJSONArray("systemDynamicAnchorSource"),
                    b.optJSONArray("systemDynamicAnchorSource"))
                val source = a.getJSONArray("compiledLayers")
                val restored = b.getJSONArray("compiledLayers")
                for (i in 0 until source.length()) {
                    val key = "clipStrokeKeyframeSets"
                    assertJsonEquals("$context layer $i", source.getJSONObject(i).getJSONArray(key),
                        restored.getJSONObject(i).getJSONArray(key))
                }
            }
        }
    }

    private fun assertJsonEquals(path: String, expected: Any?, actual: Any?) {
        when {
            expected is JSONObject && actual is JSONObject -> {
                assertEquals(path, expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
                expected.keys().forEach { assertJsonEquals("$path.$it", expected.get(it), actual.get(it)) }
            }
            expected is JSONArray && actual is JSONArray -> {
                assertEquals(path, expected.length(), actual.length())
                for (i in 0 until expected.length()) assertJsonEquals("$path[$i]", expected.get(i), actual.get(i))
            }
            expected is Number && actual is Number -> assertEquals(path, expected.toDouble(), actual.toDouble(), 0.0)
            else -> assertEquals(path, expected, actual)
        }
    }

    private fun render(symbol: SymbolInstance, pointSize: Float, on: Boolean): IntArray {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 192f, 160f, SymbolRenderingMode.Monochrome,
            Color.BLACK, Color.BLACK, Color.BLACK, false, SymbolEffect.None, 0f,
            drawProgress = .52f, drawOn = on, pointSize = pointSize)
        return IntArray(192 * 192).also { bitmap.getPixels(it, 0, 192, 0, 0, 192, 192) }
    }
}
