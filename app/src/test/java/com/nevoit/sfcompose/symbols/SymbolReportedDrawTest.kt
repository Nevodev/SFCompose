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
class SymbolReportedDrawTest {
    @Test
    fun airplayDrawOffKeepsShrinkingThroughItsNaturalCaps() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        for (scale in SymbolScale.entries) {
            val symbol = store.load("airplay.audio", scale = scale)
            val painter = AndroidSymbolPainter(symbol)
            var previous = Long.MAX_VALUE
            for (progress in listOf(0.5f, 0.75f, 0.9f, 0.98f)) {
                val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
                painter.draw(Canvas(bitmap), 320f, 320f, SymbolRenderingMode.Monochrome,
                    Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
                    drawProgress = progress, drawOn = false)
                val pixels = IntArray(320 * 320)
                bitmap.getPixels(pixels, 0, 320, 0, 0, 320, 320)
                val coverage = pixels.sumOf { Color.alpha(it).toLong() }
                assertTrue("$scale Off $progress grew from $previous to $coverage", coverage <= previous)
                previous = coverage
            }
        }
    }

    @Test
    fun circleDrawOnCapsBothTheFixedStartAndMovingEnd() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val symbol = store.load("circle")
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 320f, 320f, SymbolRenderingMode.Monochrome,
            Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f, drawProgress = 0.25f)
        var startExtendsLeft = false
        for (y in 0 until 80) for (x in 140 until 158) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 200) startExtendsLeft = true
        }
        assertTrue("The fixed circle seam needs the same outward cap as the moving endpoint", startExtendsLeft)
    }

    @Test
    fun reportedCapAndJunctionFramesAreRendered() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val names = store.catalog().names.toSet()
        val cases = listOf("cellularbars", "airplay", "airplay.audio", "airplay.video", "plus", "plus.circle", "bell.circle", "circle")
            .filter { it in names }
        val progress = listOf(0f, 0.02f, 0.1f, 0.25f, 0.5f, 0.75f, 0.98f, 1f)
        for (name in cases) {
            for (scale in SymbolScale.entries) {
                val symbol = store.load(name, scale = scale)
                assertTrue("$name ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
                val painter = AndroidSymbolPainter(symbol)
                val sheet = Bitmap.createBitmap(320 * progress.size, 240 * 2, Bitmap.Config.ARGB_8888)
                sheet.eraseColor(Color.WHITE)
                val canvas = Canvas(sheet)
                for ((row, on) in listOf(true, false).withIndex()) for ((column, value) in progress.withIndex()) {
                    val checkpoint = canvas.save()
                    canvas.translate(column * 320f, row * 240f + 40f)
                    canvas.clipRect(0f, -40f, 320f, 200f)
                    painter.draw(canvas, 320f, 160f, SymbolRenderingMode.Monochrome,
                        Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
                        drawProgress = value, drawOn = on)
                    canvas.restoreToCount(checkpoint)
                }
                val output = File("build/reports/symbols/reported-draw/$name-${scale.name}.png")
                requireNotNull(output.parentFile).mkdirs()
                output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
