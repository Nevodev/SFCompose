package com.nevoit.sfcompose.symbols

import android.content.res.AssetManager
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.GZIPInputStream

enum class SymbolWeight { Ultralight, Thin, Light, Regular, Medium, Semibold, Bold, Heavy, Black }

enum class SymbolScale(val assetName: String) { Small("small"), Medium("medium"), Large("large") }

enum class SymbolRenderingMode { Monochrome, Hierarchical, Palette, Multicolor }

class SymbolResourceVariant internal constructor(
    val id: String,
    val appearance: Int,
    val localization: Int,
    val direction: Int,
    internal val asset: String?,
    val localeTag: String?,
    val appearanceName: String?,
)

internal class SymbolFillImage(val light: Bitmap, val dark: Bitmap)

class SymbolCatalog internal constructor(
    val names: List<String>,
    internal val weights: Map<SymbolWeight, Float>,
    internal val drawWeightCoefficients: JSONObject,
    val localizations: Map<String, Int> = emptyMap(),
    val appearances: Map<String, Int> = emptyMap(),
)

class SymbolInstance internal constructor(
    val name: String,
    val weight: SymbolWeight,
    val scale: SymbolScale,
    internal val weightPosition: Float,
    val advance: Float,
    internal val capHeight: Float,
    internal val unitsPerEm: Float,
    internal val contours: List<Path>,
    internal val contourData: List<SymbolContourData>,
    internal val sourceMasters: JSONArray,
    internal val sourceSmallMasters: JSONArray,
    internal val drawWeightCoefficients: DoubleArray,
    internal val ultralightWeightPosition: Float,
    internal val bounds: RectF,
    internal val nodes: List<SymbolNode>,
    internal val metadata: JSONObject,
    internal val metricCenterX: Float = advance * 0.5f,
    internal val metricCenterY: Float = capHeight * 0.5f,
    internal val rotationAnchorPoint: Pair<Float, Float>? = null,
    internal val fillImages: Map<String, SymbolFillImage> = emptyMap(),
    internal val gradientMetrics: SymbolGradientMetrics? = null,
) {
    internal val draw = SymbolDraw(this)
    val drawUnsupportedReasons: List<String> by lazy {
        buildList {
            fun visit(nodes: List<SymbolNode>) {
                for (node in nodes) when (node) {
                    is SymbolNode.Layer -> draw.unsupportedReason(node.source, node.components)?.let { add(it) }
                    is SymbolNode.Group -> visit(node.children)
                }
            }
            visit(nodes)
        }.distinct()
    }
    val nativeDrawFallbackReasons: List<String> by lazy {
        buildList {
            fun visit(nodes: List<SymbolNode>) {
                for (node in nodes) when (node) {
                    is SymbolNode.Layer -> draw.nativeFallbackReason(node.source)?.let { add(it) }
                    is SymbolNode.Group -> visit(node.children)
                }
            }
            visit(nodes)
        }.distinct()
    }
}

internal class SymbolContourData(val verbs: String, val points: DoubleArray)

internal sealed interface SymbolNode {
    val source: JSONObject

    class Layer(
        val components: IntArray,
        override val source: JSONObject,
    ) : SymbolNode

    class Group(
        val children: List<SymbolNode>,
        override val source: JSONObject,
    ) : SymbolNode
}

