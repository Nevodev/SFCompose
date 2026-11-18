package com.nevoit.sfcompose.symbols

/** Independent RenderBox presentation channels; tint alpha belongs to the fill material. */
internal data class SymbolLayerOpacity(
    val drawing: Float = 1f,
    val erasing: Float = 1f,
    val common: Float = 1f,
) {
    fun multiplyDrawing(factor: Float, hideErasingAtZero: Boolean = false): SymbolLayerOpacity = if (factor == 1f) this else copy(
        drawing = drawing * factor,
        erasing = if (hideErasingAtZero && factor <= 0f) 0f else erasing,
    )

    fun multiplyBoth(factor: Float): SymbolLayerOpacity = if (factor == 1f) this else copy(
        drawing = drawing * factor,
        erasing = erasing * factor,
    )

    fun variableColor(activeFraction: Float, inactive: Float, active: Float): SymbolLayerOpacity =
        multiplyBoth(interpolate(inactive, active, activeFraction))

    fun lerpBothToward(target: Float, amount: Float): SymbolLayerOpacity = copy(
        drawing = interpolate(drawing, target, amount),
        erasing = interpolate(erasing, target, amount),
    )

    fun pulse(
        keyframeValue: Float,
        byLayer: Boolean,
        hasExplicitPulseLayers: Boolean,
        hierarchical: Boolean,
        currentHierarchyOpacity: Float = 1f,
        nextHierarchyOpacity: Float = 1f,
    ): SymbolLayerOpacity {
        val explicitByLayer = hasExplicitPulseLayers && byLayer
        val base = if (explicitByLayer && hierarchical)
            nextHierarchyOpacity / currentHierarchyOpacity else .3f
        return copy(
            drawing = if (drawing > 0f) drawing * interpolate(base, 1f, keyframeValue) else drawing,
            erasing = if (erasing > 0f && explicitByLayer)
                erasing * (keyframeValue.toDouble() * .7f.toDouble() + .3f.toDouble()).toFloat()
            else erasing,
        )
    }

    fun drawingOpacity(materialOpacity: Float): Float = drawing * common * materialOpacity

    fun erasingOpacity(materialOpacity: Float, clearBehind: Boolean): Float = when {
        !clearBehind -> 0f
        materialOpacity > 0f -> common
        else -> erasing * common
    }

    private fun interpolate(from: Float, to: Float, progress: Float): Float {
        val difference = to - from
        // ARM FMADD rounds once after the already-rounded float subtraction.
        return (difference.toDouble() * progress.toDouble() + from.toDouble()).toFloat()
    }
}
