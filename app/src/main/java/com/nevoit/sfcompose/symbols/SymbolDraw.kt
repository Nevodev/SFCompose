package com.nevoit.sfcompose.symbols

import android.graphics.Path
import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal enum class DrawLineCap { Butt, Round, Square }

/** Filled-outline clipping driven by compiled system keyframes or authoring ties. */
internal class SymbolDraw(private val symbol: SymbolInstance) {
    private data class Marker(
        val distance: Float,
        val progress: Float,
        val corner: Boolean = false,
        val preciseDistance: Double = distance.toDouble(),
    )
    private data class NaturalCap(val outline: DrawOutline, val first: FloatArray, val last: FloatArray)
    private data class Plan(
        val measure: DrawOutline,
        val length: Float,
        val markers: List<Marker>,
        val startCap: NaturalCap? = null,
        val endCap: NaturalCap? = null,
        val adaptiveEndCap: NaturalCap? = null,
        val pairs: List<AnnotatedPair> = emptyList(),
        val additional: List<Plan> = emptyList(),
        val components: Set<Int> = emptySet(),
        val source: JSONObject? = null,
        val masterRaw: List<List<AnnotatedPair>> = emptyList(),
        val seams: List<Pair<Float, Float>> = emptyList(),
        val hasSystemProgressRange: Boolean = true,
    )
    private data class PairTie(val first: Float, val second: Float, val artificial: Boolean, val inverted: Boolean, val smartCap: Boolean)
    private data class SingleTie(val first: Float, val second: Float, val style: Int, val smartCap: Boolean)
    private data class AnnotatedPair(val first: Float, val second: Float, val progress: Float, val smartCap: Boolean, val corner: Boolean = false, val bisection: Boolean = false)
    private data class CompiledKeyframe(
        val first: Int, val second: Int, val pair: AnnotatedPair, val flags: Int,
        val firstFraction: Float, val secondFraction: Float,
    )
    private data class Preparation(
        val plan: Plan?, val reason: String?, val raw: List<AnnotatedPair>? = null,
        val attachments: SymbolDrawAttachments? = null,
        val nativeFallback: String? = null,
    )
    private val prepared = IdentityHashMap<JSONObject, Preparation>()
    private data class SystemReference(val plan: Plan, val first: Float, val second: Float, val progress: Float)
    private val systemReferences = IdentityHashMap<SymbolDrawAttachments.Entry, SystemReference>()
    private var systemAttachmentMode = "monochrome"
    private fun isSystemSource(source: JSONObject): Boolean =
        source.has("systemClipStrokeKeyframes") || source.has("systemClipStrokeKeyframeSets")
    private fun attachmentMode(source: JSONObject): String = source.optString("systemRenderingMode", systemAttachmentMode)
    private val layers by lazy {
        buildList<SymbolNode.Layer> {
            fun visit(nodes: List<SymbolNode>) {
                for (node in nodes) when (node) {
                    is SymbolNode.Layer -> add(node)
                    is SymbolNode.Group -> visit(node.children)
                }
            }
            visit(symbol.nodes)
        }
    }
    private data class AuthoringGeometry(val advance: Double, val contours: List<DrawContour>, val outlines: List<DrawOutline>)
    private val authoringScale get() = 1000.0 / symbol.unitsPerEm
    private val systemMaster: JSONObject? by lazy {
        (0 until symbol.sourceMasters.length()).map(symbol.sourceMasters::getJSONObject)
            .firstOrNull { it.getDouble("weight").toFloat() == symbol.weightPosition }
    }
    private val systemSourceScale: Double
        get() = systemMaster?.optJSONArray("systemTransform")?.optDouble(0) ?: (13.0 / symbol.unitsPerEm)
    private var systemPointSize = 13f
    private val legacyOutlines by lazy { symbol.contourData.map { DrawOutline.from(it) } }
    private var systemOutlines: List<DrawOutline>? = null
    private val outlines: List<DrawOutline>
        get() = if (layers.any { node -> isSystemSource(node.source) }) {
            systemOutlines ?: symbol.contourData.map {
                DrawOutline.from(it, 0.5 * 13.0 / (systemPointSize * systemSourceScale))
            }.also { systemOutlines = it }
        } else legacyOutlines
    private val authoringMasters by lazy {
        listOf(symbol.ultralightWeightPosition.toDouble(), 400.0, 1000.0).map(::readAuthoringGeometry)
    }

    private fun readAuthoringGeometry(position: Double): AuthoringGeometry {
        val source = (0 until symbol.sourceSmallMasters.length()).map(symbol.sourceSmallMasters::getJSONObject)
        val lower = source.last { it.getDouble("weight") <= position }
        val upper = source.first { it.getDouble("weight") >= position }
        val lowWeight = lower.getDouble("weight")
        val highWeight = upper.getDouble("weight")
        val fraction = if (lowWeight == highWeight) 0.0 else (position - lowWeight) / (highWeight - lowWeight)
        val lowContours = lower.getJSONArray("contours")
        val highContours = upper.getJSONArray("contours")
        val contours = List(lowContours.length()) { index ->
            val low = lowContours.getJSONObject(index)
            val high = highContours.getJSONObject(index)
            require(low.getString("verbs") == high.getString("verbs"))
            val first = low.getJSONArray("points")
            val second = high.getJSONArray("points")
            SymbolContourData(low.getString("verbs"), DoubleArray(first.length()) {
                (first.getDouble(it) + (second.getDouble(it) - first.getDouble(it)) * fraction) * authoringScale
            })
        }
        return AuthoringGeometry(
            lower.getDouble("advance") + (upper.getDouble("advance") - lower.getDouble("advance")) * fraction,
            contours.map(::DrawContour), contours.map { DrawOutline.from(it) },
        )
    }

    private fun prepareSystemSize(pointSize: Float): Boolean {
        if (!pointSize.isFinite() || pointSize <= 0f) return false
        if (pointSize != systemPointSize) {
            systemPointSize = pointSize
            systemOutlines = null
            prepared.clear()
            systemReferences.clear()
        }
        return true
    }

    fun timingLengthSamples(source: JSONObject, components: IntArray, pointSize: Float = 13f): List<Double>? {
        if (isSystemSource(source)) {
            if (!prepareSystemSize(pointSize)) return null
            if (!prepared.containsKey(source)) unsupportedReason(source, components)
        }
        if (!hasGeometry(source)) return null
        val plan = prepared[source]?.plan ?: return null
        if (isSystemSource(source)) {
            return (listOf(plan) + plan.additional).filter { it.markers.isNotEmpty() }.map { part ->
                var distance = 0.0
                var progress = 0f
                for (index in 1 until part.markers.size) {
                    val first = part.markers[index - 1]
                    val second = part.markers[index]
                    val delta = abs(second.progress - first.progress)
                    if (delta >= 0.0001f) {
                        distance += second.preciseDistance - first.preciseDistance
                        progress += delta
                    }
                }
                (if (progress > 0f) distance / progress else 0.0) * systemSourceScale * (1000.0 / 13.0)
            }.takeIf { it.isNotEmpty() }
        }
        val geometry = authoringMasters[1]
        if (components.any { it !in geometry.contours.indices }) return null
        return (listOf(plan) + plan.additional).filter { it.markers.isNotEmpty() }.map { part ->
            val length = part.components.map { geometry.contours[it].length }.average()
            if (part.components.size > 1) length else length * 0.5
        }.takeIf { it.isNotEmpty() }
    }

