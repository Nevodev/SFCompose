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

@Composable
fun rememberSymbolReplaceController(initialSymbol: SymbolInstance): SymbolReplaceController =
    remember { SymbolReplaceController(initialSymbol) }

/** Draws a controller on the same absolute clock passed to replaceWith. */
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
