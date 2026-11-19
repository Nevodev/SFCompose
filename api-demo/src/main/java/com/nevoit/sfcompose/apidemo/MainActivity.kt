package com.nevoit.sfcompose.apidemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.AnimatedSFSymbol
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbolReplace
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.sfcompose.symbols.SymbolAnimation
import com.nevoit.sfcompose.symbols.SymbolDisplayMode
import com.nevoit.sfcompose.symbols.SymbolDrawEffect
import com.nevoit.sfcompose.symbols.SymbolEffect
import com.nevoit.sfcompose.symbols.SymbolRenderingMode
import com.nevoit.sfcompose.symbols.SymbolReplaceDirection
import com.nevoit.sfcompose.symbols.SymbolScale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFFADC6FF), surface = Color(0xFF131823))
            } else {
                lightColorScheme(primary = Color(0xFF234E9C), surface = Color(0xFFF8F9FE))
            }
            MaterialTheme(colorScheme = colors) { DemoScreen() }
        }
    }
}

private enum class DemoPage(val title: String, val api: String) {
    Static("静态", "SFSymbol"),
    Animated("动画", "AnimatedSFSymbol"),
    Replace("替换", "SFSymbolReplace"),
}

private enum class DemoAnimation(val label: String, val effect: SymbolAnimation) {
    DrawOn("Draw On", SymbolAnimation(drawEffect = SymbolDrawEffect.On)),
    DrawOff("Draw Off", SymbolAnimation(drawEffect = SymbolDrawEffect.Off)),
    Bounce("Bounce", SymbolAnimation(effect = SymbolEffect.Bounce)),
}

private enum class DemoColors(val label: String, val mode: SymbolRenderingMode, val gradients: Boolean = false) {
    Monochrome("单色", SymbolRenderingMode.Monochrome),
    TwoTone("双色", SymbolRenderingMode.Palette),
    Gradient("双色渐变", SymbolRenderingMode.Palette, gradients = true),
}

private val displayModes = listOf(
    SymbolDisplayMode.SquareOverflow to "方形溢出",
    SymbolDisplayMode.IntrinsicWidth to "实际宽度",
    SymbolDisplayMode.SquareFitWidth to "方形适宽",
)

private val opticalScales = SymbolScale.entries.map { it to it.name }
private val animations = DemoAnimation.entries.map { it to it.label }
private val replaceDirections = SymbolReplaceDirection.entries.map { it to it.name }
private val colorModes = DemoColors.entries.map { it to it.label }

@Composable
private fun DemoScreen() {
    var page by rememberSaveable { mutableStateOf(DemoPage.Static) }
    var displayMode by rememberSaveable { mutableStateOf(SymbolDisplayMode.SquareOverflow) }
    var scale by rememberSaveable { mutableStateOf(SymbolScale.Medium) }
    var pointSize by rememberSaveable { mutableIntStateOf(72) }
    var padding by rememberSaveable { mutableIntStateOf(0) }
    var animation by rememberSaveable { mutableStateOf(DemoAnimation.DrawOn) }
    var cellular by rememberSaveable { mutableStateOf(false) }
    var replay by rememberSaveable { mutableIntStateOf(0) }
    var filled by rememberSaveable { mutableStateOf(false) }
    var direction by rememberSaveable { mutableStateOf(SymbolReplaceDirection.DownUp) }
    var colors by rememberSaveable { mutableStateOf(DemoColors.Monochrome) }
    val ink = MaterialTheme.colorScheme.primary
    val palette = listOf(ink, Color(0xFFE5674A))
    val guide = Modifier.border(Dp.Hairline, MaterialTheme.colorScheme.outline)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Text("SFCompose API Demo", Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.headlineSmall)
            PrimaryTabRow(selectedTabIndex = page.ordinal) {
                for (option in DemoPage.entries) {
                    Tab(selected = page == option, onClick = { page = option }, text = { Text(option.title) })
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(page.api, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        when (page) {
                            DemoPage.Static -> Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SFSymbol(
                                        SFSymbols.Cellularbars, "蜂窝信号", guide,
                                        pointSize = pointSize.dp, scale = scale, tint = ink,
                                        displayMode = displayMode, padding = padding.dp,
                                        mode = colors.mode, palette = palette, gradients = colors.gradients,
                                    )
                                    Text("cellularbars", style = MaterialTheme.typography.labelMedium)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    val monochrome = colors == DemoColors.Monochrome
                                    SFSymbol(
                                        if (monochrome) SFSymbols.Wifi.Bold else SFSymbols.Airtag.Radiowaves.Forward.Fill,
                                        if (monochrome) "Wi-Fi Bold" else "AirTag", guide,
                                        pointSize = pointSize.dp, scale = scale, tint = ink,
                                        displayMode = displayMode, padding = padding.dp,
                                        mode = colors.mode, palette = palette, gradients = colors.gradients,
                                    )
                                    Text(if (monochrome) "wifi · Bold" else "AirTag", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            DemoPage.Animated -> AnimatedSFSymbol(
                                if (cellular) SFSymbols.Cellularbars.Animated else SFSymbols.Airplay.Audio.Animated,
                                if (cellular) "蜂窝信号" else "AirPlay",
                                guide,
                                pointSize = pointSize.dp, scale = scale, tint = ink,
                                displayMode = displayMode, padding = padding.dp,
                                animation = animation.effect, replayKey = replay,
                                mode = colors.mode, palette = palette, gradients = colors.gradients,
                            )
                            DemoPage.Replace -> SFSymbolReplace(
                                symbol = if (filled) SFSymbols.Person.Fill.Animated else SFSymbols.Person.Animated,
                                contentDescription = if (filled) "实心人像，点击切换" else "空心人像，点击切换",
                                modifier = guide.clickable { filled = !filled },
                                pointSize = pointSize.dp, scale = scale, tint = ink,
                                displayMode = displayMode, padding = padding.dp, direction = direction,
                                mode = colors.mode, palette = palette, gradients = colors.gradients,
                            )
                        }
                    }
                }
                when (page) {
                    DemoPage.Static -> Text("细线表示占位边界", style = MaterialTheme.typography.bodySmall)
                    DemoPage.Animated -> {
                        Choices(cellular, listOf(false to "AirPlay", true to "蜂窝信号")) { cellular = it }
                        Choices(animation, animations) { animation = it }
                        FilledTonalButton(onClick = { replay++ }) { Text("重播") }
                    }
                    DemoPage.Replace -> {
                        Choices(direction, replaceDirections) { direction = it }
                        FilledTonalButton(onClick = { filled = !filled }) {
                            Text(if (filled) "切换为空心" else "切换为实心")
                        }
                    }
                }
                Text("配色", style = MaterialTheme.typography.titleSmall)
                Choices(colors, colorModes) { colors = it }
                Text("显示模式", style = MaterialTheme.typography.titleSmall)
                Choices(displayMode, displayModes) { displayMode = it }
                Text("光学尺寸", style = MaterialTheme.typography.titleSmall)
                Choices(scale, opticalScales) { scale = it }
                SliderControl("字号", pointSize, 48..96) { pointSize = it }
                SliderControl("内边距", padding, 0..20) { padding = it }
            }
        }
    }
}

@Composable
private fun <T> Choices(selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun SliderControl(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text("$value dp", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