    fun unsupportedReason(source: JSONObject, components: IntArray): String? =
        prepared.getOrPut(source) {
            val preparation = prepare(source, components)
            if (preparation.reason != null || preparation.nativeFallback != null || preparation.plan == null) preparation else {
                val attachments = prepareAttachments(source, components, preparation.plan)
                val required = if (isSystemSource(source)) SymbolDrawAttachments.systemRecords(source, attachmentMode(source))?.size ?: 1
                    else source.optJSONObject("drawRailRoadTies")?.optJSONObject("1")?.optJSONArray("attachments")?.length() ?: 0
                preparation.copy(attachments = attachments, reason = if (required > 0 && attachments == null) "Invalid original Draw attachment metadata" else null)
            }
        }.reason

    fun hasGeometry(source: JSONObject): Boolean {
        if (!prepared.containsKey(source)) {
            val layer = layers.firstOrNull { it.source === source } ?: return false
            // Font-size changes invalidate prepared paths, not the layer's Draw capability.
            unsupportedReason(source, layer.components)
        }
        return prepared[source]?.let { it.plan != null && it.nativeFallback == null } ?: false
    }

    fun nativeFallbackReason(source: JSONObject): String? = prepared[source]?.nativeFallback

    fun hasSystemAttachments(source: JSONObject): Boolean {
        if (!prepared.containsKey(source)) {
            val layer = layers.firstOrNull { it.source === source } ?: return false
            unsupportedReason(source, layer.components)
        }
        return prepared[source]?.attachments?.isSystem == true
    }

    fun pathFor(
        source: JSONObject,
        path: Path,
        progress: Float,
        isOn: Boolean = true,
        isReverse: Boolean = false,
        lineCap: DrawLineCap = DrawLineCap.Round,
        removeOtherSubpaths: Boolean? = null,
        pointSize: Float? = null,
        systemDrawState: ((Int) -> SystemDrawState?)? = null,
        systemMode: String = "monochrome",
        adaptiveCaps: Boolean = false,
    ): Path? {
        if (!progress.isFinite()) return null
        if (isSystemSource(source)) {
            if (systemMode != systemAttachmentMode) {
                systemAttachmentMode = systemMode
                if (!source.has("systemRenderingMode")) {
                    prepared.clear()
                    systemReferences.clear()
                }
            }
            if (!prepareSystemSize(pointSize ?: 13f)) return null
            if (!prepared.containsKey(source)) unsupportedReason(source,
                layers.firstOrNull { it.source === source }?.components ?: return null)
        }
        if (source.optJSONObject("drawRailRoadTies") == null && !isSystemSource(source)) return Path(path)
        val preparation = prepared[source] ?: return null
        if (preparation.nativeFallback != null) return Path(path)
        val plan = preparation.plan ?: return Path(path)
        val removeUnannotated = removeOtherSubpaths ?: !isSystemSource(source)
        val value = progress.coerceIn(0f, 1f)
        val geometryProgress = if (isReverse) 1f - value else value
        val geometryOn = isOn != isReverse
        if ((value == 0f && !isOn || value == 1f && isOn) &&
            !(preparation.attachments?.isSystem == true && systemDrawState != null)) {
            if (!removeUnannotated) return Path(path)
            val selected = (listOf(plan) + plan.additional).filter { it.markers.isNotEmpty() }.flatMap { it.components }.toSet() +
                preparation.attachments?.components.orEmpty()
            return Path().apply { selected.forEach { addPath(symbol.contours[it]) } }
        }
        val empty = value == 0f && isOn || value == 1f && !isOn
        val resolved = preparation.attachments?.resolve(value, isOn, isReverse, sliceTransform = { entry ->
            if (entry.system != null) {
                val state = systemDrawState?.invoke(entry.system.layerIndex)
                    ?: SystemDrawState(if (geometryOn) 0f else geometryProgress, if (geometryOn) geometryProgress else 1f, 1f, 1f)
                systemAttachmentSlice(entry, state)
            } else attachmentSlice(plan, entry, geometryProgress)
        }, systemState = systemDrawState).orEmpty()
        val replaced = resolved.flatMap { it.first.subpathIndices.asIterable() }.toSet()
        val result = Path().apply {
            fillType = Path.FillType.WINDING
            for (part in listOf(plan) + plan.additional) {
                if (removeUnannotated && part.markers.isEmpty()) continue
                if (part.components.isNotEmpty() && part.components.all { it in replaced }) continue
                if (empty && part.markers.isNotEmpty()) continue
                if (!part.hasSystemProgressRange) continue
                for (piece in splitPieces(part, geometryProgress, geometryOn)) {
                    if (piece.markers.isEmpty()) {
                        piece.measure.getSegment(0f, piece.length, this, true)
                        close()
                        continue
                    }
                    val ranges = visibleRanges(piece, geometryProgress, geometryOn)
                    for ((index, range) in ranges.withIndex()) {
                        appendRange(piece, range.first, range.second, this, index == 0)
                        val next = ranges[(index + 1) % ranges.size]
                        val natural = if (geometryOn) {
                            if (adaptiveCaps) piece.adaptiveEndCap ?: piece.endCap else piece.endCap
                        } else piece.startCap
                        appendCap(piece, next.first to range.second, lineCap, this, natural, adaptiveCaps)
                    }
                    if (ranges.isNotEmpty()) close()
                }
            }
        }
        resolved.forEach { result.addPath(it.second) }
        return result
    }

    private fun prepare(source: JSONObject, components: IntArray): Preparation {
        source.optJSONArray("systemClipStrokeKeyframes")?.let {
            return prepareSystem(it, components, !SymbolDrawAttachments.systemRecords(source, attachmentMode(source)).isNullOrEmpty())
        }
        source.optJSONArray("systemClipStrokeKeyframeSets")?.let { sets ->
            return prepareSystem(sets.optJSONArray(0) ?: JSONArray(), components,
                !SymbolDrawAttachments.systemRecords(source, attachmentMode(source)).isNullOrEmpty())
        }
        if (source.optJSONObject("drawRailRoadTies") == null) return Preparation(null, null)
        val valid = components.filter { it in outlines.indices }.toIntArray()
        if (valid.size != components.size) {
            return if (valid.isNotEmpty()) prepare(source, valid) else Preparation(null, "Empty original Draw contour selection")
        }
        if (components.isEmpty()) return Preparation(null, "Empty Draw contour selection")
        if (components.size == 1) return prepareInterpolated(source, components)
        data class Group(val ties: MutableList<JSONObject> = ArrayList(), val components: MutableSet<Int> = sortedSetOf())
        val masterGroups = (0..2).map { master ->
            val section = source.getJSONObject("drawRailRoadTies").optJSONObject(master.toString())
                ?: return Preparation(null, "Missing original Draw master")
            val ties = section.optJSONArray("ties") ?: return Preparation(null, "Missing original Draw ties")
            val groups = sortedMapOf<Int, Group>()
            val geometry = authoringMasters[master]
            for (index in 0 until ties.length()) {
                val tie = ties.getJSONObject(index)
                fun selected(key: String): Int? {
                    if (tie.has("favoredSubpathIndex")) return components.getOrNull(tie.getInt("favoredSubpathIndex"))
                    val point = tie.optJSONArray(key)?.let { originalPoint(it, geometry) } ?: return null
                    return components.minWithOrNull(compareBy<Int> { geometry.contours[it].project(point).squaredDistance }.thenByDescending { it })
                }
                val first = selected("start") ?: return Preparation(null, "Invalid original Draw endpoint")
                val second = selected("end") ?: return Preparation(null, "Invalid original Draw endpoint")
                val group = groups.getOrPut(minOf(first, second)) { Group() }
                group.ties += tie
                group.components += first
                group.components += second
            }
            groups
        }
        if (masterGroups.any { it.keys != masterGroups[1].keys }) return Preparation(null, null,
            nativeFallback = "Original Draw export rejected incompatible master contour groups (0x2057F8 / 0x3AA64)")
        val plans = ArrayList<Plan>()
        val annotated = HashSet<Int>()
        for ((key, group) in masterGroups[1]) {
            val stores = JSONObject()
            for (master in 0..2) {
                val masterGroup = masterGroups[master].getValue(key)
                if (masterGroup.components != group.components) return Preparation(null, null,
                    nativeFallback = "Original Draw export rejected master endpoint contours ${masterGroup.components} / ${group.components} (0x3AA64)")
                stores.put(master.toString(), JSONObject().put("ties", JSONArray(masterGroup.ties)).put("attachments", JSONArray()))
            }
            val child = JSONObject().put("drawRailRoadTies", stores)
            val preparation = prepareInterpolated(child, group.components.toIntArray())
            if (preparation.reason != null || preparation.nativeFallback != null) return preparation
            plans += preparation.plan!!
            annotated += group.components
        }
        for (component in components) if (component !in annotated) {
            val outline = outlines[component]
            plans += Plan(outline, outline.length, emptyList(), components = setOf(component))
        }
        if (plans.isEmpty()) return Preparation(null, "No original Draw ties")
        return Preparation(plans.first().copy(additional = plans.drop(1)), null)
    }

