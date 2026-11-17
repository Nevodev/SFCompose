package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolDisplayModeTest {
    @get:Rule val compose = createComposeRule()

    private fun ink(bitmap: Bitmap): Rect {
        val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.blue(pixel) > 180 && android.graphics.Color.red(pixel) < 100) {
                bounds.left = minOf(bounds.left, x)
                bounds.top = minOf(bounds.top, y)
                bounds.right = maxOf(bounds.right, x + 1)
                bounds.bottom = maxOf(bounds.bottom, y + 1)
            }
        }
        return bounds
    }

    @Test
    fun modesSeparateSquarePlacementFromActualWidthAndOnlyShrinkWideSymbols() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val wide = store.load("cellularbars")
        val narrow = store.load("exclamationmark")
        assertTrue(wide.horizontalInkRatio() > 1f)
        assertTrue(narrow.horizontalInkRatio() < 1f)
        val selected = mutableStateOf(wide)
        val mode = mutableStateOf(SymbolDisplayMode.SquareOverflow)
        compose.setContent {
            Box(Modifier.size(400.dp).background(Color.White), contentAlignment = Alignment.Center) {
                SFSymbol(selected.value, null, Modifier.height(100.dp).testTag("symbol"), tint = Color.Blue,
                    displayMode = mode.value)
            }
        }
        fun snapshot(): Pair<androidx.compose.ui.geometry.Rect, Rect> {
            compose.waitForIdle()
            return compose.onNodeWithTag("symbol").fetchSemanticsNode().boundsInRoot to
                ink(compose.onRoot().captureToImage().asAndroidBitmap())
        }
        val (square, overflow) = snapshot()
        assertEquals(square.height, square.width, .01f)
        assertTrue("Mode 1 must permit horizontal overflow", overflow.left < square.left || overflow.right > square.right)
        compose.runOnIdle { mode.value = SymbolDisplayMode.IntrinsicWidth }
        val (intrinsic, original) = snapshot()
        assertEquals(square.height, intrinsic.height, .01f)
        assertEquals(100f * wide.advance / wide.unitsPerEm, intrinsic.width, 1f)
        assertEquals(overflow.top, original.top)
        assertEquals(overflow.bottom, original.bottom)
        assertEquals("Layout width must not change the font scale", overflow.width().toFloat(), original.width().toFloat(), 1f)
        compose.runOnIdle { mode.value = SymbolDisplayMode.SquareFitWidth }
        val (fitted, fitInk) = snapshot()
        assertEquals(square, fitted)
        assertTrue(fitInk.left >= fitted.left - 1 && fitInk.right <= fitted.right + 1)
        assertTrue(fitInk.height() < overflow.height())
        assertEquals("Reduced rendering must remain vertically centered", overflow.exactCenterY(), fitInk.exactCenterY(), 1f)
        compose.runOnIdle { selected.value = narrow; mode.value = SymbolDisplayMode.SquareOverflow }
        val (_, narrowOriginal) = snapshot()
        compose.runOnIdle { mode.value = SymbolDisplayMode.SquareFitWidth }
        assertEquals("Mode 3 must not enlarge a narrow symbol", narrowOriginal, snapshot().second)
    }

    @Test
    fun replacementKeepsOneSquareAndOneScaleForBothEndpoints() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val a = store.load("exclamationmark")
        val b = store.load("cellularbars")
        val elapsed = mutableFloatStateOf(0f)
        val display = mutableStateOf(SymbolDisplayMode.SquareFitWidth)
        compose.setContent {
            Box(Modifier.size(400.dp).background(Color.White), contentAlignment = Alignment.Center) {
                SFSymbolReplace(a, b, Modifier.height(100.dp).testTag("replace"), preferMagic = false,
                    tint = Color.Blue, elapsedSeconds = { elapsed.floatValue }, displayMode = display.value)
            }
        }
        compose.waitForIdle()
        val initialSlot = compose.onNodeWithTag("replace").fetchSemanticsNode().boundsInRoot
        assertEquals(initialSlot.width, initialSlot.height, .01f)
        val initialInk = ink(compose.onRoot().captureToImage().asAndroidBitmap())
        compose.runOnIdle { elapsed.floatValue = 10f }
        val finalSlot = compose.onNodeWithTag("replace").fetchSemanticsNode().boundsInRoot
        val finalInk = ink(compose.onRoot().captureToImage().asAndroidBitmap())
        assertEquals(initialSlot, finalSlot)
        assertTrue(finalInk.left >= finalSlot.left - 1 && finalInk.right <= finalSlot.right + 1)
        compose.runOnIdle { display.value = SymbolDisplayMode.SquareOverflow }
        val overflow = ink(compose.onRoot().captureToImage().asAndroidBitmap())
        assertTrue(overflow.width() > finalInk.width())
        assertTrue(initialInk.height() > 0)
    }
}
