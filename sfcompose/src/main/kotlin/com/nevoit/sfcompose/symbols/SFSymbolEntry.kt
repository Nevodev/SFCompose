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

/**
 * A generated catalog entry with a fixed weight, such as `SFSymbols.Wifi.Bold`.
 *
 * A bare entry such as `SFSymbols.Wifi` selects [SymbolWeight.Regular]. Pass the entry to
 * [SFSymbol] or [rememberSFSymbol]; its referenced data is selected by the SFCompose Gradle plugin.
 * Use `.Animated` for effects or replacements, or `.DynamicWeight` for runtime weight selection.
 */
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

/**
 * A generated `.DynamicWeight` entry for selecting a [SymbolWeight] at runtime.
 *
 * Retains all nine named weights instead of only one. All three optical scales are available.
 * Prefer a [StaticSymbol] when the weight is known at the call site to package less data.
 */
abstract class DynamicSymbol protected constructor(internal val name: String, internal val closure: Int) {
    internal var encodedSource: SymbolDataSource? = null
        private set
    protected constructor(name: String, source: SymbolDataSource) : this(name, 0) { encodedSource = source }
    internal open val sourceKey: Any get() = encodedSource ?: closure
}

/**
 * A generated fixed-weight `.Animated` entry for [AnimatedSFSymbol] and [SFSymbolReplace].
 *
 * Includes animation data where the source symbol provides it. For example,
 * `SFSymbols.Wifi.Bold.Animated` retains Bold with animation data and all three optical scales.
 * Selecting this entry alone does not start playback; configure [SymbolAnimation] on the component.
 */
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

/**
 * A generated `.DynamicWeight.Animated` entry with animation data and all nine named weights.
 *
 * Pass it to [AnimatedSFSymbol] with an explicit [SymbolWeight], or to [rememberSFSymbol]
 * for a caller-controlled clock. This retains more data than a fixed-weight `.Animated` entry.
 */
abstract class AnimatedDynamicSymbol protected constructor(internal val name: String, internal val closure: Int) {
    internal var encodedSource: SymbolDataSource? = null
        private set
    protected constructor(name: String, source: SymbolDataSource) : this(name, 0) { encodedSource = source }
    internal open val sourceKey: Any get() = encodedSource ?: closure
}

/**
 * Loads and displays a fixed-weight catalog symbol without starting an animation clock.
 *
 * Apply the SFCompose Gradle plugin in the consuming app so the referenced assets are packaged.
 * Loading follows the current locale, night mode and Compose layout direction. While loading,
 * the component reserves an empty square of [pointSize], subject to [modifier] constraints.
 *
 * ```kotlin
 * SFSymbol(
 *     SFSymbols.Wifi.Bold,
 *     contentDescription = "Wi-Fi",
 *     pointSize = 24.dp,
 * )
 * ```
 *
 * @param symbol Generated entry; a bare name selects Regular, and `.Bold` selects Bold.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param pointSize Requested slot height in dp, before internal [padding]; defaults to 32 dp.
 * @param scale Optical design variant, independent of layout size; defaults to Medium.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param displayMode Slot sizing and overflow policy; defaults to SquareOverflow without clipping.
 * @param padding Insets all four sides of the drawing area without increasing the requested slot size.
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param palette Layer colors in Palette mode; a nonempty list takes priority over the three colors.
 * @param gradients Enables source gradient styling where supported.
 * @see AnimatedSFSymbol
 */
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

/**
 * Displays a `.DynamicWeight` catalog entry with a weight selected at runtime.
 *
 * ```kotlin
 * SFSymbol(SFSymbols.Wifi.DynamicWeight, weight = currentWeight, contentDescription = "Wi-Fi")
 * ```
 *
 * Retains all nine named weights. Loading, resource selection and colors follow the fixed-weight
 * [SFSymbol] overload; no animation clock is started. An empty square is reserved while loading.
 *
 * @param symbol Generated `.DynamicWeight` entry.
 * @param weight Named weight to display; changing it loads the requested weight.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param pointSize Requested slot height in dp, before internal [padding]; defaults to 32 dp.
 * @param scale Optical design variant, independent of [pointSize]; defaults to Medium.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param displayMode Slot sizing and overflow policy; defaults to SquareOverflow without clipping.
 * @param padding Insets the drawing area on all sides within the requested slot size.
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param palette Layer colors in Palette mode; a nonempty list overrides the three colors.
 * @param gradients Enables source gradient styling where supported.
 */
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

