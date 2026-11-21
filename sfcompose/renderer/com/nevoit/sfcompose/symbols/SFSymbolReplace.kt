package com.nevoit.sfcompose.symbols

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Draws a replacement between loaded instances at a caller-supplied animation time.
 *
 * Load animation-capable instances with [rememberSFSymbol] using `.Animated` entries. This overload
 * neither loads resources nor advances time. For automatic playback use the catalog-entry overload;
 * for target changes during playback use [SymbolReplaceController].
 *
 * @param first Outgoing loaded symbol.
 * @param second Incoming loaded symbol, shown alone once the replacement finishes.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param direction Replacement recipe controlling outgoing and incoming scale or Draw behavior.
 * @param preferMagic Attempts source semantic matches, falling back to the standard recipe.
 * @param layered Enables staggered timing for source motion groups where supported.
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param palette Layer colors in Palette mode; a nonempty list overrides the three colors.
 * @param gradients Enables source gradient styling where supported.
 * @param rightToLeft Enables source-declared mirroring and logical effect direction; resource variants
 *   must already be selected when loading the instances.
 * @param appearance Semantic color appearance; light/dark colors follow the current system theme.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param displayMode Slot sizing policy shared by the pair; defaults to SquareOverflow without clipping.
 * @param pointSize Requested slot height in dp, before internal [padding]; defaults to 32 dp.
 * @param padding Insets the drawing area on all sides within the requested slot size.
 * @param elapsedSeconds Seconds since this replacement began, read during drawing. Restart at zero
 *   to replay, or hold the value to pause. This is relative time, unlike the controller overload.
 */
@Composable
fun SFSymbolReplace(
    first: SymbolInstance,
    second: SymbolInstance,
    modifier: Modifier = Modifier,
    direction: SymbolReplaceDirection = SymbolReplaceDirection.DownUp,
    preferMagic: Boolean = true,
    layered: Boolean = true,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    tint: Color = LocalSFSymbolColor.current,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
    rightToLeft: Boolean = false,
    appearance: SymbolAppearance = SymbolAppearance.Standard,
    contentDescription: String? = null,
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    pointSize: Dp = 32.dp,
    padding: Dp = 0.dp,
    elapsedSeconds: () -> Float,
) {
    val plans = remember(first, second, direction, layered, preferMagic, mode) {
        LruCache<Float, SymbolReplacePlan>(4)
    }
    val oldPainter = remember(first) { AndroidSymbolPainter(first) }
    val newPainter = remember(second) { AndroidSymbolPainter(second) }
    val dark = isSystemInDarkTheme()
    val tintArgb = tint.toArgb()
    val secondaryArgb = secondary.toArgb()
    val tertiaryArgb = tertiary.toArgb()
    val colors = remember(palette) { palette?.map { it.toArgb() }?.toIntArray() }
    val inkRatio = remember(first, second) { maxOf(first.horizontalInkRatio(), second.horizontalInkRatio()) }
    val semantics = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
    }
    val ratio = displayMode.layoutRatio(maxOf(first.advance / first.unitsPerEm, second.advance / second.unitsPerEm))
    Canvas(modifier.then(semantics).symbolSlot(pointSize, ratio, padding)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        // Share one scale for the pair so replacing a narrow symbol with a wide one does not resize the slot.
        val renderHeight = displayMode.renderHeight(size.width, size.height, inkRatio)
        drawIntoCanvas { canvas ->
            val pointSize = renderHeight / density
            val plan = plans.getOrBuild(pointSize) {
                SymbolReplacePlan(first, second, direction, preferMagic, layered, mode, pointSize = pointSize)
            }
            val time = elapsedSeconds()
            val checkpoint = canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(0f, (size.height - renderHeight) * .5f)
            try {
                if (time < plan.durationSeconds) oldPainter.draw(canvas.nativeCanvas, size.width, renderHeight, mode,
                    tintArgb, secondaryArgb, tertiaryArgb, dark, SymbolEffect.None, time, gradients = gradients,
                    rightToLeft = rightToLeft, appearance = appearance, palette = colors,
                    replacementState = { plan.layerState(it, false, time) }, pointSize = pointSize)
                newPainter.draw(canvas.nativeCanvas, size.width, renderHeight, mode,
                    tintArgb, secondaryArgb, tertiaryArgb, dark, SymbolEffect.None, time, gradients = gradients,
                    rightToLeft = rightToLeft, appearance = appearance, palette = colors, pointSize = pointSize,
                    replacementState = if (time < plan.durationSeconds) ({ plan.layerState(it, true, time) }) else null)
            } finally {
                canvas.nativeCanvas.restoreToCount(checkpoint)
            }
        }
    }
}

