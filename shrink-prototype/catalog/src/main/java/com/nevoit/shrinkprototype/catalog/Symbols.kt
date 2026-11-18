package com.nevoit.shrinkprototype.catalog

data class PackedSymbolRef(val name: String, val weight: String, val resources: Int)

data class SymbolRef(
    val name: String,
    val resources: Int,
    val weight: String,
    val scale: String,
    val filled: Boolean,
    val full: Boolean = false,
)

object SymbolFamilies {
    val Wifi get() = SymbolRef("wifi", R.array.full_wifi, "Regular", "Small", false, full = true)
    val WifiRegular get() = SymbolRef("wifi", R.array.family_wifi_regular, "Regular", "Small", false, full = true)
}

object SFSymbols {
    val Wifi get() = SymbolRef("wifi", R.array.symbol_wifi, "Regular", "Medium", false)
    val Localized get() = SymbolRef("0.circle.ar", R.array.symbol_localized, "Regular", "Medium", false)
    val PersonFill get() = SymbolRef("person.fill", R.array.symbol_person, "Regular", "Medium", true)
}
