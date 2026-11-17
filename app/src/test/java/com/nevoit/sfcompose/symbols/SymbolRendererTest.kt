package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
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
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolRendererTest {
    private val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)

    @Test
    fun originalNamesLoadEveryWeightAndOpticalScale() = runBlocking {
        for (name in listOf("heart.fill", "wifi", "rainbow", "circle", "person.crop.circle.fill", "arrow.down.circle.badge.checkmark")) {
            for (weight in SymbolWeight.entries) {
                for (scale in SymbolScale.entries) {
                    val symbol = store.load(name, weight, scale)
                    assertTrue("$name/$weight/$scale", symbol.contours.isNotEmpty())
                    assertTrue(symbol.advance > 0f)
                    assertTrue(symbol.capHeight > 0f)
                    assertTrue(!symbol.bounds.isEmpty)
                }
            }
        }
    }

    @Test
    fun everyImportedNameCanBeReadByTheAndroidConsumer() = runBlocking {
        val catalog = store.catalog()
        assertTrue(catalog.names.isNotEmpty())
        for (name in catalog.names) {
            val symbol = store.load(name)
            assertTrue("Empty source outline: $name", symbol.contours.isNotEmpty())
            assertTrue("Invalid source advance: $name", symbol.advance.isFinite() && symbol.advance > 0f)
        }
    }

    @Test
    fun drawCoverageIsRecordedForTheCompleteCatalog() = runBlocking {
        val failures = mutableMapOf<String, String>()
        val nativeFallbacks = mutableMapOf<String, List<String>>()
        var annotated = 0
        for (name in store.catalog().names) {
            val symbol = store.load(name)
            if (symbol.nativeDrawFallbackReasons.isNotEmpty()) nativeFallbacks[name] = symbol.nativeDrawFallbackReasons
            fun visit(nodes: List<SymbolNode>) {
                for (node in nodes) {
                    if (node is SymbolNode.Group) visit(node.children)
                    else if (node.source.has("drawRailRoadTies") ||
                        (node.source.optJSONArray("systemClipStrokeKeyframes")?.length() ?: 0) > 0) {
                        annotated++
                        val reason = symbol.draw.unsupportedReason(node.source, (node as SymbolNode.Layer).components)
                        if (reason != null) failures[name] = reason
                    }
                }
            }
            visit(symbol.nodes)
        }
        val report = org.json.JSONObject().put("annotatedLayers", annotated)
            .put("remaining", org.json.JSONObject(failures)).put("nativeFallbacks", org.json.JSONObject(nativeFallbacks))
        val output = File("build/reports/symbols/draw-coverage.json")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText(report.toString(), Charsets.UTF_8)
        assertTrue(annotated > 0)
        assertTrue("Unsupported original Draw records: $failures", failures.isEmpty())
    }

    @Test
    fun drawEndpointsAndIntermediateCoverageUseTheOriginalCircleContours() = runBlocking {
        val symbol = store.load("circle")
        assertTrue(symbol.drawUnsupportedReasons.toString(), symbol.drawUnsupportedReasons.isEmpty())
        val painter = AndroidSymbolPainter(symbol)
        fun frame(value: Float): Bitmap {
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            painter.draw(Canvas(bitmap), 160f, 160f, SymbolRenderingMode.Monochrome,
                Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
                drawProgress = value)
            return bitmap
        }
        assertTrue(alphaMask(frame(0f)).all { it == 0 })
        assertEquals(alphaMask(render(symbol, SymbolRenderingMode.Monochrome)), alphaMask(frame(1f)))
        val complete = alphaMask(frame(1f)).sum()
        for (value in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            val coverage = alphaMask(frame(value)).sum()
            assertTrue("Draw coverage at $value: $coverage/$complete", coverage in 1 until complete)
        }
    }

    @Test
    fun drawSurvivesAllWeightsAndScales() = runBlocking {
        for (weight in SymbolWeight.entries) for (scale in SymbolScale.entries) {
            val symbol = store.load("circle", weight, scale)
            assertTrue("$weight/$scale ${symbol.drawUnsupportedReasons}", symbol.drawUnsupportedReasons.isEmpty())
            for (value in listOf(0f, 0.5f, 1f)) {
                val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
                AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 160f, 160f, SymbolRenderingMode.Monochrome,
                    Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f, drawProgress = value)
            }
        }
    }

    @Test
    fun timedDrawUsesDifferentOnAndOffCurvesAndFallback() = runBlocking {
        val circle = store.load("circle")
        val on = SymbolDrawTiming(circle, true)
        val off = SymbolDrawTiming(circle, false)
        assertTrue(on.durationSeconds > 0f)
        val heart = store.load("heart.fill")
        val fallback = SymbolDrawTiming(heart, true)
        assertEquals(0.25f, fallback.durationSeconds, 0f)
        assertEquals(0f, fallback.opacity(heart.nodes.first().source, 0f), 0f)
        assertEquals(1f, fallback.opacity(heart.nodes.first().source, 0.25f), 0f)
        assertTrue(on.progress(circle.nodes.first().source, on.durationSeconds * 0.5f) !=
            off.progress(circle.nodes.first().source, off.durationSeconds * 0.5f))
    }

    @Test
    fun sourceVectorContactSheetRendersThroughAndroidCanvas() = runBlocking {
        val names = listOf("rainbow", "heart.fill", "person.crop.circle.fill", "wifi", "command", "gear", "arrow.down.circle.badge.checkmark",
            "arrow.right", "arrow.clockwise", "arrow.down.left", "bell.slash", "folder.badge.plus", "speaker.slash.circle.fill")
        val sheet = Bitmap.createBitmap(640, names.size * 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        for ((row, name) in names.withIndex()) {
            val symbol = store.load(name)
            for ((column, mode) in SymbolRenderingMode.entries.withIndex()) {
                val saved = canvas.save()
                canvas.translate(column * 160f, row * 160f)
                AndroidSymbolPainter(symbol).draw(canvas, 160f, 160f, mode,
                    Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f)
                canvas.restoreToCount(saved)
            }
        }
        val output = File("build/reports/symbols/source-vectors.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }

    @Test
    fun variableColorAndGradientReachTheCanvas() = runBlocking {
        val wifi = store.load("wifi")
        fun frame(value: Float, gradient: Boolean): Bitmap {
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            AndroidSymbolPainter(wifi).draw(Canvas(bitmap), 160f, 160f, SymbolRenderingMode.Monochrome,
                Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
                variableValue = value, gradients = gradient)
            return bitmap
        }
        assertTrue(alphaMask(frame(0f, false)).sum() < alphaMask(frame(1f, false)).sum())
        assertTrue(coloredPixels(frame(1f, true)).distinct().size > 1)
    }

    @Test
    fun heartUsesIntrinsicColorInMulticolorAndCallerTintInMonochrome() = runBlocking {
        val symbol = store.load("heart.fill")
        val monochrome = render(symbol, SymbolRenderingMode.Monochrome)
        val multicolor = render(symbol, SymbolRenderingMode.Multicolor)
        assertTrue(coloredPixels(monochrome).any { Color.blue(it) > Color.red(it) })
        assertTrue(coloredPixels(multicolor).any { Color.red(it) > 200 && Color.blue(it) < 100 })
        assertEquals(alphaMask(monochrome), alphaMask(multicolor))
    }

    @Test
    fun originalCutoutErasesOnlyWithinTheIsolatedSymbolLayer() = runBlocking {
        val symbol = store.load("person.crop.circle.fill")
        val transparent = render(symbol, SymbolRenderingMode.Monochrome)
        val colored = render(symbol, SymbolRenderingMode.Multicolor)
        val background = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        background.eraseColor(Color.GREEN)
        AndroidSymbolPainter(symbol).draw(
            Canvas(background), 160f, 160f, SymbolRenderingMode.Monochrome,
            Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
        )
        var verifiedCutout = false
        for (y in 0 until transparent.height) for (x in 0 until transparent.width) {
            if (Color.alpha(transparent.getPixel(x, y)) == 0 && colored.getPixel(x, y) == Color.WHITE) {
                assertEquals(Color.GREEN, background.getPixel(x, y))
                verifiedCutout = true
            }
        }
        assertTrue("Expected the original person cutout", verifiedCutout)
    }

    @Test
    fun zeroOpacityReplacementDoesNotClearOrPaintAnyPixels() = runBlocking {
        val symbol = store.load("person.2.crop.square.stack.fill")
        for (mode in SymbolRenderingMode.entries) {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GREEN)
            AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 96f, 96f, mode,
                Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
                replacementState = { SymbolReplaceLayerState(opacity = 0f) })
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                assertEquals("$mode/$x/$y", Color.GREEN, bitmap.getPixel(x, y))
            }
        }
    }

    @Test
    fun everyImportedSymbolRendersAllModesAndDrawMidpoints() = runBlocking {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(64 * 64)
        val empty = mutableListOf<String>()
        for (name in store.catalog().names) {
            val symbol = store.load(name)
            val painter = AndroidSymbolPainter(symbol)
            for (mode in SymbolRenderingMode.entries) {
                bitmap.eraseColor(Color.TRANSPARENT)
                painter.draw(canvas, 64f, 64f, mode,
                    Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f)
                bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                if (pixels.all { Color.alpha(it) == 0 }) empty += "$name/${mode.name}"
                if (symbol.drawUnsupportedReasons.isEmpty()) {
                    for (isOn in listOf(true, false)) {
                        bitmap.eraseColor(Color.TRANSPARENT)
                        painter.draw(canvas, 64f, 64f, mode,
                            Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
                            drawProgress = 0.5f, drawOn = isOn)
                    }
                    bitmap.eraseColor(Color.TRANSPARENT)
                    painter.draw(canvas, 64f, 64f, mode,
                        Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
                        variableDrawValue = 0.5f)
                }
            }
        }
        val output = File("build/reports/symbols/empty-renderings.json")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText(org.json.JSONArray(empty).toString(), Charsets.UTF_8)
        // These source modes contain only hidden fills and erase operations; native admission retains them.
        assertEquals(setOf("iphone.gen1.landscape.slash/Multicolor", "iphone.gen2.landscape.slash/Multicolor",
            "iphone.gen3.landscape.slash/Multicolor"), empty.toSet())
    }

    private fun render(symbol: SymbolInstance, mode: SymbolRenderingMode): Bitmap {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        AndroidSymbolPainter(symbol).draw(
            Canvas(bitmap), 160f, 160f, mode,
            Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f,
        )
        return bitmap
    }

    private fun coloredPixels(bitmap: Bitmap): List<Int> = buildList {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) > 250) add(color)
        }
    }

    private fun alphaMask(bitmap: Bitmap): List<Int> = buildList {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            add(Color.alpha(bitmap.getPixel(x, y)))
        }
    }
}
