package com.nevoit.sfcompose.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.AnimatedSFSymbol
import com.nevoit.sfcompose.symbols.LocalSFSymbolColor
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbolReplace
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.sfcompose.symbols.SymbolAnimation
import com.nevoit.sfcompose.symbols.SymbolDrawEffect
import com.nevoit.sfcompose.symbols.SymbolRenderingMode

private val primary = Color(0xFF1B429F)
private val accent = Color(0xFFEF674B)
private val twoTone = listOf(primary, accent)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selected by rememberSaveable { mutableStateOf(false) }
            CompositionLocalProvider(LocalSFSymbolColor provides primary) {
                Column(
                    Modifier.fillMaxSize().background(Color.White).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SFSymbol(SFSymbols.Wifi.Bold, "Wi-Fi")
                    SFSymbol(SFSymbols.Heart.Fill.Bold, "Heart", mode = SymbolRenderingMode.Multicolor)
                    SFSymbol(SFSymbols.Sun.Max, "Sun")
                    SFSymbol(SFSymbols.Zero.Circle.Ar, "Zero")
                    SFSymbol(SFSymbols.Airtag.Radiowaves.Forward.Fill, "AirTag",
                        mode = SymbolRenderingMode.Palette, palette = twoTone)
                    AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, "AirPlay",
                        animation = SymbolAnimation(drawEffect = SymbolDrawEffect.On),
                        mode = SymbolRenderingMode.Palette, palette = twoTone, gradients = true)
                    SFSymbolReplace(
                        symbol = if (selected) SFSymbols.Person.Fill.Animated else SFSymbols.Person.Animated,
                        contentDescription = "Select person",
                        modifier = Modifier.clickable { selected = !selected },
                        mode = SymbolRenderingMode.Palette,
                        secondary = accent,
                    )
                }
            }
        }
    }
}
