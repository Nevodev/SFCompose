package com.nevoit.sfcompose.symbols

import android.graphics.Matrix
import android.graphics.Path
import org.json.JSONObject
import kotlin.math.abs

internal data class SystemDrawState(val from: Float, val to: Float, val duration: Float, val scale: Float)

/** Native TimedAttachment evaluation, separate from the ClipStroke mapped-slice geometry. */
internal class SymbolDrawAttachments(private val entries: List<Entry>) {
    val isSystem: Boolean get() = entries.any { it.system != null }
    val components: Set<Int> = entries.flatMap { it.subpathIndices.asIterable() }.toSet()
    class Entry(
        val subpathIndices: IntArray,
        val attachingComponent: Int,
        val anchorX: Float,
        val anchorY: Float,
        val startProgress: Float,
        val path: Path,
        val ignoreTranslation: Boolean = false,
        val ignoreRotation: Boolean = false,
        val system: SystemAttachment? = null,
    )
    data class SystemAttachment(val layerIndex: Int, val flags: Int, val from: Float, val to: Float)
    data class SystemRecord(
        val flags: Int, val layerIndex: Int, val subpathIndex: Int, val anchorIndex: Int,
        val targets: IntArray, val explicitFrom: Float?, val explicitTo: Float?,
    )
    class Slice(val transform: Matrix, val originX: Float, val originY: Float)

    companion object {
        fun systemRecords(source: JSONObject, mode: String = "monochrome"): List<SystemRecord>? {
            val store = source.optJSONObject("systemAttachmentSource") ?: return emptyList()
            val csv = store.optString(mode, store.optString("monochrome"))
            if (csv.isBlank()) return emptyList()
            val records = csv.split(',')
            val result = ArrayList<SystemRecord>(records.size)
            for (record in records) {
                val values = record.trim().split(Regex("\\s+")).map { it.toDoubleOrNull() ?: return null }
                val version = values.firstOrNull()?.toInt() ?: return null
                if (version !in 0..1) return null
                val offset = if (version == 0) 2 else 0
                if (values.size < 6 + offset) return null
                result += SystemRecord(values[1].toInt(), values[2 + offset].toInt(), values[3 + offset].toInt(),
                    values[4 + offset].toInt(), values.drop(5 + offset).map(Double::toInt).toIntArray(),
                    if (version == 0) values[3].toFloat() else null,
                    if (version == 0) values[2].toFloat() else null)
            }
            return result
        }
        fun prepare(
            source: JSONObject,
            contours: List<Path>,
            interpolateAnchor: (masterAnchors: List<Pair<Float, Float>>) -> Pair<Float, Float>,
            tieProgress: (master: Int, attachingComponent: Int, tieId: String) -> Float?,
            point: (master: Int, x: Double, y: Double) -> Pair<Float, Float>,
            interpolateProgress: (FloatArray) -> Float,
            attachmentSource: (master: Int, metadata: JSONObject) -> JSONObject? = { master, _ -> sectionsFor(source, master) },
        ): SymbolDrawAttachments? {
            val stores = source.optJSONObject("drawRailRoadTies") ?: return null
            val sections = (0..2).map { stores.optJSONObject(it.toString()) ?: return null }
            val attachments = sections.map { it.optJSONArray("attachments") ?: return null }
            if (attachments.any { it.length() != attachments[1].length() }) return null
            val entries = (0 until attachments[1].length()).mapNotNull { index ->
                val masters = attachments.map { it.getJSONObject(index) }
                val regular = masters[1]
                val attaching = regular.getInt("attachingSubpathIndex")
                if (masters.any { it.getInt("attachingSubpathIndex") != attaching }) return null
                val subpaths = regular.getJSONArray("subpathIndices")
                val indices = IntArray(subpaths.length()) { subpaths.getInt(it) }
                if (indices.any { it !in contours.indices }) return null
                val anchors = masters.mapIndexed { master, metadata ->
                    val ties = attachmentSource(master, metadata)?.optJSONArray("ties") ?: return null
                    val tie = (0 until ties.length()).map(ties::getJSONObject)
                        .firstOrNull { it.optString("id") == metadata.getString("anchorTieID") } ?: return@mapNotNull null
                    fun endpoint(key: String): Pair<Float, Float> {
                        val p = tie.getJSONArray(key)
                        return point(master, p.getDouble(0), p.getDouble(1))
                    }
                    val a = endpoint("start")
                    val b = endpoint("end")
                    (a.first + b.first) * 0.5f to (a.second + b.second) * 0.5f
                }
                val anchor = interpolateAnchor(anchors)
                val starts = FloatArray(3) { master ->
                    tieProgress(master, masters[master].getInt("attachingSubpathIndex"),
                        masters[master].getString("attachingTieId")) ?: return@mapNotNull null
                }
                Entry(indices, attaching, anchor.first, anchor.second, interpolateProgress(starts),
                    Path().apply { indices.forEach { addPath(contours[it]) } })
            }
            return SymbolDrawAttachments(entries)
        }

        private fun sectionsFor(source: JSONObject, master: Int): JSONObject? =
            source.optJSONObject("drawRailRoadTies")?.optJSONObject(master.toString())
    }

