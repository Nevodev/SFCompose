package com.nevoit.shrinkprototype

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolInstance
import com.nevoit.sfcompose.symbols.SymbolResourceConfiguration
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.sfcompose.symbols.SymbolWeight
import com.nevoit.sfcompose.symbols.rememberSymbolResourceConfiguration
import com.nevoit.shrinkprototype.catalog.PackedSymbolRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PackedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra("drawBenchmarkRun")
        if (runId != null) {
            runDrawLoadBenchmark(runId)
            return
        }
        setContent {
            Column {
                packedSymbols().forEach { entry ->
                    PackedPreview(entry)
                }
            }
        }
    }

    private fun runDrawLoadBenchmark(runId: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            val entries = packedSymbols()
            val configuration = SymbolResourceConfiguration(localeTags = listOf("ar-EG"), rightToLeft = true)
            val runs = JSONArray()
            val scales = SymbolScale.entries
            val batchStart = SystemClock.elapsedRealtimeNanos()
            for (entry in entries) {
                val start = SystemClock.elapsedRealtimeNanos()
                val store = SymbolAssetStore.fromResourceClosure(resources, entry.resources)
                var variant: String? = null
                for (scale in scales) {
                    variant = store.load(entry.name, weight = SymbolWeight.valueOf(entry.weight), scale = scale,
                        configuration = configuration).variantId
                }
                runs.put(JSONObject().put("symbol", entry.name).put("variant", variant)
                    .put("ns", SystemClock.elapsedRealtimeNanos() - start))
            }
            val result = JSONObject().put("run", runId).put("package", packageName)
                .put("totalNs", SystemClock.elapsedRealtimeNanos() - batchStart).put("symbols", runs)
            Log.i("SFDrawLoad", result.toString())
        }
    }
}

@Composable
private fun PackedPreview(entry: PackedSymbolRef) {
    val resources = LocalContext.current.resources
    val store = remember(resources, entry) { SymbolAssetStore.fromResourceClosure(resources, entry.resources) }
    val configuration = rememberSymbolResourceConfiguration()
    Row {
        SymbolScale.entries.forEach { scale ->
            val symbol by produceState<SymbolInstance?>(null, store, scale, configuration) {
                value = store.load(entry.name, weight = SymbolWeight.valueOf(entry.weight), scale = scale,
                    configuration = configuration)
            }
            symbol?.let { SFSymbol(it, null, pointSize = 20.dp) }
        }
        Text(entry.name)
    }
}
