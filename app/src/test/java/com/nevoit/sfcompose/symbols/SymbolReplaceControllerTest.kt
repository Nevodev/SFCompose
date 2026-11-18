package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
class SymbolReplaceControllerTest {
    private val store get() = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)

    @Test
    fun ordinaryReplacementPreservesTheExistingRecipe() = runBlocking {
        val first = store.load("folder")
        val second = store.load("folder.badge.plus")
        for (mode in SymbolRenderingMode.entries) {
            val controller = SymbolReplaceController(first)
            controller.tracks(0f, 160f)
            controller.replaceWith(second, 1f)
            val plan = SymbolReplacePlan(first, second, mode = mode, pointSize = 160f)
            for (local in listOf(0f, .125f, .35f, plan.durationSeconds)) {
                val expected = listOf(first to false, second to true).map { (symbol, incoming) ->
                    SymbolReplaceTrack(symbol, state = { _, source, _ -> plan.layerState(source, incoming, local) })
                }
                assertArrayEquals("Uninterrupted replacement / $mode / $local", render(expected, mode),
                    render(controller.tracks(1f + local, 160f), mode))
            }
        }
    }

    @Test
    fun thirdTargetKeepsEveryCurrentVectorLayerAtTheInterruptionBoundary() = runBlocking {
        for ((firstName, secondName) in listOf("rainbow" to "airplay.audio", "heart.circle" to "star.circle.fill",
            "folder" to "folder.badge.plus", "bell" to "bell.slash")) {
            val first = store.load(firstName)
            val second = store.load(secondName)
            val third = store.load("shareplay")
            for (mode in SymbolRenderingMode.entries) for (direction in SymbolReplaceDirection.entries) {
                val controller = SymbolReplaceController(first)
                controller.tracks(0f, 160f)
                controller.replaceWith(second, 0f, direction)
                val time = controller.durationSeconds * .43f
                val before = render(controller.tracks(time, 160f), mode)
                controller.replaceWith(third, time, direction)
                assertArrayEquals("Interrupted $firstName -> $secondName -> shareplay / $mode / $direction", before,
                    render(controller.tracks(time, 160f), mode))
                val end = controller.endTimeSeconds
                assertArrayEquals("Completion / $mode / $direction", render(listOf(SymbolReplaceTrack(third)), mode),
                    render(controller.tracks(end, 160f), mode))
                assertFalse(controller.advanceTo(end))
                assertEquals(listOf(third), controller.layoutSymbols())
            }
        }
    }

    @Test
    fun reversingAndResumingUseTheOriginalPresentationTime() = runBlocking {
        val first = store.load("bell")
        val second = store.load("bell.slash")
        val controller = SymbolReplaceController(first)
        controller.tracks(0f, 160f)
        controller.replaceWith(second, 1f)
        val forwardTime = 1.19f
        val before = render(controller.tracks(forwardTime, 160f), SymbolRenderingMode.Monochrome)
        controller.replaceWith(first, forwardTime)
        assertArrayEquals(before, render(controller.tracks(forwardTime, 160f), SymbolRenderingMode.Monochrome))
        val backTime = 1.24f
        val backwards = render(controller.tracks(backTime, 160f), SymbolRenderingMode.Monochrome)
        controller.replaceWith(second, backTime)
        assertArrayEquals(backwards, render(controller.tracks(backTime, 160f), SymbolRenderingMode.Monochrome))
        val end = controller.endTimeSeconds
        assertFalse(controller.advanceTo(end))
        assertEquals(listOf(second), controller.layoutSymbols())
    }

    @Test
    fun rapidRetargetsKeepOriginalTailDeadlinesAndReleaseAllPreviousSymbols() = runBlocking {
        val symbols = listOf("heart.fill", "star.fill", "bell.fill", "folder.fill").map { store.load(it) }
        val controller = SymbolReplaceController(symbols[0])
        controller.tracks(0f, 160f)
        var time = 0f
        for (i in 1..40) {
            time += .025f
            val before = render(controller.tracks(time, 160f), SymbolRenderingMode.Hierarchical)
            controller.replaceWith(symbols[i % symbols.size], time, SymbolReplaceDirection.UpUp, preferMagic = false)
            assertArrayEquals("Retarget $i", before, render(controller.tracks(time, 160f), SymbolRenderingMode.Hierarchical))
            assertTrue("Expired tails must not be renewed forever", controller.tracks(time, 160f).size <= 12)
        }
        val ratio = controller.layoutAdvanceRatio
        assertFalse(controller.advanceTo(controller.endTimeSeconds))
        assertEquals(1, controller.tracks(controller.endTimeSeconds, 160f).size)
        assertEquals(listOf(controller.target), controller.layoutSymbols())
        assertEquals("Finishing does not jump the slot width", ratio, controller.layoutAdvanceRatio, 0f)
    }

    @Test
    fun shrinkingTheSharedFitScaleDoesNotResizeTheCapturedPresentation() = runBlocking {
        val first = store.load("folder")
        val second = store.load("folder.badge.plus")
        val third = store.load("rainbow")
        val controller = SymbolReplaceController(first)
        controller.tracks(0f, 160f)
        controller.replaceWith(second, 0f)
        val before = render(controller.tracks(.2f, 160f), SymbolRenderingMode.Monochrome)
        controller.replaceWith(third, .2f)
        assertArrayEquals(before, render(controller.tracks(.2f, 80f), SymbolRenderingMode.Monochrome, 80f))
    }

    @Test
    fun reversingAfterFitScaleChangesReturnsToTheOriginalSize() = runBlocking {
        val first = store.load("person.fill")
        val second = store.load("rainbow")
        val controller = SymbolReplaceController(first)
        val original = render(controller.tracks(0f, 160f), SymbolRenderingMode.Monochrome)
        controller.replaceWith(second, 0f, preferMagic = false)
        assertArrayEquals(original, render(controller.tracks(0f, 80f), SymbolRenderingMode.Monochrome, 80f))
        controller.tracks(.1f, 80f)
        controller.replaceWith(first, .1f)
        val end = controller.endTimeSeconds
        assertArrayEquals(original, render(controller.tracks(end, 80f), SymbolRenderingMode.Monochrome, 80f))
        controller.advanceTo(end)
        assertArrayEquals(original, render(controller.tracks(end, 80f), SymbolRenderingMode.Monochrome, 80f))
    }

    @Test
    fun drawingTheCompletedFrameReleasesPreviousSymbolsWithoutAnExplicitAdvance() = runBlocking {
        val first = store.load("rainbow")
        val second = store.load("airplay.audio")
        val third = store.load("person.fill")
        val controller = SymbolReplaceController(first)
        controller.tracks(0f, 160f)
        controller.replaceWith(second, 0f)
        controller.tracks(.15f, 160f)
        controller.replaceWith(third, .15f)
        val ratio = controller.layoutAdvanceRatio
        val revision = controller.revision
        assertTrue(controller.layoutSymbols().size > 1)
        val final = controller.tracks(controller.endTimeSeconds, 160f)
        assertEquals(listOf(third), final.map { it.symbol })
        assertEquals(listOf(third), controller.layoutSymbols())
        assertEquals("Draw cleanup must not write Compose snapshot state", revision, controller.revision)
        assertEquals(ratio, controller.layoutAdvanceRatio, 0f)
    }

    private fun render(tracks: List<SymbolReplaceTrack>, mode: SymbolRenderingMode, height: Float = 160f): IntArray {
        val bitmap = Bitmap.createBitmap(320, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, (224f - height) * .5f)
        for (track in tracks) {
            if (track.opacity <= 0f) continue
            val save = if (track.opacity < 1f) canvas.saveLayerAlpha(null,
                (track.opacity * 255).toInt().coerceIn(0, 255)) else canvas.save()
            canvas.scale(track.scale, track.scale, 160f, height * .5f)
            AndroidSymbolPainter(track.symbol).draw(canvas, 320f, height, mode, Color.BLUE, Color.YELLOW, Color.RED,
                false, SymbolEffect.None, 0f, pointSize = height,
                replacementState = { track.layerState(mode, it, height) })
            canvas.restoreToCount(save)
        }
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }
}
