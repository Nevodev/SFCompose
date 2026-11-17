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