/** Loads one symbol at a time; font decoding and extraction happen in the offline importer. */
class SymbolAssetStore private constructor(
    private val openAsset: (String) -> InputStream,
    private val listAssets: (String) -> Array<String>?,
) {
    constructor(assets: AssetManager) : this({ assets.open(it) }, { assets.list(it) })

    internal constructor(openAsset: (String) -> InputStream) : this(openAsset, { null })

    private val mutex = Mutex()
    private var loadedCatalog: SymbolCatalog? = null
    private val instances = LruCache<String, SymbolInstance>(48)
    private val assetDirectoryNames = HashMap<String, Set<String>>()
    private val fillImages = HashMap<String, SymbolFillImage>()

    suspend fun catalog(): SymbolCatalog = withContext(Dispatchers.IO) {
        mutex.withLock { readCatalog() }
    }

    suspend fun variants(name: String): List<SymbolResourceVariant> = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(name in readCatalog().names) { "No imported SF Symbol named $name" }
            readSymbolVariants(readJson("symbols/$name.json"))
        }
    }

    suspend fun load(
        name: String,
        weight: SymbolWeight = SymbolWeight.Regular,
        scale: SymbolScale = SymbolScale.Medium,
        variantId: String? = null,
    ): SymbolInstance = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "$name/${weight.name}/${scale.name}/$variantId"
            instances.get(key)?.let { return@withLock it }
            val catalog = readCatalog()
            require(name in catalog.names) { "No imported SF Symbol named $name" }
            val defaultData = readJson("symbols/$name.json")
            val variant = variantId?.let { requested ->
                readSymbolVariants(defaultData).singleOrNull { it.id == requested }
                    ?: error("No system resource variant $requested for $name")
            }
            val data = variant?.asset?.let(::readJson) ?: defaultData
            val masters = data.getJSONObject("scales").getJSONObject(scale.assetName)
                .getJSONArray("masters")
            val position = catalog.weights.getValue(weight)
            var left = masters.getJSONObject(0)
            var right = masters.getJSONObject(masters.length() - 1)
            for (index in 0 until masters.length() - 1) {
                val lower = masters.getJSONObject(index)
                val upper = masters.getJSONObject(index + 1)
                if (position <= upper.getDouble("weight")) {
                    left = lower
                    right = upper
                    break
                }
            }
            val lowerWeight = left.getDouble("weight").toFloat()
            val upperWeight = right.getDouble("weight").toFloat()
            val fraction = ((position - lowerWeight) / (upperWeight - lowerWeight)).coerceIn(0f, 1f)
            val lowerContours = left.getJSONArray("contours")
            val upperContours = right.getJSONArray("contours")
            require(lowerContours.length() == upperContours.length()) { "Incompatible contour masters: $name" }
            val bounds = RectF()
            val temporaryBounds = RectF()
            val contours = List(lowerContours.length()) { index ->
                interpolateContour(lowerContours.getJSONObject(index), upperContours.getJSONObject(index), fraction)
                    .also { path ->
                        path.computeBounds(temporaryBounds, true)
                        if (index == 0) bounds.set(temporaryBounds) else bounds.union(temporaryBounds)
                    }
            }
            val contourData = List(lowerContours.length()) { index ->
                val lower = lowerContours.getJSONObject(index)
                val upper = upperContours.getJSONObject(index)
                val first = lower.getJSONArray("points")
                val second = upper.getJSONArray("points")
                SymbolContourData(lower.getString("verbs"), DoubleArray(first.length()) { coordinate ->
                    val start = first.getDouble(coordinate)
                    start + (second.getDouble(coordinate) - start) * fraction
                })
            }
            val tree = data.optJSONArray("layerTree")
                ?: data.optJSONObject("metadata")?.getJSONArray("layerTree")
                ?: error("Missing original layer tree: $name")
            val nodes = readNodes(tree, contours.size)
            if (left.has("compiledLayers") || right.has("compiledLayers")) {
                val selected = when {
                    fraction <= 0f -> left
                    fraction >= 1f -> right
                    else -> error("System assets require an exact named-weight sample: $name/$weight")
                }
                bindCompiledLayers(nodes, selected.getJSONArray("compiledLayers"))
            }
            val instance = SymbolInstance(
                name = name,
                weight = weight,
                scale = scale,
                weightPosition = position,
                advance = lerp(left.getDouble("advance").toFloat(), right.getDouble("advance").toFloat(), fraction),
                capHeight = data.getDouble("capHeight").toFloat(),
                unitsPerEm = data.getDouble("upm").toFloat(),
                contours = contours,
                contourData = contourData,
                sourceMasters = masters,
                sourceSmallMasters = data.getJSONObject("scales").getJSONObject("small").getJSONArray("masters"),
                drawWeightCoefficients = catalog.drawWeightCoefficients.getJSONArray(scale.assetName)
                    .getJSONArray(weight.ordinal).let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) },
                ultralightWeightPosition = catalog.weights.getValue(SymbolWeight.Ultralight),
                bounds = bounds,
                nodes = nodes,
                metadata = data.optJSONObject("metadata") ?: data,
                metricCenterX = lerp(left.optDouble("metricCenterX", left.getDouble("advance") * 0.5).toFloat(),
                    right.optDouble("metricCenterX", right.getDouble("advance") * 0.5).toFloat(), fraction),
                metricCenterY = lerp(left.optDouble("metricCenterY", data.getDouble("capHeight") * 0.5).toFloat(),
                    right.optDouble("metricCenterY", data.getDouble("capHeight") * 0.5).toFloat(), fraction),
                rotationAnchorPoint = interpolateAnchor(left, right, fraction, "rotation"),
                fillImages = loadFillImages(data.optJSONObject("metadata") ?: data),
                gradientMetrics = readGradientMetrics(if (fraction >= 1f) right else left),
            )
            instance.drawUnsupportedReasons
            instances.put(key, instance)
            instance
        }
    }

    private fun readCatalog(): SymbolCatalog {
        loadedCatalog?.let { return it }
        val data = readJson("symbols/index.json")
        require(data.getInt("formatVersion") == 1) { "Unsupported symbol asset format" }
        val names = data.getJSONArray("symbols")
        val weights = data.getJSONObject("weights")
        return SymbolCatalog(
            names = List(names.length()) { names.getString(it) },
            weights = SymbolWeight.entries.associateWith { weight ->
                val key = weights.keys().asSequence().first { it.equals(weight.name, ignoreCase = true) }
                weights.getDouble(key).toFloat()
            },
            drawWeightCoefficients = data.getJSONObject("drawWeightCoefficients"),
            localizations = readIdentifiers(data.optJSONObject("source")?.optJSONObject("localizations")),
            appearances = readIdentifiers(data.optJSONObject("source")?.optJSONObject("appearances")),
        ).also { loadedCatalog = it }
    }

    private fun loadFillImages(metadata: JSONObject): Map<String, SymbolFillImage> {
        val defaults = metadata.optJSONObject("systemModeDefaults") ?: return emptyMap()
        if (defaults.optString("-sfsymbols-has-fill-images") != "true") return emptyMap()
        val names = defaults.getString("-sfsymbols-fill-image-names").trim().split(Regex("\\s+"))
        val index = readJson("symbols/fill-images/index.json").getJSONArray("images")
        return names.associateWith { name ->
            fillImages.getOrPut(name) {
                fun read(appearance: Int): Bitmap {
                    val entry = (0 until index.length()).map(index::getJSONObject).single {
                        it.getString("name") == name && it.getInt("appearance") == appearance
                    }
                    return openAsset(entry.getString("asset")).use { stream ->
                        requireNotNull(BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                            inScaled = false
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        })) { "Unable to decode system fill image $name" }
                    }
                }
                SymbolFillImage(read(0), read(1))
            }
        }
    }

    private fun readJson(path: String): JSONObject {
        val directory = path.substringBeforeLast('/', "")
        val names = assetDirectoryNames.getOrPut(directory) { listAssets(directory)?.toSet().orEmpty() }
        // .sfgz keeps the compressed bytes opaque to Android's automatic .gz asset expansion.
        val candidates = if (path.endsWith(".gz")) listOf(path.removeSuffix(".gz") + ".sfgz", path, path.removeSuffix(".gz"))
            else listOf("$path.sfgz", "$path.gz", path)
        val resolved = candidates.firstOrNull { it.substringAfterLast('/') in names } ?: path
        return openAsset(resolved).use(::readSymbolJson)
    }

    private fun readNodes(nodes: JSONArray, contourCount: Int): List<SymbolNode> = List(nodes.length()) { index ->
        val source = nodes.getJSONObject(index)
        when (source.getString("type")) {
            "layer" -> {
                val array = source.getJSONArray("components")
                val components = IntArray(array.length()) { array.getInt(it) }
                SymbolNode.Layer(components, source)
            }
            "group", "shapeGroup" -> SymbolNode.Group(readNodes(source.getJSONArray("children"), contourCount), source)
            else -> error("Unsupported original layer node: ${source.getString("type")}")
        }
    }

    private fun bindCompiledLayers(nodes: List<SymbolNode>, layers: JSONArray) {
        for (node in nodes) when (node) {
            is SymbolNode.Layer -> {
                val index = node.source.getInt("sourceLayerIndex")
                val compiled = layers.getJSONObject(index)
                node.source.put("systemClipStrokeKeyframes", compiled.getJSONArray("clipStrokeKeyframes"))
                compiled.optJSONArray("clipStrokeKeyframeSets")?.let { node.source.put("systemClipStrokeKeyframeSets", it) }
                compiled.optJSONObject("systemAttachmentSource")?.let {
                    node.source.put("systemAttachmentSource", it)
                }
                if (!node.source.has("sourceModeStyles")) {
                    compiled.optJSONObject("sourceModeStyles")?.let { node.source.put("sourceModeStyles", it) }
                }
            }
            is SymbolNode.Group -> bindCompiledLayers(node.children, layers)
        }
    }
}