    fun resolve(
        progress: Float,
        isOn: Boolean,
        isReverse: Boolean,
        sliceTransform: (Entry) -> Slice?,
        systemState: ((Int) -> SystemDrawState?)? = null,
    ): List<Pair<Entry, Path>> = entries.map { entry ->
        if (entry.system != null) {
            val metadata = entry.system
            val state = systemState?.invoke(metadata.layerIndex)
                ?: SystemDrawState(if (isOn != isReverse) 0f else if (isReverse) 1f - progress else progress,
                    if (isOn != isReverse) if (isReverse) 1f - progress else progress else 1f, 1f, 1f)
            val width = state.to - state.from
            val hidden = if (width < 0.0001f || width >= 0.9999f) width < 0.9999f
                else if (state.from == 0f) state.to < metadata.from else state.from > metadata.to
            val result = when {
                width >= 0.9999f -> Path(entry.path)
                hidden || state.scale <= 0f -> Path()
                metadata.flags and 3 == 3 -> Path(entry.path)
                else -> {
                    val scale = if (metadata.flags and 4 == 0) 1f else if (state.from == 0f) {
                        val end = minOf(metadata.from + 0.2f / state.duration, 1f)
                        val fraction = if (end == metadata.from) 1f else ((state.to - metadata.from) / (end - metadata.from)).coerceIn(0f, 1f)
                        0.5f + fraction * 0.5f
                    } else state.scale
                    val slice = sliceTransform(entry)
                    val transform = slice?.transform?.let(::Matrix) ?: Matrix()
                    val centerX = slice?.originX ?: entry.anchorX
                    val centerY = slice?.originY ?: entry.anchorY
                    if (scale != 1f) transform.preScale(scale, scale, centerX, centerY)
                    Path(entry.path).apply { transform(transform) }
                }
            }
            return@map entry to result
        }
        val phase = AttachmentPhase.evaluate(progress, entry.startProgress, isOn, isReverse)
        val result = when {
            phase.preserveOriginal -> Path(entry.path)
            phase.scale <= 0f -> Path()
            else -> {
                val slice = sliceTransform(entry)
                if (slice == null) Path(entry.path) else {
                    val transform = Matrix(slice.transform)
                    val values = FloatArray(9)
                    transform.getValues(values)
                    val mappedAnchor = floatArrayOf(slice.originX, slice.originY)
                    transform.mapPoints(mappedAnchor)
                    val deltaX = mappedAnchor[0] - slice.originX
                    val deltaY = mappedAnchor[1] - slice.originY
                    if (entry.ignoreRotation) {
                        transform.setTranslate(deltaX, deltaY)
                        transform.getValues(values)
                    }
                    if (entry.ignoreTranslation) {
                        values[Matrix.MTRANS_X] -= deltaX
                        values[Matrix.MTRANS_Y] -= deltaY
                        transform.setValues(values)
                    }
                    if (phase.scale in 0f..1f && phase.scale != 1f) {
                        val scale = Matrix().apply { setScale(phase.scale, phase.scale, slice.originX, slice.originY) }
                        transform.setConcat(Matrix(transform), scale)
                    }
                    Path(entry.path).apply { transform(transform) }
                }
            }
        }
        entry to result
    }
}

internal data class AttachmentPhase(val scale: Float, val preserveOriginal: Boolean = false) {
    companion object {
        fun evaluate(progress: Float, startProgress: Float, isOn: Boolean, isReverse: Boolean): AttachmentPhase {
            val value = progress.coerceIn(0f, 1f)
            val phase = if (isReverse) 1f - value else value
            if (isOn && phase >= 1f || !isOn && phase <= 0f) return AttachmentPhase(1f, true)
            val duration = if (!isOn && !isReverse) 0.033f else 0.2f
            val time = inverseDrawCurve(phase)
            val low: Float
            val high: Float
            if (isOn) {
                low = inverseDrawCurve(if (isReverse) 0f else startProgress)
                if (time <= low) return AttachmentPhase(0f)
                high = minOf(1f, low + duration)
            } else {
                high = inverseDrawCurve(if (isReverse) 1f - startProgress else 1f)
                if (time >= high) return AttachmentPhase(0f)
                low = maxOf(0f, high - duration)
            }
            val fraction = if (high == low) 1f else ((time - low) / (high - low)).coerceIn(0f, 1f)
            val eased = SymbolTiming.cubicBezier(fraction, 0.2f, 0f, 0.25f, 1f)
            return AttachmentPhase(if (isOn) 0.5f + 0.5f * eased else 1f - 0.5f * eased)
        }

        private fun inverseDrawCurve(progress: Float): Float {
            if (progress <= 0f || progress >= 1f) return progress.coerceIn(0f, 1f)
            var low = 0.0
            var high = 1.0
            var t = progress.toDouble()
            repeat(32) {
                val y = t * t * (3.0 - 2.0 * t)
                if (abs(y - progress) < 0.00001) return (t * (0.6 + t * (-0.45 + 0.85 * t))).toFloat()
                if (y < progress) low = t else high = t
                t = (low + high) * 0.5
            }
            return (t * (0.6 + t * (-0.45 + 0.85 * t))).toFloat()
        }
    }
}
