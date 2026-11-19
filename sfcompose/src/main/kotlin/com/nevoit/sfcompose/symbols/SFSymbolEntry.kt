package com.nevoit.sfcompose.symbols

import android.content.res.Resources
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

abstract class StaticSymbol protected constructor(
    internal val name: String,
    internal val weight: SymbolWeight,
    internal val closure: Int,
) {
    internal var encodedSource: SymbolDataSource? = null
        private set
    protected constructor(name: String, weight: SymbolWeight, source: SymbolDataSource) : this(name, weight, 0) {
        encodedSource = source
    }
    internal open val sourceKey: Any get() = encodedSource ?: closure
}

/** Opt-in entry whose resource closure retains all named weights for this symbol. */
abstract class DynamicSymbol protected constructor(internal val name: String, internal val closure: Int) {
    internal var encodedSource: SymbolDataSource? = null
        private set
    protected constructor(name: String, source: SymbolDataSource) : this(name, 0) { encodedSource = source }
    internal open val sourceKey: Any get() = encodedSource ?: closure
}

abstract class AnimatedStaticSymbol protected constructor(
    internal val name: String,
    internal val weight: SymbolWeight,
    internal val closure: Int,
) {
    internal var encodedSource: SymbolDataSource? = null
        private set
    protected constructor(name: String, weight: SymbolWeight, source: SymbolDataSource) : this(name, weight, 0) {
        encodedSource = source
    }
    internal open val sourceKey: Any get() = encodedSource ?: closure
}

/** Opt-in animation entry retaining all named weights for this symbol. */
abstract class AnimatedDynamicSymbol protected constructor(internal val name: String, internal val closure: Int) {
    internal var encodedSource: SymbolDataSource? = null
        private set
    protected constructor(name: String, source: SymbolDataSource) : this(name, 0) { encodedSource = source }
    internal open val sourceKey: Any get() = encodedSource ?: closure
}

@Composable
fun SFSymbol(
    symbol: StaticSymbol,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    pointSize: Dp = 32.dp,
    scale: SymbolScale = SymbolScale.Medium,
    tint: Color = LocalSFSymbolColor.current,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    padding: Dp = 0.dp,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
) {
    PackedSFSymbol(symbol.name, symbol.sourceKey, symbol.weight, contentDescription, modifier, pointSize, scale, tint, mode,
        displayMode, padding, secondary, tertiary, palette, gradients)
}

@Composable
fun SFSymbol(
    symbol: DynamicSymbol,
    weight: SymbolWeight,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    pointSize: Dp = 32.dp,
    scale: SymbolScale = SymbolScale.Medium,
    tint: Color = LocalSFSymbolColor.current,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    padding: Dp = 0.dp,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
) {
    PackedSFSymbol(symbol.name, symbol.sourceKey, weight, contentDescription, modifier, pointSize, scale, tint, mode,
        displayMode, padding, secondary, tertiary, palette, gradients)
}

@Composable
fun AnimatedSFSymbol(
    symbol: AnimatedStaticSymbol,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    pointSize: Dp = 32.dp,
    scale: SymbolScale = SymbolScale.Medium,
    tint: Color = LocalSFSymbolColor.current,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    animation: SymbolAnimation = SymbolAnimation(),
    replayKey: Any? = Unit,
    variableValue: (() -> Float)? = null,
    variableDrawValue: (() -> Float)? = null,
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    padding: Dp = 0.dp,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
) {
    PackedAnimatedSFSymbol(symbol.name, symbol.sourceKey, symbol.weight, contentDescription, modifier, pointSize, scale, tint, mode,
        animation, replayKey, variableValue, variableDrawValue, displayMode, padding, secondary, tertiary, palette, gradients)
}

@Composable
fun AnimatedSFSymbol(
    symbol: AnimatedDynamicSymbol,
    weight: SymbolWeight,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    pointSize: Dp = 32.dp,
    scale: SymbolScale = SymbolScale.Medium,
    tint: Color = LocalSFSymbolColor.current,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    animation: SymbolAnimation = SymbolAnimation(),
    replayKey: Any? = Unit,
    variableValue: (() -> Float)? = null,
    variableDrawValue: (() -> Float)? = null,
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    padding: Dp = 0.dp,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
) {
    PackedAnimatedSFSymbol(symbol.name, symbol.sourceKey, weight, contentDescription, modifier, pointSize, scale, tint, mode,
        animation, replayKey, variableValue, variableDrawValue, displayMode, padding, secondary, tertiary, palette, gradients)
}

/** Loads a generated symbol for the lower-level renderer. */
@Composable
fun rememberSFSymbol(symbol: StaticSymbol, scale: SymbolScale = SymbolScale.Medium): SymbolInstance? =
    rememberPackedSymbol(symbol.name, symbol.sourceKey, symbol.weight, scale, rememberSymbolResourceConfiguration())

@Composable
fun rememberSFSymbol(symbol: DynamicSymbol, weight: SymbolWeight, scale: SymbolScale = SymbolScale.Medium): SymbolInstance? =
    rememberPackedSymbol(symbol.name, symbol.sourceKey, weight, scale, rememberSymbolResourceConfiguration())

