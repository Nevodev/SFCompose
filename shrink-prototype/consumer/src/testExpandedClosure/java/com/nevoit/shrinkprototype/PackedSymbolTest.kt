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
import com.nevoit.shrinkprototype.catalog.PackedSymbolRef
import kotlinx.coroutines.runBlocking
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
            ExpandedSymbols.AirplayAudioRegular to CompactSymbols.AirplayAudioRegular,
            ExpandedSymbols.CellularBarsRegular to CompactSymbols.CellularBarsRegular,
            ExpandedSymbols.HomepodMiniArrowForwardRegular to CompactSymbols.HomepodMiniArrowForwardRegular,
            ExpandedSymbols.ArrowClockwiseRegular to CompactSymbols.ArrowClockwiseRegular,
        )
        for ((expanded, compact) in pairs) {
            val a = store(expanded)
            val b = store(compact)
            for (scale in SymbolScale.entries) {
                val original = a.load(expanded.name, scale = scale)
                val reconstructed = b.load(compact.name, scale = scale)
                for (pointSize in listOf(13f, 32f, 160f)) for (on in listOf(true, false)) {
                    assertArrayEquals("${expanded.name} $scale $pointSize drawOn=$on",
                        render(original, pointSize, on), render(reconstructed, pointSize, on))
                }
            }
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
