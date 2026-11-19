package com.nevoit.sfcompose.symbols

internal data class PackagedSymbolKey(val name: String, val weight: String, val animated: Boolean) {
    val asset: String get() = "sfcompose/entries/$name/$weight/" + (if (animated) "animated" else "static") + ".json"
}

open class PackagedStaticSymbol protected constructor(name: String, weight: SymbolWeight) : StaticSymbol(name, weight, 0) {
    internal final override val sourceKey: Any = PackagedSymbolKey(name, weight.name, false)
    val Animated: PackagedAnimatedStaticSymbol get() = SymbolEntries.animated(name, weight)
}

open class PackagedDynamicSymbol protected constructor(name: String) : DynamicSymbol(name, 0) {
    internal final override val sourceKey: Any = PackagedSymbolKey(name, "DynamicWeight", false)
    val Animated: PackagedAnimatedDynamicSymbol get() = SymbolEntries.animatedDynamic(name)
}

class PackagedAnimatedStaticSymbol internal constructor(name: String, weight: SymbolWeight) : AnimatedStaticSymbol(name, weight, 0) {
    internal override val sourceKey: Any = PackagedSymbolKey(name, weight.name, true)
}

class PackagedAnimatedDynamicSymbol internal constructor(name: String) : AnimatedDynamicSymbol(name, 0) {
    internal override val sourceKey: Any = PackagedSymbolKey(name, "DynamicWeight", true)
}

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