/** Loads the animation data for caller-controlled clocks and replacements. */
@Composable
fun rememberSFSymbol(symbol: AnimatedStaticSymbol, scale: SymbolScale = SymbolScale.Medium): SymbolInstance? =
    rememberPackedSymbol(symbol.name, symbol.sourceKey, symbol.weight, scale, rememberSymbolResourceConfiguration())

@Composable
fun rememberSFSymbol(symbol: AnimatedDynamicSymbol, weight: SymbolWeight, scale: SymbolScale = SymbolScale.Medium): SymbolInstance? =
    rememberPackedSymbol(symbol.name, symbol.sourceKey, weight, scale, rememberSymbolResourceConfiguration())

@Composable
private fun PackedSFSymbol(
    name: String, closure: Any, weight: SymbolWeight, description: String?, modifier: Modifier,
    pointSize: Dp, scale: SymbolScale, tint: Color, mode: SymbolRenderingMode,
    displayMode: SymbolDisplayMode, padding: Dp,
    secondary: Color, tertiary: Color, palette: List<Color>?, gradients: Boolean,
) {
    val configuration = rememberSymbolResourceConfiguration()
    val instance = rememberPackedSymbol(name, closure, weight, scale, configuration)
    if (instance == null) {
        Box(modifier.size(pointSize))
    } else {
        SFSymbol(instance, description, modifier, mode = mode, tint = tint, pointSize = pointSize,
            rightToLeft = configuration.rightToLeft, darkTheme = configuration.darkTheme,
            displayMode = displayMode, padding = padding,
            secondary = secondary, tertiary = tertiary, palette = palette, gradients = gradients)
    }
}

@Composable
private fun PackedAnimatedSFSymbol(
    name: String, closure: Any, weight: SymbolWeight, description: String?, modifier: Modifier,
    pointSize: Dp, scale: SymbolScale, tint: Color, mode: SymbolRenderingMode,
    animation: SymbolAnimation, replayKey: Any?, variableValue: (() -> Float)?, variableDrawValue: (() -> Float)?,
    displayMode: SymbolDisplayMode, padding: Dp,
    secondary: Color, tertiary: Color, palette: List<Color>?, gradients: Boolean,
) {
    val configuration = rememberSymbolResourceConfiguration()
    val instance = rememberPackedSymbol(name, closure, weight, scale, configuration)
    if (instance == null) {
        Box(modifier.size(pointSize))
    } else {
        val draw = animation.drawEffect
        var trackedModifier = modifier
        val effectivePointSize = if (draw == null) pointSize.value else {
            var measuredSlot by remember { mutableStateOf<IntSize?>(null) }
            trackedModifier = trackedModifier.onSizeChanged { measuredSlot = it }
            val inkRatio = remember(instance) { instance.horizontalInkRatio() }
            displayMode.renderPointSize(pointSize, padding, measuredSlot, instance.advance / instance.unitsPerEm, inkRatio)
        }
        val duration = remember(instance, animation, mode, effectivePointSize) {
            animation.duration(instance, mode, effectivePointSize.coerceAtLeast(1f))
        }
        val clock = rememberSymbolAnimationTime(duration, instance, animation, mode, replayKey,
            startDelayMillis = animation.startDelayMillis)
        SFSymbol(instance, description, trackedModifier, mode = mode, tint = tint, pointSize = pointSize,
            rightToLeft = configuration.rightToLeft, darkTheme = configuration.darkTheme,
            effect = animation.effect, drawEffect = draw,
            playback = animation.playback, effectLayerMode = animation.layerMode,
            effectRepeatCount = animation.repeatCount, variableAnimation = animation.variableColor,
            drawReversed = animation.drawReversed, drawReversesMotionGroups = animation.drawReversesMotionGroups,
            elapsedSeconds = clock, variableValue = variableValue, variableDrawValue = variableDrawValue,
            displayMode = displayMode, padding = padding,
            secondary = secondary, tertiary = tertiary, palette = palette, gradients = gradients)
    }
}

/** Shows the first loaded [symbol] and animates subsequent changes to it. */
@Composable
fun SFSymbolReplace(
    symbol: AnimatedStaticSymbol,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    pointSize: Dp = 32.dp,
    scale: SymbolScale = SymbolScale.Medium,
    direction: SymbolReplaceDirection = SymbolReplaceDirection.DownUp,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    tint: Color = LocalSFSymbolColor.current,
    preferMagic: Boolean = true,
    layered: Boolean = true,
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    padding: Dp = 0.dp,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
) {
    val configuration = rememberSymbolResourceConfiguration()
    val loaded = rememberPackedSymbol(symbol.name, symbol.sourceKey, symbol.weight, scale, configuration)
    var lastLoaded by remember { mutableStateOf<SymbolInstance?>(null) }
    if (loaded != null) SideEffect { lastLoaded = loaded }
    // Keep the outgoing image and its clock alive while the next target loads.
    val target = loaded ?: lastLoaded
    if (target == null) {
        Box(modifier.size(pointSize))
        return
    }

    val controller = rememberSymbolReplaceController(target)
    val elapsed = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(controller, target) {
        if (controller.target === target) return@LaunchedEffect
        val offset = elapsed.floatValue
        controller.replaceWith(target, offset, direction, preferMagic, layered)
        val start = withFrameNanos { it }
        while (controller.advanceTo(elapsed.floatValue)) {
            elapsed.floatValue = withFrameNanos { offset + (it - start) / 1_000_000_000f }
        }
    }
    SFSymbolReplace(controller, modifier, mode = mode, tint = tint, rightToLeft = configuration.rightToLeft,
        contentDescription = contentDescription, pointSize = pointSize, displayMode = displayMode, padding = padding,
        secondary = secondary, tertiary = tertiary, palette = palette, gradients = gradients,
        elapsedSeconds = { elapsed.floatValue })
}