internal fun readSymbolJson(stream: InputStream): JSONObject {
    val buffered = stream.buffered()
    buffered.mark(2)
    val compressed = buffered.read() == 0x1f && buffered.read() == 0x8b
    buffered.reset()
    return (if (compressed) GZIPInputStream(buffered) else buffered).bufferedReader(Charsets.UTF_8).use {
        JSONObject(it.readText())
    }
}

internal fun readSymbolVariants(data: JSONObject): List<SymbolResourceVariant> {
    val variants = data.optJSONArray("systemVariants") ?: return emptyList()
    return List(variants.length()) { index ->
        val variant = variants.getJSONObject(index)
        SymbolResourceVariant(
            id = variant.getString("id"),
            appearance = variant.getInt("appearance"),
            localization = variant.getInt("localization"),
            direction = variant.getInt("direction"),
            asset = if (variant.isNull("asset")) null else variant.getString("asset"),
            localeTag = if (variant.isNull("localeTag")) null else variant.getString("localeTag"),
            appearanceName = if (variant.isNull("appearanceName")) null else variant.getString("appearanceName"),
        )
    }.also { require(it.map(SymbolResourceVariant::id).distinct().size == it.size) { "Duplicate system resource variant" } }
}

private fun readIdentifiers(data: JSONObject?): Map<String, Int> {
    if (data == null) return emptyMap()
    return data.keys().asSequence().associateWith(data::getInt)
}

