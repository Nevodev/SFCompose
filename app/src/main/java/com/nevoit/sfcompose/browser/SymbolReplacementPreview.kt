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
import com.nevoit.sfcompose.symbols.SymbolCatalog
import com.nevoit.sfcompose.symbols.SymbolReplaceController
import com.nevoit.sfcompose.symbols.SymbolRenderingMode
import com.nevoit.sfcompose.symbols.SymbolReplaceDirection
import com.nevoit.sfcompose.symbols.SymbolReplacePlan
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.sfcompose.symbols.SymbolWeight
import com.nevoit.sfcompose.symbols.DefaultSymbolDisplayMode
import com.nevoit.sfcompose.symbols.rememberSymbolResourceConfiguration
import com.nevoit.sfcompose.ui.SymbolDisplayModeSelector
import com.nevoit.sfcompose.ui.SymbolWeightSelector
import kotlinx.coroutines.CancellationException
import java.util.Locale

@Composable
internal fun SymbolReplacementPreview(store: SymbolAssetStore, firstName: String, secondName: String,
    onBack: () -> Unit, onSwap: () -> Unit, modifier: Modifier = Modifier) {
    var first by remember { mutableStateOf<SymbolInstance?>(null) }
    var second by remember { mutableStateOf<SymbolInstance?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var weight by remember { mutableStateOf(SymbolWeight.Regular) }
    var weightPosition by remember { mutableStateOf<Float?>(null) }
    var catalog by remember { mutableStateOf<SymbolCatalog?>(null) }
    val resourceConfiguration = rememberSymbolResourceConfiguration()
    var scale by remember { mutableStateOf(SymbolScale.Medium) }
    var mode by remember { mutableStateOf(SymbolRenderingMode.Monochrome) }
    var displayMode by remember { mutableStateOf(DefaultSymbolDisplayMode) }
    var direction by remember { mutableStateOf(SymbolReplaceDirection.DownUp) }
    var magic by remember { mutableStateOf(true) }
    var layered by remember { mutableStateOf(true) }
    var looping by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var replay by remember { mutableIntStateOf(0) }
    var interactive by remember { mutableStateOf(false) }
    var targetFirst by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<SymbolReplaceController?>(null) }
    var transitionClock by remember { mutableFloatStateOf(0f) }
    var elapsed by remember(first, second, direction, magic, layered, mode, replay) { mutableFloatStateOf(0f) }
    LaunchedEffect(store) { catalog = store.catalog() }
    LaunchedEffect(store, firstName, secondName, weight, weightPosition, scale, resourceConfiguration) {
        error = null
        try {
            suspend fun load(name: String) = weightPosition?.let { position ->
                store.load(name, weightPosition = position, scale = scale, configuration = resourceConfiguration)
            } ?: store.load(name, weight, scale, configuration = resourceConfiguration)
            val loadedFirst = load(firstName)
            val loadedSecond = load(secondName)
            first = loadedFirst
            second = loadedSecond
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "符号加载失败"
        }
    }
    LaunchedEffect(interactive, first, second, targetFirst, direction, magic, layered) {
        if (interactive) first?.let { a -> second?.let { b ->
            val active = controller ?: SymbolReplaceController(a).also { controller = it }
            active.replaceWith(if (targetFirst) a else b, transitionClock, direction, magic, layered)
        } }
    }
    LaunchedEffect(interactive, playing, controller, controller?.target, targetFirst, first, second,
        direction, magic, layered, replay) {
        if (interactive && playing && controller?.isRunning(transitionClock) == true) {
            val start = withFrameNanos { it }
            val offset = transitionClock
            do {
                transitionClock = withFrameNanos { offset + (it - start) / 1_000_000_000f }
            } while (controller?.advanceTo(transitionClock) == true)
        }
    }
    val duration = remember(first, second, direction, magic, layered, mode) {
        first?.let { a -> second?.let { b -> SymbolReplacePlan(a, b, direction, magic, layered, mode, pointSize = 144f).durationSeconds } }
            ?: 1f
    }.coerceAtLeast(.001f)
    LaunchedEffect(first, second, direction, magic, layered, mode, replay, looping, playing, duration, interactive) {
        if (!interactive && playing && first != null && second != null && (looping || elapsed < duration)) {
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
                    interactive && controller != null -> SFSymbolReplace(controller!!, Modifier.height(144.dp), mode = mode,
                        tint = MaterialTheme.colorScheme.primary, secondary = MaterialTheme.colorScheme.tertiary,
                        contentDescription = "$firstName → $secondName", elapsedSeconds = { transitionClock }, displayMode = displayMode)
                    else -> SFSymbolReplace(a, b, Modifier.height(144.dp), direction, magic, layered, mode,
                        tint = MaterialTheme.colorScheme.primary, secondary = MaterialTheme.colorScheme.tertiary,
                        contentDescription = "$firstName → $secondName", elapsedSeconds = { elapsed }, displayMode = displayMode)
                }
            }
        }
        ReplacementTimeline({ elapsed }, duration, interactive, targetFirst, { transitionClock },
            controller, playing, looping, first != null && second != null) { playing = false; elapsed = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                if (interactive) playing = !playing
                else if (!looping && elapsed >= duration) { elapsed = 0f; replay++; playing = true }
                else playing = !playing
            }) {
                ReplacementPlaybackLabel({ elapsed }, duration, interactive, looping, playing)
            }
            OutlinedButton(onClick = {
                if (interactive) first?.let { a -> second?.let { b ->
                    controller?.reset(if (targetFirst) b else a)
                    controller?.replaceWith(if (targetFirst) a else b, transitionClock, direction, magic, layered)
                    replay++
                } }
                else { elapsed = 0f; replay++ }
                playing = true
            }) { Text("重播") }
            FilterChip(looping, { looping = !looping }, enabled = !interactive, label = { Text("循环") })
            FilterChip(interactive, { interactive = !interactive; playing = true },
                modifier = Modifier.testTag("replace-interactive"), label = { Text("交互接续") })
            if (interactive) OutlinedButton(onClick = { targetFirst = !targetFirst },
                modifier = Modifier.testTag("replace-retarget")) { Text(if (targetFirst) "切换到 B" else "切换到 A") }
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
        SymbolWeightSelector(catalog, weight, weightPosition,
            onNamedWeight = { weight = it; weightPosition = null },
            onWeightPosition = { weightPosition = it })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SymbolScale.entries.forEach { value -> FilterChip(scale == value, { scale = value }, label = { Text(value.name) }) }
        }
    }
}