/**
 * Loads a fixed-weight `.Animated` entry and manages its animation clock.
 *
 * The default empty [SymbolAnimation] is stationary. Configure an effect to play after loading,
 * then change [replayKey] or call [SymbolAnimationState.replay] to restart it. Resource selection
 * follows the current locale, night mode and Compose layout direction.
 *
 * ```kotlin
 * val playback = rememberSymbolAnimationState()
 * AnimatedSFSymbol(
 *     SFSymbols.Wifi.Animated,
 *     contentDescription = "Wi-Fi",
 *     animation = SymbolAnimation(effect = SymbolEffect.Bounce),
 *     state = playback,
 *     autoPlay = false,
 *     modifier = Modifier.clickable { playback.replay() },
 * )
 * ```
 *
 * Hoist [state] above a lazy item to pause on leaving composition and resume on reentry.
 * One state may be attached to only one icon at a time. Once playback has been triggered, changes
 * to the resource request, [animation], [mode], effective point size or [replayKey] restart it.
 *
 * @param symbol Generated `.Animated` entry with a fixed weight.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param pointSize Requested slot height in dp, before internal [padding]; defaults to 32 dp.
 * @param scale Optical design variant, independent of [pointSize]; defaults to Medium.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param animation Effects, repeat policy and initial delay. Draw effects require source Draw data.
 * @param replayKey Equality-based trigger; changing it restarts playback, including the start delay.
 * @param variableValue Optional Variable Color value in `0f..1f`, read during drawing.
 * @param variableDrawValue Optional Variable Draw value in `0f..1f`, read during drawing. Requires
 *   source support and is used when timed Draw has not supplied geometry for the layer.
 * @param displayMode Slot sizing and overflow policy; defaults to SquareOverflow without clipping.
 * @param padding Insets the drawing area on all sides within the requested slot size.
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param palette Layer colors in Palette mode; a nonempty list overrides the three colors.
 * @param gradients Enables source gradient styling where supported.
 * @param state Playback owner; by default it is local and playback restarts after reentry.
 * @param autoPlay Whether to trigger initial playback. With `false`, the resting symbol stays visible
 *   until an explicit trigger. Setting it to `false` does not stop playback already triggered.
 */
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
    state: SymbolAnimationState = rememberSymbolAnimationState(),
    autoPlay: Boolean = true,
) {
    PackedAnimatedSFSymbol(symbol.name, symbol.sourceKey, symbol.weight, contentDescription, modifier, pointSize, scale, tint, mode,
        animation, replayKey, variableValue, variableDrawValue, displayMode, padding, secondary, tertiary, palette, gradients, state, autoPlay)
}

/**
 * Animates a `.DynamicWeight.Animated` entry with a weight selected at runtime.
 *
 * ```kotlin
 * AnimatedSFSymbol(
 *     SFSymbols.Wifi.DynamicWeight.Animated,
 *     weight = currentWeight,
 *     contentDescription = "Wi-Fi",
 *     animation = SymbolAnimation(effect = SymbolEffect.Bounce),
 * )
 * ```
 *
 * Retains all nine named weights and animation data. Playback and resource selection follow the
 * fixed-weight [AnimatedSFSymbol] overload. After playback is triggered, changing [weight] restarts
 * it after the requested instance loads. An empty square is reserved while loading.
 *
 * @param symbol Generated `.DynamicWeight.Animated` entry.
 * @param weight Named weight to load and animate.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param pointSize Requested slot height in dp, before internal [padding]; defaults to 32 dp.
 * @param scale Optical design variant, independent of [pointSize]; defaults to Medium.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param animation Effects, repeat policy and initial delay; empty settings remain stationary.
 * @param replayKey Equality-based trigger that restarts playback, including the start delay.
 * @param variableValue Optional Variable Color value in `0f..1f`, read during drawing.
 * @param variableDrawValue Optional Variable Draw value in `0f..1f`, read during drawing. Requires
 *   source support and is used when timed Draw has not supplied geometry for the layer.
 * @param displayMode Slot sizing and overflow policy; defaults to SquareOverflow without clipping.
 * @param padding Insets the drawing area on all sides within the requested slot size.
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param palette Layer colors in Palette mode; a nonempty list overrides the three colors.
 * @param gradients Enables source gradient styling where supported.
 * @param state Playback owner. Hoist above a lazy item to resume on reentry; do not share between icons.
 * @param autoPlay Whether to trigger initial playback. With `false`, explicit replay starts playback
 *   from the resting symbol; this flag does not stop playback already triggered.
 */
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
    state: SymbolAnimationState = rememberSymbolAnimationState(),
    autoPlay: Boolean = true,
) {
    PackedAnimatedSFSymbol(symbol.name, symbol.sourceKey, weight, contentDescription, modifier, pointSize, scale, tint, mode,
        animation, replayKey, variableValue, variableDrawValue, displayMode, padding, secondary, tertiary, palette, gradients, state, autoPlay)
}

