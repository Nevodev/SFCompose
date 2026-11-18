package com.nevoit.sfcompose.symbols

import android.content.res.Configuration
import android.os.LocaleList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolAutomaticVariantsTest {
    @Test
    fun configurationPreservesLocaleListNightAndDirection() {
        val configuration = Configuration().apply {
            setLocales(LocaleList(Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("zh-Hant-TW")))
            setLayoutDirection(Locale.forLanguageTag("ar"))
            uiMode = Configuration.UI_MODE_NIGHT_YES
        }
        val resolved = SymbolResourceConfiguration.from(configuration)
        assertEquals(listOf("ar-EG", "zh-Hant-TW"), resolved.localeTags)
        assertTrue(resolved.rightToLeft)
        assertTrue(resolved.darkTheme)
        assertEquals(listOf("zh-TW", "zh-Hant", "zh"), symbolLocaleFallbacks("zh-TW"))
        assertEquals(listOf("zh-CN", "zh-Hans", "zh"), symbolLocaleFallbacks("zh-CN"))
    }

    @Test
    fun realLocalizedAndDarkAssetsSelectAutomaticallyWithExplicitOverride() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val localizedName = "0.circle.ar"
        val variants = store.variants(localizedName)
        val arabic = variants.single { it.localeTag == "ar" }
        val explicit = store.load(localizedName, variantId = arabic.id)
        val automatic = store.load(localizedName, configuration = SymbolResourceConfiguration(listOf("ar-EG"), true))
        assertEquals(arabic.id, automatic.variantId)
        assertEquals(explicit.advance, automatic.advance, 0f)
        assertEquals(variants.single { it.asset == null }.id,
            store.load(localizedName, configuration = SymbolResourceConfiguration(listOf("en-US"))).variantId)
        val darkName = "circle.bottomhalf.filled.inverse"
        val darkVariants = store.variants(darkName)
        val dark = darkVariants.single { it.appearanceName == "NSAppearanceNameDarkAqua" }
        val light = darkVariants.single { it.asset == null }
        assertNotEquals(light.id, dark.id)
        assertEquals(dark.id, store.load(darkName, configuration = SymbolResourceConfiguration(darkTheme = true)).variantId)
        assertEquals(light.id, store.load(darkName, variantId = light.id,
            configuration = SymbolResourceConfiguration(darkTheme = true)).variantId)
    }

    @Test
    fun localePreferenceAndDirectionalVariantsHaveDeterministicFallbacks() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val name = "airtag.radiowaves.forward.fill.rtl"
        val variants = store.variants(name)
        assertEquals(variants.single { it.direction == 4 }.id,
            store.load(name, configuration = SymbolResourceConfiguration(rightToLeft = true)).variantId)
        assertEquals(variants.single { it.direction == 0 }.id,
            store.load(name, configuration = SymbolResourceConfiguration(rightToLeft = false)).variantId)
        val localized = store.variants("0.circle.ar")
        assertEquals(localized.single { it.localeTag == "ur" }.id,
            store.load("0.circle.ar", configuration = SymbolResourceConfiguration(listOf("fr-FR", "ur-PK", "ar-EG"))).variantId)
    }

    @Test
    fun automaticRtlUsesOriginalFlippabilityOrAnActualCounterpartWithoutDoubleMirroring() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val rtl = SymbolResourceConfiguration(rightToLeft = true)
        val forward = store.load("arrow.forward", configuration = rtl)
        val ltr = store.load("arrow.forward", configuration = SymbolResourceConfiguration())
        assertNotSame(forward, ltr)
        assertTrue(forward.mirroredForRightToLeft)
        assertFalse(ltr.mirroredForRightToLeft)
        assertFalse(store.load("arrow.right", configuration = rtl).mirroredForRightToLeft)
        assertFalse(store.load("arrow.forward", variantId = forward.variantId, configuration = rtl).mirroredForRightToLeft)
        val counterpart = store.load("airtag.radiowaves.forward.fill", configuration = rtl)
        assertEquals("airtag.radiowaves.forward.fill.rtl", counterpart.resourceName)
        assertFalse(counterpart.mirroredForRightToLeft)
        assertEquals(store.load("airtag.radiowaves.forward.fill.rtl", configuration = rtl).variantId, counterpart.variantId)
        fun render(symbol: SymbolInstance): IntArray {
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 160f, 160f, SymbolRenderingMode.Monochrome,
                Color.BLUE, Color.BLUE, Color.BLUE, false, SymbolEffect.None, 0f)
            return IntArray(160 * 160).also { bitmap.getPixels(it, 0, 160, 0, 0, 160, 160) }
        }
        val original = render(ltr)
        val mirrored = render(forward)
        var error = 0L
        var unchangedError = 0L
        for (y in 0 until 160) for (x in 0 until 160) {
            val alpha = Color.alpha(original[y * 160 + x])
            error += kotlin.math.abs(alpha - Color.alpha(mirrored[y * 160 + (159 - x)]))
            unchangedError += kotlin.math.abs(alpha - Color.alpha(mirrored[y * 160 + x]))
        }
        val coverage = original.sumOf { Color.alpha(it).toLong() }
        // Canvas rasterizes reflected curves again; coverage on an edge can round differently
        // from a reflection of already-rasterized pixels, as in the native geometry fixtures.
        assertTrue("Mirrored alpha error=$error coverage=$coverage", error <= coverage * .015 + 1024)
        assertTrue("The directional fixture must detect a missing reflection", unchangedError > coverage * .2)
    }
}
