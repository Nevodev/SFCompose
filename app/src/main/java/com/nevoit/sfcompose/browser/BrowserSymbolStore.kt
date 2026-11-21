package com.nevoit.sfcompose.browser

import android.content.Context
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolCatalog
import com.nevoit.sfcompose.symbols.SymbolInstance
import com.nevoit.sfcompose.symbols.SymbolResourceConfiguration
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.sfcompose.symbols.SymbolWeight

internal class BrowserSymbolStore(context: Context) {
    val publicStore = SymbolAssetStore.shared(context)
    val privateStore = SymbolAssetStore.fromAssetDirectory(context.applicationContext.assets, "private")

    suspend fun catalog(): SymbolCatalog {
        val publicCatalog = publicStore.catalog()
        val privateCatalog = privateStore.catalog()
        require(publicCatalog.weights == privateCatalog.weights) { "Catalog weight axes do not match" }
        require(publicCatalog.nameSet.intersect(privateCatalog.nameSet).isEmpty()) { "Catalog names overlap" }
        return SymbolCatalog(publicCatalog.names + privateCatalog.names, publicCatalog.weights,
            publicCatalog.drawWeightCoefficients, publicCatalog.localizations, publicCatalog.appearances)
    }

    suspend fun load(name: String, weight: SymbolWeight = SymbolWeight.Regular,
        scale: SymbolScale = SymbolScale.Medium, configuration: SymbolResourceConfiguration? = null): SymbolInstance =
        source(name).load(name, weight, scale, configuration = configuration)

    suspend fun load(name: String, weightPosition: Float, scale: SymbolScale = SymbolScale.Medium,
        configuration: SymbolResourceConfiguration? = null): SymbolInstance =
        source(name).load(name, weightPosition, scale, configuration = configuration)

    private suspend fun source(name: String): SymbolAssetStore =
        if (name in privateStore.catalog().nameSet) privateStore else publicStore
}
