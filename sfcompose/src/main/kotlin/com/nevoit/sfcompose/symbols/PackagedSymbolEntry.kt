package com.nevoit.sfcompose.symbols

internal data class PackagedSymbolKey(val name: String, val weight: String, val animated: Boolean) {
    val asset: String get() = "sfcompose/entries/$name/$weight/" + (if (animated) "animated" else "static") + ".json"
}

/**
 * Base for fixed-weight entries in the generated build-plugin catalog.
 *
 * Use a generated accessor such as `SFSymbols.Wifi.Bold` with [SFSymbol]. Consumers normally do not
 * subclass this type; the build plugin discovers generated references to package their assets.
 */
open class PackagedStaticSymbol protected constructor(name: String, weight: SymbolWeight) : StaticSymbol(name, weight, 0) {
    internal final override val sourceKey: Any = PackagedSymbolKey(name, weight.name, false)
    /** This symbol and weight with animation data for [AnimatedSFSymbol] or [SFSymbolReplace]. */
    val Animated: PackagedAnimatedStaticSymbol get() = SymbolEntries.animated(name, weight)
}

/** Generated `.DynamicWeight` entry retaining all named weights for runtime weight selection. */
open class PackagedDynamicSymbol protected constructor(name: String) : DynamicSymbol(name, 0) {
    internal final override val sourceKey: Any = PackagedSymbolKey(name, "DynamicWeight", false)
    /** All named weights with animation data for [AnimatedSFSymbol] and [rememberSFSymbol]. */
    val Animated: PackagedAnimatedDynamicSymbol get() = SymbolEntries.animatedDynamic(name)
}

/** Fixed-weight animation entry obtained through a generated symbol's `.Animated` accessor. */
class PackagedAnimatedStaticSymbol internal constructor(name: String, weight: SymbolWeight) : AnimatedStaticSymbol(name, weight, 0) {
    internal override val sourceKey: Any = PackagedSymbolKey(name, weight.name, true)
}

/** Animation entry obtained through `.DynamicWeight.Animated`, retaining all named weights. */
class PackagedAnimatedDynamicSymbol internal constructor(name: String) : AnimatedDynamicSymbol(name, 0) {
    internal override val sourceKey: Any = PackagedSymbolKey(name, "DynamicWeight", true)
}

/** Runtime factories used by generated catalog accessors; consumers use the generated entries. */
object SymbolEntries {
    @JvmStatic @JvmName("fixed")
    internal fun fixed(name: String, weight: SymbolWeight): PackagedStaticSymbol = Fixed(name, weight)
    @JvmStatic @JvmName("dynamic")
    internal fun dynamic(name: String): PackagedDynamicSymbol = Dynamic(name)
    internal fun animated(name: String, weight: SymbolWeight): PackagedAnimatedStaticSymbol = PackagedAnimatedStaticSymbol(name, weight)
    internal fun animatedDynamic(name: String): PackagedAnimatedDynamicSymbol = PackagedAnimatedDynamicSymbol(name)

    private class Fixed(name: String, weight: SymbolWeight) : PackagedStaticSymbol(name, weight)
    private class Dynamic(name: String) : PackagedDynamicSymbol(name)
}
