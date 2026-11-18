package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
class SymbolCutoutMaterialTest {
    @Test
    fun transparentClearCoverageDoesNotInheritTintOrHierarchyMaterialAlpha() = runBlocking {
        val symbol = fixture(materialOpacity = 0.0, maskHierarchy = 2)
        for (mode in SymbolRenderingMode.entries) {
            val image = frame(symbol, mode, Color.argb(102, 0, 0, 255))
            assertEquals("Cutout must fully erase independently of its material / $mode", 0,
                Color.alpha(image.getPixel(50, 50)))
            assertEquals("Background still has the requested tint alpha / $mode", 102,
                Color.alpha(image.getPixel(20, 20)))
        }
    }

    @Test
    fun drawingAnimationDoesNotChangeTheClearCoverageOfPositiveMaterial() = runBlocking {
        val symbol = fixture(materialOpacity = .5, maskHierarchy = 0)
        for (mode in SymbolRenderingMode.entries) {
            val image = frame(symbol, mode, Color.BLUE, .5f)
            val expectedAlpha = .25f * 255f
            assertTrue("Clear coverage and fill alpha must compose separately / $mode: ${Color.alpha(image.getPixel(50, 50))}",
                kotlin.math.abs(Color.alpha(image.getPixel(50, 50)) - expectedAlpha) <= 1f)
            assertEquals(255, Color.alpha(image.getPixel(20, 20)))
        }
    }

    @Test
    fun commonOpacityErasesBeforeTheTranslucentFill() = runBlocking {
        val symbol = fixture(materialOpacity = .5, maskHierarchy = 0)
        for (mode in SymbolRenderingMode.entries) {
            val image = frame(symbol, mode, Color.BLUE, commonOpacity = .5f)
            val expectedAlpha = (.25f + .5f * .75f) * 255f
            assertTrue("Common presentation opacity must affect both operations / $mode",
                kotlin.math.abs(Color.alpha(image.getPixel(50, 50)) - expectedAlpha) <= 1f)
        }
    }

    @Test
    fun replacementFadeOnlyHidesAPureCutoutAtZeroDrawingOpacity() = runBlocking {
        val symbol = fixture(0.0, 0)
        for (mode in SymbolRenderingMode.entries) {
            assertEquals(0, Color.alpha(frame(symbol, mode, Color.BLUE, .5f).getPixel(50, 50)))
            assertEquals(255, Color.alpha(frame(symbol, mode, Color.BLUE, 0f).getPixel(50, 50)))
        }
    }

    @Test
    fun unannotatedAndWholeSymbolPulsesDoNotFadeTheCutout() = runBlocking {
        for (explicit in listOf(false, true)) {
            val symbol = fixture(0.0, 0, explicitPulse = explicit)
            for (effect in listOf(SymbolEffect.Pulse, SymbolEffect.BreathePulse)) {
                for (mode in SymbolRenderingMode.entries) for (time in listOf(.25f, .5f, 1f)) {
                    if (!explicit) assertEquals("Generic pulse must keep erasure full / $effect / $mode / $time", 0,
                        Color.alpha(frame(symbol, mode, Color.argb(102, 0, 0, 255),
                            effect = effect, time = time).getPixel(50, 50)))
                    assertEquals("Whole-symbol pulse changes drawing, not erasure / $effect / $mode / $time", 0,
                        Color.alpha(frame(symbol, mode, Color.argb(102, 0, 0, 255), effect = effect,
                            time = time, layerMode = SymbolEffectLayerMode.WholeSymbol).getPixel(50, 50)))
                }
            }
        }
    }

