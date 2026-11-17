package com.nevoit.sfcompose.symbols

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
class SymbolReplaceTest {
    private val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)

    @Test
    fun downUpUsesTheNativeQuarterSecondCut() = runBlocking {
        val old = store.load("heart.fill")
        val new = store.load("star.fill")
        val plan = SymbolReplacePlan(old, new, preferMagic = false, layered = false)
        val oldSource = old.nodes.first().source
        val newSource = new.nodes.first().source
        assertEquals(1f, plan.layerState(oldSource, false, 0.249f).opacity, 0f)
        assertEquals(0f, plan.layerState(newSource, true, 0.249f).opacity, 0f)
        assertEquals(0f, plan.layerState(oldSource, false, 0.25f).opacity, 0f)
        assertEquals(1f, plan.layerState(newSource, true, 0.25f).opacity, 0f)
        assertEquals(0.5f, plan.layerState(newSource, true, 0.25f).scaleX, 0f)
        assertEquals(1f, plan.layerState(newSource, true, 0.5f).scaleX, 0f)
    }

    @Test
    fun upUpOverlapsAndOffUpCutsImmediately() = runBlocking {
        val old = store.load("heart.fill")
        val new = store.load("star.fill")
        val oldSource = old.nodes.first().source
        val newSource = new.nodes.first().source
        val up = SymbolReplacePlan(old, new, SymbolReplaceDirection.UpUp, false, false)
        assertTrue(up.layerState(oldSource, false, 0.2f).scaleX > 1f)
        assertTrue(up.layerState(oldSource, false, 0.2f).opacity > 0f)
        assertTrue(up.layerState(newSource, true, 0.2f).opacity > 0f)
        val off = SymbolReplacePlan(old, new, SymbolReplaceDirection.OffUp, false, false)
        assertEquals(0f, off.layerState(oldSource, false, 0f).opacity, 0f)
        assertEquals(0.5f, off.layerState(newSource, true, 0f).scaleX, 0f)
        assertEquals(1f, off.layerState(newSource, true, 0.25f).opacity, 0f)
    }

    @Test
    fun magicBadgesRequireSemanticLayerTagsAndMatchingBase() = runBlocking {
        val template = store.load("heart.fill")
        val plain = fixture(template, listOf(listOf("base")))
        val badged = fixture(template, listOf(listOf("base.badge", "base"), listOf("base.badge", "_badge", "plus")))
        val plan = SymbolReplacePlan(plain, badged)
        assertEquals(SymbolReplaceKind.BadgeAppear, plan.kind)
        val base = badged.nodes[0].source
        val badge = badged.nodes[1].source
        assertEquals(1f, plan.layerState(base, true, 0f).opacity, 0f)
        assertEquals(0f, plan.layerState(badge, true, 0f).opacity, 0f)
        assertEquals(0.75f, plan.layerState(badge, true, 0f).scaleX, 0f)
        assertEquals(1f, plan.layerState(badge, true, 0.25f).opacity, 0f)
        val unrelated = fixture(template, listOf(listOf("other")))
        assertEquals(SymbolReplaceKind.Standard, SymbolReplacePlan(unrelated, badged).kind)
        assertEquals(SymbolReplaceKind.Standard, SymbolReplacePlan(badged, badged).kind)
        val replaced = fixture(template, listOf(listOf("base.newbadge", "base"), listOf("base.newbadge", "_badge", "minus")))
        assertEquals(SymbolReplaceKind.BadgeReplace, SymbolReplacePlan(badged, replaced).kind)
    }

    @Test
    fun layeredReplaceUsesReverseOutgoingAndForwardIncomingDelays() = runBlocking {
        val template = store.load("heart.fill")
        val old = fixture(template, List(4) { listOf("old") })
        val new = fixture(template, List(4) { listOf("new") })
        val down = SymbolReplacePlan(old, new, preferMagic = false)
        assertTrue(down.layerState(old.nodes.first().source, false, 0.1f).scaleX < 1f)
        assertEquals(1f, down.layerState(old.nodes.last().source, false, 0.1f).scaleX, 0f)
        val up = SymbolReplacePlan(old, new, SymbolReplaceDirection.UpUp, false)
        assertEquals(0f, up.layerState(new.nodes.first().source, true, 0.2f).opacity, 0f)
        assertTrue(up.layerState(new.nodes.last().source, true, 0.2f).opacity > 0f)
    }

    @Test
    fun drawReplacementUsesRecoveredAdaptiveCompletionBeforeIncomingFallback() = runBlocking {
        val old = store.load("circle")
        val new = store.load("heart.fill")
        val timing = SymbolDrawTiming(old, on = false)
        val plan = SymbolReplacePlan(old, new)
        assertEquals(SymbolReplaceKind.Draw, plan.kind)
        assertTrue(timing.drawDurationSeconds > 0f)
        assertEquals(timing.drawDurationSeconds + 0.5f, plan.durationSeconds, 0.00001f)
        val oldSource = old.nodes.first().source
        val newSource = new.nodes.first().source
        assertTrue(old.metadata.optString("sourceKind").startsWith("macOS-system"))
        assertTrue(old.draw.hasGeometry(oldSource))
        assertEquals(0f, plan.layerState(oldSource, false, 0f).drawProgress!!, 0f)
        assertEquals(0f, plan.layerState(newSource, true, timing.drawDurationSeconds).opacity, 0f)
        assertEquals(1f, plan.layerState(newSource, true, timing.drawDurationSeconds + 0.25f).opacity, 0f)
    }

    @Test
    fun offUpDrawReplacementKeepsTheOutgoingStaticLayerAtTheStart() = runBlocking {
        val old = store.load("airplay.audio")
        val new = store.load("cellularbars")
        val native = javaClass.getResourceAsStream("/symbols/system-replace-offup-native.json")!!
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }.getJSONArray("samples")
        for (mode in SymbolRenderingMode.entries) {
            val plan = SymbolReplacePlan(old, new, SymbolReplaceDirection.OffUp, mode = mode)
            assertEquals(SymbolReplaceKind.Draw, plan.kind)
            val timing = SymbolDrawTiming(old, on = false, mode = mode)
            val staticLayers = old.nodes.filter { it.source.supportsSymbolMode(mode) &&
                timing.progress(it.source, 0f) == null }
            assertTrue("Expected the unannotated AirPlay triangle / $mode", staticLayers.isNotEmpty())
            for (layer in staticLayers) {
                val initial = plan.layerState(layer.source, false, 0f)
                assertEquals("Outgoing static layer disappears at OffUp start / $mode", 1f, initial.opacity, 0f)
                assertEquals(1f, initial.scaleX, 0f)
                assertEquals(0f, plan.layerState(layer.source, false, plan.durationSeconds).opacity, 0f)
            }
            for (layer in old.nodes.filter { it.source.supportsSymbolMode(mode) }) {
                for (index in 0 until native.length()) {
                    val sample = native.getJSONArray(index)
                    assertEquals("Native OffUp fade / $mode sample $index", sample.getDouble(1).toFloat(),
                        plan.layerState(layer.source, false, sample.getDouble(0).toFloat()).opacity, 0f)
                }
                val middle = plan.layerState(layer.source, false, 0.0415f)
                assertEquals(0.5f, middle.opacity, 0.00001f)
                assertEquals(1f, middle.scaleX, 0f)
                assertEquals(null, middle.drawProgress)
                assertEquals(0f, plan.layerState(layer.source, false, 0.083f).opacity, 0f)
            }
            val incomingTiming = SymbolDrawTiming(new, on = true, mode = mode)
            val incomingLayer = new.nodes.first { it.source.supportsSymbolMode(mode) &&
                incomingTiming.progress(it.source, 0f) != null }
            assertEquals(incomingTiming.progress(incomingLayer.source, 0.04f),
                plan.layerState(incomingLayer.source, true, 0.04f).drawProgress)
            assertEquals(maxOf(0.083f, incomingTiming.drawDurationSeconds), plan.durationSeconds, 0.00001f)
            assertPixelsEqual("OffUp initial mixed Draw frame / $mode", frame(old, mode),
                replacementFrame(old, new, plan, mode, 0f))
        }
    }

    @Test
    fun offUpEnclosureReplacementFadesTheInteriorAndCrossfadesChangedEnclosures() = runBlocking {
        for ((oldName, newName) in listOf("heart.circle" to "star.circle",
            "heart.circle" to "star.circle.fill", "heart.circle.fill" to "star.circle")) {
            val old = store.load(oldName)
            val new = store.load(newName)
            for (mode in SymbolRenderingMode.entries) {
                val plan = SymbolReplacePlan(old, new, SymbolReplaceDirection.OffUp, mode = mode)
                assertEquals(SymbolReplaceKind.EnclosureMatch, plan.kind)
                fun enclosure(source: JSONObject): Boolean = source.systemStyle(mode)
                    ?.optString("-sfsymbols-layer-tags")?.split(Regex("\\s+"))
                    ?.any { it == "_enclosure.fill" || it == "_enclosure.stroke" } == true
                val interior = old.nodes.filter { it.source.supportsSymbolMode(mode) && !enclosure(it.source) }
                assertTrue(interior.isNotEmpty())
                for (layer in interior) {
                    assertEquals(1f, plan.layerState(layer.source, false, 0f).opacity, 0f)
                    assertEquals(0.5f, plan.layerState(layer.source, false, 0.0415f).opacity, 0.00001f)
                    assertEquals(0f, plan.layerState(layer.source, false, 0.083f).opacity, 0f)
                }
                if (oldName.endsWith(".fill") != newName.endsWith(".fill")) {
                    for ((symbol, incoming) in listOf(old to false, new to true)) {
                        for (layer in symbol.nodes.filter { it.source.supportsSymbolMode(mode) && enclosure(it.source) }) {
                            assertEquals(if (incoming) 0f else 1f, plan.layerState(layer.source, incoming, 0f).opacity, 0f)
                            assertEquals(0.5f, plan.layerState(layer.source, incoming, 0.0415f).opacity, 0.00001f)
                            assertEquals(if (incoming) 1f else 0f, plan.layerState(layer.source, incoming, 0.083f).opacity, 0f)
                        }
                    }
                }
                assertPixelsEqual("OffUp enclosure initial $oldName -> $newName / $mode", frame(old, mode),
                    replacementFrame(old, new, plan, mode, 0f))
                assertPixelsEqual("OffUp enclosure completion $oldName -> $newName / $mode", frame(new, mode),
                    replacementFrame(old, new, plan, mode, plan.durationSeconds))
            }
        }
    }

    @Test
    fun semanticSlashUsesDrawStateAndPreservesTheBase() = runBlocking {
        val template = store.load("heart.fill")
        val plain = fixture(template, listOf(listOf("base")))
        val slashed = fixture(template, listOf(listOf("base.slash", "base"), listOf("base.slash", "_slash")))
        val plan = SymbolReplacePlan(plain, slashed)
        assertEquals(SymbolReplaceKind.SlashAppear, plan.kind)
        assertEquals(1f, plan.layerState(slashed.nodes[0].source, true, 0f).opacity, 0f)
        assertEquals(0f, plan.layerState(slashed.nodes[1].source, true, 0f).drawProgress!!, 0f)
        assertEquals(1f, plan.layerState(slashed.nodes[1].source, true, 0.333f).drawProgress!!, 0f)
    }

    @Test
    fun originalCompositeAssetsSelectMagicBranchesAndFinishAtTheTargetPixels() = runBlocking {
        val cases = listOf(
            Triple("bell", "bell.slash", SymbolReplaceKind.SlashAppear),
            Triple("bell.slash", "bell", SymbolReplaceKind.SlashDisappear),
            Triple("folder", "folder.badge.plus", SymbolReplaceKind.BadgeAppear),
            Triple("folder.badge.plus", "folder", SymbolReplaceKind.BadgeDisappear),
            Triple("folder.badge.plus", "folder.badge.minus", SymbolReplaceKind.BadgeReplace),
        )
        for ((oldName, newName, expectedKind) in cases) {
            val old = store.load(oldName)
            val new = store.load(newName)
            for (mode in SymbolRenderingMode.entries) {
                val plan = SymbolReplacePlan(old, new, mode = mode)
                assertEquals("$oldName -> $newName / $mode", expectedKind, plan.kind)
                val first = replacementFrame(old, new, plan, mode, 0f)
                val intermediate = replacementFrame(old, new, plan, mode, 0.125f)
                val last = replacementFrame(old, new, plan, mode, plan.durationSeconds)
                assertTrue("Empty initial frame: $oldName -> $newName / $mode", first.any { Color.alpha(it) > 0 })
                assertTrue("No animated pixels: $oldName -> $newName / $mode", !intermediate.contentEquals(last))
                assertPixelsEqual("$oldName -> $newName / $mode", frame(new, mode), last)
            }
        }
    }

    @Test
    fun originalShapeGroupsReceiveReplacementStateAsOneResolvedPath() = runBlocking {
        val old = store.load("bell.slash.circle.fill")
        val new = store.load("heart.fill")
        for (mode in SymbolRenderingMode.entries) {
            val shape = old.nodes.filterIsInstance<SymbolNode.Group>()
                .first { it.source.supportsSymbolMode(mode) && it.source.optString("type") == "shapeGroup" }
            val plan = SymbolReplacePlan(old, new, preferMagic = false, mode = mode)
            assertEquals(0f, plan.layerState(shape.source, false, plan.durationSeconds).opacity, 0f)
            assertPixelsEqual("Initial shape group / $mode", frame(old, mode), replacementFrame(old, new, plan, mode, 0f))
            assertPixelsEqual("Completed shape group / $mode", frame(new, mode),
                replacementFrame(old, new, plan, mode, plan.durationSeconds))
        }
    }

    @Test
    fun originalEnclosuresMatchAcrossFillAndStrokeAndInterpolateTheirOwnDescriptors() = runBlocking {
        val pairs = listOf("heart.circle" to "star.circle", "heart.circle" to "star.circle.fill",
            "heart.circle.fill" to "star.circle")
        for ((firstName, secondName) in pairs) {
            val old = store.load(firstName)
            val new = store.load(secondName)
            for (mode in SymbolRenderingMode.entries) {
                val plan = SymbolReplacePlan(old, new, mode = mode)
                assertEquals("$firstName -> $secondName / $mode", SymbolReplaceKind.EnclosureMatch, plan.kind)
                val oldSource = old.nodes.first { it.source.supportsSymbolMode(mode) }.source
                val newSource = new.nodes.first { it.source.supportsSymbolMode(mode) }.source
                val middle = plan.layerState(oldSource, false, 0.175f)
                assertTrue(middle.drawModeFrom === oldSource)
                assertTrue(middle.drawModeTo === newSource)
                assertTrue(middle.drawModeProgress > 0f && middle.drawModeProgress < 1f)
                assertEquals(1f, middle.scaleX, 0f)
                assertEquals(0f, middle.translateX, 0f)
                val incoming = plan.layerState(newSource, true, 0.175f)
                assertEquals(middle.drawModeProgress, incoming.drawModeProgress, 0f)
                val intermediate = replacementFrame(old, new, plan, mode, 0.175f)
                assertTrue(intermediate.any { Color.alpha(it) > 0 })
                assertTrue(!intermediate.contentEquals(frame(new, mode)))
                assertPixelsEqual("Enclosure completion $firstName -> $secondName / $mode",
                    frame(new, mode), replacementFrame(old, new, plan, mode, plan.durationSeconds))
            }
        }
        val old = store.load("heart.circle")
        val new = store.load("star.circle.fill")
        val plan = SymbolReplacePlan(old, new)
        assertEquals(0f, plan.layerState(new.nodes.first().source, true, 0.267f).opacity, 0.00001f)
        assertEquals(1f, plan.layerState(new.nodes.first().source, true, 0.35f).opacity, 0f)
        assertEquals(1f, plan.layerState(old.nodes.first().source, false, 0.35f).opacity, 0f)
        assertEquals(0f, plan.layerState(old.nodes.first().source, false, 0.433f).opacity, 0.00001f)
    }

    @Test
    fun realBadgeWithDifferentBoundsKeepsItsExactCurveCenterDuringTheMiddleFrame() = runBlocking {
        val old = store.load("externaldrive")
        val new = store.load("externaldrive.badge.timemachine")
        val plan = SymbolReplacePlan(old, new)
        assertEquals(SymbolReplaceKind.BadgeAppear, plan.kind)
        val visibleBadge = taggedLayers(new, "_badge").first { it.source.optString("monochrome") == "draw" }
        val expectedPivot = oracle(new.name).getJSONArray("visibleBadgePivot")
        val expectedX = expectedPivot.getDouble(0).toFloat()
        val expectedY = expectedPivot.getDouble(1).toFloat()
        val state = plan.layerState(visibleBadge.source, true, 0.125f)
        assertEquals(expectedX, state.pivotX!!, 0.0001f)
        assertEquals(expectedY, state.pivotY!!, 0.0001f)
        assertTrue(state.scaleX > 0.75f && state.scaleX < 1f)
        val controlBounds = RectF().also { bounds ->
            Path().apply { visibleBadge.components.forEach { addPath(new.contours[it]) } }.computeBounds(bounds, true)
        }
        assertTrue(kotlin.math.abs(controlBounds.centerX() - state.pivotX) > 9f)
        val nativeMatrix = android.graphics.Matrix().apply { setScale(state.scaleX, state.scaleY, expectedX, expectedY) }
        val expectedPath = Path().apply {
            visibleBadge.components.forEach { addPath(new.contours[it]) }
            transform(nativeMatrix)
        }
        fun badgePixels(useNativePath: Boolean): IntArray {
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            AndroidSymbolPainter(new).draw(Canvas(bitmap), 512f, 512f, SymbolRenderingMode.Monochrome,
                Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0.125f,
                replacementState = { source ->
                    if (source !== visibleBadge.source) SymbolReplaceLayerState(opacity = 0f)
                    else if (useNativePath) SymbolReplaceLayerState(opacity = state.opacity, pathOverride = expectedPath)
                    else state
                })
            return pixels(bitmap)
        }
        assertPixelsEqual("Native badge pivot at the middle frame", badgePixels(true), badgePixels(false))
        assertTrue(badgePixels(false).any { Color.alpha(it) > 0 })
    }

    @Test
    fun changingOriginalBadgeKeepsEachSidesOwnSmallestLayerCenter() = runBlocking {
        val old = store.load("folder.badge.plus")
        val new = store.load("folder.badge.questionmark")
        val plan = SymbolReplacePlan(old, new)
        assertEquals(SymbolReplaceKind.BadgeReplace, plan.kind)
        val oldLayers = taggedLayers(old, "_badge")
        val newLayers = taggedLayers(new, "_badge")
        val oldPivot = oracle(old.name).getJSONArray("smallestBadgePivot")
        val newPivot = oracle(new.name).getJSONArray("smallestBadgePivot")
        for (node in oldLayers) {
            val state = plan.layerState(node.source, false, 0.125f)
            assertEquals(oldPivot.getDouble(0).toFloat(), state.pivotX!!, 0f)
            assertEquals(oldPivot.getDouble(1).toFloat(), state.pivotY!!, 0f)
            assertTrue(state.scaleX > 0.5f && state.scaleX < 1f)
        }
        for (node in newLayers) {
            val state = plan.layerState(node.source, true, 0.375f)
            assertEquals(newPivot.getDouble(0).toFloat(), state.pivotX!!, 0f)
            assertEquals(newPivot.getDouble(1).toFloat(), state.pivotY!!, 0f)
            assertTrue(state.scaleX > 0.5f && state.scaleX < 1f)
        }
        for (mode in SymbolRenderingMode.entries) {
            val modePlan = SymbolReplacePlan(old, new, mode = mode)
            val middle = replacementFrame(old, new, modePlan, mode, 0.375f)
            assertTrue(middle.any { Color.alpha(it) > 0 })
            assertTrue(!middle.contentEquals(frame(new, mode)))
        }
    }

    @Test
    fun originalSlashBuildsRoundCapsAndOvershootsTheLineBeforeSettling() = runBlocking {
        val old = store.load("bell")
        val new = store.load("bell.slash")
        val plan = SymbolReplacePlan(old, new)
        val slash = taggedLayers(new, "_slash").last { it.source.optString("monochrome") == "draw" }.source
        assertTrue(plan.layerState(slash, true, 0f).pathOverride!!.isEmpty)
        fun capBounds(time: Float): RectF {
            val points = plan.layerState(slash, true, time).pathOverride!!.approximate(0.001f)
            val indices = points.indices.step(3)
            return RectF(indices.minOf { points[it + 1] }, indices.minOf { points[it + 2] },
                indices.maxOf { points[it + 1] }, indices.maxOf { points[it + 2] })
        }
        val peak = capBounds(0.3333f)
        val settled = capBounds(0.6666f)
        val expectedBounds = oracle(new.name).getJSONArray("slashCapsBounds")
        assertEquals(expectedBounds.getDouble(0).toFloat(), settled.left, 0.05f)
        assertEquals(expectedBounds.getDouble(3).toFloat(), settled.bottom, 0.05f)
        assertEquals(expectedBounds.getDouble(2).toFloat(), settled.right, 0.05f)
        assertEquals(expectedBounds.getDouble(1).toFloat(), settled.top, 0.05f)
        assertTrue(peak.right > settled.right)
        assertTrue(peak.top < settled.top)
    }

    private fun frame(symbol: SymbolInstance, mode: SymbolRenderingMode): IntArray {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        AndroidSymbolPainter(symbol).draw(Canvas(bitmap), 160f, 160f, mode,
            Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, 0f)
        return pixels(bitmap)
    }

    private fun replacementFrame(
        old: SymbolInstance,
        new: SymbolInstance,
        plan: SymbolReplacePlan,
        mode: SymbolRenderingMode,
        time: Float,
    ): IntArray {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for ((symbol, incoming) in listOf(old to false, new to true)) {
            AndroidSymbolPainter(symbol).draw(canvas, 160f, 160f, mode,
                Color.BLUE, Color.YELLOW, Color.RED, false, SymbolEffect.None, time,
                replacementState = { plan.layerState(it, incoming, time) })
        }
        return pixels(bitmap)
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun assertPixelsEqual(message: String, expected: IntArray, actual: IntArray) {
        assertTrue("$message: ${expected.indices.count { expected[it] != actual[it] }} pixels differ",
            expected.contentEquals(actual))
    }

    private fun taggedLayers(symbol: SymbolInstance, tag: String): List<SymbolNode.Layer> {
        fun visit(nodes: List<SymbolNode>): List<SymbolNode.Layer> = nodes.flatMap { node ->
            if (!node.source.supportsSymbolMode(SymbolRenderingMode.Monochrome)) emptyList()
            else when (node) {
                is SymbolNode.Layer -> listOf(node)
                is SymbolNode.Group -> visit(node.children)
            }
        }
        return visit(symbol.nodes).filter { node ->
            node.source.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).any { tags.getString(it) == tag }
            } == true
        }
    }

    private fun oracle(name: String): JSONObject = javaClass
        .getResourceAsStream("/symbols/system-replacement-oracles.json")!!
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        .getJSONObject("symbols").getJSONObject(name)

    private fun fixture(template: SymbolInstance, tags: List<List<String>>): SymbolInstance {
        val path = Path().apply { addRect(0f, 0f, 100f, 100f, Path.Direction.CW) }
        return SymbolInstance(
            name = "fixture", weight = template.weight, scale = template.scale,
            weightPosition = template.weightPosition, advance = 200f, capHeight = 100f,
            unitsPerEm = template.unitsPerEm, contours = listOf(path),
            contourData = template.contourData, sourceMasters = template.sourceMasters,
            sourceSmallMasters = template.sourceSmallMasters,
            drawWeightCoefficients = template.drawWeightCoefficients,
            ultralightWeightPosition = template.ultralightWeightPosition,
            bounds = RectF(0f, 0f, 100f, 100f),
            nodes = tags.map { SymbolNode.Layer(intArrayOf(0), JSONObject().put("tags", JSONArray(it)).put("monochrome", "draw")) },
            metadata = JSONObject(),
        )
    }
}