@Composable
private fun ReplacementTimeline(elapsed: () -> Float, duration: Float, interactive: Boolean, targetFirst: Boolean,
    transitionTime: () -> Float, controller: SymbolReplaceController?, playing: Boolean, looping: Boolean,
    loaded: Boolean, seek: (Float) -> Unit) {
    val time = elapsed()
    val clock = if (interactive) transitionTime() else 0f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (interactive) "目标 ${if (targetFirst) "A" else "B"} · ${String.format(Locale.ROOT, "%.2f", clock)} s"
            else "${String.format(Locale.ROOT, "%.2f", minOf(time, duration))} / ${String.format(Locale.ROOT, "%.2f", duration)} s",
            style = MaterialTheme.typography.labelMedium)
        Text(if (interactive) if (!playing) "已暂停" else if (controller?.isRunning(clock) == true) "播放中" else "播放完成"
            else if (time >= duration && !looping) "播放完成" else if (playing) "播放中" else "已暂停",
            style = MaterialTheme.typography.labelMedium)
    }
    Slider(time.coerceIn(0f, duration), seek, valueRange = 0f..duration,
        modifier = Modifier.testTag("replace-time"), enabled = !interactive && loaded)
}

@Composable
private fun ReplacementPlaybackLabel(elapsed: () -> Float, duration: Float, interactive: Boolean,
    looping: Boolean, playing: Boolean) {
    Text(if (!interactive && !looping && elapsed() >= duration) "播放" else if (playing) "暂停" else "播放")
}
