package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemFiniteCanvasTest {
    @Test
    fun finiteVariableAnimationReleasesItsOverrideAndRestoresTheStaticValue() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("cellularbars")
        val painter = AndroidSymbolPainter(symbol)
        val timing = SymbolMotionTiming(system = true)
        fun frame(animation: SymbolVariableAnimation?, value: Float, time: Float): IntArray {
            val bitmap = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
            painter.draw(Canvas(bitmap), 384f, 160f, SymbolRenderingMode.Multicolor,
                Color.BLUE, Color.GREEN, Color.RED, false, SymbolEffect.None, time,
                playback = SymbolEffectPlayback.Continuous, variableValue = value,
                variableAnimation = animation, effectRepeatCount = 2)
            return IntArray(384 * 256).also { bitmap.getPixels(it, 0, 384, 0, 0, 384, 256) }
        }
        for (iterative in listOf(false, true)) for (bounces in listOf(false, true)) {
            val duration = timing.variableContinuousDuration(4, iterative, bounces, willLoop = true, repeatCount = 2)
            for (value in listOf(0f, .5f, 1f)) {
                assertArrayEquals("iterative=$iterative bounces=$bounces static=$value",
                    frame(null, value, 0f), frame(SymbolVariableAnimation(iterative = iterative,
                        bounces = bounces, willLoop = true), value, duration + .1f))
            }
        }
    }

    @Test
    fun finitePublicPainterReturnsToRestAfterTheLastLayersNativeOutro() = runBlocking {
        val symbol = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("rainbow")
        val painter = AndroidSymbolPainter(symbol)
        val timing = SymbolMotionTiming(system = true)
        fun frame(effect: SymbolEffect, time: Float, mode: SymbolEffectLayerMode): IntArray {
            val bitmap = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
            painter.draw(Canvas(bitmap), 384f, 160f, SymbolRenderingMode.Multicolor,
                Color.BLUE, Color.GREEN, Color.RED, false, effect, time,
                playback = SymbolEffectPlayback.Continuous, effectLayerMode = mode, effectRepeatCount = 2)
            return IntArray(384 * 384).also { bitmap.getPixels(it, 0, 384, 0, 0, 384, 384) }
        }
        val groups = symbolMotionGroups(symbol.nodes, SymbolRenderingMode.Multicolor).maxGroupIndex + 1
        for (mode in listOf(SymbolEffectLayerMode.WholeSymbol, SymbolEffectLayerMode.ByLayer)) {
            val rest = frame(SymbolEffect.None, 0f, mode)
            for (effect in listOf(SymbolEffect.Bounce, SymbolEffect.BounceDown, SymbolEffect.WiggleLeft,
                SymbolEffect.WiggleClockwise, SymbolEffect.RotateClockwise, SymbolEffect.RotateCounterclockwise,
                SymbolEffect.Breathe, SymbolEffect.BreathePulse, SymbolEffect.Pulse)) {
                val duration = timing.continuousDuration(effect, 2, groups,
                    wholeSymbol = mode == SymbolEffectLayerMode.WholeSymbol)
                assertTrue("$effect/$mode must have a finite duration", duration.isFinite() && duration > 0f)
                assertArrayEquals("$effect/$mode must return to the original canvas after its outro",
                    rest, frame(effect, duration + .1f, mode))
            }
        }
    }
}
