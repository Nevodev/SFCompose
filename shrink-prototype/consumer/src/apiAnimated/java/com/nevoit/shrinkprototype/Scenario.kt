package com.nevoit.shrinkprototype

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.AnimatedSFSymbol
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.sfcompose.symbols.SymbolAnimation
import com.nevoit.sfcompose.symbols.SymbolDrawEffect
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.shrinkprototype.catalog.SymbolRef

fun scenarioSymbols() = emptyList<SymbolRef>()

@Composable
fun ApiExample() {
    AnimatedSFSymbol(SFSymbols.Wifi.Bold.Animated, contentDescription = "Wi-Fi", pointSize = 20.dp,
        scale = SymbolScale.Small, animation = SymbolAnimation(drawEffect = SymbolDrawEffect.On))
}
