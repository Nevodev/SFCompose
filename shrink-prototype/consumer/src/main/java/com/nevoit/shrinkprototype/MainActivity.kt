package com.nevoit.shrinkprototype

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.nevoit.shrinkprototype.catalog.SymbolRef
import java.util.zip.GZIPInputStream
import org.json.JSONObject

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = scenarioSymbols()
            .joinToString("\n") { inspect(it) }
        setContentView(TextView(this).apply { text = result; textSize = 18f })
    }

    private fun inspect(symbol: SymbolRef): String {
        val array = resources.obtainTypedArray(symbol.resources)
        return try {
            val entries = (0 until array.length()).map { index ->
                val id = array.getResourceId(index, 0)
                check(id != 0) { "Missing resource ${symbol.name}/$index" }
                resources.openRawResource(id).use { raw ->
                    JSONObject(GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).readText()).also { data ->
                        check(data.getString("name") == symbol.name)
                        if (!data.has("kind") && !symbol.full) {
                            check(data.getString("weight") == symbol.weight && data.getString("scale") == symbol.scale &&
                                data.getBoolean("filled") == symbol.filled)
                        } else if (data.has("kind")) check(data.getString("kind") == "symbol-metadata" && data.has("layerTree"))
                    }.getString("name")
                }
            }
            check(entries.isNotEmpty() && entries.all { it == symbol.name }) {
                "Unexpected resource closure for ${symbol.name}: $entries"
            }
            "${symbol.name} · ${entries.size} resources"
        } finally {
            array.recycle()
        }
    }
}
