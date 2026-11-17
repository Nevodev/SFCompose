package com.nevoit.sfcompose.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.SymbolInspector
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolInstance
import com.nevoit.sfcompose.symbols.SymbolScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SymbolCatalogBrowser(modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val keyboard = LocalSoftwareKeyboardController.current
    val store = remember(assets) { SymbolAssetStore(assets) }
    var catalog by remember { mutableStateOf<SymbolBrowserCatalog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("all") }
    var inspected by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingPair by rememberSaveable { mutableStateOf(false) }
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var second by rememberSaveable { mutableStateOf<String?>(null) }
    var replacing by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(query, category) { gridState.scrollToItem(0) }
    LaunchedEffect(assets) {
        try {
            catalog = SymbolBrowserCatalog.load(assets)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "目录加载失败"
        }
    }
    BackHandler(replacing || inspected != null || choosingPair) {
        when {
            replacing -> replacing = false
            inspected != null -> inspected = null
            else -> choosingPair = false
        }
    }
    when {
        replacing && first != null && second != null -> SymbolReplacementPreview(store, first!!, second!!,
            onBack = { replacing = false }, onSwap = { val old = first; first = second; second = old }, modifier = modifier)
        inspected != null -> SymbolInspector(store, inspected!!, { inspected = null }, modifier)
        else -> Column(modifier.fillMaxSize().testTag("catalog-browser")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SF Symbols", style = MaterialTheme.typography.headlineMedium)
                    Text(catalog?.let { "${it.symbols.size} 个符号" } ?: "正在加载目录…",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { inspected = "rainbow" }) { Text("动画预览") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("catalog-search"), singleLine = true, label = { Text("搜索符号、别名或关键词") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } })
            val data = catalog
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
                }
            } else {
                val results by produceState<List<BrowserSymbol>?>(null, data, query, category) {
                    value = withContext(Dispatchers.Default) { data.search(query, category) }
                }
                BoxWithConstraints(Modifier.weight(1f)) {
                    val wide = maxWidth >= 840.dp
                    Row(Modifier.fillMaxSize()) {
                        if (wide) CategoryList(data, category, { category = it }, Modifier.width(230.dp).fillMaxHeight())
                        Column(Modifier.weight(1f)) {
                            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!wide) CategoryPicker(data, category) { category = it }
                                else Text(data.categories.firstOrNull { it.key == category }?.label().orEmpty(),
                                    style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                Text("${results?.size ?: 0} 个结果", style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.testTag("catalog-result-count"))
                                FilterChip(choosingPair, { choosingPair = !choosingPair }, label = { Text(if (choosingPair) "完成选择" else "选择替换") })
                            }
                            if (choosingPair || first != null || second != null) {
                                PairSelection(first, second, choosingPair, onChoose = { choosingPair = true },
                                    onClear = { first = null; second = null },
                                    onSwap = { val old = first; first = second; second = old },
                                    onReplace = { replacing = true })
                            }
                            val shown = results
                            if (shown == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            } else if (shown.isEmpty()) Column(Modifier.fillMaxWidth().padding(32.dp)) {
                                Text("没有匹配的符号", style = MaterialTheme.typography.titleMedium)
                                Text("试试名称、英文关键词，或切换到全部分类。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { query = ""; category = "all" }) { Text("查看全部") }
                            } else LazyVerticalGrid(GridCells.Adaptive(104.dp), state = gridState,
                                modifier = Modifier.weight(1f).fillMaxWidth().testTag("symbol-grid"),
                                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(shown, key = { it.name }) { entry ->
                                    val slot = when (entry.name) { first -> "A"; second -> "B"; else -> null }
                                    SymbolTile(store, entry.name, slot) {
                                        keyboard?.hide()
                                        if (!choosingPair) inspected = entry.name
                                        else when (entry.name) {
                                            first -> { first = second; second = null }
                                            second -> second = null
                                            else -> if (first == null) first = entry.name else second = entry.name
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPicker(catalog: SymbolBrowserCatalog, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("category-picker")) {
            Text(catalog.categories.firstOrNull { it.key == selected }?.label() ?: "分类")
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.height(440.dp).width(276.dp)) {
            catalog.categories.forEach { category ->
                DropdownMenuItem(text = { Text(category.label()) },
                    trailingIcon = { Text(catalog.categoryCounts[category.key].toString()) },
                    onClick = { onSelect(category.key); expanded = false },
                    modifier = Modifier.testTag("category-${category.key}"))
            }
        }
    }
}

@Composable
private fun CategoryList(catalog: SymbolBrowserCatalog, selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.testTag("category-list"), contentPadding = PaddingValues(8.dp)) {
        items(catalog.categories, key = { it.key }) { category ->
            Surface(shape = RoundedCornerShape(12.dp),
                color = if (category.key == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().testTag("category-${category.key}").clickable { onSelect(category.key) }
                    .semantics { this.selected = category.key == selected }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(category.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(catalog.categoryCounts[category.key].toString(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PairSelection(first: String?, second: String?, choosing: Boolean, onChoose: () -> Unit,
    onClear: () -> Unit, onSwap: () -> Unit, onReplace: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (choosing) "${if (first == null) "选择来源 A" else if (second == null) "选择目标 B" else "A → B 已就绪"} · 点按符号选择"
                else "替换组合", style = MaterialTheme.typography.labelMedium)
            Text("A  ${first ?: "未选择"}", Modifier.testTag("replace-source"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("B  ${second ?: "未选择"}", Modifier.testTag("replace-target"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onClear) { Text("清空") }
                TextButton(onClick = onSwap, enabled = first != null && second != null) { Text("交换") }
                Spacer(Modifier.weight(1f))
                if (!choosing) TextButton(onClick = onChoose) { Text("重选") }
                Button(onClick = onReplace, enabled = first != null && second != null,
                    modifier = Modifier.testTag("open-replace")) { Text("Replace") }
            }
        }
    }
}

@Composable
private fun SymbolTile(store: SymbolAssetStore, name: String, slot: String?, onClick: () -> Unit) {
    var symbol by remember(name) { mutableStateOf<SymbolInstance?>(null) }
    var failed by remember(name) { mutableStateOf(false) }
    LaunchedEffect(store, name) {
        try {
            symbol = store.load(name, scale = SymbolScale.Small)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = if (slot != null) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (slot != null) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().testTag("symbol-$name").clickable(onClick = onClick)
            .semantics { contentDescription = "选择 $name"; selected = slot != null }) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.height(56.dp).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                symbol?.let { SFSymbol(it, null, Modifier.height(36.dp).testTag("glyph-$name"), tint = MaterialTheme.colorScheme.onSurface) }
                if (symbol == null) {
                    if (failed) Text("—") else CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.dp)
                }
                if (slot != null) Text(slot, Modifier.align(Alignment.TopEnd), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(name, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