/**
 * Loads a static catalog entry for the lower-level [SFSymbol] renderer without starting a clock.
 *
 * @param symbol Generated fixed-weight entry; use its `.Animated` entry if animation data is needed.
 * @param scale Optical design variant; defaults to Medium.
 * @return Loaded instance, or `null` while the current resource request is loading. Changes to the
 *   symbol, scale, locale, night mode or Compose layout direction issue a new request.
 */
@Composable
fun rememberSFSymbol(symbol: StaticSymbol, scale: SymbolScale = SymbolScale.Medium): SymbolInstance? =
    rememberPackedSymbol(symbol.name, symbol.sourceKey, symbol.weight, scale, rememberSymbolResourceConfiguration())

/**
 * Loads a `.DynamicWeight` entry for static rendering with a runtime-selected named weight.
 *
 * @param symbol Generated `.DynamicWeight` entry retaining all nine named weights.
 * @param weight Named weight to load.
 * @param scale Optical design variant; defaults to Medium.
 * @return Loaded instance, or `null` while loading. Resource selection observes the current locale,
 *   night mode and Compose layout direction, as in the fixed-weight [rememberSFSymbol] overload.
 */
@Composable
fun rememberSFSymbol(symbol: DynamicSymbol, weight: SymbolWeight, scale: SymbolScale = SymbolScale.Medium): SymbolInstance? =
    rememberPackedSymbol(symbol.name, symbol.sourceKey, weight, scale, rememberSymbolResourceConfiguration())

/**
 * Loads a fixed-weight `.Animated` entry for caller-controlled effects or replacements.
 *
 * Pass the result to [SFSymbol] with your own `elapsedSeconds`, or to [SFSymbolReplace].
 * This function loads data only; it does not start a clock.
 *
 * @param symbol Generated fixed-weight `.Animated` entry.
 * @param scale Optical design variant; defaults to Medium.
 * @return Loaded instance, or `null` while the current symbol, scale or configuration is loading.
 *   Locale, night mode and Compose layout direction are observed automatically.
 */
@Composable
fun rememberSFSymbol(symbol: AnimatedStaticSymbol, scale: SymbolScale = SymbolScale.Medium): SymbolInstance? =
    rememberPackedSymbol(symbol.name, symbol.sourceKey, symbol.weight, scale, rememberSymbolResourceConfiguration())

/**
 * Loads a `.DynamicWeight.Animated` entry for caller-controlled animation at a named weight.
 *
 * @param symbol Generated entry retaining all nine named weights and animation data.
 * @param weight Named weight to load.
 * @param scale Optical design variant; defaults to Medium.
 * @return Loaded instance, or `null` while loading. Locale, night mode and Compose layout direction
 *   are observed automatically. No clock is started.
 */
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
    state: SymbolAnimationState,
    autoPlay: Boolean,
) {
    val playRequested = autoPlay || state.hasStarted || state.replayGeneration != 0 ||
        (state.replayKeyInitialized && state.initialReplayKey != replayKey)
    SideEffect {
        if (!state.replayKeyInitialized) {
            state.initialReplayKey = replayKey
            state.replayKeyInitialized = true
        }
        if (playRequested) state.hasStarted = true
    }
    val configuration = rememberSymbolResourceConfiguration()
    val loadKey = listOf(name, closure, weight, scale, configuration)
    val instance = rememberPackedSymbol(name, closure, weight, scale, configuration,
        initial = state.loadedSymbol.takeIf { state.loadedKey == loadKey })
    if (instance != null) SideEffect {
        state.loadedKey = loadKey
        state.loadedSymbol = instance
    }
    if (instance == null) {
        Box(modifier.size(pointSize))
    } else {
        val draw = animation.drawEffect
        var trackedModifier = modifier
        val effectivePointSize = if (draw == null) pointSize.value else {
            var measuredSlot by remember(state) { mutableStateOf(state.measuredSlot) }
            trackedModifier = trackedModifier.onSizeChanged { measuredSlot = it; state.measuredSlot = it }
            val inkRatio = remember(instance) { instance.horizontalInkRatio() }
            displayMode.renderPointSize(pointSize, padding, measuredSlot, instance.advance / instance.unitsPerEm, inkRatio)
        }
        val duration = remember(instance, animation, mode, effectivePointSize) {
            animation.duration(instance, mode, effectivePointSize.coerceAtLeast(1f))
        }
        val clock = if (playRequested) {
            rememberSymbolAnimationTime(duration, loadKey, animation, mode, effectivePointSize, replayKey,
                startDelayMillis = animation.startDelayMillis, state = state)
        } else StationarySymbolClock
        SFSymbol(instance, description, trackedModifier, mode = mode, tint = tint, pointSize = pointSize,
            rightToLeft = configuration.rightToLeft, darkTheme = configuration.darkTheme,
            effect = if (playRequested) animation.effect else SymbolEffect.None,
            drawEffect = draw.takeIf { playRequested },
            playback = animation.playback, effectLayerMode = animation.layerMode,
            effectRepeatCount = animation.repeatCount, variableAnimation = animation.variableColor.takeIf { playRequested },
            drawReversed = animation.drawReversed, drawReversesMotionGroups = animation.drawReversesMotionGroups,
            elapsedSeconds = clock, variableValue = variableValue, variableDrawValue = variableDrawValue,
            displayMode = displayMode, padding = padding,
            secondary = secondary, tertiary = tertiary, palette = palette, gradients = gradients)
    }
}

