package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SymbolAttachmentRecoveryTest {
    private val names = listOf("person.fill.and.arrow.left.and.arrow.right.outward",
        "rectangle.portrait.and.arrow.forward", "rectangle.portrait.and.arrow.right",
        "arrow.trianglehead.turn.up.right.circle.fill", "arrow.trianglehead.turn.up.right.diamond.fill")

    @Test
    fun allRejectedStoresKeepTheirNativeBaselineAndRecoverCompleteDrawEndpoints() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        for (name in names) for (weight in listOf(SymbolWeight.Light, SymbolWeight.Regular, SymbolWeight.Bold)) {
            for (scale in SymbolScale.entries) {
                val symbol = store.load(name, weight, scale)
                for (mode in listOf("monochrome", "hierarchical", "multicolor")) for (size in listOf(13f, 160f)) {
                    val layer = attachedLayer(symbol, mode)
                    val original = Path().apply { layer.components.forEach { addPath(symbol.contours[it]) } }
                    val rawBefore = symbol.draw.pathFor(layer.source, original, 0f, pointSize = size, systemMode = mode)!!
                    assertFalse("Native store must stay rejected: $name", symbol.draw.hasSystemAttachments(layer.source))
                    assertTrue("Recoverable store: $name / $weight / $scale / $mode", symbol.draw.hasSystemAttachments(layer.source, true))
                    for (on in listOf(false, true)) for (reverse in listOf(false, true)) {
                        fun draw(progress: Float) = symbol.draw.pathFor(layer.source, original, progress, on, reverse,
                            pointSize = size, systemMode = mode, recoverMissingAttachmentAnchors = true)!!
                        val first = draw(0f)
                        val last = draw(1f)
                        assertTrue("Empty endpoint $name / $mode / on=$on / reverse=$reverse", (if (on) first else last).isEmpty)
                        assertTrue("Full endpoint $name / $mode / on=$on / reverse=$reverse",
                            pixels(original, symbol.bounds).contentEquals(pixels(if (on) last else first, symbol.bounds)))
                        assertTrue("Draw must contain intermediate geometry: $name", !draw(.8f).isEmpty)
                    }
                    val rawAfter = symbol.draw.pathFor(layer.source, original, 0f, pointSize = size, systemMode = mode)!!
                    assertTrue("Compatibility must not mutate raw native geometry: $name",
                        pixels(rawBefore, symbol.bounds).contentEquals(pixels(rawAfter, symbol.bounds)))
                    assertFalse(symbol.draw.hasSystemAttachments(layer.source))
                }
            }
        }
    }

    @Test
    fun recoveredHeadsFollowTheirOwnShaftAndRetainTheirOriginalAppearanceThreshold() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        for (name in names) {
            val symbol = store.load(name)
            val layer = attachedLayer(symbol, "monochrome")
            assertTrue(symbol.draw.hasSystemAttachments(layer.source, true))
            val attachments = recoveredAttachments(symbol.draw, layer.source)
            val entries = entries(attachments)
            assertEquals(if (name.startsWith("person.")) 2 else 1, entries.size)
            val slice = SymbolDraw::class.java.getDeclaredMethod("systemAttachmentSlice",
                SymbolDrawAttachments.Entry::class.java, SystemDrawState::class.java).apply { isAccessible = true }
            fun resolved(progress: Float): List<Path> {
                val state = SystemDrawState(0f, progress, 4f, 1f)
                return attachments.resolve(progress, true, false,
                    sliceTransform = { slice.invoke(symbol.draw, it, state) as SymbolDrawAttachments.Slice? },
                    systemState = { state }).map { it.second }
            }
            for ((index, entry) in entries.withIndex()) {
                val from = entry.system!!.from
                assertTrue(resolved(maxOf(0f, from - .01f))[index].isEmpty)
                val early = resolved(from + (1f - from) * .25f)[index]
                val late = resolved(from + (1f - from) * .75f)[index]
                assertFalse(early.isEmpty)
                assertFalse(late.isEmpty)
                val a = RectF().also { early.computeBounds(it, true) }
                val b = RectF().also { late.computeBounds(it, true) }
                if (name.startsWith("person.") && entry.anchorX < symbol.advance / 2f) {
                    assertTrue("Left head must travel left with its shaft", b.centerX() < a.centerX() - 30f)
                } else {
                    assertTrue("Right head must travel right with its shaft: $name", b.centerX() > a.centerX() + 30f)
                }
                assertTrue("Head motion must stay on the shaft's terminal row: $name",
                    kotlin.math.abs(a.centerY() - b.centerY()) < 8f)
                assertTrue(pixels(entry.path, symbol.bounds).contentEquals(pixels(resolved(1f)[index], symbol.bounds)))
            }
        }
    }

    @Test
    fun recoveredAnchorsFollowContinuousWeightAndOpticalSizeInterpolation() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val positions = store.catalog().weightPositions
        val weights = listOf(SymbolWeight.Light to SymbolWeight.Regular,
            SymbolWeight.Regular to SymbolWeight.Semibold, SymbolWeight.Bold to SymbolWeight.Heavy)
            .map { (a, b) -> (positions.getValue(a) + positions.getValue(b)) / 2f }
        for (name in names) for (weight in weights) for (scale in SymbolScale.entries) {
            val symbol = store.load(name, weightPosition = weight, scale = scale)
            for (mode in listOf("monochrome", "hierarchical", "multicolor")) for (size in listOf(13f, 32f, 160f)) {
                val layer = attachedLayer(symbol, mode)
                val original = Path().apply { layer.components.forEach { addPath(symbol.contours[it]) } }
                fun draw(progress: Float) = symbol.draw.pathFor(layer.source, original, progress,
                    pointSize = size, systemMode = mode, recoverMissingAttachmentAnchors = true)!!
                assertTrue("Empty Draw On: $name / $weight / $scale / $mode / $size", draw(0f).isEmpty)
                assertTrue(symbol.draw.hasSystemAttachments(layer.source, true))
                assertFalse(symbol.draw.hasSystemAttachments(layer.source))
                assertFalse("Continuous weight must retain clipping", draw(.5f).isEmpty)
                assertTrue("Completed continuous-weight Draw must preserve the static outline",
                    pixels(original, symbol.bounds).contentEquals(pixels(draw(1f), symbol.bounds)))
            }
        }
    }

    private fun attachedLayer(symbol: SymbolInstance, mode: String): SymbolNode.Layer =
        symbol.nodes.filterIsInstance<SymbolNode.Layer>().single {
            it.source.optString("systemRenderingMode") == mode &&
                !SymbolDrawAttachments.systemRecords(it.source, mode).isNullOrEmpty()
        }

    private fun recoveredAttachments(draw: SymbolDraw, source: JSONObject): SymbolDrawAttachments {
        val layers = draw.currentDrawSizeField("layers") as Map<*, *>
        val preparation = layers[source]!!
        return preparation.javaClass.getDeclaredMethod("getRecoveredAttachments").apply { isAccessible = true }
            .invoke(preparation) as SymbolDrawAttachments
    }

    private fun entries(attachments: SymbolDrawAttachments): List<SymbolDrawAttachments.Entry> {
        val field = SymbolDrawAttachments::class.java.getDeclaredField("entries").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(attachments) as List<SymbolDrawAttachments.Entry>
    }

    private fun pixels(path: Path, bounds: RectF): IntArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            translate(8f, 8f)
            val scale = 240f / maxOf(bounds.width(), bounds.height())
            scale(scale, scale)
            translate(-bounds.left, -bounds.top)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE })
        }
        val result = IntArray(256 * 256).also { bitmap.getPixels(it, 0, 256, 0, 0, 256, 256) }
        bitmap.recycle()
        return result
    }
}

internal fun SymbolDraw.currentDrawSizeField(name: String): Any {
    val sizePreparation = SymbolDraw::class.java.getDeclaredField("sizePreparation")
        .apply { isAccessible = true }.get(this)
    return checkNotNull(sizePreparation.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(sizePreparation))
}
