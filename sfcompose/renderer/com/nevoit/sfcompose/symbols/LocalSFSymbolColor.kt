package com.nevoit.sfcompose.symbols

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Default primary tint for SFCompose components in this subtree; defaults to [Color.Black].
 *
 * Provide it with `CompositionLocalProvider(LocalSFSymbolColor provides color)` to color a group
 * of icons. An explicit `tint` on a component takes priority. This local is independent of Material
 * content colors, so provide the desired theme color when placing icons in a Material surface.
 */
val LocalSFSymbolColor = compositionLocalOf { Color.Black }
