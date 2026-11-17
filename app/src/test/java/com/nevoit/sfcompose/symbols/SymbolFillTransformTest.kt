package com.nevoit.sfcompose.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolFillTransformTest {
    private fun matrixValues(matrix: Matrix): FloatArray = FloatArray(9).also(matrix::getValues)

    @Suppress("DEPRECATION")
    @Test
    fun shaderCoordinatesMatchNativeFillWithoutAnimationAnglesAcrossNestedPivots() {
        val canvas = Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        val reference = Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        canvas.translate(13f, -7f)
        reference.translate(13f, -7f)
        val parent = SymbolFillTransform(canvas)
        parent.translate(40f, 30f)
        reference.translate(40f, 30f)
        parent.scale(1.3f, 0.8f, 7f, -3f)
        reference.scale(1.3f, 0.8f, 7f, -3f)
        parent.rotate(67f, -5f, 2f)
        val originalParent = matrixValues(parent.shaderMatrix()!!)
        val child = parent.copy()
        child.translate(-3f, 9f)
        reference.translate(-3f, 9f)
        child.rotate(-21f, 4f, 6f)
        child.scale(0.75f, 1.7f, -2f, 5f)
        reference.scale(0.75f, 1.7f, -2f, 5f)
        child.rotate(18f, 1f, 3f, skipInNativeFill = false)
        reference.rotate(18f, 1f, 3f)
        child.translate(5f, -4f)
        reference.translate(5f, -4f)
        val base = Matrix().apply { setValues(floatArrayOf(2f, 0.2f, 11f, -0.3f, 3f, -6f, 0f, 0f, 1f)) }
        val points = floatArrayOf(0f, 0f, 10f, -2f, -7f, 6f, 2f, 12f)
        val actual = points.copyOf()
        child.shaderMatrix(base)!!.mapPoints(actual)
        canvas.matrix.mapPoints(actual)
        val expected = points.copyOf()
        base.mapPoints(expected)
        reference.matrix.mapPoints(expected)
        assertArrayEquals(expected, actual, 0.0001f)
        assertArrayEquals(originalParent, matrixValues(parent.shaderMatrix()!!), 0f)
    }

    @Test
    fun rotatedPathKeepsTheNativeGradientField() {
        val actual = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        fun shader() = LinearGradient(-50f, 0f, 50f, 0f, Color.RED, Color.BLUE, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setValues(floatArrayOf(0.8f, 0.2f, 4f, 0.1f, 1.2f, -3f, 0f, 0f, 1f)) })
        }
        val source = Path().apply { addRect(-45f, -35f, 45f, 35f, Path.Direction.CW) }
        val actualCanvas = Canvas(actual)
        actualCanvas.translate(96f, 96f)
        val transforms = SymbolFillTransform(actualCanvas)
        transforms.scale(1.1f, 0.9f)
        transforms.rotate(37f, 4f, -6f)
        val actualShader = shader()
        transforms.withShader(actualShader) {
            actualCanvas.drawPath(source, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = actualShader })
        }
        val referencePath = Path(source).apply { transform(Matrix().apply { setRotate(37f, 4f, -6f) }) }
        Canvas(expected).apply {
            translate(96f, 96f)
            scale(1.1f, 0.9f)
            drawPath(referencePath, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader() })
        }
        var count = 0
        var error = 0L
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = actual.getPixel(x, y)
            val b = expected.getPixel(x, y)
            if (Color.alpha(a) == 255 && Color.alpha(b) == 255) {
                count++
                error += abs(Color.red(a) - Color.red(b)) + abs(Color.blue(a) - Color.blue(b))
            }
        }
        assertTrue("Compared $count opaque pixels", count > 1000)
        assertTrue("Native gradient error=$error pixels=$count", error <= count)
    }

    @Test
    fun shaderMatrixIsRestoredWhenDrawingFails() {
        val transforms = SymbolFillTransform(Canvas())
        transforms.rotate(60f, 3f, -9f)
        val shader = LinearGradient(0f, 0f, 10f, 0f, Color.RED, Color.BLUE, Shader.TileMode.CLAMP)
        val base = Matrix().apply { setScale(3f, 2f, -2f, 4f) }
        shader.setLocalMatrix(base)
        assertTrue(runCatching { transforms.withShader(shader) { error("draw failed") } }.isFailure)
        val restored = Matrix()
        assertTrue(shader.getLocalMatrix(restored))
        assertArrayEquals(matrixValues(base), matrixValues(restored), 0f)
    }

    @Test
    fun collapsedPathDoesNotCorruptTheSharedShader() {
        val transforms = SymbolFillTransform(Canvas())
        transforms.scale(0f, 1f)
        val shader = LinearGradient(0f, 0f, 10f, 0f, Color.RED, Color.BLUE, Shader.TileMode.CLAMP)
        assertTrue(transforms.shaderMatrix() == null)
        var drawn = false
        transforms.withShader(shader) { drawn = true }
        assertTrue(drawn)
        assertTrue(!shader.getLocalMatrix(Matrix()))
    }
}
