package com.nevoit.sfcompose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbolReplace
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolCatalog
import com.nevoit.sfcompose.symbols.SymbolEffect
import com.nevoit.sfcompose.symbols.SymbolInstance
import com.nevoit.sfcompose.symbols.SymbolRenderingMode
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.sfcompose.symbols.SymbolWeight
import com.nevoit.sfcompose.symbols.SymbolDrawEffect
import com.nevoit.sfcompose.symbols.SymbolEffectPlayback
import com.nevoit.sfcompose.symbols.SymbolEffectLayerMode
import com.nevoit.sfcompose.symbols.SymbolReplaceDirection
import com.nevoit.sfcompose.symbols.SymbolVariableAnimation
import com.nevoit.sfcompose.symbols.SymbolDrawTiming
import com.nevoit.sfcompose.symbols.SymbolReplacePlan
import com.nevoit.sfcompose.symbols.SymbolMotionTiming
import com.nevoit.sfcompose.symbols.SymbolVariableDraw
import com.nevoit.sfcompose.symbols.SymbolDisplayMode
import com.nevoit.sfcompose.ui.SymbolDisplayModeSelector
import com.nevoit.sfcompose.symbols.symbolMotionGroups
import com.nevoit.sfcompose.symbols.systemStyle
import com.nevoit.sfcompose.ui.theme.SFComposeTheme
import com.nevoit.sfcompose.browser.SymbolCatalogBrowser
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SFComposeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SymbolCatalogBrowser(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
internal fun SymbolInspector(store: SymbolAssetStore, initialName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var catalog by remember { mutableStateOf<SymbolCatalog?>(null) }
    var name by remember { mutableStateOf(initialName) }
    var requestedName by remember { mutableStateOf(initialName) }
    var weight by remember { mutableStateOf(SymbolWeight.Regular) }
    var scale by remember { mutableStateOf(SymbolScale.Medium) }
    var displayMode by remember { mutableStateOf(SymbolDisplayMode.IntrinsicWidth) }
    var symbol by remember { mutableStateOf<SymbolInstance?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var effect by remember { mutableStateOf(SymbolEffect.None) }
    var replay by remember { mutableIntStateOf(0) }
    var playback by remember { mutableStateOf(SymbolEffectPlayback.Discrete) }
    var effectRepeatCount by remember { mutableStateOf<Int?>(null) }
    var layerMode by remember { mutableStateOf(SymbolEffectLayerMode.ByLayer) }
    var gradient by remember { mutableStateOf(false) }
    var draw by remember { mutableStateOf<SymbolDrawEffect?>(null) }
    var drawReversed by remember { mutableStateOf(false) }
    var drawReversesMotionGroups by remember { mutableStateOf(false) }
    var variable by remember { mutableFloatStateOf(1f) }
    var variableDraw by remember { mutableStateOf(false) }
    var animateVariable by remember { mutableStateOf(false) }
    var iterative by remember { mutableStateOf(false) }
    var bounceVariable by remember { mutableStateOf(false) }
    var hideInactive by remember { mutableStateOf(false) }
    var replacementName by remember { mutableStateOf("rainbow") }
    var requestedReplacement by remember { mutableStateOf("rainbow") }
    var replacement by remember { mutableStateOf<SymbolInstance?>(null) }
    var replaceDirection by remember { mutableStateOf(SymbolReplaceDirection.DownUp) }
    var magic by remember { mutableStateOf(true) }
    var layeredReplace by remember { mutableStateOf(true) }
    var replaceEnabled by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    // Reset in the same composition as the new effect; an asynchronous reset can draw one frame at the old time.
    var elapsed by remember(replay, symbol, replacement, effect, draw) { mutableFloatStateOf(0f) }
    val nativeVariableDrawModes = remember(symbol) {
        SymbolRenderingMode.entries.filter { mode -> symbol?.let {
            SymbolVariableDraw(it.nodes, it.metadata.optString("variableColorOrder") == "backToFront",
                mode, it.draw::hasGeometry).isAvailable
        } == true }.toSet()
    }
    val supportsManualDraw = remember(symbol) {
        fun supported(nodes: List<com.nevoit.sfcompose.symbols.SymbolNode>): Boolean = nodes.any { node ->
            when (node) {
                is com.nevoit.sfcompose.symbols.SymbolNode.Layer -> symbol?.draw?.hasGeometry(node.source) == true
                is com.nevoit.sfcompose.symbols.SymbolNode.Group -> supported(node.children)
            }
        }
        symbol?.let { supported(it.nodes) } == true
    }
    val manualDrawLabel = if (nativeVariableDrawModes.isNotEmpty()) "Variable Draw" else "Draw 进度"
    val previewDuration = remember(symbol, replacement, draw, layerMode, drawReversed, drawReversesMotionGroups,
        replaceEnabled, magic, layeredReplace, replaceDirection, playback, effect, effectRepeatCount,
        animateVariable, iterative, bounceVariable) {
        val drawing = symbol?.let { current -> draw?.let { selected ->
            SymbolRenderingMode.entries.maxOf { mode ->
                SymbolDrawTiming(current, selected == SymbolDrawEffect.On, reverse = drawReversed,
                    layerMode = layerMode, mode = mode, drawReversesMotionGroups = drawReversesMotionGroups,
                    pointSize = 112f).durationSeconds
            }
        } } ?: 0f
        val replacing = if (replaceEnabled) symbol?.let { first -> replacement?.let { second ->
            SymbolReplacePlan(first, second, replaceDirection, magic, layeredReplace, pointSize = 160f).durationSeconds
        } } ?: 0f else 0f
        val finiteAnimation = if (playback == SymbolEffectPlayback.Continuous) effectRepeatCount?.let { count ->
            symbol?.let { current ->
                val timing = SymbolMotionTiming(current.metadata.optString("sourceKind").startsWith("macOS-system"))
                SymbolRenderingMode.entries.maxOf { mode ->
                    val groups = symbolMotionGroups(current.nodes, mode).maxGroupIndex + 1
                    val motion = timing.continuousDuration(effect, count, groups,
                        wholeSymbol = layerMode == SymbolEffectLayerMode.WholeSymbol)
                    var variableGroups = 0
                    fun visit(nodes: List<com.nevoit.sfcompose.symbols.SymbolNode>) {
                        for (node in nodes) when (node) {
                            is com.nevoit.sfcompose.symbols.SymbolNode.Layer ->
                                if (node.source.systemStyle(mode)?.has("-sfsymbols-variable-threshold") == true) variableGroups++
                            is com.nevoit.sfcompose.symbols.SymbolNode.Group -> visit(node.children)
                        }
                    }
                    visit(current.nodes)
                    val variableDuration = if (animateVariable && variableGroups > 0)
                        timing.variableContinuousDuration(variableGroups, iterative, bounceVariable,
                            willLoop = !bounceVariable, repeatCount = count) else 0f
                    maxOf(motion, variableDuration)
                }
            }
        } ?: 0f else 0f
        maxOf(6f, drawing, replacing, finiteAnimation)
    }
    LaunchedEffect(store) {
        try {
            catalog = store.catalog()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        }
    }
    LaunchedEffect(store, requestedName, weight, scale) {
        symbol = null
        error = null
        try {
            symbol = store.load(requestedName, weight, scale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        }
    }
    LaunchedEffect(store, requestedReplacement, weight, scale) {
        replacement = null
        try {
            replacement = store.load(requestedReplacement, weight, scale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        }
    }
    LaunchedEffect(effect, draw, playback, replay, symbol, replacement, animateVariable, replaceEnabled, playing, previewDuration) {
        if (playing && symbol != null && (effect != SymbolEffect.None || draw != null || animateVariable || replaceEnabled)) {
            val start = withFrameNanos { it }
            val offset = elapsed
            do {
                elapsed = withFrameNanos { offset + (it - start) / 1_000_000_000f }
            } while ((playback == SymbolEffectPlayback.Continuous && effectRepeatCount == null) || elapsed < previewDuration)
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("symbol-browser").padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) { Text("返回符号库") }
        Text("动画预览")
        Text("原始矢量 · ${catalog?.names?.size ?: 0} 个符号")
        OutlinedTextField(name, { name = it }, label = { Text("Symbol name") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { requestedName = name.trim() }) { Text("显示") }
            Button(onClick = {
                catalog?.names?.let { names ->
                    names.firstOrNull { it.startsWith(name.trim()) }?.let { name = it; requestedName = it }
                }
            }) { Text("匹配名称") }
        }
        for (row in SymbolRenderingMode.entries.chunked(2)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (mode in row) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        symbol?.let {
                            SFSymbol(
                                it, requestedName, Modifier.height(112.dp), mode,
                                tint = Color(0xFF6254E8), secondary = Color(0xFFFA9D31),
                                tertiary = Color(0xFF33B899), effect = effect,
                                playback = playback,
                                effectRepeatCount = effectRepeatCount,
                                effectLayerMode = layerMode,
                                gradients = gradient,
                                variableValue = if (variableDraw) null else { { variable } },
                                variableDrawValue = if (variableDraw && mode in nativeVariableDrawModes) { { variable } } else null,
                                drawProgress = if (variableDraw && mode !in nativeVariableDrawModes && supportsManualDraw)
                                    { { variable } } else null,
                                variableAnimation = if (animateVariable) SymbolVariableAnimation(
                                    iterative, bounceVariable, hideInactive,
                                    playback == SymbolEffectPlayback.Continuous && !bounceVariable,
                                ) else null,
                                drawEffect = draw,
                                drawReversed = drawReversed,
                                drawReversesMotionGroups = drawReversesMotionGroups,
                                elapsedSeconds = { elapsed },
                                displayMode = displayMode,
                            )
                        }
                        Text(mode.name)
                    }
                }
            }
        }
        error?.let { Text(it, color = Color.Red) }
        SymbolDisplayModeSelector(displayMode) { displayMode = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(gradient, { gradient = !gradient }, label = { Text("渐变") })
            FilterChip(playback == SymbolEffectPlayback.Continuous, {
                playback = if (playback == SymbolEffectPlayback.Continuous) SymbolEffectPlayback.Discrete else SymbolEffectPlayback.Continuous
            }, label = { Text("循环") })
        }
        Text(if (variableDraw) manualDrawLabel else "变量颜色")
        if (variableDraw && nativeVariableDrawModes.isEmpty()) {
            Text(if (supportsManualDraw) "该符号无原生 Variable Draw 范围；滑块控制 Draw On 进度。"
                else "该符号不支持 Draw 进度。")
        }
        Slider(variable, { variable = it }, modifier = Modifier.testTag("variable-value"),
            enabled = !variableDraw || supportsManualDraw)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(variableDraw, {
                variableDraw = !variableDraw
                if (variableDraw) {
                    draw = null
                    animateVariable = false
                }
            }, enabled = supportsManualDraw, label = { Text(manualDrawLabel) })
            FilterChip(animateVariable, { animateVariable = !animateVariable; replay++ }, label = { Text("变量动画") })
            FilterChip(iterative, { iterative = !iterative; replay++ }, label = { Text("依次") })
            FilterChip(bounceVariable, { bounceVariable = !bounceVariable; replay++ }, label = { Text("往返") })
            FilterChip(hideInactive, { hideInactive = !hideInactive }, label = { Text("隐藏非活动层") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in SymbolEffectLayerMode.entries) {
                FilterChip(layerMode == value, { layerMode = value }, label = { Text(value.name) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(draw == null && !variableDraw, { draw = null; variableDraw = false }, label = { Text("静态") })
            for (value in SymbolDrawEffect.entries) {
                FilterChip(draw == value, { variableDraw = false; draw = value; replay++ }, label = { Text("Draw ${value.name}") })
            }
            FilterChip(drawReversed, { drawReversed = !drawReversed; replay++ }, label = { Text("反向 Draw") })
            FilterChip(drawReversesMotionGroups, { drawReversesMotionGroups = !drawReversesMotionGroups; replay++ }, label = { Text("反转图层顺序") })
        }
        Text("字重：${weight.name}")
        Slider(
            value = weight.ordinal.toFloat(),
            onValueChange = { weight = SymbolWeight.entries[it.toInt().coerceIn(0, 8)] },
            valueRange = 0f..8f,
            steps = 7,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in SymbolScale.entries) {
                FilterChip(scale == value, { scale = value }, label = { Text(value.name) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in SymbolEffect.entries) {
                FilterChip(effect == value, { effect = value; replay++ }, label = { Text(value.name) })
            }
        }
        if (playback == SymbolEffectPlayback.Continuous) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (count in listOf(null, 1, 2, 3)) {
                    FilterChip(effectRepeatCount == count, { effectRepeatCount = count; replay++ },
                        label = { Text(count?.let { "$it 次" } ?: "无限循环") })
                }
            }
        }
        Text("替换目标")
        OutlinedTextField(replacementName, { replacementName = it }, label = { Text("Symbol name") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { requestedReplacement = replacementName.trim(); replaceEnabled = true; replay++ }) { Text("播放替换") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(magic, { magic = !magic; replay++ }, label = { Text("Magic Replace") })
            FilterChip(layeredReplace, { layeredReplace = !layeredReplace; replay++ }, label = { Text("逐层替换") })
            for (value in SymbolReplaceDirection.entries) {
                FilterChip(replaceDirection == value, { replaceDirection = value; replay++ }, label = { Text(value.name) })
            }
        }
        if (replaceEnabled) symbol?.let { first -> replacement?.let { second ->
            SFSymbolReplace(first, second, Modifier.height(160.dp),
                direction = replaceDirection, preferMagic = magic, layered = layeredReplace,
                tint = Color(0xFF6254E8), elapsedSeconds = { elapsed }, displayMode = displayMode)
        } }
        Text("时间：${"%.2f".format(elapsed)} s")
        Slider(elapsed.coerceIn(0f, previewDuration), { playing = false; elapsed = it }, valueRange = 0f..previewDuration,
            modifier = Modifier.testTag("animation-time"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { playing = !playing }) { Text(if (playing) "暂停" else "播放") }
            Button(onClick = { elapsed = 0f; playing = true; replay++ }) { Text("重播") }
        }
    }
}
