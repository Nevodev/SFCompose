package com.nevoit.sfcompose.symbols

import org.json.JSONObject

internal fun JSONObject.systemStyle(mode: SymbolRenderingMode): JSONObject? =
    optJSONObject("sourceModeStyles")?.optJSONObject(when (mode) {
        SymbolRenderingMode.Monochrome -> "monochrome"
        SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette -> "hierarchical"
        SymbolRenderingMode.Multicolor -> "multicolor"
    })

internal fun JSONObject.symbolBoolean(mode: SymbolRenderingMode, property: String, legacyProperty: String): Boolean {
    val style = systemStyle(mode)
    return if (style != null) style.optString(property) == "true" else optBoolean(legacyProperty)
}

internal fun JSONObject.symbolErases(mode: SymbolRenderingMode): Boolean {
    systemStyle(mode)?.let { style ->
        return style.optString("-sfsymbols-clear-behind") == "true" && style.optDouble("opacity", 1.0) == 0.0
    }
    return when (mode) {
        SymbolRenderingMode.Monochrome -> optString("monochrome") == "erase"
        SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette -> optInt("hierarchical") == -1
        SymbolRenderingMode.Multicolor -> optJSONObject("multicolor")?.optString("color") in setOf("erase", "knockout")
    }
}

internal fun JSONObject.symbolOpacity(mode: SymbolRenderingMode): Double {
    systemStyle(mode)?.let { style ->
        // A transparent clear-behind layer cuts out its whole path; opacity zero is its material, not coverage.
        return if (symbolErases(mode)) 100.0 else style.optDouble("opacity", 1.0) * 100.0
    }
    return if (mode == SymbolRenderingMode.Multicolor) {
        optJSONObject("multicolor")?.optDouble("opacity", optDouble("opacity", 100.0)) ?: optDouble("opacity", 100.0)
    } else optDouble("opacity", 100.0)
}

internal fun JSONObject.symbolMaterialOpacity(mode: SymbolRenderingMode): Float =
    systemStyle(mode)?.optDouble("opacity", 1.0)?.toFloat()
        ?: if (symbolErases(mode)) 0f else (symbolOpacity(mode) / 100.0).toFloat()

internal fun JSONObject.symbolMaterialMatches(other: JSONObject, mode: SymbolRenderingMode): Boolean {
    val style = systemStyle(mode) ?: return false
    val otherStyle = other.systemStyle(mode) ?: return false
    if (symbolBoolean(mode, "-sfsymbols-clear-behind", "clearBehind") !=
        other.symbolBoolean(mode, "-sfsymbols-clear-behind", "clearBehind")) return false
    // Current system enclosures only use solid materials. Image/custom fill identity cannot
    // be inferred from a selector or a shared material slot.
    val customKeys = listOf("fillImage", "fill-image", "stroke", "strokeColor", "strokeColorName", "gradient")
    if (customKeys.any { style.has(it) || otherStyle.has(it) } || has("gradient") || other.has("gradient")) return false
    fun materialIndex(source: JSONObject): Int = when {
        source.symbolMaterialOpacity(mode) <= 0f -> 10
        mode == SymbolRenderingMode.Palette -> source.optInt("hierarchical", 0).let { if (it > 0) 4 + minOf(it, 4) else 0 }
        mode == SymbolRenderingMode.Multicolor &&
            (source.optJSONObject("multicolor")?.optString("color", "tintColor") ?: "tintColor") != "tintColor" -> 9
        else -> 0
    }
    val index = materialIndex(this)
    if (index != materialIndex(other)) return false
    return index != 9 || optJSONObject("multicolor")?.optString("color", "tintColor") ==
        other.optJSONObject("multicolor")?.optString("color", "tintColor")
}
