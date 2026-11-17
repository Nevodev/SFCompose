package com.nevoit.sfcompose.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SFSymbolReplace
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolInstance
import com.nevoit.sfcompose.symbols.SymbolRenderingMode
import com.nevoit.sfcompose.symbols.SymbolReplaceDirection
import com.nevoit.sfcompose.symbols.SymbolReplacePlan
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.sfcompose.symbols.SymbolWeight
import com.nevoit.sfcompose.symbols.SymbolDisplayMode
import com.nevoit.sfcompose.ui.SymbolDisplayModeSelector
import kotlinx.coroutines.CancellationException
import java.util.Locale

@Composable
internal fun SymbolReplacementPreview(store: SymbolAssetStore, firstName: String, secondName: String,
    onBack: () -> Unit, onSwap: () -> Unit, modifier: Modifier = Modifier) {
    var first by remember { mutableStateOf<SymbolInstance?>(null) }
    var second by remember { mutableStateOf<SymbolInstance?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var weight by remember { mutableStateOf(SymbolWeight.Regular) }
    var scale by remember { mutableStateOf(SymbolScale.Medium) }
    var mode by remember { mutableStateOf(SymbolRenderingMode.Monochrome) }
    var displayMode by remember { mutableStateOf(SymbolDisplayMode.IntrinsicWidth) }
    var direction by remember { mutableStateOf(SymbolReplaceDirection.DownUp) }
    var magic by remember { mutableStateOf(true) }
    var layered by remember { mutableStateOf(true) }
    var looping by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var replay by remember { mutableIntStateOf(0) }
    var elapsed by remember(first, second, direction, magic, layered, mode, replay) { mutableFloatStateOf(0f) }
    LaunchedEffect(store, firstName, secondName, weight, scale) {
        first = null
        second = null
        error = null
        try {
            val loadedFirst = store.load(firstName, weight, scale)
            val loadedSecond = store.load(secondName, weight, scale)
            first = loadedFirst
            second = loadedSecond
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "符号加载失败"
        }
    }
    val duration = remember(first, second, direction, magic, layered, mode) {
        first?.let { a -> second?.let { b -> SymbolReplacePlan(a, b, direction, magic, layered, mode, pointSize = 144f).durationSeconds } }
            ?: 1f
    }.coerceAtLeast(.001f)
    LaunchedEffect(first, second, direction, magic, layered, mode, replay, looping, playing, duration) {
        if (playing && first != null && second != null) {
            val start = withFrameNanos { it }
            val offset = elapsed
            do {
                val time = withFrameNanos { offset + (it - start) / 1_000_000_000f }
                elapsed = if (looping) time % (duration + .4f) else time.coerceAtMost(duration)
            } while (looping || elapsed < duration)
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("replace-preview"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回符号库") }
            Spacer(Modifier.weight(1f))
            Text("Replace", style = MaterialTheme.typography.headlineSmall)
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A  $firstName", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("preview-source"))
                Text("B  $secondName", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("preview-target"))
                OutlinedButton(onClick = onSwap, Modifier.align(Alignment.End)) { Text("交换 A / B") }
            }
        }
        Surface(Modifier.fillMaxWidth().height(224.dp), shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val a = first
                val b = second
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                    a == null || b == null -> CircularProgressIndicator()
                    else -> SFSymbolReplace(a, b, Modifier.height(144.dp), direction, magic, layered, mode,
                        tint = MaterialTheme.colorScheme.primary, secondary = MaterialTheme.colorScheme.tertiary,
                        contentDescription = "$firstName → $secondName", elapsedSeconds = { elapsed }, displayMode = displayMode)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${String.format(Locale.ROOT, "%.2f", minOf(elapsed, duration))} / ${String.format(Locale.ROOT, "%.2f", duration)} s",
                style = MaterialTheme.typography.labelMedium)
            Text(if (elapsed >= duration && !looping) "播放完成" else if (playing) "播放中" else "已暂停",
                style = MaterialTheme.typography.labelMedium)
        }
        Slider(elapsed.coerceIn(0f, duration), { playing = false; elapsed = it }, valueRange = 0f..duration,
            modifier = Modifier.testTag("replace-time"), enabled = first != null && second != null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                if (!looping && elapsed >= duration) { elapsed = 0f; replay++; playing = true }
                else playing = !playing
            }) {
                Text(if (!looping && elapsed >= duration) "播放" else if (playing) "暂停" else "播放")
            }
            OutlinedButton(onClick = { elapsed = 0f; playing = true; replay++ }) { Text("重播") }
            FilterChip(looping, { looping = !looping }, label = { Text("循环") })
        }
        Text("替换方式", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SymbolReplaceDirection.entries.forEach { value ->
                FilterChip(direction == value, { direction = value }, label = { Text(value.name) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(magic, { magic = !magic }, label = { Text("Magic Replace") })
            FilterChip(layered, { layered = !layered }, label = { Text("按图层") })
        }
        SymbolDisplayModeSelector(displayMode) { displayMode = it }
        Text("渲染模式", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SymbolRenderingMode.entries.forEach { value ->
                FilterChip(mode == value, { mode = value }, label = { Text(value.name) })
            }
        }
        Text("字重 · ${weight.name}", style = MaterialTheme.typography.titleMedium)
        Slider(weight.ordinal.toFloat(), { weight = SymbolWeight.entries[it.toInt().coerceIn(0, SymbolWeight.entries.lastIndex)] },
            valueRange = 0f..SymbolWeight.entries.lastIndex.toFloat(), steps = SymbolWeight.entries.size - 2)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SymbolScale.entries.forEach { value -> FilterChip(scale == value, { scale = value }, label = { Text(value.name) }) }
        }
    }
}
