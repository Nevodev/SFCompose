package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import java.util.IdentityHashMap

internal class SymbolMotionGroups(
    val indices: IdentityHashMap<JSONObject, Int>,
    val maxGroupIndex: Int,
)

internal fun symbolMotionGroups(
    nodes: List<SymbolNode>,
    mode: SymbolRenderingMode,
): SymbolMotionGroups {
    val flattened = mutableListOf<Pair<JSONObject, Int>>()
    var counter = 0
    var togetherDepth = 0
    fun visit(node: SymbolNode) {
        if (!node.source.supportsSymbolMode(mode)) return
        if (togetherDepth == 0) counter++
        when (node) {
            is SymbolNode.Layer -> flattened += node.source to counter
            is SymbolNode.Group -> {
                if (node.source.optString("type") == "shapeGroup") {
                    flattened += node.source to counter
                    return
                }
                val together = node.source.optBoolean("animateTogether")
                if (together) {
                    if (togetherDepth == 0) counter++
                    togetherDepth++
                }
                node.children.forEach(::visit)
                if (together) togetherDepth--
            }
        }
    }
    nodes.forEach(::visit)
    val indices = IdentityHashMap<JSONObject, Int>()
    var previous = -1
    var compact = -1
    // Native deliverables compact eligible motion groups while traversing front to back.
    for ((source, preliminary) in flattened.asReversed()) {
        val eligible = when (mode) {
            SymbolRenderingMode.Monochrome -> source.has("monochrome") && !source.isNull("monochrome")
            SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette ->
                source.has("hierarchical") && !source.isNull("hierarchical")
            SymbolRenderingMode.Multicolor -> source.optJSONObject("multicolor")?.let {
                it.has("color") && !it.isNull("color") && it.optString("color") != "hidden"
            } == true
        }
        if (!eligible) continue
        if (source.has("sourceModeStyles")) {
            val styleMode = when (mode) {
                SymbolRenderingMode.Monochrome -> "monochrome"
                SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette -> "hierarchical"
                SymbolRenderingMode.Multicolor -> "multicolor"
            }
            val explicit = source.optJSONObject("sourceModeStyles")?.optJSONObject(styleMode)
                ?.optString("-sfsymbols-motion-group")?.toIntOrNull() ?: source.optInt("motionGroup", -1)
            if (explicit >= 0) {
                indices[source] = explicit
                continue
            }
        }
        if (preliminary != previous) compact++
        indices[source] = compact
        previous = preliminary
    }
    return SymbolMotionGroups(indices, indices.values.maxOrNull() ?: 0)
}