/**
 * Displays the current target and animates changes to a different fixed-weight `.Animated` entry.
 *
 * The first symbol appears immediately after loading. While a later target loads, the previous
 * symbol remains visible. Switching back during a replacement reverses the active pair; switching
 * to a third target preserves the current vector presentation before starting the new replacement.
 * Locale, night mode and Compose layout direction select resources automatically.
 *
 * ```kotlin
 * SFSymbolReplace(
 *     symbol = if (selected) SFSymbols.Person.Fill.Animated else SFSymbols.Person.Animated,
 *     contentDescription = "Person",
 *     modifier = Modifier.clickable { selected = !selected },
 * )
 * ```
 *
 * @param symbol Current target. Change this entry to trigger a replacement in either direction.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param pointSize Requested slot height in dp, before internal [padding]; defaults to 32 dp.
 * @param scale Optical design variant; defaults to Medium.
 * @param direction Replacement recipe controlling outgoing and incoming scale or Draw behavior.
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param preferMagic Uses semantic matches such as enclosures, badges and slashes when supported;
 *   otherwise uses the standard replacement. Set to `false` to always use the standard recipe.
 * @param layered Enables staggered timing for source motion groups where the recipe supports it.
 * @param displayMode Slot sizing and overflow policy; defaults to SquareOverflow without clipping.
 * @param padding Insets the drawing area on all sides within the requested slot size.
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param palette Layer colors in Palette mode; a nonempty list overrides the three colors.
 * @param gradients Enables source gradient styling where supported.
 */
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

/**
 * Automatically plays a replacement from [first] to [second] after both entries load.
 *
 * Change [replayKey] to replay the same pair. Changing either entry, [direction], [preferMagic],
 * [layered], [mode] or effective point size also restarts playback. The component reserves an empty
 * square while either entry loads.
 * Use the single-target [SFSymbolReplace] overload for interruptible state changes.
 *
 * ```kotlin
 * SFSymbolReplace(
 *     first = SFSymbols.Person.Animated,
 *     second = SFSymbols.Person.Fill.Animated,
 *     contentDescription = "Person",
 *     replayKey = tapCount,
 * )
 * ```
 *
 * @param first Outgoing fixed-weight `.Animated` entry.
 * @param second Incoming fixed-weight `.Animated` entry.
 * @param modifier Layout, interaction and semantics modifiers for the icon.
 * @param contentDescription Accessibility label, or `null` for a decorative icon.
 * @param pointSize Requested slot height in dp, before internal [padding]; defaults to 32 dp.
 * @param scale Optical design variant for both symbols; defaults to Medium.
 * @param direction Replacement recipe controlling outgoing and incoming scale or Draw behavior.
 * @param mode Layer coloring policy; defaults to Monochrome.
 * @param tint Primary color, defaulting to [LocalSFSymbolColor].
 * @param preferMagic Attempts a semantic replacement, falling back to the standard recipe.
 * @param layered Enables staggered motion-group timing where supported.
 * @param replayKey Equality-based trigger that restarts playback when changed.
 * @param displayMode Slot sizing policy shared by both symbols; defaults to SquareOverflow.
 * @param padding Insets the drawing area on all sides within the requested slot size.
 * @param secondary Second Palette color when [palette] is absent; defaults to [tint].
 * @param tertiary Third Palette color when [palette] is absent; defaults to [secondary].
 * @param palette Layer colors in Palette mode; a nonempty list overrides the three colors.
 * @param gradients Enables source gradient styling where supported.
 */
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
    initial: SymbolInstance? = null,
): SymbolInstance? {
    val resources = LocalResources.current
    val store = remember(resources, closure) { PackedStores.get(resources, closure) }
    return key(store, name, weight, scale, configuration) {
        val instance by produceState<SymbolInstance?>(initial) {
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
