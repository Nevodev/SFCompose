package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolResourceVariantsTest {
    @Test
    fun compressedSystemResourcesPreserveLocalizedVariants() {
        val data = JSONObject("""{"name":"测试","systemVariants":[
            {"id":"default","appearance":0,"localization":0,"direction":5,"asset":null},
            {"id":"localized","appearance":1,"localization":7,"direction":4,"asset":"symbols/variants/localized.json.gz"}
        ]}""")
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val decoded = readSymbolJson(ByteArrayInputStream(bytes))
        assertEquals("测试", decoded.getString("name"))
        val variants = readSymbolVariants(decoded)
        assertEquals(2, variants.size)
        assertNull(variants[0].asset)
        assertEquals(7, variants[1].localization)
        assertEquals(1, variants[1].appearance)
        assertEquals(4, variants[1].direction)
        assertEquals("symbols/variants/localized.json.gz", variants[1].asset)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateResourceKeysCannotSilentlySelectOneVariant() {
        readSymbolVariants(JSONObject("""{"systemVariants":[
            {"id":"same","appearance":0,"localization":0,"direction":0,"asset":null},
            {"id":"same","appearance":1,"localization":0,"direction":0,"asset":null}
        ]}"""))
    }
}
