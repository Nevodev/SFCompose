package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import java.util.IdentityHashMap

internal class SymbolVariableDraw(
    nodes: List<SymbolNode>,
    backToFront: Boolean,
    mode: SymbolRenderingMode,
    hasGeometry: (JSONObject) -> Boolean = { true },
) {
    private val ordinals = IdentityHashMap<JSONObject, Int>()
    private val systemRanges = IdentityHashMap<JSONObject, Pair<Float, Float>>()
    private val count: Int
    val isAvailable: Boolean get() = systemRanges.isNotEmpty() || ordinals.isNotEmpty()

    init {
        val visible = symbolMotionGroups(nodes, mode).indices
        val eligible = mutableListOf<JSONObject>()
        fun visit(node: SymbolNode) {
            val range = node.source.optJSONObject("variableDraw")
            val style = node.source.systemStyle(mode)
            val compiledRange = style?.optString("-sfsymbols-variable-draw")?.trim()?.split(Regex("\\s+"))
                ?.mapNotNull(String::toFloatOrNull)?.takeIf { it.size == 2 }
            if (node.source in visible && (range != null || compiledRange != null) && hasGeometry(node.source)) {
                val offset = compiledRange?.get(0) ?: range!!.optDouble("offset", Double.NaN).toFloat()
                val length = compiledRange?.get(1) ?: range!!.optDouble("length", Double.NaN).toFloat()
                if (offset.isFinite() && length.isFinite() && length > 0f) systemRanges[node.source] = offset to length
            }
            if (node.source in visible && node.source.optBoolean("enableVariableDraw") && hasGeometry(node.source) &&
                (node.source.optJSONObject("drawRailRoadTies")?.optJSONObject("1")?.optJSONArray("ties")?.length() ?: 0) > 1
            ) eligible += node.source
            if (node is SymbolNode.Group && node.source.optString("type") != "shapeGroup") node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
        val ordered = if (backToFront) eligible else eligible.asReversed()
        ordered.forEachIndexed { index, source -> ordinals[source] = index }
        count = ordered.size
    }

    fun progress(source: JSONObject, value: Float): Float? {
        if (!value.isFinite()) return null
        systemRanges[source]?.let { (offset, length) ->
            return ((value.coerceIn(0f, 1f) - offset) / length).coerceIn(0f, 1f)
        }
        return ordinals[source]?.let { (value.coerceIn(0f, 1f) * count - it).coerceIn(0f, 1f) }
    }
}