    private fun prepareSystem(keyframes: JSONArray, components: IntArray, allowEmptyKeyframes: Boolean = false): Preparation {
        if (keyframes.length() == 0 && !allowEmptyKeyframes) return Preparation(null, null)
        if (components.any { it !in outlines.indices }) return Preparation(null, "Invalid system Draw contour index")
        val compiled = ArrayList<CompiledKeyframe>(keyframes.length())
        for (index in 0 until keyframes.length()) {
            val record = keyframes.getJSONObject(index)
            val first = record.optDouble("first", Double.NaN)
            val second = record.optDouble("second", Double.NaN)
            val progress = record.optDouble("progress", Double.NaN)
            if (!first.isFinite() || !second.isFinite() || !progress.isFinite() || first < 0.0 || second < 0.0) {
                return Preparation(null, "Invalid system Draw keyframe")
            }
            // RenderBox consumes the ordered keyframe prefix as each contour closes.
            // A rounded index beyond the final contour leaves the remaining records unused.
            val firstIndex = components.getOrNull(floor(first).toInt()) ?: break
            val secondIndex = components.getOrNull(floor(second).toInt())
                ?: return Preparation(null, "Invalid system Draw subpath")
            val flags = record.getInt("flags")
            compiled += CompiledKeyframe(firstIndex, secondIndex, AnnotatedPair(
                (first - floor(first)).toFloat() * outlines[firstIndex].length,
                (second - floor(second)).toFloat() * outlines[secondIndex].length,
                progress.toFloat(), flags and 2 != 0, flags and 1 != 0, flags and 8 != 0,
            ), flags, (first - floor(first)).toFloat(), (second - floor(second)).toFloat())
        }
        val plans = ArrayList<Plan>()
        val groups = components.associateWith { mutableSetOf(it) }.toMutableMap()
        for (record in compiled) {
            val first = groups.getValue(record.first)
            val second = groups.getValue(record.second)
            if (first !== second) {
                first += second
                for (component in second) groups[component] = first
            }
        }
        val handled = HashSet<Int>()
        for (component in components) {
            if (!handled.add(component)) continue
            val group = groups.getValue(component)
            handled += group
            val records = compiled.filter { it.first in group }
            if (group.size > 1) {
                val preparation = prepareSystemCompound(group, records)
                if (preparation.reason != null) return preparation
                plans += preparation.plan ?: continue
                continue
            }
            val outline = outlines[component]
            val pairs = records.map { it.pair }
            val markers = records.flatMap { record ->
                val pair = record.pair
                listOf(
                    Marker(pair.first, pair.progress, pair.corner, outline.preciseLength * record.firstFraction),
                    Marker(pair.second, pair.progress, pair.corner, outline.preciseLength * record.secondFraction),
                )
            }
                .sortedBy { it.distance }
            if (pairs.isNotEmpty() && (!outline.isClosed || outline.length <= 0f)) {
                return Preparation(null, "System Draw requires a closed contour")
            }
            plans += addNaturalCaps(Plan(outline, outline.length, markers, pairs = pairs, components = setOf(component),
                hasSystemProgressRange = markers.isEmpty() || markers.any { abs(it.progress - markers.first().progress) >= 0.0001f }), pairs)
        }
        return if (plans.isEmpty()) Preparation(null, "Empty system Draw contour selection")
        else Preparation(plans.first().copy(additional = plans.drop(1)), null)
    }

    private fun prepareSystemCompound(components: Set<Int>, records: List<CompiledKeyframe>): Preparation {
        if (components.size != 2) return Preparation(null, "System Draw requires a supported joined-contour graph")
        val bridge = records.firstOrNull { it.first != it.second }
            ?: return Preparation(null, "Missing system compound Draw bridge")
        val indices = listOf(bridge.first, bridge.second)
        val original = indices.map(outlines::get)
        if (original.any { !it.isClosed || it.length <= 0f }) return Preparation(null, "System Draw requires closed compound contours")
        val cuts = listOf(bridge.pair.first, bridge.pair.second)
        val rotations = original.indices.map { original[it].cut(cuts[it], cuts[it]) }
        val firstLength = rotations[0].outline.length
        val secondLength = rotations[1].outline.length
        val firstPoint = FloatArray(2)
        val secondPoint = FloatArray(2)
        original[0].getPosTan(cuts[0], firstPoint, null)
        original[1].getPosTan(cuts[1], secondPoint, null)
        val dx = firstPoint[0] - secondPoint[0]
        val dy = firstPoint[1] - secondPoint[1]
        val bridgeLength = sqrt(dx * dx + dy * dy)
        val measure = rotations[0].outline.joined(rotations[1].outline)
        val inverted = bridge.flags and 4 != 0
        fun distance(record: CompiledKeyframe, second: Boolean): Float? {
            val component = if (second) record.second else record.first
            val value = if (second) record.pair.second else record.pair.first
            val side = indices.indexOf(component)
            val length = rotations[side].outline.length
            val isBridgePoint = circularDistance(value, cuts[side], original[side].length) <= original[side].length * 0.000001f
            val mapped = if (isBridgePoint && record.first != record.second) {
                val start = (record.pair.progress == bridge.pair.progress) != inverted
                if (start == (side == 0)) 0f else length
            } else rotations[side].map(value) ?: return null
            return mapped + if (side == 0) 0f else firstLength + bridgeLength
        }
        val pairs = records.map { record ->
            record.pair.copy(
                first = distance(record, false) ?: return Preparation(null, "Invalid system joined Draw endpoint"),
                second = distance(record, true) ?: return Preparation(null, "Invalid system joined Draw endpoint"),
            )
        }
        val markers = pairs.flatMap { listOf(Marker(it.first, it.progress, it.corner), Marker(it.second, it.progress, it.corner)) }
            .sortedBy { it.distance }
        val plan = Plan(measure, measure.length, markers, pairs = pairs, components = components,
            seams = listOf(firstLength to firstLength + bridgeLength,
                firstLength + bridgeLength + secondLength to measure.length))
        return Preparation(addNaturalCaps(plan, pairs), null)
    }

