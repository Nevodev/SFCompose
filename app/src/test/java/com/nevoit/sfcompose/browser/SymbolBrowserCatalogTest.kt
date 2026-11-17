package com.nevoit.sfcompose.browser

import com.nevoit.sfcompose.symbols.SymbolAssetStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolBrowserCatalogTest {
    @Test
    fun originalOrderAndCategoryMembershipArePreserved() = runBlocking {
        val assets = RuntimeEnvironment.getApplication().assets
        val catalog = SymbolBrowserCatalog.load(assets)
        assertEquals(SymbolAssetStore(assets).catalog().names, catalog.search("").map { it.name })
        assertEquals(catalog.symbols.size, catalog.categoryCounts.getValue("all"))
        for (category in catalog.categories) {
            val expected = catalog.symbols.filter { category.key == "all" || category.key in it.categories }
            assertEquals(expected, catalog.search("", category.key))
            assertTrue(category.labels.getValue("en").isNotBlank())
            assertTrue(category.labels.getValue("zh-Hans").isNotBlank())
        }
    }

    @Test
    fun searchUsesAliasesKeywordsAndOriginalCategoryTranslations() = runBlocking {
        val catalog = SymbolBrowserCatalog.load(RuntimeEnvironment.getApplication().assets)
        assertEquals("heart.fill", catalog.search("  HEART.FILL  ").first().name)
        assertEquals("heart.fill", catalog.search("heart fill").first().name)
        val aliased = catalog.symbols.first { it.aliases.isNotEmpty() }
        assertTrue(catalog.search(aliased.aliases.first()).any { it.name == aliased.name })
        val keyword = catalog.symbols.first { it.keywords.any { term -> term.length > 2 && !it.name.contains(term) } }
        assertTrue(catalog.search(keyword.keywords.first()).any { it.name == keyword.name })
        val weather = catalog.categories.first { it.key == "weather" }
        val chinese = catalog.search(weather.labels.getValue("zh-Hans"))
        assertTrue(chinese.any { weather.key in it.categories })
        assertTrue(catalog.search("heart", "weather").all { "weather" in it.categories })
        assertTrue(catalog.search("no-such-symbol-94827364").isEmpty())
    }
}
