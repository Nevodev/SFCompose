package com.nevoit.shrinkprototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolInstance
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.sfcompose.symbols.SymbolWeight
import com.nevoit.sfcompose.symbols.rememberSymbolResourceConfiguration
import com.nevoit.shrinkprototype.catalog.PackedSymbolRef

class PackedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column {
                packedSymbols().forEach { entry ->
                    PackedPreview(entry)
                }
            }
        }
    }
}

@Composable
private fun PackedPreview(entry: PackedSymbolRef) {
    val resources = LocalContext.current.resources
    val store = remember(resources, entry) { SymbolAssetStore.fromResourceClosure(resources, entry.resources) }
    val configuration = rememberSymbolResourceConfiguration()
    Row {
        SymbolScale.entries.forEach { scale ->
            val symbol by produceState<SymbolInstance?>(null, store, scale, configuration) {
                value = store.load(entry.name, weight = SymbolWeight.valueOf(entry.weight), scale = scale,
                    configuration = configuration)
            }
            symbol?.let { SFSymbol(it, null, pointSize = 20.dp) }
        }
        Text(entry.name)
    }
}
