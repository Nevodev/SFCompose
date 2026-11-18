package com.nevoit.sfcompose.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SymbolCatalog
import com.nevoit.sfcompose.symbols.SymbolWeight
import java.util.Locale

@Composable
internal fun SymbolWeightSelector(
    catalog: SymbolCatalog?,
    namedWeight: SymbolWeight,
    weightPosition: Float?,
    onNamedWeight: (SymbolWeight) -> Unit,
    onWeightPosition: (Float) -> Unit,
) {
    val position = weightPosition ?: catalog?.weightPositions?.get(namedWeight)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("字重 · ${position?.let { String.format(Locale.ROOT, "%.2f", it) } ?: namedWeight.name}")
        if (catalog != null && position != null) {
            Slider(position, onWeightPosition, valueRange = catalog.weightRange,
                modifier = Modifier.testTag("weight-position"))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (weight in SymbolWeight.entries) {
                FilterChip(position == catalog?.weightPositions?.get(weight) ||
                    (position == null && weight == namedWeight), { onNamedWeight(weight) }, label = { Text(weight.name) })
            }
        }
    }
}
