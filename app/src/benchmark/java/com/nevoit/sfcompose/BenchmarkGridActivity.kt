package com.nevoit.sfcompose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolDisplayMode
import com.nevoit.sfcompose.symbols.SymbolInstance

class BenchmarkGridActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val useVector = intent.getBooleanExtra("vectorDrawable", false)
        val store = SymbolAssetStore.shared(this)
        setContent {
            val symbol by produceState<SymbolInstance?>(null, store, useVector) {
                if (!useVector) value = store.load("xmark")
            }
            if (useVector || symbol != null) ScrollGrid(useVector, symbol)
        }
    }
}

private enum class ScrollStage { Warmup, Ready, Measuring, Done }

@Composable
private fun ScrollGrid(useVector: Boolean, symbol: SymbolInstance?) {
    val state = rememberLazyGridState()
    val stepPx = with(LocalDensity.current) { 8.dp.toPx() }
    val tint = Color(0xFF272727)
    val colorFilter = remember(tint) { ColorFilter.tint(tint) }
    val vector = if (useVector) painterResource(R.drawable.benchmark_xmark_24) else null
    var stage by remember { mutableStateOf(ScrollStage.Warmup) }

    LaunchedEffect(state, stepPx) {
        state.scrollFrames(stepPx, 40)
        state.scrollToItem(0)
        repeat(3) { withFrameNanos {} }
        stage = ScrollStage.Ready
    }
    LaunchedEffect(stage, stepPx) {
        if (stage != ScrollStage.Measuring) return@LaunchedEffect
        state.scrollFrames(stepPx, 300)
        check(state.firstVisibleItemIndex >= 100) { "The measured workload did not scroll through the grid" }
        withFrameNanos {}
        stage = ScrollStage.Done
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            userScrollEnabled = false,
        ) {
            items(800) { index ->
                Box(
                    modifier = Modifier.height(64.dp)
                        .background(if (index % 2 == 0) Color(0xFFF4F4F4) else Color(0xFFEDEDED)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (vector != null) {
                        Image(vector, null, Modifier.size(24.dp), colorFilter = colorFilter)
                    } else {
                        SFSymbol(requireNotNull(symbol), null, Modifier.size(24.dp), tint = tint,
                            displayMode = SymbolDisplayMode.SquareOverflow)
                    }
                }
            }
        }
        if (stage == ScrollStage.Ready) {
            Button(onClick = { stage = ScrollStage.Measuring }, modifier = Modifier.align(Alignment.Center)) {
                Text("Start benchmark")
            }
        } else if (stage == ScrollStage.Done) {
            Text("Scroll complete", modifier = Modifier.align(Alignment.Center))
        }
    }
}

private suspend fun LazyGridState.scrollFrames(stepPx: Float, frames: Int) = scroll {
    repeat(frames) {
        withFrameNanos {
            check(scrollBy(stepPx) > 0f) { "Benchmark grid reached the end" }
        }
    }
}
