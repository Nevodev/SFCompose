package com.nevoit.sfcompose.symbols

import android.graphics.RectF
import kotlin.math.ceil

internal class SymbolGradientMetrics(
    private val width: Double,
    private val height: Double,
    private val referencePointSize: Double,
    private val transform: DoubleArray,
) {
    fun bounds(pointSize: Float, density: Float): RectF {
        require(pointSize > 0f && density > 0f)
        val ratio = pointSize / referencePointSize
        val pixelToPoint = 1f / density
        val roundedWidth = (ceil(((width * ratio).toFloat() * density).toDouble()).toFloat() * pixelToPoint) / ratio
        val roundedHeight = (ceil(((height * ratio).toFloat() * density).toDouble()).toFloat() * pixelToPoint) / ratio
        val determinant = transform[0] * transform[3] - transform[1] * transform[2]
        require(determinant != 0.0)
        val points = listOf(0.0 to 0.0, roundedWidth to 0.0, 0.0 to roundedHeight, roundedWidth to roundedHeight).map { (x, y) ->
            val localX = x - transform[4]
            val localY = y - transform[5]
            ((transform[3] * localX - transform[2] * localY) / determinant).toFloat() to
                ((transform[1] * localX - transform[0] * localY) / determinant).toFloat()
        }
        return RectF(points.minOf { it.first }, points.minOf { it.second },
            points.maxOf { it.first }, points.maxOf { it.second })
    }
}
