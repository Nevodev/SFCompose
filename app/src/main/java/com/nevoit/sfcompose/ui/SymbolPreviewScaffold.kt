package com.nevoit.sfcompose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SymbolPreviewScaffold(
    title: String,
    onBack: () -> Unit,
    tabs: List<String>,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    controlsTag: String,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
    playback: @Composable () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val scrollStates = tabs.map { rememberScrollState() }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回符号库") }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        preview()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), content = { playback() })
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, label ->
                Tab(selected = selectedTab == index, onClick = { onTabChange(index) }, text = { Text(label) },
                    modifier = Modifier.testTag("$controlsTag-tab-$index"))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollStates[selectedTab])
            .testTag(controlsTag).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = controls)
    }
}