private fun readGradientMetrics(master: JSONObject): SymbolGradientMetrics? {
    val canvas = master.optJSONObject("systemSampling")?.optJSONArray("nativeCanvasSize") ?: return null
    val transform = master.getJSONArray("systemTransform")
    return SymbolGradientMetrics(canvas.getDouble(0), canvas.getDouble(1),
        master.getJSONObject("systemMetrics").getDouble("standardPointSize"), DoubleArray(6, transform::getDouble))
}

private fun interpolateContour(lower: JSONObject, upper: JSONObject, fraction: Float): Path {
    val verbs = lower.getString("verbs")
    require(verbs == upper.getString("verbs")) { "Incompatible path commands across font masters" }
    val first = lower.getJSONArray("points")
    val second = upper.getJSONArray("points")
    require(first.length() == second.length()) { "Incompatible path coordinates across font masters" }
    var cursor = 0
    fun coordinate(): Float {
        val index = cursor++
        return lerp(first.getDouble(index).toFloat(), second.getDouble(index).toFloat(), fraction)
    }
    return Path().apply {
        for (verb in verbs) {
            when (verb) {
                'M' -> moveTo(coordinate(), coordinate())
                'L' -> lineTo(coordinate(), coordinate())
                'Q' -> quadTo(coordinate(), coordinate(), coordinate(), coordinate())
                'C' -> cubicTo(coordinate(), coordinate(), coordinate(), coordinate(), coordinate(), coordinate())
                'Z' -> close()
                else -> error("Unsupported contour verb: $verb")
            }
        }
        require(cursor == first.length()) { "Unexpected trailing path coordinates" }
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

private fun interpolateAnchor(left: JSONObject, right: JSONObject, fraction: Float, kind: String): Pair<Float, Float>? {
    val first = left.optJSONObject("systemAnchors")?.optJSONArray(kind) ?: return null
    val second = right.optJSONObject("systemAnchors")?.optJSONArray(kind) ?: return null
    return lerp(first.getDouble(0).toFloat(), second.getDouble(0).toFloat(), fraction) to
        lerp(first.getDouble(1).toFloat(), second.getDouble(1).toFloat(), fraction)
}