    private fun prepareInterpolated(source: JSONObject, components: IntArray): Preparation {
        val preparations = (0..2).map { prepareMaster(source, components, it, authoringMasters[it]) }
        preparations.firstOrNull { it.reason != null }?.let { return it }
        val raw = preparations.map { it.raw!! }
        if (raw.any { it.size != raw[0].size }) return Preparation(null, "Incompatible original Draw master annotations")
        val firstComponent = components.minOrNull() ?: return Preparation(null, "Empty Draw layer")
        val secondComponent = components.maxOrNull() ?: firstComponent
        val light = symbol.drawWeightCoefficients[0]
        val bold = symbol.drawWeightCoefficients[1]
        fun distance(index: Int, second: Boolean): Float? {
            val component = if (second) secondComponent else firstComponent
            val target = outlines[component]
            val targetLength = target.preciseLength
            val distances = (0..2).map { master ->
                val original = if (second) raw[master][index].second else raw[master][index].first
                val fraction = original / target.length
                val position = authoringMasters[master].outlines[component].elementAt(fraction) ?: return null
                target.distanceAt(position) ?: return null
            }
            val regular = distances[1]
            fun delta(value: Double): Double {
                val direct = value - regular
                return if (targetLength - abs(direct) >= abs(direct)) direct
                else direct + if (direct <= 0.0) targetLength else -targetLength
            }
            var value = regular + light * delta(distances[0]) + bold * delta(distances[2])
            value = when {
                value < 0.0 -> value + targetLength
                value >= targetLength -> value - targetLength
                else -> value
            }
            val fraction = (value / targetLength).toFloat()
            return if (fraction >= 0f && fraction < 1f) fraction * target.length else 0f
        }
        val mixed = raw[1].indices.map { index ->
            val regular = raw[1][index]
            val progress = regular.progress + (raw[0][index].progress - regular.progress) * light.toFloat() +
                (raw[2][index].progress - regular.progress) * bold.toFloat()
            AnnotatedPair(distance(index, false) ?: return Preparation(null, "Invalid original Draw master element"),
                distance(index, true) ?: return Preparation(null, "Invalid original Draw master element"),
                progress, regular.smartCap, regular.corner, regular.bisection)
        }
        val final = prepareMaster(source, components, 1, authoringMasters[1], mixed)
        return final.copy(plan = final.plan?.copy(components = components.toSet(), source = source, masterRaw = raw))
    }

    private fun prepareMaster(
        source: JSONObject,
        components: IntArray,
        masterIndex: Int,
        authoring: AuthoringGeometry,
        overrides: List<AnnotatedPair>? = null,
    ): Preparation {
        fun reject(reason: String) = Preparation(null, reason)
        val stores = source.optJSONObject("drawRailRoadTies")
            ?: return reject("No original Draw annotation")
        val section = stores.optJSONObject(masterIndex.toString()) ?: return reject("Missing original Draw master")
        if (components.size == 2) return preparePair(section, components, authoring, overrides)
        if (components.size != 1) return reject("Draw contour joining is not implemented")
        val component = components.single()
        if (component !in symbol.contours.indices) return reject("Invalid Draw contour index")
        val ties = section.optJSONArray("ties") ?: return reject("No original Draw ties")
        if (ties.length() == 0) return Preparation(Plan(outlines[component], outlines[component].length, emptyList()), null, emptyList())
        val measure = outlines[component]
        if (!measure.isClosed || measure.length <= 0f) return reject("Draw requires a closed contour")
        val length = measure.length
        val projected = ArrayList<SingleTie>(ties.length())
        for (index in 0 until ties.length()) {
            val tie = ties.getJSONObject(index)
            val endpoints = ArrayList<Float>(2)
            for (key in listOf("start", "end")) {
                val point = tie.optJSONArray(key) ?: return reject("Invalid original Draw endpoint")
                val position = originalPoint(point, authoring) ?: return reject("Invalid original Draw endpoint")
                endpoints += projectionDistance(authoring.contours[component].project(position), length)
            }
            if (tie.optBoolean("isArtificialEndTie") && projected.isNotEmpty()) {
                endpoints[0] = projected.first().first
                endpoints[1] = projected.first().second
            }
            val style = when (tie.optString("style", "automatic")) {
                "automatic" -> 0
                "standard" -> 1
                "corner" -> 2
                "endPoint" -> 3
                else -> return reject("Unknown original Draw tie style")
            }
            projected += SingleTie(overrides?.get(index)?.first ?: endpoints[0], overrides?.get(index)?.second ?: endpoints[1], style, tie.optBoolean("useSmartEndCap"))
        }
        val candidates = endCandidates(projected, length)
        val initial = projected.first()
        val markers = ArrayList<Marker>(projected.size * 2)
        val annotated = ArrayList<AnnotatedPair>(projected.size)
        for ((index, tie) in projected.withIndex()) {
            val progress = overrides?.get(index)?.progress ?: when {
                candidates.isEmpty() -> 0f
                index == 0 -> 0f
                ties.getJSONObject(index).optBoolean("isArtificialEndTie") -> 1f
                index in candidates -> 1f
                else -> {
                    val terminal = projected[candidates.minBy { circularDistance(tie.first, projected[it].first, length) }]
                    fun ratio(start: Float): Float {
                        val numerator = minOf(circularDistance(start, tie.first, length), circularDistance(start, tie.second, length))
                        val denominator = minOf(circularDistance(start, terminal.first, length), circularDistance(start, terminal.second, length))
                        return if (denominator == 0f) 0f else numerator / denominator
                    }
                    ((ratio(initial.first) + ratio(initial.second)) * 0.5f).coerceIn(0f, 1f)
                }
            }
            val corner = tie.style == 2
            markers += Marker(tie.first, progress, corner)
            markers += Marker(tie.second, progress, corner)
            annotated += AnnotatedPair(tie.first, tie.second, progress, tie.smartCap, corner,
                overrides?.get(index)?.bisection ?: (candidates.size >= 2 && progress == 0f))
        }
        markers.sortBy { it.distance }
        val plan = addNaturalCaps(Plan(measure, length, markers, pairs = annotated), annotated)
        for (sample in 1 until 16) {
            if (!hasValidCuts(plan, visibleRanges(plan, sample / 16f, true))) {
                return reject("Draw has a degenerate cap tangent")
            }
        }
        return Preparation(plan, null, annotated)
    }

    private fun endCandidates(ties: List<SingleTie>, length: Float): List<Int> {
        data class Endpoint(val tie: Int, val kind: Int, val distance: Float)
        val candidates = ArrayList<Int>(2)
        for (reverse in listOf(true, false)) {
            val points = ArrayList<Endpoint>((ties.size - 1) * 2)
            for (index in 1 until ties.size) {
                val tie = ties[index]
                for ((kind, distance) in listOf(tie.first, tie.second).withIndex()) {
                    val delta = if (reverse) ties.first().first - distance else distance - ties.first().first
                    points += Endpoint(index, kind, wrap(delta, length))
                }
            }
            points.sortBy { it.distance }
            for (index in 0 until points.lastIndex) {
                val first = points[index]
                val second = points[index + 1]
                if (ties[first.tie].style == 3 ||
                    (first.tie == second.tie && first.kind != second.kind &&
                        ties[first.tie].style != 1 && ties[second.tie].style != 2)
                ) {
                    if (first.tie !in candidates) candidates += first.tie
                    break
                }
            }
        }
        return candidates
    }

