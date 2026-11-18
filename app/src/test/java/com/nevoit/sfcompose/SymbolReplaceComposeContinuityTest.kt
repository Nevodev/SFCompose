package com.nevoit.sfcompose

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.AndroidSymbolPainter
import com.nevoit.sfcompose.symbols.SFSymbolReplace
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolDisplayMode
import com.nevoit.sfcompose.symbols.SymbolEffect
import com.nevoit.sfcompose.symbols.SymbolRenderingMode
import com.nevoit.sfcompose.symbols.SymbolReplaceController
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
class SymbolReplaceComposeContinuityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun retargetingWithAPausedClockPreservesThePresentedFrameAndRedraws() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val first = store.load("person.fill")
        val second = store.load("heart.fill")
        val third = store.load("star.fill")
        val controller = SymbolReplaceController(first)
        val clock = mutableFloatStateOf(0f)
        compose.activity.setContent {
            SFSymbolReplace(controller, Modifier.size(256.dp).testTag("continuity-frame"),
                displayMode = SymbolDisplayMode.SquareOverflow, elapsedSeconds = { clock.floatValue })
        }
        fun frame() = compose.onNodeWithTag("continuity-frame").captureToImage().asAndroidBitmap()
        fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        compose.runOnIdle {
            controller.replaceWith(second, 0f, preferMagic = false)
            clock.floatValue = .12f
        }
        val before = pixels(frame())
        compose.runOnIdle { controller.replaceWith(third, .12f, preferMagic = false) }
        assertArrayEquals("A paused clock must preserve the frame at interruption", before, pixels(frame()))
        compose.runOnIdle { clock.floatValue = .52f }
        assertFalse("The controller must keep drawing the new transition", before.contentEquals(pixels(frame())))
        compose.runOnIdle {
            clock.floatValue = controller.endTimeSeconds + .01f
            controller.advanceTo(clock.floatValue)
        }
        val actual = frame()
        val expected = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
        expected.eraseColor(actual.getPixel(0, 0))
        AndroidSymbolPainter(third).draw(Canvas(expected), actual.width.toFloat(), actual.height.toFloat(),
            SymbolRenderingMode.Monochrome, android.graphics.Color.BLACK, android.graphics.Color.BLACK,
            android.graphics.Color.BLACK, false, SymbolEffect.None, 0f)
        assertArrayEquals("Completion must leave exactly the new target", pixels(expected), pixels(actual))
    }
}
