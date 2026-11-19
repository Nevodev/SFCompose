package com.nevoit.shrinkprototype

import androidx.compose.runtime.Composable
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.shrinkprototype.catalog.SymbolRef

fun scenarioSymbols() = emptyList<SymbolRef>()

@Composable
fun ApiExample() {
    SFSymbol(SFSymbols.Person.Fill, contentDescription = "Person")
}
