package com.nevoit.sfcompose.symbols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolStylesTest {
    @Test
    fun clearBehindAndOpacityBelongToTheSelectedSystemMode() {
        val layer = JSONObject("""{"clearBehind":true,"monochrome":"erase","sourceModeStyles":{
            "monochrome":{"opacity":"0.0","-sfsymbols-clear-behind":"true","-sfsymbols-always-pulses":"true"},
            "hierarchical":{"opacity":"0.0","-sfsymbols-clear-behind":"true"},
            "multicolor":{"opacity":"0.5"}
        }}""")
        assertTrue(layer.symbolErases(SymbolRenderingMode.Monochrome))
        assertTrue(layer.symbolErases(SymbolRenderingMode.Palette))
        assertEquals(100.0, layer.symbolOpacity(SymbolRenderingMode.Monochrome), 0.0)
        assertFalse(layer.symbolErases(SymbolRenderingMode.Multicolor))
        assertFalse(layer.symbolBoolean(SymbolRenderingMode.Multicolor, "-sfsymbols-clear-behind", "clearBehind"))
        assertEquals(50.0, layer.symbolOpacity(SymbolRenderingMode.Multicolor), 0.0)
        assertTrue(layer.symbolBoolean(SymbolRenderingMode.Monochrome, "-sfsymbols-always-pulses", "canPulse"))
        assertFalse(layer.symbolBoolean(SymbolRenderingMode.Palette, "-sfsymbols-always-pulses", "canPulse"))
    }
}