    private fun preparePair(section: JSONObject, components: IntArray, authoring: AuthoringGeometry, overrides: List<AnnotatedPair>?): Preparation {
        fun reject(reason: String) = Preparation(null, reason)
        val indices = components.sorted()
        if (indices.any { it !in symbol.contours.indices }) {
            return reject("Invalid compound Draw contours")
        }
        val first = outlines[indices[0]]
        val second = outlines[indices[1]]
        if (!first.isClosed || !second.isClosed || first.length <= 0f || second.length <= 0f) {
            return reject("Draw requires closed compound contours")
        }
        val firstLength = first.length
        val secondLength = second.length
        val ties = section.optJSONArray("ties") ?: return reject("Missing compound Draw ties")
        if (ties.length() < 2) return reject("Missing compound Draw endpoint ties")
        val projected = ArrayList<PairTie>(ties.length())
        for (index in 0 until ties.length()) {
            val tie = ties.getJSONObject(index)
            if (tie.optString("style", "automatic") != "automatic") {
                return reject("Draw corner geometry is not implemented")
            }
            val start = tie.optJSONArray("start")?.let { originalPoint(it, authoring) }
                ?: return reject("Invalid compound Draw endpoint")
            val end = tie.optJSONArray("end")?.let { originalPoint(it, authoring) }
                ?: return reject("Invalid compound Draw endpoint")
            val startFirstProjection = authoring.contours[indices[0]].project(start)
            val startSecondProjection = authoring.contours[indices[1]].project(start)
            val endFirstProjection = authoring.contours[indices[0]].project(end)
            val endSecondProjection = authoring.contours[indices[1]].project(end)
            val startFirst = projectionDistance(startFirstProjection, firstLength)
            val startSecond = projectionDistance(startSecondProjection, secondLength)
            val endFirst = projectionDistance(endFirstProjection, firstLength)
            val endSecond = projectionDistance(endSecondProjection, secondLength)
            val startOnFirst = startFirstProjection.squaredDistance < startSecondProjection.squaredDistance
            val endOnFirst = endFirstProjection.squaredDistance < endSecondProjection.squaredDistance
            if (startOnFirst == endOnFirst) return reject("Draw needs mixed contour annotation handling")
            projected += PairTie(
                overrides?.get(index)?.first ?: if (startOnFirst) startFirst else endFirst,
                overrides?.get(index)?.second ?: if (startOnFirst) endSecond else startSecond,
                tie.optBoolean("isArtificialEndTie"),
                tie.optBoolean("pairedPathsInverted"),
                tie.optBoolean("useSmartEndCap"),
            )
        }
        val initial = projected.first()
        for (index in projected.indices) if (projected[index].artificial) {
            projected[index] = projected[index].copy(first = initial.first, second = initial.second)
        }
        if (initial.artificial || projected.count { it.artificial } != 1) {
            return reject("Draw needs compound endpoint selection")
        }
        val firstCut = initial.first
        val secondCut = initial.second
        val firstPoint = FloatArray(2)
        val secondPoint = FloatArray(2)
        first.getPosTan(firstCut, firstPoint, null)
        second.getPosTan(secondCut, secondPoint, null)
        val dx = firstPoint[0] - secondPoint[0]
        val dy = firstPoint[1] - secondPoint[1]
        val bridge = kotlin.math.sqrt(dx * dx + dy * dy)
        val firstRotation = first.cut(firstCut, firstCut)
        val secondRotation = second.cut(secondCut, secondCut)
        val rotatedFirstLength = firstRotation.outline.length
        val rotatedSecondLength = secondRotation.outline.length
        val measure = firstRotation.outline.joined(secondRotation.outline)
        val markers = ArrayList<Marker>(projected.size * 2)
        val annotated = ArrayList<AnnotatedPair>(projected.size)
        val raw = ArrayList<AnnotatedPair>(projected.size)
        for ((index, tie) in projected.withIndex()) {
            val progress = overrides?.get(index)?.progress ?: when {
                index == 0 -> 0f
                tie.artificial -> 1f
                initial.inverted -> (1f - wrap(tie.first - initial.first, firstLength) / firstLength).coerceIn(0f, 1f)
                else -> (wrap(tie.first - initial.first, firstLength) / firstLength).coerceIn(0f, 1f)
            }
            val endpoint = index == 0 || tie.artificial
            val useFirstStart = if (initial.inverted) tie.artificial else index == 0
            val firstDistance = if (endpoint) {
                if (useFirstStart) 0f else rotatedFirstLength
            } else firstRotation.map(tie.first) ?: return reject("Invalid rotated Draw endpoint")
            val secondDistance = rotatedFirstLength + bridge + if (endpoint) {
                if (useFirstStart) rotatedSecondLength else 0f
            } else secondRotation.map(tie.second) ?: return reject("Invalid rotated Draw endpoint")
            markers += Marker(firstDistance, progress)
            markers += Marker(secondDistance, progress)
            annotated += AnnotatedPair(firstDistance, secondDistance, progress, tie.smartCap)
            raw += AnnotatedPair(tie.first, tie.second, progress, tie.smartCap)
        }
        markers.sortBy { it.distance }
        val plan = addNaturalCaps(Plan(measure, measure.length, markers, pairs = annotated,
            seams = listOf(rotatedFirstLength to rotatedFirstLength + bridge,
                rotatedFirstLength + bridge + rotatedSecondLength to measure.length)), annotated)
        for (sample in 1 until 16) {
            if (!hasValidCuts(plan, visibleRanges(plan, sample / 16f, true))) {
                return reject("Draw compound geometry has a degenerate cap tangent")
            }
        }
        return Preparation(plan, null, raw)
    }

    private fun addNaturalCaps(plan: Plan, pairs: List<AnnotatedPair>): Plan {
        val smart = pairs.filter { it.smartCap }
        fun cap(pair: AnnotatedPair?): NaturalCap? {
            pair ?: return null
            val lower = minOf(pair.first, pair.second)
            val upper = maxOf(pair.first, pair.second)
            val containsOtherTie = pairs.any {
                it !== pair && (it.first in lower..<upper || it.second in lower..<upper)
            }
            val from = if (containsOtherTie) upper else lower
            val to = if (containsOtherTie) lower + plan.length else upper
            if (to - from <= 0f) return null
            val outline = plan.measure.range(from, to)
            val first = FloatArray(2)
            val last = FloatArray(2)
            plan.measure.getPosTan(wrap(from, plan.length), first, null)
            plan.measure.getPosTan(wrap(to, plan.length), last, null)
            if (first.contentEquals(last)) return null
            return NaturalCap(outline, first, last)
        }
        return plan.copy(startCap = cap(smart.minByOrNull { it.progress }), endCap = cap(smart.maxByOrNull { it.progress }),
            adaptiveEndCap = if (smart.isNotEmpty()) cap(pairs.maxByOrNull { it.progress }) else null)
    }

    private fun prepareAttachments(source: JSONObject, components: IntArray, plan: Plan): SymbolDrawAttachments? {
        if (isSystemSource(source)) return prepareSystemAttachments(source, components, plan)
        val light = symbol.drawWeightCoefficients[0].toFloat()
        val bold = symbol.drawWeightCoefficients[1].toFloat()
        fun mix(values: FloatArray): Float = values[1] + (values[0] - values[1]) * light + (values[2] - values[1]) * bold
        return SymbolDrawAttachments.prepare(source, symbol.contours,
            interpolateAnchor = { points -> mix(FloatArray(3) { points[it].first }) to mix(FloatArray(3) { points[it].second }) },
            tieProgress = { master, component, tieId ->
                var part = (listOf(plan) + plan.additional).firstOrNull { component in it.components && hasTie(it, master, tieId) }
                if (part == null) {
                    val layer = layers.firstOrNull { node ->
                        val ties = node.source.optJSONObject("drawRailRoadTies")?.optJSONObject(master.toString())?.optJSONArray("ties")
                        ties != null && (0 until ties.length()).any { ties.getJSONObject(it).optString("id") == tieId }
                    }
                    val other = layer?.let { prepare(it.source, it.components).plan }
                    part = other?.let { (listOf(it) + it.additional).firstOrNull { hasTie(it, master, tieId) } }
                }
                val ties = part?.source?.optJSONObject("drawRailRoadTies")?.optJSONObject(master.toString())?.optJSONArray("ties")
                val index = ties?.let { (0 until it.length()).firstOrNull { index -> it.getJSONObject(index).optString("id") == tieId } }
                if (index != null) part.masterRaw.getOrNull(master)?.getOrNull(index)?.progress else null
            },
            point = { master, x, y ->
                val geometry = authoringMasters[master]
                (x * geometry.advance).toFloat() to ((1.0 - y) * symbol.capHeight).toFloat()
            },
            interpolateProgress = ::mix,
            attachmentSource = { master, metadata ->
                val all = layers.mapNotNull { it.source.optJSONObject("drawRailRoadTies")?.optJSONObject(master.toString()) }
                val ties = JSONArray()
                for (section in all) section.optJSONArray("ties")?.let { array ->
                    for (index in 0 until array.length()) ties.put(array.getJSONObject(index))
                }
                JSONObject().put("ties", ties)
            },
        )
    }

