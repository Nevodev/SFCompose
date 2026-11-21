package com.nevoit.sfcompose.symbols

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Controls the layout slot and horizontal fitting independently of color and animation settings.
 *
 * Components default to [SquareOverflow]. `pointSize` requests the slot height; `padding` insets
 * the drawing area within it. For example, 32 dp with 4 dp padding leaves a 24 dp drawing height.
 * Parent constraints still apply. None of these modes adds clipping; an ancestor may clip overflow.
 * Replacements use shared extents so both symbols keep the same scale during their transition.
 */
enum class SymbolDisplayMode {
    /** A square slot; keeps the nominal line height and allows drawing outside the slot. */
    SquareOverflow,
    /** A fixed line height with width taken from the glyph advance. */
    IntrinsicWidth,
    /** A square slot; reduces the nominal line height only when the horizontal ink would overflow. */
    SquareFitWidth,
}

internal val DefaultSymbolDisplayMode = SymbolDisplayMode.SquareOverflow

internal fun Modifier.symbolSlot(pointSize: Dp, ratio: Float, padding: Dp): Modifier {
    val slot = height(pointSize).aspectRatio(ratio, matchHeightConstraintsFirst = true)
    return if (padding == 0.dp) slot else slot.padding(padding)
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