/**
 * Remembers an interruptible replacement controller initialized with [initialSymbol].
 *
 * The initial instance is used only when the controller is created. Later parameter changes do
 * not retarget it: call [SymbolReplaceController.replaceWith] to animate or
 * [SymbolReplaceController.reset] to switch immediately.
 */
@Composable
fun rememberSymbolReplaceController(initialSymbol: SymbolInstance): SymbolReplaceController =
    remember { SymbolReplaceController(initialSymbol) }

/**
 * Draws interruptible replacements using the controller's absolute clock in seconds.
 *
 * Use the same monotonic clock for [SymbolReplaceController.replaceWith] and [elapsedSeconds].
 * Unlike the pair overload, do not reset that clock to zero on each target change. The caller owns
 * frame updates; no clock is started here. Read snapshot state inside [elapsedSeconds] to update
 * drawing without recomposition.
 *
 * ```kotlin
 * val controller = rememberSymbolReplaceController(initialSymbol)
 * // In a target-change handler: controller.replaceWith(nextSymbol, clockSeconds)
 * SFSymbolReplace(controller = controller, elapsedSeconds = { clockSeconds })
 * ```
 *
 * @param controller Replacement state updated by `replaceWith` or `reset`.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param palette Layer colors in Palette mode; a nonempty list overrides the three colors.
 * @param gradients Enables source gradient styling where supported.
 * @param rightToLeft Enables source-declared mirroring and logical effect direction; resource variants
 *   must already be selected when loading the instances.
 * @param appearance Semantic color appearance; light/dark colors follow the current system theme.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param displayMode Slot sizing policy for the retained extents; defaults to SquareOverflow.
 * @param pointSize Requested slot height in dp, before internal [padding]; defaults to 32 dp.
 * @param padding Insets the drawing area on all sides within the requested slot size.
 * @param elapsedSeconds Current finite absolute clock value in seconds, shared with target changes.
 */
@Composable
fun SFSymbolReplace(
    controller: SymbolReplaceController,
    modifier: Modifier = Modifier,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    tint: Color = LocalSFSymbolColor.current,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
    rightToLeft: Boolean = false,
    appearance: SymbolAppearance = SymbolAppearance.Standard,
    contentDescription: String? = null,
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    pointSize: Dp = 32.dp,
    padding: Dp = 0.dp,
    elapsedSeconds: () -> Float,
) {
    // Reading the revision makes target changes update layout even with a paused external clock.
    val layout = remember(controller, controller.revision) { controller.layoutAdvanceRatio to controller.layoutInkRatio }
    val painters = remember(controller) { java.util.IdentityHashMap<SymbolInstance, AndroidSymbolPainter>() }
    val dark = isSystemInDarkTheme()
    val tintArgb = tint.toArgb()
    val secondaryArgb = secondary.toArgb()
    val tertiaryArgb = tertiary.toArgb()
    val colors = remember(palette) { palette?.map { it.toArgb() }?.toIntArray() }
    val semantics = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(modifier.then(semantics).symbolSlot(pointSize, displayMode.layoutRatio(layout.first), padding)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        // A paused clock must still redraw when a reset or retarget leaves the same layout metrics.
        controller.revision
        val renderHeight = displayMode.renderHeight(size.width, size.height, layout.second)
        val pointSize = renderHeight / density
        val time = elapsedSeconds()
        val tracks = controller.tracks(time, pointSize)
        painters.keys.removeAll { symbol -> tracks.none { it.symbol === symbol } }
        drawIntoCanvas { canvas ->
            val checkpoint = canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(0f, (size.height - renderHeight) * .5f)
            try {
                for (track in tracks) {
                    if (track.opacity <= 0f) continue
                    val save = if (track.opacity < 1f) canvas.nativeCanvas.saveLayerAlpha(null,
                        (track.opacity * 255f).toInt().coerceIn(0, 255)) else canvas.nativeCanvas.save()
                    if (track.scale != 1f) {
                        canvas.nativeCanvas.scale(track.scale, track.scale, size.width * .5f, renderHeight * .5f)
                    }
                    val painter = painters.getOrPut(track.symbol) { AndroidSymbolPainter(track.symbol) }
                    painter.draw(canvas.nativeCanvas, size.width, renderHeight, mode, tintArgb, secondaryArgb,
                        tertiaryArgb, dark, SymbolEffect.None, time, gradients = gradients, rightToLeft = rightToLeft,
                        appearance = appearance, palette = colors,
                        replacementState = track.painterState(mode, pointSize), pointSize = pointSize)
                    canvas.nativeCanvas.restoreToCount(save)
                }
            } finally {
                canvas.nativeCanvas.restoreToCount(checkpoint)
            }
        }
    }
}
