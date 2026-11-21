package com.nevoit.sfcompose

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.graphics.Canvas
import com.nevoit.sfcompose.symbols.AndroidSymbolPainter
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolEffect
import com.nevoit.sfcompose.symbols.SymbolEffectPlayback
import com.nevoit.sfcompose.symbols.SymbolRenderingMode
import com.nevoit.sfcompose.symbols.SymbolVariableAnimation
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertArrayEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolBrowserVariableDrawTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openInspector(name: String) {
        compose.onNodeWithTag("catalog-search").performTextReplacement(name)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("glyph-$name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("symbol-$name").performClick()
    }

    @Test
    fun actualBrowserSliderChangesTheSymbolPixels() {
        assertSliderChangesPixels("cellularbars", "Variable Draw")
    }

    @Test
    fun defaultRainbowVariableDrawChangesTheSymbolPixels() {
        assertSliderChangesPixels("rainbow", "Draw 进度")
    }

    @Test
    fun airplayManualDrawChangesTheSymbolPixels() {
        assertSliderChangesPixels("airplay.audio", "Draw 进度")
    }

    @Test
    fun choosingManualDrawReplacesAutomaticDraw() {
        assertSliderChangesPixels("cellularbars", "Variable Draw", automaticDraw = true)
    }

    @Test
    fun shareplayKeepsItsHierarchyBeforeAndAfterManualDraw() {
        openInspector("shareplay")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("shareplay").fetchSemanticsNodes().size == 4
        }
        fun capture(label: String): Bitmap {
            val bitmap = compose.onAllNodesWithContentDescription("shareplay")[SymbolRenderingMode.Hierarchical.ordinal]
                .captureToImage().asAndroidBitmap()
            val output = File("build/reports/symbols/browser-variable/shareplay-$label.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap
        }
        fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val initial = capture("initial")
        val symbol = runBlocking { SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("shareplay") }
        val expected = reference(initial) { canvas, width, height ->
            AndroidSymbolPainter(symbol).draw(canvas, width, height,
                SymbolRenderingMode.Hierarchical, 0xff6254e8.toInt(), 0xfffa9d31.toInt(), 0xff33b899.toInt(),
                false, SymbolEffect.None, 0f)
        }
        assertMatchesPainter("Default preview must preserve the source secondary layers", expected, initial)
        selectTab(3)
        compose.onNodeWithText("Draw 进度").performScrollTo().performClick()
        compose.onNodeWithTag("variable-value").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertArrayEquals("Completed Draw must retain static hierarchy", pixels(initial), pixels(capture("draw-full")))
        selectTab(2)
        compose.onNodeWithText("静态").performScrollTo().performClick()
        assertArrayEquals("Returning to static must retain hierarchy", pixels(initial), pixels(capture("static-again")))
        selectTab(3)
        compose.onNodeWithTag("variable-color").performScrollTo().performClick()
        assertTrue("Explicit variable color must still activate the secondary layers",
            !pixels(initial).contentEquals(pixels(capture("variable-color"))))
        compose.onNodeWithTag("variable-color").performScrollTo().performClick()
        assertArrayEquals("Disabling variable color restores the source hierarchy", pixels(initial), pixels(capture("color-off")))
    }

    @Test
    fun continuousWeightSliderRendersPositionsBetweenNamedWeights() {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val catalog = runBlocking { store.catalog() }
        openInspector("person.fill")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("person.fill").fetchSemanticsNodes().size == 4
        }
        val start = catalog.weightPositions.getValue(com.nevoit.sfcompose.symbols.SymbolWeight.Regular)
        val end = catalog.weightPositions.getValue(com.nevoit.sfcompose.symbols.SymbolWeight.Medium)
        val frames = mutableListOf<IntArray>()
        for (fraction in listOf(.2f, .5f, .8f)) {
            val position = start + (end - start) * fraction
            val symbol = runBlocking { store.load("person.fill", weightPosition = position) }
            compose.onNodeWithTag("weight-position").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(position) }
            var rendered = IntArray(0)
            var maxChannelDifference = 0
            try {
                compose.waitUntil(10_000) {
                    val actual = compose.onAllNodesWithContentDescription("person.fill")[0].captureToImage().asAndroidBitmap()
                    val width = actual.width
                    val height = actual.height
                    val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    expected.eraseColor(actual.getPixel(0, 0))
                    AndroidSymbolPainter(symbol).draw(Canvas(expected), width.toFloat(), height.toFloat(),
                        SymbolRenderingMode.Monochrome, 0xff6254e8.toInt(), 0xfffa9d31.toInt(), 0xff33b899.toInt(),
                        false, SymbolEffect.None, 0f)
                    fun pixels(bitmap: Bitmap) = IntArray(width * height).also {
                        bitmap.getPixels(it, 0, width, 0, 0, width, height)
                    }
                    rendered = pixels(actual)
                    val reference = pixels(expected)
                    maxChannelDifference = 0
                    rendered.indices.all { index ->
                        if (rendered[index] == reference[index]) return@all true
                        val difference = (0..24 step 8).maxOf { shift ->
                            kotlin.math.abs((rendered[index] ushr shift and 255) - (reference[index] ushr shift and 255))
                        }
                        maxChannelDifference = maxOf(maxChannelDifference, difference)
                        val x = index % width
                        val y = index / width
                        val atEdge = (maxOf(0, y - 1)..minOf(height - 1, y + 1)).any { row ->
                            (maxOf(0, x - 1)..minOf(width - 1, x + 1)).any { column ->
                                reference[row * width + column] != reference[index]
                            }
                        }
                        // Direct rasterization and capture compositing round edge coverage differently.
                        atEdge && difference <= 6
                    }
                }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Weight $position did not match the painter; max channel difference $maxChannelDifference", failure)
            }
            frames += rendered
        }
        assertFalse("Continuous slider must not snap to a named weight", frames[0].contentEquals(frames[1]))
        assertFalse("Continuous slider must preserve intermediate geometry", frames[1].contentEquals(frames[2]))
    }

    @Test
    fun choosingDrawStartsAtZeroWithoutReusingThePreviousClock() {
        openInspector("cellularbars")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("cellularbars").fetchSemanticsNodes().size == 4
        }
        selectTab(2)
        for (effect in listOf("Draw On", "Draw Off", "Draw On")) {
            compose.onNodeWithTag("animation-time")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
            compose.onNodeWithText(effect).performScrollTo().performClick()
            val actual = compose.onAllNodesWithContentDescription("cellularbars")[0].captureToImage().asAndroidBitmap()
            val symbol = runBlocking { SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("cellularbars") }
            val expected = reference(actual) { canvas, width, height ->
                AndroidSymbolPainter(symbol).draw(canvas, width, height,
                    SymbolRenderingMode.Monochrome, 0xff6254e8.toInt(), 0xfffa9d31.toInt(), 0xff33b899.toInt(), false,
                    SymbolEffect.None, 0f, variableValue = 1f,
                    drawEffect = if (effect == "Draw On") com.nevoit.sfcompose.symbols.SymbolDrawEffect.On
                        else com.nevoit.sfcompose.symbols.SymbolDrawEffect.Off)
            }
            assertMatchesPainter("$effect kept a previous elapsed time", expected, actual)
        }
    }

    @Test
    fun bouncingContinuousPreviewDoesNotWrapBackToTheFirstGroupAtTheTurn() {
        openInspector("cellularbars")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("cellularbars").fetchSemanticsNodes().size == 4
        }
        compose.onNodeWithText("暂停").performClick()
        selectTab(3)
        for (label in listOf("变量动画", "依次", "往返")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        selectTab(2)
        compose.onNodeWithText("循环").performScrollTo().performClick()
        val symbol = runBlocking { SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("cellularbars") }
        for (time in listOf(1.5833334f, 2.4166667f, 4.0833335f)) {
            compose.onNodeWithTag("animation-time")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(time) }
            val actual = compose.onAllNodesWithContentDescription("cellularbars")[0].captureToImage().asAndroidBitmap()
            val expected = reference(actual) { canvas, width, height ->
                AndroidSymbolPainter(symbol).draw(canvas, width, height,
                    SymbolRenderingMode.Monochrome, 0xff6254e8.toInt(), 0xfffa9d31.toInt(), 0xff33b899.toInt(),
                    false, SymbolEffect.None, time, variableValue = 1f, playback = SymbolEffectPlayback.Continuous,
                    variableAnimation = SymbolVariableAnimation(iterative = true, bounces = true, willLoop = false))
            }
            assertMatchesPainter("Looping bounce inserts a virtual first layer at $time", expected, actual)
        }
    }

    private fun assertSliderChangesPixels(name: String, control: String, automaticDraw: Boolean = false) {
        openInspector(name)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().size == 4
        }
        if (automaticDraw) {
            selectTab(2)
            compose.onNodeWithText("Draw Off").performScrollTo().performClick()
        }
        selectTab(3)
        compose.onNodeWithText(control).performScrollTo().performClick()
        if (control == "Draw 进度") {
            compose.onNodeWithText("该符号无原生 Variable Draw 范围；滑块控制 Draw On 进度。").assertExists()
        }
        fun pixels(value: Float): IntArray {
            compose.onNodeWithTag("variable-value").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
            val preview = compose.onAllNodesWithContentDescription(name)[0]
            compose.waitForIdle()
            val capture = preview.captureToImage()
            val output = File("build/reports/symbols/browser-variable/$name-$automaticDraw-$value.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { capture.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            val image = capture.toPixelMap()
            return IntArray(image.width * image.height) { image[it % image.width, it / image.width].hashCode() }
        }
        val zero = pixels(0f)
        val middle = pixels(.5f)
        val full = pixels(1f)
        assertFalse("The actual browser ignores zero", zero.contentEquals(full))
        assertFalse("The actual browser ignores intermediate progress", middle.contentEquals(full))
        assertFalse("The actual browser never advances", zero.contentEquals(middle))
    }

    private fun selectTab(index: Int) {
        compose.onNodeWithTag("inspector-controls-tab-$index").performClick()
    }

    private fun reference(actual: Bitmap, draw: (Canvas, Float, Float) -> Unit): Bitmap {
        return Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(actual.getPixel(0, 0))
            draw(Canvas(this), width.toFloat(), height.toFloat())
        }
    }

    private fun assertMatchesPainter(message: String, expected: Bitmap, actual: Bitmap) {
        // RenderNode replay and direct bitmap drawing round compositing and contour coverage
        // differently. Allow one channel level in flat interiors and at most one pixel at edges.
        fun matches(reference: Bitmap, rendered: Bitmap): Boolean {
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                val pixel = rendered.getPixel(x, y)
                if (pixel == reference.getPixel(x, y)) continue
                for (shift in 0..24 step 8) {
                    val values = (maxOf(0, y - 1)..minOf(reference.height - 1, y + 1)).flatMap { row ->
                        (maxOf(0, x - 1)..minOf(reference.width - 1, x + 1)).map { column ->
                            reference.getPixel(column, row) ushr shift and 255
                        }
                    }
                    if ((pixel ushr shift and 255) !in (values.min() - 1)..(values.max() + 1)) return false
                }
            }
            return true
        }
        assertTrue(message, matches(expected, actual) && matches(actual, expected))
    }

}
