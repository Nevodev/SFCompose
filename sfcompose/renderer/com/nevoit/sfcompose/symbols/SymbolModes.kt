package com.nevoit.sfcompose.symbols

import org.json.JSONObject

internal fun JSONObject.supportsSymbolMode(mode: SymbolRenderingMode): Boolean {
    val systemMode = optString("systemRenderingMode")
    return systemMode.isEmpty() || systemMode == when (mode) {
        SymbolRenderingMode.Monochrome -> "monochrome"
        SymbolRenderingMode.Hierarchical, SymbolRenderingMode.Palette -> "hierarchical"
        SymbolRenderingMode.Multicolor -> "multicolor"
    }
}