    private fun prepareSystemAttachments(source: JSONObject, components: IntArray, plan: Plan): SymbolDrawAttachments? {
        val mode = attachmentMode(source)
        val records = SymbolDrawAttachments.systemRecords(source, mode) ?: return null
        if (records.isEmpty()) return SymbolDrawAttachments(emptyList())
        val anchors = systemMaster?.optJSONObject("systemAnchors")?.optJSONArray("dynamicFrames") ?: JSONArray()
        // CoreUI rejects the entire attachment store if any referenced dynamic
        // anchor is missing, including records that were already parsed.
        if (records.any { it.anchorIndex !in 0 until anchors.length() }) return SymbolDrawAttachments(emptyList())
        fun nativeLayerIndex(layerSource: JSONObject): Int? {
            if (layerSource.has("systemRenderingMode") && layerSource.getString("systemRenderingMode") != mode) return null
            if (layerSource.has("systemNativeLayerIndex")) return layerSource.getInt("systemNativeLayerIndex")
            val selector = layerSource.optJSONObject("sourceModeStyles")?.optJSONObject(mode)
                ?.optString("selector") ?: return null
            return Regex("\\.${Regex.escape(mode)}-(\\d+)").find(selector)?.groupValues?.get(1)?.toInt()
        }
        val hasModeLayers = layers.any { nativeLayerIndex(it.source) != null }
        fun members(index: Int): List<SymbolNode.Layer> = layers.filter {
            if (hasModeLayers) nativeLayerIndex(it.source) == index else it.source.optInt("sourceLayerIndex", -1) == index
        }
        val targetLayer = nativeLayerIndex(source) ?: source.optInt("sourceLayerIndex", -1)
        val targetComponents = members(targetLayer).flatMap { it.components.asIterable() }.distinct()
        val entries = ArrayList<SymbolDrawAttachments.Entry>()
        for (record in records) {
            val sourceMembers = members(record.layerIndex)
            val sourceComponents = sourceMembers.flatMap { it.components.asIterable() }.distinct()
            val component = sourceComponents.getOrNull(record.subpathIndex) ?: return null
            val layer = sourceMembers.first { component in it.components }
            val indices = record.targets.map { targetComponents.getOrNull(it) ?: return null }.filter { it in components }.toIntArray()
            if (indices.isEmpty()) continue
            val anchor = anchors.optJSONObject(record.anchorIndex) ?: return null
            val point = anchor.getJSONArray("point")
            val parameters = anchor.getJSONArray("sourceParameters")
            val to = record.explicitTo ?: parameters.getDouble(0).toFloat()
            val from = record.explicitFrom ?: parameters.getDouble(1).toFloat()
            val other = if (layer.source === source) plan else prepare(layer.source, layer.components).plan ?: return null
            val part = (listOf(other) + other.additional).firstOrNull { component in it.components } ?: return null
            val anchorX = point.getDouble(0).toFloat()
            val anchorY = point.getDouble(1).toFloat()
            val candidate = part.pairs.filter { abs(it.progress - to) < 0.0001f }.minByOrNull { pair ->
                val a = FloatArray(2)
                val b = FloatArray(2)
                part.measure.getPosTan(pair.first, a, null)
                part.measure.getPosTan(pair.second, b, null)
                val dx = anchorX - (a[0] + b[0]) * 0.5f
                val dy = anchorY - (a[1] + b[1]) * 0.5f
                dx * dx + dy * dy
            }
            if (candidate == null && record.flags and 4 == 0) continue
            val entry = SymbolDrawAttachments.Entry(indices, component, anchorX, anchorY, from,
                Path().apply { indices.forEach { addPath(symbol.contours[it]) } },
                system = SymbolDrawAttachments.SystemAttachment(layer.source.getInt("sourceLayerIndex"), record.flags, from,
                    if (candidate == null) from else to))
            if (candidate != null) {
                val difference = (candidate.second - candidate.first) / part.length
                val preserveFirst = if (abs(difference) <= 0.5f) difference >= 0f else difference < 0f
                systemReferences[entry] = SystemReference(part,
                    if (preserveFirst) candidate.first else candidate.second,
                    if (preserveFirst) candidate.second else candidate.first, candidate.progress)
            }
            entries += entry
        }
        return SymbolDrawAttachments(entries)
    }

    private fun systemAttachmentSlice(entry: SymbolDrawAttachments.Entry, state: SystemDrawState): SymbolDrawAttachments.Slice? {
        val reference = systemReferences[entry] ?: return null
        val part = reference.plan
        val value = if (state.from == 0f) state.to else state.from
        val originalA = FloatArray(2)
        val originalB = FloatArray(2)
        part.measure.getPosTan(reference.first, originalA, null)
        part.measure.getPosTan(reference.second, originalB, null)
        if (state.from != 0f) return SymbolDrawAttachments.Slice(Matrix(),
            (originalA[0] + originalB[0]) * 0.5f, (originalA[1] + originalB[1]) * 0.5f)
        fun endpoint(distance: Float): FloatArray? {
            if (state.from != 0f || abs(value - reference.progress) < 0.0001f) {
                return FloatArray(2).also { part.measure.getPosTan(distance, it, null) }
            }
            val start = part.markers.indices.minByOrNull { abs(part.markers[it].distance - distance) } ?: return null
            for (direction in listOf(-1, 1)) {
                val neighbor = part.markers[(start + direction + part.markers.size) % part.markers.size]
                if ((neighbor.progress - part.markers[start].progress) * (value - reference.progress) <= 0f) continue
                var index = start
                repeat(part.markers.size) {
                    val next = (index + direction + part.markers.size) % part.markers.size
                    val a = part.markers[index]
                    val b = part.markers[next]
                    if (abs(a.progress - b.progress) >= 0.0001f && value in minOf(a.progress, b.progress)..maxOf(a.progress, b.progress)) {
                        var end = b.distance
                        if (direction < 0 && next > index) end -= part.length
                        if (direction > 0 && next < index) end += part.length
                        val fraction = (value - a.progress) / (b.progress - a.progress)
                        return FloatArray(2).also { part.measure.getPosTan(wrap(a.distance + (end - a.distance) * fraction, part.length), it, null) }
                    }
                    index = next
                }
            }
            return null
        }
        val currentA = endpoint(reference.first) ?: return null
        val currentB = endpoint(reference.second) ?: return null
        val sx = originalB[0] - originalA[0]
        val sy = originalB[1] - originalA[1]
        val tx = currentB[0] - currentA[0]
        val ty = currentB[1] - currentA[1]
        val divisor = sqrt((sx * sx + sy * sy) * (tx * tx + ty * ty))
        if (divisor <= 0f) return null
        val ignoreRotation = entry.system!!.flags and 2 != 0
        val a = if (ignoreRotation) 1f else (sx * tx + sy * ty) / divisor
        val b = if (ignoreRotation) 0f else (sx * ty - sy * tx) / divisor
        val matrix = Matrix().apply { setValues(floatArrayOf(
            a, -b, currentA[0] - a * originalA[0] + b * originalA[1],
            b, a, currentA[1] - b * originalA[0] - a * originalA[1],
            0f, 0f, 1f,
        )) }
        return SymbolDrawAttachments.Slice(matrix, originalA[0], originalA[1])
    }

