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
class SymbolDrawTimingAssetTest {
    @Test
    fun wifiUsesDrawTracksForArcsAndAppearanceForTheDot() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("wifi")
        val dot = symbol.nodes[0].source
        val inner = symbol.nodes[1].source
        val on = SymbolDrawTiming(symbol, on = true)
        assertEquals(null, on.progress(dot, 0f))
        assertEquals(0f, on.opacity(dot, 0f), 0f)
        assertEquals(0.75f, on.scale(dot, 0f), 0f)
        assertEquals(1f, on.opacity(dot, on.durationSeconds), 0.00001f)
        assertEquals(1f, on.scale(dot, on.durationSeconds), 0.00001f)
        assertEquals(0f, on.progress(inner, 0f)!!, 0f)
        assertEquals(1f, on.progress(inner, on.durationSeconds)!!, 0.00001f)
        assertTrue(on.drawDurationSeconds > 0f)
        val off = SymbolDrawTiming(symbol, on = false)
        assertEquals(1f, off.opacity(dot, 0f), 0f)
        assertEquals(0f, off.opacity(dot, off.durationSeconds), 0.00001f)
        assertEquals(0.75f, off.scale(dot, off.durationSeconds), 0.00001f)
        assertEquals(1f, off.progress(inner, off.durationSeconds)!!, 0.00001f)
    }
}
