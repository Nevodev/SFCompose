package com.nevoit.sfcompose.symbols

import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject
import java.util.IdentityHashMap

internal class PreparedSymbol(private val symbol: SymbolInstance) {
    internal class Node(val source: JSONObject, val path: Path, val children: List<Node>?) {
        val bounds by lazy { symbolPathBounds(path) }
        val gradient = source.optJSONObject("gradient")
        val drawAnnotated = source.has("systemClipStrokeKeyframes") || source.has("systemClipStrokeKeyframeSets") ||
            source.has("drawRailRoadTies")
        private val styles = SymbolRenderingMode.entries.map { mode -> lazy { Style(source, mode) } }
        fun style(mode: SymbolRenderingMode): Style = styles[mode.ordinal].value
    }

    internal class Style(source: JSONObject, mode: SymbolRenderingMode) {
        val colorName = source.optJSONObject("multicolor")?.optString("color", "tintColor") ?: "tintColor"
        val hierarchy = source.optInt("hierarchical", 0)
        val erases = source.symbolErases(mode)
        val clearBehind = source.symbolBoolean(mode, "-sfsymbols-clear-behind", "clearBehind")
        val native = source.systemStyle(mode) != null
        val opacity = source.symbolOpacity(mode)
        val materialOpacity = if (native) source.symbolMaterialOpacity(mode) else (opacity / 100.0).toFloat()
        val pulses = source.symbolBoolean(mode, "-sfsymbols-always-pulses", "canPulse")
        val rotates = source.symbolBoolean(mode, "-sfsymbols-always-rotates", "canRotate")
        val visible = source.supportsSymbolMode(mode) && when (mode) {
            SymbolRenderingMode.Monochrome -> source.optString("monochrome", "draw") != "hidden"
            SymbolRenderingMode.Multicolor -> colorName != "hidden"
            else -> true
        }
        val drawable = source.has(when (mode) {
            SymbolRenderingMode.Monochrome -> "monochrome"
            SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette -> "hierarchical"
            SymbolRenderingMode.Multicolor -> "multicolor"
        })
    }

    val roots = compile(symbol.nodes)
    private fun leaves(nodes: List<Node>): List<Node> = nodes.flatMap { node ->
        node.children?.let(::leaves) ?: listOf(node)
    }

    private val leaves = leaves(roots)
    val minimumHierarchy by lazy {
        leaves.mapNotNull { it.source.optInt("hierarchical", -1).takeIf { level -> level >= 0 } }.minOrNull() ?: 0
    }

    internal inner class Mode(val mode: SymbolRenderingMode) {
        // Respect mode and visibility on ancestors as well as flattened shape layers.
        val layers = visibleLeaves(roots, mode)
        val requiresIsolation = layers.any { it.style(mode).let { style -> style.erases || style.clearBehind } }
        val inkBounds by lazy { RectF().apply { layers.forEach { union(it.bounds) } } }
        val motion by lazy { symbolMotionGroups(symbol.nodes, mode) }
        val hasPulseLayers by lazy {
            motion.indices.keys.any { it.symbolBoolean(mode, "-sfsymbols-always-pulses", "canPulse") }
        }
        val systemLayers by lazy {
            buildMap<Int, JSONObject> {
                fun visit(nodes: List<SymbolNode>) {
                    for (node in nodes) {
                        if (!node.source.supportsSymbolMode(mode)) continue
                        when (node) {
                            is SymbolNode.Group -> visit(node.children)
                            is SymbolNode.Layer -> if (node.source.has("sourceLayerIndex")) {
                                put(node.source.getInt("sourceLayerIndex"), node.source)
                            }
                        }
                    }
                }
                visit(symbol.nodes)
            }
        }
        val variableDraw by lazy {
            SymbolVariableDraw(symbol.nodes, symbol.metadata.optString("variableColorOrder") == "backToFront",
                mode, symbol.draw::hasGeometry)
        }
        val variableThresholds by lazy {
            val eligible = layers.filter { node ->
                node.source.systemStyle(mode)?.has("-sfsymbols-variable-threshold") ?: node.source.optBoolean("autovariable")
            }
            IdentityHashMap<JSONObject, Float>().apply {
                eligible.forEachIndexed { index, node ->
                    val threshold = node.source.systemStyle(mode)?.optDouble("-sfsymbols-variable-threshold", Double.NaN)
                        ?: node.source.optDouble("variableThreshold", Double.NaN)
                    put(node.source, if (threshold.isFinite()) threshold.toFloat() else
                        SymbolTiming.variableThreshold(index, eligible.size,
                            reversed = symbol.metadata.optString("variableColorOrder") == "frontToBack"))
                }
            }
        }
    }

    private val modes = SymbolRenderingMode.entries.map { mode -> lazy { Mode(mode) } }
    private val drawableModes = SymbolRenderingMode.entries.map { mode -> lazy { leaves.any { it.style(mode).drawable } } }
    fun mode(mode: SymbolRenderingMode): Mode = modes[mode.ordinal].value
    fun resolveMode(mode: SymbolRenderingMode): SymbolRenderingMode =
        if (drawableModes[mode.ordinal].value) mode else SymbolRenderingMode.Monochrome

    private fun visibleLeaves(nodes: List<Node>, mode: SymbolRenderingMode): List<Node> = buildList {
        for (node in nodes) {
            val style = node.style(mode)
            if (!style.visible) continue
            if (node.children != null) addAll(visibleLeaves(node.children, mode))
            else if (style.drawable) add(node)
        }
    }

    private fun compile(nodes: List<SymbolNode>): List<Node> = nodes.map { node ->
        when (node) {
            is SymbolNode.Layer -> Node(node.source, Path().apply {
                for (component in node.components) symbol.contours.getOrNull(component)?.let(::addPath)
            }, null)
            is SymbolNode.Group -> {
                val children = compile(node.children)
                if (node.source.optString("type") == "shapeGroup") {
                    val shape = Path()
                    for (child in children) {
                        val operation = if (child.source.optString("shapeGroupBehavior", "draw") == "erase")
                            Path.Op.DIFFERENCE else Path.Op.UNION
                        check(shape.op(child.path, operation)) { "Unable to resolve original shape group" }
                    }
                    Node(node.source, shape, null)
                } else Node(node.source, Path().apply { children.forEach { addPath(it.path) } }, children)
            }
        }
    }
}
