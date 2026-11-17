package com.nevoit.sfcompose.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SymbolColorsTest {
    @Test
    fun originalStandardAppearancesKeepTheirDifferentColors() {
        assertEquals(0xffff383c.toInt(), SymbolColors.resolve("systemRedColor", 0))
        assertEquals(0xffff4245.toInt(), SymbolColors.resolve("systemRedColor", 0, darkTheme = true))
        assertEquals(0xff0088ff.toInt(), SymbolColors.resolve("systemBlueColor", 0))
        assertEquals(0xff0091ff.toInt(), SymbolColors.resolve("systemBlueColor", 0, darkTheme = true))
        assertEquals(0xff123456.toInt(), SymbolColors.resolve("tintColor", 0xff123456.toInt()))
    }

    @Test
    fun hierarchyUsesColorSpecificPercentagesAndNativeFallback() {
        assertEquals(0.18f, SymbolColors.hierarchyOpacity(2, "systemRedColor"), 0f)
        assertEquals(0.20f, SymbolColors.hierarchyOpacity(2, "systemRedColor", darkTheme = true), 0f)
        assertEquals(0.25f, SymbolColors.hierarchyOpacity(2, "systemBlueColor", darkTheme = true), 0f)
        assertEquals(0.25f, SymbolColors.hierarchyOpacity(2), 0f)
        assertEquals(1f, SymbolColors.hierarchyOpacity(3), 0f)
        assertEquals(1f, SymbolColors.hierarchyOpacity(1, "labelColor"), 0f)
        assertEquals(0.5f, SymbolColors.hierarchyOpacity(1, "labelColor", darkTheme = true), 0f)
    }

    @Test
    fun vibrantAndContrastAppearancesUseTheirOriginalCsvColumns() {
        assertEquals(0xfff52f32.toInt(), SymbolColors.resolve("red", 0, appearance = SymbolAppearance.Vibrant))
        assertEquals(0xffff4747.toInt(), SymbolColors.resolve("red", 0, darkTheme = true, appearance = SymbolAppearance.Vibrant))
        assertEquals(0xffe9152d.toInt(), SymbolColors.resolve("red", 0, appearance = SymbolAppearance.IncreasedContrast))
        assertEquals(0xffd40d23.toInt(), SymbolColors.resolve("red", 0, appearance = SymbolAppearance.VibrantIncreasedContrast))
        assertEquals(0xff4c4c4c.toInt(), SymbolColors.resolve("labelColor", 0, appearance = SymbolAppearance.Vibrant))
        assertEquals(0xc1000000.toInt(), SymbolColors.resolve("secondaryLabelColor", 0, appearance = SymbolAppearance.IncreasedContrast))
        assertEquals(0xb2ffffff.toInt(), SymbolColors.resolve("secondaryLabelColor", 0, darkTheme = true, appearance = SymbolAppearance.IncreasedContrast))
        assertEquals(1f, SymbolColors.hierarchyOpacity(1, "quinaryLabelColor", darkTheme = true), 0f)
    }

    @Test
    fun twoColorPaletteStartsAtTheLowestLevelActuallyPresent() {
        assertEquals(0, SymbolColors.paletteIndex(level = 1, minimumLevel = 1, paletteCount = 2))
        assertEquals(1, SymbolColors.paletteIndex(level = 2, minimumLevel = 1, paletteCount = 2))
        assertEquals(1, SymbolColors.paletteIndex(level = 1, minimumLevel = 0, paletteCount = 2))
        assertEquals(2, SymbolColors.paletteIndex(level = 3, minimumLevel = 0, paletteCount = 3))
        assertEquals(2, SymbolColors.paletteIndex(level = 3, minimumLevel = 0, paletteCount = 9))
        assertEquals(0, SymbolColors.paletteIndex(level = 3, minimumLevel = 0, paletteCount = 1))
        assertEquals(-1, SymbolColors.paletteIndex(level = 0, minimumLevel = 0, paletteCount = 0))
    }

    @Test
    fun gradientUsesLabBranchesAndPreservesAlpha() {
        val black = SymbolColors.gradient(0xff000000.toInt(), 0f)
        assertEquals(0xff333333.toInt(), black[0])
        assertEquals(0xff000000.toInt(), black[1])
        val white = SymbolColors.gradient(0x80ffffff.toInt(), 1f)
        assertEquals(0x80ffffff.toInt(), white[0])
        assertEquals(0x80e3e3e3.toInt(), white[1])
        assertEquals(
            0xffff0000.toInt(),
            SymbolColors.gradient(0xffff0000.toInt(), 0.54f)[1],
        )
    }

    @Test
    fun gradientAngleUsesNativeRectanglePerimeterMapping() {
        assertArrayEquals(floatArrayOf(50f, 0f, 50f, 200f), SymbolColors.gradientPoints(0, 0f, 0f, 100f, 200f), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 100f, 100f, 100f), SymbolColors.gradientPoints(90, 0f, 0f, 100f, 200f), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 200f), SymbolColors.gradientPoints(45, 0f, 0f, 100f, 200f), 0.0001f)
    }
}
