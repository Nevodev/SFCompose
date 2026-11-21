package com.nevoit.sfcompose.browser

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

internal data class BrowserCategory(val key: String, val icon: String, val labels: Map<String, String>) {
    fun label(): String = labels[if (Locale.getDefault().language == "zh") "zh-Hans" else "en"]
        ?: labels["en"] ?: key
}

internal data class BrowserSymbol(val name: String, val categories: Set<String>, val aliases: List<String>, val keywords: List<String>)

internal class SymbolBrowserCatalog(val categories: List<BrowserCategory>, val symbols: List<BrowserSymbol>) {
    private data class SearchEntry(val symbol: BrowserSymbol, val name: String, val aliases: List<String>, val text: String)
    private val categoryLabels = categories.associate { it.key to it.labels.values.joinToString(" ") }
    private val searchEntries = symbols.map { symbol ->
        SearchEntry(symbol, normalize(symbol.name), symbol.aliases.map(::normalize), normalize(buildString {
            append(symbol.name).append(' ')
            append(symbol.aliases.joinToString(" ")).append(' ')
            append(symbol.keywords.joinToString(" ")).append(' ')
            symbol.categories.forEach { append(categoryLabels[it].orEmpty()).append(' ') }
        }))
    }
    val categoryCounts: Map<String, Int> = categories.associate { category ->
        category.key to if (category.key == "all") symbols.size else symbols.count { category.key in it.categories }
    }

    fun search(query: String, category: String = "all"): List<BrowserSymbol> {
        val normalized = normalize(query).trim()
        val tokens = normalized.split(Regex("\\s+")).filter(String::isNotEmpty)
        val matches = searchEntries.asSequence().filter {
            (category == "all" || category in it.symbol.categories) && tokens.all(it.text::contains)
        }
        if (tokens.isEmpty()) return matches.map { it.symbol }.toList()
        return matches.sortedBy {
            when {
                it.name == normalized -> 0
                it.name.startsWith(normalized) -> 1
                normalized in it.aliases -> 2
                else -> 3
            }
        }.map { it.symbol }.toList()
    }

    companion object {
        suspend fun load(assets: AssetManager, privateNames: List<String> = emptyList()): SymbolBrowserCatalog = withContext(Dispatchers.IO) {
            val text = assets.open("symbols/browser-catalog.json.sfgz").use { stream ->
                GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            val catalog = parse(JSONObject(text))
            if (privateNames.isEmpty()) catalog else {
                val publicNames = catalog.symbols.mapTo(HashSet()) { it.name }
                require(privateNames.none(publicNames::contains)) { "Catalog names overlap" }
                SymbolBrowserCatalog(
                    catalog.categories + BrowserCategory("private", "lock", mapOf("en" to "Private", "zh-Hans" to "私有符号")),
                    catalog.symbols + privateNames.map { BrowserSymbol(it, setOf("private"), emptyList(), listOf("private", "私有")) },
                )
            }
        }

        internal fun parse(data: JSONObject): SymbolBrowserCatalog {
            require(data.getInt("formatVersion") == 1)
            fun JSONArray.strings() = List(length()) { getString(it) }
            val categories = data.getJSONArray("categories").let { array -> List(array.length()) {
                val item = array.getJSONObject(it)
                val labels = item.getJSONObject("labels")
                BrowserCategory(item.getString("key"), item.getString("icon"),
                    labels.keys().asSequence().associateWith(labels::getString))
            } }
            val symbols = data.getJSONArray("symbols").let { array -> List(array.length()) {
                val item = array.getJSONObject(it)
                BrowserSymbol(item.getString("name"), item.getJSONArray("categories").strings().toSet(),
                    item.getJSONArray("aliases").strings(), item.getJSONArray("keywords").strings())
            } }
            return SymbolBrowserCatalog(categories, symbols)
        }

        private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
            .replace('.', ' ').replace('_', ' ').replace('-', ' ')
    }
}