    @Test
    fun explicitlyAnnotatedByLayerPulseUsesIndependentErasingTrack() = runBlocking {
        val symbol = fixture(0.0, 2, explicitPulse = true)
        val time = (0..120).map { it / 60f }.minBy { SymbolSystemTiming.pulseOpacity(it) }
        val keyframe = (SymbolSystemTiming.pulseOpacity(time) - .3f) / .7f
        val erasure = keyframe * .7f + .3f
        for (mode in SymbolRenderingMode.entries) for (alpha in listOf(102, 255)) {
            val image = frame(symbol, mode, Color.argb(alpha, 0, 0, 255), effect = SymbolEffect.Pulse, time = time)
            assertTrue("Explicit pulse erasure must ignore tint/hierarchy / $mode / alpha=$alpha",
                kotlin.math.abs(Color.alpha(image.getPixel(50, 50)) - alpha * (1f - erasure)) <= 1f)
            assertEquals("The non-pulsing background must remain unchanged", alpha, Color.alpha(image.getPixel(20, 20)))
        }
    }

    @Test
    fun variableColorUpdatesErasingWithoutInheritingTheCutoutHierarchy() = runBlocking {
        val symbol = fixture(0.0, 1, variableThreshold = true)
        for (mode in SymbolRenderingMode.entries) {
            val inactive = if (mode == SymbolRenderingMode.Hierarchical) .25f else .3f
            val image = frame(symbol, mode, Color.BLUE, variableValue = 0f)
            assertTrue("Inactive variable color has its own erasing multiplier / $mode",
                kotlin.math.abs(Color.alpha(image.getPixel(50, 50)) - 255f * (1f - inactive)) <= 1f)
            assertEquals(0, Color.alpha(frame(symbol, mode, Color.BLUE, variableValue = 1f).getPixel(50, 50)))
        }
    }

    @Test
    fun compatibleMaterialsInterpolateMaterialAndHierarchySeparately() = runBlocking {
        val old = fixture(.25, 0)
        val new = fixture(.75, 2)
        val from = old.nodes[1].source
        val to = new.nodes[1].source
        assertTrue(from.symbolMaterialMatches(to, SymbolRenderingMode.Hierarchical))
        val image = frame(new, SymbolRenderingMode.Hierarchical, Color.BLUE,
            descriptorState = SymbolReplaceLayerState(drawModeFrom = from, drawModeTo = to, drawModeProgress = .5f))
        assertTrue("Interpolated hierarchy .625 and material .5 must multiply after interpolation",
            kotlin.math.abs(Color.alpha(image.getPixel(50, 50)) - 255f * .625f * .5f) <= 1f)
    }

    @Test
    fun materialAdmissionDistinguishesNonePaletteSlotsAndNamedColors() = runBlocking {
        val old = fixture(0.0, 0).nodes[1].source
        val new = fixture(.75, 0).nodes[1].source
        for (mode in SymbolRenderingMode.entries) assertTrue(!old.symbolMaterialMatches(new, mode))
        val secondary = fixture(.75, 1).nodes[1].source
        assertTrue(new.symbolMaterialMatches(secondary, SymbolRenderingMode.Hierarchical))
        assertTrue(!new.symbolMaterialMatches(secondary, SymbolRenderingMode.Palette))
        new.getJSONObject("multicolor").put("color", "systemRedColor")
        secondary.getJSONObject("multicolor").put("color", "systemBlueColor")
        assertTrue(!new.symbolMaterialMatches(secondary, SymbolRenderingMode.Multicolor))
        secondary.getJSONObject("multicolor").put("color", "systemRedColor")
        assertTrue(new.symbolMaterialMatches(secondary, SymbolRenderingMode.Multicolor))
        secondary.getJSONObject("sourceModeStyles").getJSONObject("multicolor").remove("-sfsymbols-clear-behind")
        assertTrue(!new.symbolMaterialMatches(secondary, SymbolRenderingMode.Multicolor))
    }

