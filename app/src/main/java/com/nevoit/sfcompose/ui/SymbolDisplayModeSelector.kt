package com.nevoit.sfcompose.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SymbolDisplayMode

@Composable
internal fun SymbolDisplayModeSelector(value: SymbolDisplayMode, onChange: (SymbolDisplayMode) -> Unit) {
    Column {
        Text("显示模式", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in SymbolDisplayMode.entries) {
                FilterChip(mode == value, { onChange(mode) }, label = {
                    Text(when (mode) {
                        SymbolDisplayMode.SquareOverflow -> "1 · 方形可溢出"
                        SymbolDisplayMode.IntrinsicWidth -> "2 · 实际宽度"
                        SymbolDisplayMode.SquareFitWidth -> "3 · 方形适宽"
                    })
                })
            }
        }
    }
}
