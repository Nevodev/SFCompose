package com.nevoit.shrinkprototype

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.shrinkprototype.catalog.SymbolRef

fun scenarioSymbols() = emptyList<SymbolRef>()

@Composable
fun ApiExample() {
    SFSymbol(SFSymbols.Wifi.Bold, contentDescription = "Wi-Fi", pointSize = 20.dp, scale = SymbolScale.Small)
}
