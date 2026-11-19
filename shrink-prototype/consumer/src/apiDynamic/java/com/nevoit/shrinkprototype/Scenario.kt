package com.nevoit.shrinkprototype

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.sfcompose.symbols.SymbolWeight
import com.nevoit.shrinkprototype.catalog.SymbolRef

fun scenarioSymbols() = emptyList<SymbolRef>()

@Composable
fun ApiExample() {
    var index by remember { mutableIntStateOf(SymbolWeight.Regular.ordinal) }
    val weight = SymbolWeight.entries[index]
    Column {
        SFSymbol(SFSymbols.Wifi.DynamicWeight, weight = weight, contentDescription = "Wi-Fi")
        Button(onClick = { index = (index + 1) % SymbolWeight.entries.size }) { Text(weight.name) }
    }
}
