package com.nevoit.sfcompose.symbols

import android.os.Build
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SymbolMotionGroupsTest {
    @Test
    fun ordinaryLayersAreNumberedFromTheFrontmostLayer() {
        val dot = layer()
        val inner = layer()
        val outer = layer()
        val groups = symbolMotionGroups(listOf(dot, inner, outer), SymbolRenderingMode.Monochrome)
        assertEquals(2, groups.indices[dot.source])
        assertEquals(1, groups.indices[inner.source])
        assertEquals(0, groups.indices[outer.source])
        assertEquals(2, groups.maxGroupIndex)
    }

    @Test
    fun nestedTogetherGroupsRemainOneMotionGroupAcrossErases() {
        val body = layer()
        val badge = layer()
        val erase = layer().also { it.source.put("monochrome", "erase") }
        val nested = SymbolNode.Group(listOf(erase), JSONObject().put("animateTogether", true))
        val group = SymbolNode.Group(listOf(badge, nested), JSONObject().put("animateTogether", true))
        val groups = symbolMotionGroups(listOf(body, group), SymbolRenderingMode.Monochrome)
        assertEquals(1, groups.indices[body.source])
        assertEquals(0, groups.indices[badge.source])
        assertEquals(0, groups.indices[erase.source])
        assertEquals(1, groups.maxGroupIndex)
    }

    @Test
    fun missingModesAndHiddenColorsDoNotCreateGaps() {
        val first = layer()
        val absent = SymbolNode.Layer(intArrayOf(), JSONObject())
        val hidden = layer().also { it.source.put("multicolor", JSONObject().put("color", "hidden")) }
        val erase = layer().also { it.source.put("hierarchical", -1) }
        val nodes = listOf(first, absent, hidden, erase)
        val multicolor = symbolMotionGroups(nodes, SymbolRenderingMode.Multicolor)
        assertEquals(1, multicolor.indices[first.source])
        assertEquals(0, multicolor.indices[erase.source])
        assertFalse(multicolor.indices.containsKey(absent.source))
        assertFalse(multicolor.indices.containsKey(hidden.source))
        assertEquals(1, multicolor.maxGroupIndex)
        val hierarchy = symbolMotionGroups(nodes, SymbolRenderingMode.Hierarchical)
        assertEquals(0, hierarchy.indices[erase.source])
        assertEquals(1, hierarchy.indices[hidden.source])
        assertEquals(2, hierarchy.indices[first.source])
    }

    @Test
    fun booleanShapeGroupsAreSingleResolvedMotionLayers() {
        val child = layer()
        val shape = SymbolNode.Group(listOf(child), layer().source.put("type", "shapeGroup"))
        val other = layer()
        val groups = symbolMotionGroups(listOf(shape, other), SymbolRenderingMode.Monochrome)
        assertEquals(1, groups.indices[shape.source])
        assertEquals(0, groups.indices[other.source])
        assertFalse(groups.indices.containsKey(child.source))
    }

    private fun layer() = SymbolNode.Layer(intArrayOf(), JSONObject()
        .put("monochrome", "draw")
        .put("hierarchical", 0)
        .put("multicolor", JSONObject().put("color", "tintColor")))
}
