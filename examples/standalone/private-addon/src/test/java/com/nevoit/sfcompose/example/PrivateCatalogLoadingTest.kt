package com.nevoit.sfcompose.example

import com.nevoit.sfcompose.symbols.SymbolAssetStore
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.sfcompose.symbols.SymbolResourceConfiguration
import com.nevoit.sfcompose.symbols.SymbolWeight
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateCatalogLoadingTest {
    @Test
    fun selectedPublicAndPrivateClosuresLoadEveryScale() = runBlocking {
        val assets = RuntimeEnvironment.getApplication().assets
        val requests = listOf(
            Triple("wifi", SymbolWeight.Bold, false),
            Triple("apps.ipad.badge.icloud", SymbolWeight.Bold, false),
            Triple("apple.math.notes", SymbolWeight.Regular, true),
        )
        for ((name, weight, animated) in requests) {
            val motion = if (animated) "animated" else "static"
            val store = SymbolAssetStore.fromPackagedEntry(assets, "sfcompose/entries/$name/$weight/$motion.json")
            assertTrue(name in store.catalog().names)
            for (scale in SymbolScale.entries) {
                val instance = store.load(name, weight, scale)
                assertEquals(name, instance.name)
                assertEquals(weight, instance.weight)
                assertEquals(scale, instance.scale)
            }
        }
    }

    @Test
    fun fixedPrivateEntryRejectsAnUnretainedWeight() {
        val assets = RuntimeEnvironment.getApplication().assets
        val name = "apps.ipad.badge.icloud"
        val store = SymbolAssetStore.fromPackagedEntry(assets, "sfcompose/entries/$name/Bold/static.json")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.load(name, SymbolWeight.Regular) }
        }
    }

    @Test
    fun privateRtlCounterpartLoadsFromItsOwnClosure() = runBlocking {
        val assets = RuntimeEnvironment.getApplication().assets
        val name = "apps.ipad.badge.icloud"
        val store = SymbolAssetStore.fromPackagedEntry(assets, "sfcompose/entries/$name/Bold/static.json")
        val configuration = SymbolResourceConfiguration(listOf("ar-EG"), rightToLeft = true, darkTheme = true)
        for (scale in SymbolScale.entries) {
            val instance = store.load(name, SymbolWeight.Bold, scale, configuration = configuration)
            assertEquals("$name.rtl", instance.resourceName)
            assertEquals(SymbolWeight.Bold, instance.weight)
        }
    }
}
