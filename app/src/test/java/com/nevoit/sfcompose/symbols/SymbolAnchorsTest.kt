package com.nevoit.sfcompose.symbols

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolAnchorsTest {
    private val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)

    @Test
    fun systemRegularAnchorUsesCanvasMasterInterpolationAndOriginalTransform() = runBlocking {
        val arrow = store.load("arrow.clockwise", SymbolWeight.Regular, SymbolScale.Medium)
        val anchor = arrow.rotationAnchor()
        val expected = systemAnchor(0.18209370032725572, 0.0, 1.276)
        assertEquals(expected.first, anchor.first, 0.0001f)
        assertEquals(expected.second, anchor.second, 0.0001f)
        assertTrue(anchor.second != arrow.metricCenterY)
        val wifi = store.load("wifi")
        assertEquals(wifi.metricCenterX, wifi.rotationAnchor().first, 0f)
        assertEquals(wifi.metricCenterY, wifi.rotationAnchor().second, 0f)
    }

    @Test
    fun systemIntermediateWeightUsesBothMasterDeltasAndLargeScale() = runBlocking {
        val arrow = store.load("arrow.clockwise", SymbolWeight.Semibold, SymbolScale.Large)
        val expected = systemAnchor(0.24711082153264766, 0.2309538564666666, 1.647)
        assertEquals(expected.first, arrow.rotationAnchor().first, 0.0001f)
        assertEquals(expected.second, arrow.rotationAnchor().second, 0.0001f)
    }

    private fun systemAnchor(ultralight: Double, black: Double, scale: Double): Pair<Float, Float> {
        // Original BVG ra coordinates are canvas Y down; named CoreUI sampling scales after blending.
        val x = (4.42963 + ultralight * (4.21799 - 4.42963) + black * (4.70913 - 4.42963)) * scale
        val y = (6.30322 + ultralight * (6.23649 - 6.30322) + black * (6.69038 - 6.30322)) * scale
        return ((x + 0.888671) / 0.00634765).toFloat() to (-(y - 10.5117) / 0.00634765).toFloat()
    }
}
