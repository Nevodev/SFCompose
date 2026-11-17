package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemFillImageTest {
    @Test
    fun writingToolsUsesItsOriginalImageWithoutEnablingAutomaticGradients() = runBlocking<Unit> {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val symbol = store.load("apple.writing.tools")
        assertTrue("System image fill must be loaded with its glyph", "_gradient.highlegibility" in symbol.fillImages)
        val image = symbol.fillImages.getValue("_gradient.highlegibility")
        assertEquals(256, image.light.width)
        assertEquals(256, image.dark.height)
        fun render(mode: SymbolRenderingMode): Bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it).apply { translate(0f, 112f) }
            AndroidSymbolPainter(symbol).draw(canvas, 512f, 160f, mode, Color.BLUE, Color.BLUE, Color.BLUE,
                false, SymbolEffect.None, 0f, gradients = false)
        }
        val monochrome = render(SymbolRenderingMode.Monochrome)
        val multicolor = render(SymbolRenderingMode.Multicolor)
        val colors = HashSet<Int>()
        var alphaError = 0L
        for (y in 0 until 384) for (x in 0 until 512) {
            val first = monochrome.getPixel(x, y)
            val second = multicolor.getPixel(x, y)
            alphaError += kotlin.math.abs(Color.alpha(first) - Color.alpha(second))
            if (Color.alpha(second) == 255) colors += second
        }
        assertTrue("Intrinsic image fill must preserve the glyph silhouette", alphaError < 4 * 255)
        assertTrue("Original image must produce varied colors rather than an unresolved white fill", colors.size > 100)
        assertTrue(colors.any { Color.red(it) > Color.blue(it) + 40 })
        assertTrue(colors.any { Color.blue(it) > Color.red(it) + 40 })
        val output = File("build/reports/symbols/system-image-fill.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { multicolor.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
