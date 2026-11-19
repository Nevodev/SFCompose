package com.nevoit.sfcompose.symbols

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Shader

internal class SymbolFillTransform private constructor(
    private val canvas: Canvas,
    private val pathMatrix: Matrix,
    private val fillMatrix: Matrix,
    private val skipAnimationRotation: Boolean,
) {
    constructor(canvas: Canvas, skipAnimationRotation: Boolean = true) : this(canvas, Matrix(), Matrix(), skipAnimationRotation)

    // Canvas save/restore remains owned by the caller. These matrices track only
    // transforms applied after the caller's common glyph placement transform.
    fun reset() {
        pathMatrix.reset()
        fillMatrix.reset()
    }

    fun copy(): SymbolFillTransform = SymbolFillTransform(canvas, Matrix(pathMatrix), Matrix(fillMatrix), skipAnimationRotation)

    fun translate(dx: Float, dy: Float) {
        canvas.translate(dx, dy)
        pathMatrix.preTranslate(dx, dy)
        fillMatrix.preTranslate(dx, dy)
    }

    fun scale(sx: Float, sy: Float, pivotX: Float = 0f, pivotY: Float = 0f) {
        canvas.scale(sx, sy, pivotX, pivotY)
        pathMatrix.preScale(sx, sy, pivotX, pivotY)
        fillMatrix.preScale(sx, sy, pivotX, pivotY)
    }

    fun rotate(degrees: Float, pivotX: Float = 0f, pivotY: Float = 0f, skipInNativeFill: Boolean = skipAnimationRotation) {
        canvas.rotate(degrees, pivotX, pivotY)
        pathMatrix.preRotate(degrees, pivotX, pivotY)
        if (!skipInNativeFill) fillMatrix.preRotate(degrees, pivotX, pivotY)
    }

    fun shaderMatrix(base: Matrix? = null): Matrix? {
        // Native fills omit animator angles while retaining placement and scale.
        // A shader is evaluated beneath the path CTM, so cancel that CTM first.
        val inverse = Matrix()
        if (!pathMatrix.invert(inverse)) return null
        return Matrix().apply {
            setConcat(inverse, fillMatrix)
            if (base != null) preConcat(base)
        }
    }

    fun <T> withShader(shader: Shader?, block: () -> T): T {
        if (shader == null) return block()
        val original = Matrix()
        val hadOriginal = shader.getLocalMatrix(original)
        val compensated = shaderMatrix(original)
        if (compensated != null) shader.setLocalMatrix(compensated)
        try {
            return block()
        } finally {
            shader.setLocalMatrix(if (hadOriginal) original else null)
        }
    }
}