    private fun hasTie(plan: Plan, master: Int, tieId: String): Boolean {
        val ties = plan.source?.optJSONObject("drawRailRoadTies")?.optJSONObject(master.toString())?.optJSONArray("ties") ?: return false
        return (0 until ties.length()).any { ties.getJSONObject(it).optString("id") == tieId }
    }

    private fun attachmentSlice(plan: Plan, entry: SymbolDrawAttachments.Entry, progress: Float): SymbolDrawAttachments.Slice? {
        fun crossings(part: Plan, value: Float): List<FloatArray> {
            val result = ArrayList<FloatArray>()
            for (index in part.markers.indices) {
                val first = part.markers[index]
                val next = part.markers[(index + 1) % part.markers.size]
                if (first.progress == next.progress || value !in minOf(first.progress, next.progress)..maxOf(first.progress, next.progress)) continue
                val end = next.distance + if (index == part.markers.lastIndex) part.length else 0f
                val fraction = (value - first.progress) / (next.progress - first.progress)
                val point = FloatArray(2)
                part.measure.getPosTan(wrap(first.distance + (end - first.distance) * fraction, part.length), point, null)
                result += point
            }
            return result
        }
        var selected: SymbolDrawAttachments.Slice? = null
        var nearest = Float.POSITIVE_INFINITY
        var parts = listOf(plan) + plan.additional
        if (parts.none { entry.attachingComponent in it.components }) {
            val layer = layers.firstOrNull { entry.attachingComponent in it.components }
            val other = layer?.let { prepare(it.source, it.components).plan }
            parts = other?.let { listOf(it) + it.additional }.orEmpty()
        }
        for (part in parts) {
            if (entry.attachingComponent !in part.components) continue
            val original = crossings(part, 1f)
            val current = crossings(part, progress)
            for (index in 0 until original.size - 1 step 2) {
                if (index + 1 >= current.size) continue
                val a = original[index]
                val b = original[index + 1]
                val c = current[index]
                val d = current[index + 1]
                val ox = (a[0] + b[0]) * 0.5f
                val oy = (a[1] + b[1]) * 0.5f
                val squared = (ox - entry.anchorX) * (ox - entry.anchorX) + (oy - entry.anchorY) * (oy - entry.anchorY)
                if (squared >= nearest) continue
                val sx = b[0] - a[0]
                val sy = b[1] - a[1]
                val tx = d[0] - c[0]
                val ty = d[1] - c[1]
                val divisor = sqrt((sx * sx + sy * sy) * (tx * tx + ty * ty))
                if (divisor <= 0f) continue
                val rotationA = (sx * tx + sy * ty) / divisor
                val rotationB = (sx * ty - sy * tx) / divisor
                val matrix = Matrix().apply { setValues(floatArrayOf(
                    rotationA, -rotationB, (c[0] + d[0]) * 0.5f - rotationA * ox + rotationB * oy,
                    rotationB, rotationA, (c[1] + d[1]) * 0.5f - rotationB * ox - rotationA * oy,
                    0f, 0f, 1f,
                )) }
                nearest = squared
                selected = SymbolDrawAttachments.Slice(matrix, ox, oy)
            }
        }
        return selected
    }

    private fun splitPieces(plan: Plan, progress: Float, isOn: Boolean, depth: Int = 0): List<Plan> {
        if (depth > plan.pairs.size * 2 + 4) return listOf(plan)
        fun signed(value: Float): Float = when {
            value < -plan.length * 0.5f -> value + plan.length
            value > plan.length * 0.5f -> value - plan.length
            else -> value
        }
        fun cut(from: Float, to: Float, clearIndex: Int, bisection: Boolean): Plan {
            val result = plan.measure.cut(from, to)
            val pairs = plan.pairs.mapIndexedNotNull { index, pair ->
                val first = result.map(pair.first) ?: return@mapIndexedNotNull null
                val second = result.map(pair.second) ?: return@mapIndexedNotNull null
                pair.copy(first = first, second = second,
                    corner = pair.corner && (index != clearIndex || bisection),
                    bisection = pair.bisection && (index != clearIndex || !bisection))
            }
            val markers = pairs.flatMap { listOf(Marker(it.first, it.progress, it.corner), Marker(it.second, it.progress, it.corner)) }.sortedBy { it.distance }
            return plan.copy(measure = result.outline, length = result.outline.length, markers = markers, pairs = pairs)
        }
        val bisection = plan.pairs.indexOfFirst { it.bisection }
        if (bisection >= 0) {
            val pair = plan.pairs[bisection]
            val low = if (signed(pair.second - pair.first) >= 0f) pair.first else pair.second
            val high = if (low == pair.first) pair.second else pair.first
            val first = cut(low, high, bisection, true)
            val second = cut(high, low, bisection, true)
            return splitPieces(first, progress, isOn, depth + 1) + splitPieces(second, progress, isOn, depth + 1)
        }
        for (index in 1 until plan.pairs.lastIndex) {
            val pair = plan.pairs[index]
            if (!pair.corner) continue
            val previous = plan.pairs[index - 1].progress
            val next = plan.pairs[index + 1].progress
            val lower = minOf(previous, next)
            val upper = maxOf(previous, next)
            if (progress !in lower..upper && (if (isOn) 0f else 1f) !in lower..upper) continue
            val low = if (signed(pair.second - pair.first) >= 0f) pair.first else pair.second
            val high = if (low == pair.first) pair.second else pair.first
            var leftDistance = Float.POSITIVE_INFINITY
            var rightDistance = Float.POSITIVE_INFINITY
            var left: Float? = null
            var right: Float? = null
            for ((otherIndex, other) in plan.pairs.withIndex()) {
                if (otherIndex == index) continue
                for ((point, paired) in listOf(other.first to other.second, other.second to other.first)) {
                    val toLow = signed(low - point)
                    val fromHigh = signed(point - high)
                    if (toLow >= 0f && toLow < leftDistance) { leftDistance = toLow; left = paired }
                    if (fromHigh >= 0f && fromHigh < rightDistance) { rightDistance = fromHigh; right = paired }
                }
            }
            if (left != null && right != null) {
                val first = cut(left, high, index, false)
                val second = cut(low, right, index, false)
                return splitPieces(first, progress, isOn, depth + 1) + splitPieces(second, progress, isOn, depth + 1)
            }
        }
        return listOf(plan)
    }

    private fun circularDistance(first: Float, second: Float, length: Float): Float {
        val delta = abs(first - second)
        return minOf(delta, length - delta)
    }

    private fun projectionDistance(projection: DrawContour.Projection, length: Float): Float {
        val fraction = projection.fraction.toFloat()
        return if (fraction >= 0f && fraction < 1f) fraction * length else 0f
    }

    private fun originalPoint(point: JSONArray, authoring: AuthoringGeometry): DrawPoint? {
        if (point.length() != 2) return null
        val x = point.optDouble(0, Double.NaN)
        val y = point.optDouble(1, Double.NaN)
        if (!x.isFinite() || !y.isFinite()) return null
        // SymbolPath normalizes against (0, -capHeight, advance, capHeight);
        // cglGlyph flips authoring Y when exporting the font outline.
        return DrawPoint(x * authoring.advance * authoringScale, (1.0 - y) * symbol.capHeight * authoringScale)
    }

