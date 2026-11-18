package com.nevoit.sfcompose.symbols

import org.json.JSONArray
import org.json.JSONObject

internal fun restorePackedSymbolOutlines(data: JSONObject): JSONObject {
    val outlines = data.optJSONArray("outlineMasters") ?: return data
    val ultralight = outlines.getJSONArray(0)
    val regular = outlines.getJSONArray(1)
    val black = outlines.getJSONArray(2)
    val scales = data.getJSONObject("scales")
    for (name in scales.keys()) {
        val samples = scales.getJSONObject(name).getJSONArray("masters")
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val sampling = sample.getJSONObject("systemSampling")
            val coefficients = sampling.getJSONArray("coefficients")
            val u = coefficients.getDouble(0)
            val b = coefficients.getDouble(1)
            val scale = sampling.getDouble("sizeScale")
            val transform = sample.getJSONArray("systemTransform")
            val a = transform.getDouble(0)
            val xy = transform.getDouble(1)
            val yx = transform.getDouble(2)
            val d = transform.getDouble(3)
            val tx = transform.getDouble(4)
            val ty = transform.getDouble(5)
            val determinant = a * d - xy * yx
            // Scale in the native canvas, whose origin maps to an offset in font space.
            val dx = (scale - 1.0) * ((d * tx - yx * ty) / determinant)
            val dy = (scale - 1.0) * ((xy * tx - a * ty) / determinant)
            val contours = JSONArray()
            for (j in 0 until regular.length()) {
                val contour = regular.getJSONObject(j)
                val lightPoints = ultralight.getJSONObject(j).getJSONArray("points")
                val regularPoints = contour.getJSONArray("points")
                val blackPoints = black.getJSONObject(j).getJSONArray("points")
                val points = JSONArray()
                for (k in 0 until regularPoints.length()) {
                    val center = regularPoints.getDouble(k)
                    val value = center + u * (lightPoints.getDouble(k) - center) + b * (blackPoints.getDouble(k) - center)
                    points.put(value * scale + if (k % 2 == 0) dx else dy)
                }
                contours.put(JSONObject().put("verbs", contour.getString("verbs")).put("points", points))
            }
            sample.put("contours", contours)
        }
    }
    data.remove("outlineMasters")
    return data
}
