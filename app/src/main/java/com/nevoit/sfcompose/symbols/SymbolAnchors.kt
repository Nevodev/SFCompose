package com.nevoit.sfcompose.symbols

internal fun SymbolInstance.rotationAnchor(): Pair<Float, Float> {
    rotationAnchorPoint?.let { return it }
    if (metadata.optString("sourceKind").startsWith("macOS-system")) return metricCenterX to metricCenterY
    val points = metadata.optJSONArray("rotationAnchorPoints")
        ?: return metricCenterX to metricCenterY
    require(points.length() >= 3) { "Rotation anchors require the original three weight masters" }
    fun coordinate(axis: Int): Double {
        val direct = when (weight) {
            SymbolWeight.Ultralight -> 0
            SymbolWeight.Regular -> 1
            SymbolWeight.Black -> 2
            else -> -1
        }
        if (direct >= 0) return points.getJSONArray(direct).getDouble(axis)
        val regular = points.getJSONArray(1).getDouble(axis)
        return regular + drawWeightCoefficients[0] * (points.getJSONArray(0).getDouble(axis) - regular) +
            drawWeightCoefficients[1] * (points.getJSONArray(2).getDouble(axis) - regular)
    }
    return (coordinate(0) * advance).toFloat() to ((1.0 - coordinate(1)) * capHeight).toFloat()
}
