package com.nevoit.sfcompose.symbols

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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
class SymbolReplaceControllerComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pausedClockRetargetKeepsTheFrameAndResetInvalidatesTheCanvas() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val first = store.load("person.fill")
        val second = store.load("rainbow")
        val third = store.load("bell.slash")
        val controller = SymbolReplaceController(first)
        val clock = mutableFloatStateOf(0f)
        compose.setContent {
            Box(Modifier.size(240.dp).background(Color.White), contentAlignment = Alignment.Center) {
                SFSymbolReplace(controller, Modifier.size(160.dp), tint = Color.Blue,
                    displayMode = SymbolDisplayMode.SquareOverflow, elapsedSeconds = { clock.floatValue })
            }
        }
        fun pixels(): IntArray {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        }
        compose.runOnIdle { controller.replaceWith(second, 0f, preferMagic = false); clock.floatValue = .12f }
        val before = pixels()
        compose.runOnIdle { controller.replaceWith(third, .12f) }
        assertArrayEquals("Changing target on a paused clock must keep the exact current presentation", before, pixels())
        compose.runOnIdle { controller.reset(second) }
        val afterReset = pixels()
        assertFalse("Reset must invalidate drawing even without advancing the clock", before.contentEquals(afterReset))
        compose.runOnIdle { controller.replaceWith(third, .12f); clock.floatValue = controller.endTimeSeconds }
        val completed = pixels()
        compose.runOnIdle { controller.advanceTo(clock.floatValue); controller.reset(third) }
        assertArrayEquals("Completion must produce the static target", completed, pixels())
    }
}