    @Test
    fun variableAnimationReplacesTheHierarchyDuringItsMainPhase() = runBlocking {
        val symbol = fixture(1.0, 1, variableThreshold = true)
        val animation = SymbolVariableAnimation(iterative = false, bounces = false, willLoop = false)
        val track = SymbolMotionTiming(system = true).variableColorTrack(0, 1, false, false, false)
        for (time in listOf(.1f, .3f, .5f)) {
            val expected = track.applyOpacity(SymbolLayerOpacity(drawing = .5f), time, .25f)
            val image = frame(symbol, SymbolRenderingMode.Hierarchical, Color.BLUE, time = time,
                variableAnimation = animation)
            assertTrue("Production painter must apply SetBoth rather than keep hierarchy / $time",
                kotlin.math.abs(Color.alpha(image.getPixel(50, 50)) - 255f * expected.drawing) <= 1f)
        }
    }

    private fun frame(symbol: SymbolInstance, mode: SymbolRenderingMode, tint: Int, opacity: Float = 1f,
        commonOpacity: Float = 1f, effect: SymbolEffect = SymbolEffect.None, time: Float = 0f,
        layerMode: SymbolEffectLayerMode = SymbolEffectLayerMode.ByLayer, variableValue: Float? = null,
        descriptorState: SymbolReplaceLayerState? = null, variableAnimation: SymbolVariableAnimation? = null): Bitmap {
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        AndroidSymbolPainter(symbol).draw(Canvas(image), 100f, 100f, mode, tint, tint, tint,
            false, effect, time, effectLayerMode = layerMode, variableValue = variableValue,
            variableAnimation = variableAnimation, replacementState = { source ->
                if (source.getInt("sourceLayerIndex") == 1 && descriptorState != null) descriptorState else
                    SymbolReplaceLayerState(opacity = if (source.getInt("sourceLayerIndex") == 1) opacity else 1f,
                    commonOpacity = if (source.getInt("sourceLayerIndex") == 1) commonOpacity else 1f)
            })
        return image
    }

    private suspend fun fixture(materialOpacity: Double, maskHierarchy: Int, explicitPulse: Boolean = false,
        variableThreshold: Boolean = false): SymbolInstance {
        val template = SymbolAssetStore(RuntimeEnvironment.getApplication().assets).load("heart.fill")
        fun rectangle(left: Double, right: Double): SymbolContourData = SymbolContourData("MLLLZ",
            doubleArrayOf(left, left, right, left, right, right, left, right))
        val data = listOf(rectangle(100.0, 900.0), rectangle(400.0, 600.0))
        val paths = data.map { contour -> Path().apply {
            val p = contour.points
            moveTo(p[0].toFloat(), p[1].toFloat())
            for (i in 2..6 step 2) lineTo(p[i].toFloat(), p[i + 1].toFloat())
            close()
        } }
        fun source(index: Int): JSONObject {
            val styles = JSONObject()
            for (mode in listOf("monochrome", "hierarchical", "multicolor")) styles.put(mode, JSONObject().apply {
                put("selector", ".$mode-$index")
                if (index == 1) {
                    put("-sfsymbols-clear-behind", "true")
                    put("opacity", materialOpacity.toString())
                    if (explicitPulse) put("-sfsymbols-always-pulses", "true")
                    if (variableThreshold) put("-sfsymbols-variable-threshold", ".5")
                }
            })
            return JSONObject().put("sourceLayerIndex", index).put("sourceModeStyles", styles)
                .put("monochrome", "draw").put("hierarchical", if (index == 1) maskHierarchy else 0)
                .put("multicolor", JSONObject().put("color", "tintColor"))
        }
        return SymbolInstance("cutout-fixture", SymbolWeight.Regular, SymbolScale.Medium,
            template.weightPosition, 1000f, 1000f, 1000f, paths, data, JSONArray(), JSONArray(),
            doubleArrayOf(0.0, 0.0), template.ultralightWeightPosition, RectF(100f, 100f, 900f, 900f),
            listOf(SymbolNode.Layer(intArrayOf(0), source(0)), SymbolNode.Layer(intArrayOf(1), source(1))),
            JSONObject().put("sourceKind", "macOS-system-fixture"))
    }
}
