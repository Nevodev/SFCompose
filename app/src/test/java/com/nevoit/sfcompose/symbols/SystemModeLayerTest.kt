package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
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
class SystemModeLayerTest {
    @Test
    fun aPathCanParticipateInBothCutoutAndVisibleLayersWithoutInheritingTheirStyles() = runBlocking {
        val assets = RuntimeEnvironment.getApplication().assets
        val store = SymbolAssetStore { path ->
            javaClass.getResourceAsStream("/system-mode-layers/${path.substringAfterLast('/')}.gz") ?: assets.open(path)
        }
        for (name in listOf("air.purifier.fill", "car.side.air.fresh.fill", "flag.slash.circle.fill")) {
            val symbol = store.load(name)
            for (mode in SymbolRenderingMode.entries) {
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 256f, 160f, mode,
                    Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f)
                val pixels = IntArray(256 * 256).also { bitmap.getPixels(it, 0, 256, 0, 0, 256, 256) }
                assertTrue("$name/$mode must retain its visible system layer", pixels.any { Color.alpha(it) > 127 })
                val visible = symbol.nodes.filter { it.source.supportsSymbolMode(mode) }
                assertTrue("$name/$mode must select its own native layer descriptors", visible.isNotEmpty())
                assertTrue(visible.all { it.source.systemStyle(mode) != null })
                val output = File("build/reports/symbols/system-mode-layers/$name-$mode.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
