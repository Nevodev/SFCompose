package com.nevoit.sfcompose.symbols

/** ARM reciprocal estimate and two refinement steps used by native keyframe time normalization. */
internal object SystemFloatTiming {
    fun reciprocal(value: Float): Float {
        if (!value.isFinite() || value <= 0f) return 1f / value
        val bits = value.toRawBits()
        val exponent = (bits ushr 23) and 255
        if (exponent !in 1..252) return 1f / value
        val significand = 256 or ((bits ushr 15) and 255)
        val estimate = (((1 shl 19) / (2 * significand + 1)) + 1) ushr 1
        var result = Float.fromBits(((253 - exponent) shl 23) or ((estimate and 255) shl 15))
        repeat(2) {
            val correction = (2.0 - value.toDouble() * result.toDouble()).toFloat()
            result *= correction
        }
        return result
    }

    fun progress(time: Float, duration: Float): Float =
        if (duration > 0f) (time * reciprocal(duration)).coerceIn(0f, 1f) else 0f
}
