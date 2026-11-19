package com.nevoit.shrinkprototype

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.shrinkprototype.catalog.SymbolRef

fun scenarioSymbols() = emptyList<SymbolRef>()

@Composable
fun ApiExample() {
    Column {
        SFSymbol(SFSymbols.Wifi, contentDescription = "Wi-Fi")
        SFSymbol(SFSymbols.Zero.Circle.Ar, contentDescription = "Zero")
        SFSymbol(SFSymbols.Airtag.Radiowaves.Forward.Fill, contentDescription = "AirTag")
    }
}
