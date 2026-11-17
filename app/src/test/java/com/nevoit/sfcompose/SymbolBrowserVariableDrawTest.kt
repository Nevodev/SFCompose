package com.nevoit.sfcompose

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertFalse
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
import org.junit.Before

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolBrowserVariableDrawTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun openAnimationInspector() {
        compose.onNodeWithText("动画预览").performClick()
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
    fun choosingDrawStartsAtZeroWithoutReusingThePreviousClock() {
        compose.onAllNodesWithText("Symbol name")[0].performTextReplacement("cellularbars")
        compose.onNodeWithText("显示").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("cellularbars").fetchSemanticsNodes().size == 4
        }
        for (effect in listOf("Draw On", "Draw Off", "Draw On")) {
            compose.onNodeWithTag("animation-time").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
            compose.onNodeWithText(effect).performScrollTo().performClick()
            compose.onNodeWithTag("symbol-browser").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
            val actual = compose.onAllNodesWithContentDescription("cellularbars")[0].captureToImage().asAndroidBitmap()
            val symbol = runBlocking { SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("cellularbars") }
            val expected = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
            expected.eraseColor(actual.getPixel(0, 0))
            AndroidSymbolPainter(symbol).draw(Canvas(expected), actual.width.toFloat(), actual.height.toFloat(),
                SymbolRenderingMode.Monochrome, 0xff6254e8.toInt(), 0xfffa9d31.toInt(), 0xff33b899.toInt(), false,
                SymbolEffect.None, 0f, variableValue = 1f,
                drawEffect = if (effect == "Draw On") com.nevoit.sfcompose.symbols.SymbolDrawEffect.On
                    else com.nevoit.sfcompose.symbols.SymbolDrawEffect.Off)
            val first = IntArray(actual.width * actual.height)
            val second = IntArray(first.size)
            actual.getPixels(first, 0, actual.width, 0, 0, actual.width, actual.height)
            expected.getPixels(second, 0, expected.width, 0, 0, expected.width, expected.height)
            assertArrayEquals("$effect kept a previous elapsed time", second, first)
        }
    }

    @Test
    fun bouncingContinuousPreviewDoesNotWrapBackToTheFirstGroupAtTheTurn() {
        compose.onAllNodesWithText("Symbol name")[0].performTextReplacement("cellularbars")
        compose.onNodeWithText("显示").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("cellularbars").fetchSemanticsNodes().size == 4
        }
        compose.onNodeWithText("暂停").performScrollTo().performClick()
        for (label in listOf("变量动画", "依次", "往返", "循环")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        val symbol = runBlocking { SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("cellularbars") }
        for (time in listOf(1.5833334f, 2.4166667f, 4.0833335f)) {
            compose.onNodeWithTag("animation-time").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(time) }
            compose.onNodeWithTag("symbol-browser").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
            val actual = compose.onAllNodesWithContentDescription("cellularbars")[0].captureToImage().asAndroidBitmap()
            val expected = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
            expected.eraseColor(actual.getPixel(0, 0))
            AndroidSymbolPainter(symbol).draw(Canvas(expected), actual.width.toFloat(), actual.height.toFloat(),
                SymbolRenderingMode.Monochrome, 0xff6254e8.toInt(), 0xfffa9d31.toInt(), 0xff33b899.toInt(),
                false, SymbolEffect.None, time, variableValue = 1f, playback = SymbolEffectPlayback.Continuous,
                variableAnimation = SymbolVariableAnimation(iterative = true, bounces = true, willLoop = false))
            fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            assertArrayEquals("Looping bounce inserts a virtual first layer at $time", pixels(expected), pixels(actual))
        }
    }

    private fun assertSliderChangesPixels(name: String, control: String, automaticDraw: Boolean = false) {
        compose.onAllNodesWithText("Symbol name")[0].performTextReplacement(name)
        compose.onNodeWithText("显示").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().size == 4
        }
        if (automaticDraw) compose.onNodeWithText("Draw Off").performScrollTo().performClick()
        compose.onNodeWithText(control).performScrollTo().performClick()
        if (control == "Draw 进度") {
            compose.onNodeWithText("该符号无原生 Variable Draw 范围；滑块控制 Draw On 进度。").assertExists()
        }
        fun pixels(value: Float): IntArray {
            compose.onNodeWithTag("variable-value").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
            compose.onNodeWithTag("symbol-browser").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
            val preview = compose.onAllNodesWithContentDescription(name)[0].performScrollTo()
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
}
