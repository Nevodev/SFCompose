package com.nevoit.sfcompose.symbols

import org.json.JSONArray
import org.json.JSONObject

internal fun continuousDrawWeightCoefficients(catalog: SymbolCatalog, scale: SymbolScale, position: Float): DoubleArray {
    val weights = SymbolWeight.entries.sortedBy { catalog.weightPositions.getValue(it) }
    val upper = weights.indexOfFirst { catalog.weightPositions.getValue(it) >= position }.coerceAtLeast(0)
    val second = weights[upper]
    val first = weights[(upper - 1).coerceAtLeast(0)]
    val start = catalog.weightPositions.getValue(first)
    val end = catalog.weightPositions.getValue(second)
    val amount = if (end == start) 0.0 else ((position - start) / (end - start)).toDouble()
    val table = catalog.drawWeightCoefficients.getJSONArray(scale.assetName)
    val left = table.getJSONArray(first.ordinal)
    val right = table.getJSONArray(second.ordinal)
    return DoubleArray(2) { left.getDouble(it) + (right.getDouble(it) - left.getDouble(it)) * amount }
}

internal fun interpolateSystemMaster(left: JSONObject, right: JSONObject, amount: Float): JSONObject {
    fun interpolate(first: Any, second: Any, key: String): Any = when {
        first is JSONObject && second is JSONObject -> JSONObject().apply {
            val keys = first.keys().asSequence().toSet()
            require(keys == second.keys().asSequence().toSet()) { "Incompatible system master fields: $key" }
            for (name in keys) put(name, interpolate(first.get(name), second.get(name), name))
        }
        first is JSONArray && second is JSONArray -> JSONArray().apply {
            require(first.length() == second.length()) { "Incompatible system master arrays: $key" }
            repeat(first.length()) { put(interpolate(first.get(it), second.get(it), key)) }
        }
        first is Number && second is Number -> {
            if (key == "flags") {
                require(first.toInt() == second.toInt()) { "Incompatible system Draw flags" }
                first
            } else first.toDouble() + (second.toDouble() - first.toDouble()) * amount
        }
        else -> {
            require(first == second) { "Incompatible system master value: $key" }
            first
        }
    }
    return interpolate(left, right, "master") as JSONObject
}
