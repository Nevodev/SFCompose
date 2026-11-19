package com.nevoit.sfcompose.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import com.nevoit.sfcompose.symbols.AnimatedSFSymbol
import com.nevoit.sfcompose.symbols.PrivateSFSymbols
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SymbolWeight

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column {
                SFSymbol(PrivateSFSymbols.Apps.Ipad.Badge.Icloud.Bold, "iPad apps in iCloud")
                AnimatedSFSymbol(PrivateSFSymbols.Apple.Math.Notes.DynamicWeight.Animated,
                    weight = SymbolWeight.Heavy, contentDescription = "Math Notes")
            }
        }
    }
}
