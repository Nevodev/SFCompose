package com.nevoit.sfcompose.symbols

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
    tint: Color = Color.Black,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
    rightToLeft: Boolean = false,
    appearance: SymbolAppearance = SymbolAppearance.Standard,
    contentDescription: String? = null,
    displayMode: SymbolDisplayMode = SymbolDisplayMode.IntrinsicWidth,
    elapsedSeconds: () -> Float,
) {
    val plans = remember(first, second, direction, layered, preferMagic, mode) {
        HashMap<Float, SymbolReplacePlan>()
    }
    val oldPainter = remember(first) { AndroidSymbolPainter(first) }
    val newPainter = remember(second) { AndroidSymbolPainter(second) }
    val dark = isSystemInDarkTheme()
    val semantics = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(modifier.then(semantics).height(32.dp)
        .aspectRatio(displayMode.layoutRatio(maxOf(first.advance / first.unitsPerEm, second.advance / second.unitsPerEm)),
            matchHeightConstraintsFirst = true)) {
        // Share one scale for the pair so replacing a narrow symbol with a wide one does not resize the slot.
        val renderHeight = displayMode.renderHeight(size.width, size.height,
            maxOf(first.horizontalInkRatio(), second.horizontalInkRatio()))
        drawIntoCanvas { canvas ->
            val pointSize = renderHeight / density
            val plan = plans.getOrPut(pointSize) {
                SymbolReplacePlan(first, second, direction, preferMagic, layered, mode, pointSize = pointSize)
            }
            val time = elapsedSeconds()
            val checkpoint = canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(0f, (size.height - renderHeight) * .5f)
            try {
                oldPainter.draw(canvas.nativeCanvas, size.width, renderHeight, mode, tint.toArgb(), secondary.toArgb(), tertiary.toArgb(), dark,
                    SymbolEffect.None, time, gradients = gradients, rightToLeft = rightToLeft, appearance = appearance,
                    palette = palette?.map { it.toArgb() }?.toIntArray(),
                    replacementState = { plan.layerState(it, false, time) }, pointSize = pointSize)
                newPainter.draw(canvas.nativeCanvas, size.width, renderHeight, mode, tint.toArgb(), secondary.toArgb(), tertiary.toArgb(), dark,
                    SymbolEffect.None, time, gradients = gradients, rightToLeft = rightToLeft, appearance = appearance,
                    palette = palette?.map { it.toArgb() }?.toIntArray(),
                    replacementState = { plan.layerState(it, true, time) }, pointSize = pointSize)
            } finally {
                canvas.nativeCanvas.restoreToCount(checkpoint)
            }
        }
    }
}
