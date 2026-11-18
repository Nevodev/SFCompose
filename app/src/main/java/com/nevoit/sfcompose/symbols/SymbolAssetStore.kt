package com.nevoit.sfcompose.symbols

import android.content.res.AssetManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    internal val packedWeight: SymbolWeight? = null,
) {
    internal val nameSet = names.toHashSet()
    val weightPositions: Map<SymbolWeight, Float> get() = weights
    val weightRange: ClosedFloatingPointRange<Float>
        get() = weights.values.min()..weights.values.max()
}

class SymbolInstance internal constructor(
    val name: String,
    val weight: SymbolWeight,
    val scale: SymbolScale,
    val weightPosition: Float,
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
    val variantId: String? = null,
    val resourceName: String = name,
    val mirroredForRightToLeft: Boolean = false,
) {
    internal val draw = SymbolDraw(this)
    internal val prepared by lazy { PreparedSymbol(this) }
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

    companion object {
        private var applicationStore: SymbolAssetStore? = null

        /** Shares decoded and prepared symbols across screens in the application process. */
        @Synchronized
        fun shared(context: Context): SymbolAssetStore = applicationStore
            ?: SymbolAssetStore(context.applicationContext.assets).also { applicationStore = it }

        /** Opens only the raw resources referenced by a generated static symbol entry. */
        fun fromResourceClosure(resources: Resources, arrayId: Int): SymbolAssetStore {
            val array = resources.obtainTypedArray(arrayId)
            val ids = try {
                IntArray(array.length()) { i -> array.getResourceId(i, 0).also { require(it != 0) } }
            } finally {
                array.recycle()
            }
            val indexId = ids.first()
            val files by lazy {
                val paths = resources.openRawResource(indexId).use(::readSymbolJson).getJSONArray("packedFiles")
                require(paths.length() == ids.size - 1) { "Symbol resource index does not match its closure" }
                List(paths.length()) { i -> paths.getString(i) to ids[i + 1] }.toMap()
            }
            return SymbolAssetStore { path ->
                val id = if (path == "symbols/index.json") indexId else
                    files[path] ?: error("Missing packed symbol asset: $path")
                resources.openRawResource(id)
            }
        }
    }

    private class Document(val json: JSONObject, val sourceChars: Int)
    private data class InstanceKey(val name: String, val resourceName: String, val position: Float,
        val scale: SymbolScale, val variant: String?, val mirrored: Boolean)
    private data class ResourceKey(val name: String, val variant: String?, val configuration: SymbolResourceConfiguration?)
    private data class Resource(val name: String, val asset: String, val variant: String?, val mirrored: Boolean)

    private val loader = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    private val catalogs = SymbolLoadCache(loader, LruCache<Unit, SymbolCatalog>(1))
    private val selections = SymbolLoadCache(loader, LruCache<Unit, SymbolResourceSelection>(1))
    private val resources = SymbolLoadCache(loader, LruCache<ResourceKey, Resource>(256))
    private val directories = SymbolLoadCache(loader, LruCache<String, Set<String>>(16))
    private val decodedAssets = SymbolLoadCache(loader, object : LruCache<String, Document>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Document): Int = maxOf(2 * 1024 * 1024, value.sourceChars * 4)
    })
    private val instances = SymbolLoadCache(loader, object : LruCache<InstanceKey, SymbolInstance>(48 * 256 * 1024) {
        override fun sizeOf(key: InstanceKey, value: SymbolInstance): Int =
            maxOf(256 * 1024, value.contourData.sumOf { it.points.size * 24 })
    })
    private val fillImages = SymbolLoadCache(loader, object : LruCache<String, SymbolFillImage>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: SymbolFillImage): Int =
            value.light.allocationByteCount + value.dark.allocationByteCount
    })

    suspend fun catalog(): SymbolCatalog = catalogs.get(Unit) { readCatalog() }

    suspend fun variants(name: String): List<SymbolResourceVariant> {
        require(name in catalog().nameSet) { "No imported SF Symbol named $name" }
        return readSymbolVariants(readJson("symbols/$name.json"))
    }

    suspend fun load(
        name: String,
        weight: SymbolWeight = SymbolWeight.Regular,
        scale: SymbolScale = SymbolScale.Medium,
        variantId: String? = null,
        configuration: SymbolResourceConfiguration? = null,
    ): SymbolInstance = loadResolved(name, weight, null, scale, variantId, configuration)

    /** The continuous source axis uses [SymbolCatalog.weightPositions] and [SymbolCatalog.weightRange]. */
    suspend fun load(
        name: String,
        weightPosition: Float,
        scale: SymbolScale = SymbolScale.Medium,
        variantId: String? = null,
        configuration: SymbolResourceConfiguration? = null,
    ): SymbolInstance = loadResolved(name, null, weightPosition, scale, variantId, configuration)

    private suspend fun loadResolved(
        name: String,
        namedWeight: SymbolWeight?,
        requestedPosition: Float?,
        scale: SymbolScale,
        variantId: String?,
        configuration: SymbolResourceConfiguration?,
    ): SymbolInstance {
        val catalog = catalog()
        val position = requestedPosition ?: catalog.weights.getValue(requireNotNull(namedWeight))
        catalog.packedWeight?.let { retained ->
            require(position == catalog.weights.getValue(retained)) { "This symbol resource closure only contains $retained" }
        }
        require(position.isFinite() && position in catalog.weightRange) {
            "Weight position must be finite and in ${catalog.weightRange}: $position"
        }
        require(name in catalog.nameSet) { "No imported SF Symbol named $name" }
        val request = ResourceKey(name, variantId, configuration.takeIf { variantId == null })
        val resource = resources.get(request) { resolveResource(request, catalog) }
        val key = InstanceKey(name, resource.name, position, scale, resource.variant, resource.mirrored)
        return instances.get(key) {
            val weight = namedWeight ?: catalog.weights.minBy { kotlin.math.abs(it.value - position) }.key
            val data = readJson(resource.asset)
            val masters = data.getJSONObject("scales").getJSONObject(scale.assetName)
                .getJSONArray("masters")
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
            val fraction = if (lowerWeight == upperWeight) 0f else
                ((position - lowerWeight) / (upperWeight - lowerWeight)).coerceIn(0f, 1f)
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
            // Binding is instance-specific: decoded source documents are shared by the bounded cache.
            val nodes = readNodes(JSONArray(tree.toString()), contours.size)
            var resolvedMasters = masters
            var selectedMaster = if (fraction >= 1f) right else left
            if (left.has("compiledLayers") || right.has("compiledLayers")) {
                selectedMaster = when {
                    fraction <= 0f -> left
                    fraction >= 1f -> right
                    else -> interpolateSystemMaster(left, right, fraction).also { selected ->
                        selected.put("weight", position.toDouble())
                        resolvedMasters = JSONArray().apply {
                            repeat(masters.length()) { put(masters.getJSONObject(it)) }
                            put(selected)
                        }
                    }
                }
                bindCompiledLayers(nodes, selectedMaster.getJSONArray("compiledLayers"))
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
                sourceMasters = resolvedMasters,
                sourceSmallMasters = data.getJSONObject("scales").getJSONObject("small").getJSONArray("masters"),
                drawWeightCoefficients = continuousDrawWeightCoefficients(catalog, scale, position),
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
                gradientMetrics = readGradientMetrics(selectedMaster),
                variantId = resource.variant,
                resourceName = resource.name,
                mirroredForRightToLeft = resource.mirrored,
            )
            instance.prepared
            instance
        }
    }

    private suspend fun resolveResource(request: ResourceKey, catalog: SymbolCatalog): Resource {
        val configuration = request.configuration
        var name = request.name
        var defaultAsset = "symbols/$name.json"
        var data = readJson(defaultAsset)
        val variants = readSymbolVariants(data)
        var variant = request.variant?.let { requested ->
            variants.singleOrNull { it.id == requested } ?: error("No system resource variant $requested for $name")
        } ?: configuration?.let { selectSymbolVariant(variants, it, catalog) }
        val selection = if (configuration != null) selections.get(Unit) { readResourceSelection() } else null
        if (configuration?.rightToLeft == true && selection != null && variant?.direction != 4 &&
            variant?.id !in selection.flippableVariants) {
            selection.rightToLeftNames[name]?.let { counterpart ->
                name = counterpart
                defaultAsset = "symbols/$name.json"
                data = readJson(defaultAsset)
                variant = selectSymbolVariant(readSymbolVariants(data), configuration, catalog)
            }
        }
        val mirrored = configuration?.rightToLeft == true && selection != null && variant?.direction != 4 &&
            variant?.id in selection.flippableVariants
        return Resource(name, variant?.asset ?: defaultAsset,
            variant?.id ?: data.optString("systemVariantId").takeIf(String::isNotEmpty), mirrored)
    }

    private suspend fun readCatalog(): SymbolCatalog {
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
            packedWeight = data.optString("packedWeight").takeIf(String::isNotEmpty)?.let(SymbolWeight::valueOf),
        )
    }

    private suspend fun readResourceSelection(): SymbolResourceSelection {
        val index = readJson("symbols/index.json")
        return index.optString("resourceSelectionAsset").takeIf(String::isNotEmpty)?.let {
            readSymbolResourceSelection(readJson(it), index.getJSONObject("source").getString("sourceCarSha256"))
        } ?: SymbolResourceSelection()
    }

    private suspend fun loadFillImages(metadata: JSONObject): Map<String, SymbolFillImage> {
        val defaults = metadata.optJSONObject("systemModeDefaults") ?: return emptyMap()
        if (defaults.optString("-sfsymbols-has-fill-images") != "true") return emptyMap()
        val names = defaults.getString("-sfsymbols-fill-image-names").trim().split(Regex("\\s+"))
        val index = readJson("symbols/fill-images/index.json").getJSONArray("images")
        return names.associateWith { name ->
            fillImages.get(name) {
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

    private suspend fun readJson(path: String): JSONObject = decodedAssets.get(path) {
        val directory = path.substringBeforeLast('/', "")
        val names = directories.get(directory) { listAssets(directory)?.toSet().orEmpty() }
        // .sfgz keeps the compressed bytes opaque to Android's automatic .gz asset expansion.
        val candidates = if (path.endsWith(".gz")) listOf(path.removeSuffix(".gz") + ".sfgz", path, path.removeSuffix(".gz"))
            else listOf("$path.sfgz", "$path.gz", path)
        val resolved = candidates.firstOrNull { it.substringAfterLast('/') in names } ?: path
        openAsset(resolved).use { stream ->
            val source = readSymbolText(stream)
            Document(restorePackedSymbolOutlines(JSONObject(source)), source.length)
        }
    }.json

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

internal fun readSymbolJson(stream: InputStream): JSONObject = JSONObject(readSymbolText(stream))

private fun readSymbolText(stream: InputStream): String {
    val buffered = stream.buffered()
    buffered.mark(2)
    val compressed = buffered.read() == 0x1f && buffered.read() == 0x8b
    buffered.reset()
    return (if (compressed) GZIPInputStream(buffered) else buffered).bufferedReader(Charsets.UTF_8).use {
        it.readText()
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
