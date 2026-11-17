package com.nevoit.sfcompose.symbols

/** Controls layout independently of the symbol's rendering and animation modes. */
enum class SymbolDisplayMode {
    /** A square slot; keeps the nominal line height and allows drawing outside the slot. */
    SquareOverflow,
    /** A fixed line height with width taken from the glyph advance. */
    IntrinsicWidth,
    /** A square slot; reduces the nominal line height only when the horizontal ink would overflow. */
    SquareFitWidth,
}

internal fun SymbolInstance.horizontalInkRatio(): Float {
    // Include asymmetric bearings: the painter is centered on the native metric center, not the ink center.
    return 2f * maxOf(kotlin.math.abs(bounds.left - metricCenterX), kotlin.math.abs(bounds.right - metricCenterX)) / unitsPerEm
}

internal fun SymbolDisplayMode.layoutRatio(intrinsicRatio: Float): Float =
    if (this == SymbolDisplayMode.IntrinsicWidth) intrinsicRatio else 1f

internal fun SymbolDisplayMode.renderHeight(width: Float, lineHeight: Float, horizontalInkRatio: Float): Float =
    if (this == SymbolDisplayMode.SquareFitWidth && horizontalInkRatio > 0f)
        minOf(lineHeight, width / horizontalInkRatio) else lineHeight