    private fun visibleRanges(plan: Plan, progress: Float, isOn: Boolean): List<Pair<Float, Float>> {
        val intervals = ArrayList<Pair<Float, Float>>(4)
        for (index in plan.markers.indices) {
            val first = plan.markers[index]
            val next = plan.markers[(index + 1) % plan.markers.size]
            val end = next.distance + if (index == plan.markers.lastIndex) plan.length else 0f
            if (first.progress == next.progress) {
                if ((isOn && first.progress <= progress) || (!isOn && first.progress >= progress)) {
                    intervals += first.distance to end
                }
            } else {
                val minimum = minOf(first.progress, next.progress)
                val maximum = maxOf(first.progress, next.progress)
                if (if (isOn) progress >= maximum else progress <= minimum) {
                    intervals += first.distance to end
                    continue
                }
                if (if (isOn) progress <= minimum else progress >= maximum) continue
                val fraction = (progress - first.progress) / (next.progress - first.progress)
                val cut = first.distance + (end - first.distance) * fraction
                val keepStart = if (isOn) first.progress < next.progress else first.progress > next.progress
                intervals += if (keepStart) first.distance to cut else cut to end
            }
        }
        val withoutSeams = plan.seams.fold(intervals.toList()) { ranges, seam ->
            ranges.flatMap { range ->
                if (range.second <= seam.first || range.first >= seam.second) listOf(range)
                else buildList {
                    if (range.first < seam.first) add(range.first to seam.first)
                    if (range.second > seam.second) add(seam.second to range.second)
                }
            }
        }
        val merged = ArrayList<Pair<Float, Float>>(2)
        for (range in withoutSeams) {
            if (range.second <= range.first) continue
            val previous = merged.lastOrNull()
            if (previous != null && abs(previous.second - range.first) < 0.0001f) {
                merged[merged.lastIndex] = previous.first to range.second
            } else merged += range
        }
        if (merged.size >= 2) {
            val first = merged.first()
            val last = merged.last()
            if (abs(first.first + plan.length - last.second) < 0.0001f) {
                merged[0] = last.first to first.second + plan.length
                merged.removeAt(merged.lastIndex)
            }
        }
        return merged
    }

    private fun hasValidCuts(plan: Plan, ranges: List<Pair<Float, Float>>): Boolean =
        ranges.indices.all { index ->
            hasValidCut(plan, ranges[(index + 1) % ranges.size].first to ranges[index].second)
        }

    private fun hasValidCut(plan: Plan, range: Pair<Float, Float>): Boolean {
        val start = FloatArray(2)
        val end = FloatArray(2)
        val firstTangent = FloatArray(2)
        val lastTangent = FloatArray(2)
        if (!plan.measure.getPosTan(wrap(range.first, plan.length), start, firstTangent) ||
            !plan.measure.getPosTan(wrap(range.second, plan.length), end, lastTangent)
        ) return false
        return (start + end + firstTangent + lastTangent).all { it.isFinite() }
    }

    private fun appendCap(plan: Plan, range: Pair<Float, Float>, cap: DrawLineCap, result: Path, natural: NaturalCap?, adaptive: Boolean) {
        val start = FloatArray(2)
        val end = FloatArray(2)
        val startTangent = FloatArray(2)
        val endTangent = FloatArray(2)
        plan.measure.getPosTan(wrap(range.first, plan.length), start, startTangent, outgoing = true)
        val endDistance = wrap(range.second, plan.length).let { if (it == 0f) plan.length else it }
        plan.measure.getPosTan(endDistance, end, endTangent)
        if (natural != null && adaptive) {
            // A natural outline already spans the cut. Projecting its endpoints first tilts its rotation basis.
            appendNaturalCap(natural, start, end, result)
            return
        }
        val originalStart = start.copyOf()
        val useEnd = boundarySpan(plan, range.second) > boundarySpan(plan, range.first)
        val ux = if (useEnd) endTangent[0] else -startTangent[0]
        val uy = if (useEnd) endTangent[1] else -startTangent[1]
        val projection = (start[0] - end[0]) * ux + (start[1] - end[1]) * uy
        if (projection <= 0f) {
            start[0] -= ux * projection
            start[1] -= uy * projection
        } else {
            end[0] += ux * projection
            end[1] += uy * projection
        }
        result.lineTo(end[0], end[1])
        val dx = start[0] - end[0]
        val dy = start[1] - end[1]
        val radius = sqrt(dx * dx + dy * dy) * 0.5f
        val fx = ux * radius
        val fy = uy * radius
        if (natural != null) {
            appendNaturalCap(natural, start, end, result)
            result.lineTo(originalStart[0], originalStart[1])
            return
        }
        when (cap) {
            DrawLineCap.Butt -> result.lineTo(start[0], start[1])
            DrawLineCap.Square -> {
                result.lineTo(end[0] + fx, end[1] + fy)
                result.lineTo(start[0] + fx, start[1] + fy)
                result.lineTo(start[0], start[1])
            }
            DrawLineCap.Round -> {
                val cx = (start[0] + end[0]) * 0.5f
                val cy = (start[1] + end[1]) * 0.5f
                val nx = (end[0] - start[0]) * 0.5f
                val ny = (end[1] - start[1]) * 0.5f
                val k = 0.5522847498307936f
                result.cubicTo(
                    end[0] + k * fx, end[1] + k * fy,
                    cx + fx + k * nx, cy + fy + k * ny,
                    cx + fx, cy + fy,
                )
                result.cubicTo(
                    cx + fx - k * nx, cy + fy - k * ny,
                    start[0] + k * fx, start[1] + k * fy,
                    start[0], start[1],
                )
            }
        }
        result.lineTo(originalStart[0], originalStart[1])
    }

    private fun appendNaturalCap(natural: NaturalCap, start: FloatArray, end: FloatArray, result: Path) {
        val dx = start[0] - end[0]
        val dy = start[1] - end[1]
        val sx = natural.last[0] - natural.first[0]
        val sy = natural.last[1] - natural.first[1]
        val divisor = sx * sx + sy * sy
        val a = (dx * sx + dy * sy) / divisor
        val b = (dy * sx - dx * sy) / divisor
        natural.outline.appendTransformed(result, a.toDouble(), b.toDouble(),
            (end[0] - a * natural.first[0] + b * natural.first[1]).toDouble(),
            (end[1] - b * natural.first[0] - a * natural.first[1]).toDouble())
    }

    private fun boundarySpan(plan: Plan, distance: Float): Float {
        val value = wrap(distance, plan.length)
        for (index in plan.markers.indices) {
            val first = plan.markers[index].distance
            val end = plan.markers[(index + 1) % plan.markers.size].distance +
                if (index == plan.markers.lastIndex) plan.length else 0f
            val adjusted = if (value < plan.markers.first().distance) value + plan.length else value
            if (adjusted >= first && adjusted <= end) {
                if (plan.markers[index].corner || plan.markers[(index + 1) % plan.markers.size].corner) return Float.POSITIVE_INFINITY
                val span = end - first
                return minOf(span, plan.length - span)
            }
        }
        return Float.POSITIVE_INFINITY
    }

    private fun appendRange(plan: Plan, from: Float, to: Float, result: Path, moveTo: Boolean) {
        val start = wrap(from, plan.length)
        val count = to - from
        val firstEnd = minOf(plan.length, start + count)
        plan.measure.getSegment(start, firstEnd, result, moveTo)
        val remaining = count - (firstEnd - start)
        if (remaining > 0f) plan.measure.getSegment(0f, remaining, result, false)
    }

    private fun wrap(distance: Float, length: Float): Float =
        distance - floor(distance / length) * length
}