/** Plays the replacement when both entries are loaded or the replay key changes. */
@Composable
fun SFSymbolReplace(
    first: AnimatedStaticSymbol,
    second: AnimatedStaticSymbol,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    pointSize: Dp = 32.dp,
    scale: SymbolScale = SymbolScale.Medium,
    direction: SymbolReplaceDirection = SymbolReplaceDirection.DownUp,
    mode: SymbolRenderingMode = SymbolRenderingMode.Monochrome,
    tint: Color = LocalSFSymbolColor.current,
    preferMagic: Boolean = true,
    layered: Boolean = true,
    replayKey: Any? = Unit,
    displayMode: SymbolDisplayMode = DefaultSymbolDisplayMode,
    padding: Dp = 0.dp,
    secondary: Color = tint,
    tertiary: Color = secondary,
    palette: List<Color>? = null,
    gradients: Boolean = false,
) {
    val configuration = rememberSymbolResourceConfiguration()
    val from = rememberPackedSymbol(first.name, first.sourceKey, first.weight, scale, configuration)
    val to = rememberPackedSymbol(second.name, second.sourceKey, second.weight, scale, configuration)
    if (from == null || to == null) {
        Box(modifier.size(pointSize))
        return
    }
    var measuredSlot by remember { mutableStateOf<IntSize?>(null) }
    val inkRatio = remember(from, to) { maxOf(from.horizontalInkRatio(), to.horizontalInkRatio()) }
    val effectivePointSize = displayMode.renderPointSize(pointSize, padding, measuredSlot,
        maxOf(from.advance / from.unitsPerEm, to.advance / to.unitsPerEm), inkRatio)
    val duration = remember(from, to, direction, preferMagic, layered, mode, effectivePointSize) {
        SymbolReplacePlan(from, to, direction, preferMagic, layered, mode,
            pointSize = effectivePointSize.coerceAtLeast(1f)).durationSeconds
    }
    val clock = rememberSymbolAnimationTime(duration, from, to, direction, preferMagic, layered, mode, replayKey)
    SFSymbolReplace(from, to, modifier.onSizeChanged { measuredSlot = it }, direction = direction,
        preferMagic = preferMagic, layered = layered, mode = mode, tint = tint, rightToLeft = configuration.rightToLeft,
        contentDescription = contentDescription, pointSize = pointSize, displayMode = displayMode, padding = padding,
        secondary = secondary, tertiary = tertiary, palette = palette, gradients = gradients,
        elapsedSeconds = clock)
}

@Composable
private fun SymbolDisplayMode.renderPointSize(
    pointSize: Dp, padding: Dp, measuredSlot: IntSize?, advanceRatio: Float, inkRatio: Float,
): Float = with(LocalDensity.current) {
    val height = measuredSlot?.height?.toFloat() ?: pointSize.roundToPx().toFloat()
    val width = measuredSlot?.width?.toFloat() ?: (height * layoutRatio(advanceRatio))
    val inset = padding.roundToPx() * 2f
    renderHeight((width - inset).coerceAtLeast(0f), (height - inset).coerceAtLeast(0f), inkRatio) / density
}

@Composable
private fun rememberPackedSymbol(
    name: String, closure: Any, weight: SymbolWeight, scale: SymbolScale, configuration: SymbolResourceConfiguration,
): SymbolInstance? {
    val resources = LocalResources.current
    val store = remember(resources, closure) { PackedStores.get(resources, closure) }
    return key(store, name, weight, scale, configuration) {
        val instance by produceState<SymbolInstance?>(null) {
            value = store.load(name, weight, scale, configuration = configuration)
        }
        instance
    }
}

private object PackedStores {
    private val stores = LruCache<Pair<Resources, Any>, SymbolAssetStore>(64)

    @Synchronized
    fun get(resources: Resources, closure: Any): SymbolAssetStore {
        val key = resources to closure
        return stores.get(key) ?: when (closure) {
            is SymbolDataSource -> SymbolAssetStore.fromDataSource(closure)
            is PackagedSymbolKey -> SymbolAssetStore.fromPackagedEntry(resources.assets, closure.asset)
            else -> SymbolAssetStore.fromResourceClosure(resources, closure as Int)
        }.also { stores.put(key, it) }
    }
}
