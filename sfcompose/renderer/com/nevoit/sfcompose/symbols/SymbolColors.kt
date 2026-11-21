package com.nevoit.sfcompose.symbols

import android.graphics.ColorSpace
import kotlin.math.roundToInt

/**
 * Source semantic-color palette used by Multicolor rendering.
 *
 * Selects standard, vibrant, increased-contrast or combined source color variants. This does not
 * enable an Android blur/vibrancy effect or change resource selection; light/dark colors are
 * selected separately by the renderer's theme setting.
 */
enum class SymbolAppearance {
    Standard,
    Vibrant,
    IncreasedContrast,
    VibrantIncreasedContrast,
}

internal object SymbolColors {
    private data class Entry(
        val platformName: String,
        val colors: IntArray,
        val lightTertiary: Int = 18,
        val darkTertiary: Int = 25,
        val lightHierarchy: Boolean = true,
        val darkHierarchy: Boolean = true,
    )

    // colors-26.csv column order is luminosity pairs for each SymbolAppearance.
    // Its malformed gray vibrant-contrast entry falls back to the non-vibrant contrast color.
    private val entries = mapOf(
        "red" to Entry("systemRedColor", intArrayOf(0xffff383c.toInt(), 0xffff4245.toInt(), 0xfff52f32.toInt(), 0xffff4747.toInt(), 0xffe9152d.toInt(), 0xffff6165.toInt(), 0xffd40d23.toInt(), 0xffff6165.toInt()), darkTertiary = 20),
        "orange" to Entry("systemOrangeColor", intArrayOf(0xffff8d28.toInt(), 0xffff9230.toInt(), 0xfff58625.toInt(), 0xffff9e33.toInt(), 0xffc55300.toInt(), 0xffffa056.toInt(), 0xffa84600.toInt(), 0xffffa056.toInt())),
        "yellow" to Entry("systemYellowColor", intArrayOf(0xffffcc00.toInt(), 0xffffd600.toInt(), 0xfff5c200.toInt(), 0xffffe014.toInt(), 0xffa16a00.toInt(), 0xfffedf43.toInt(), 0xff915c00.toInt(), 0xfffedf43.toInt()), lightTertiary = 20),
        "green" to Entry("systemGreenColor", intArrayOf(0xff34c759.toInt(), 0xff30d158.toInt(), 0xff26bf4d.toInt(), 0xff3bdb63.toInt(), 0xff008932.toInt(), 0xff4ad968.toInt(), 0xff00732a.toInt(), 0xff4ad968.toInt())),
        "mint" to Entry("systemMintColor", intArrayOf(0xff00c8b3.toInt(), 0xff00dac3.toInt(), 0xff00bda9.toInt(), 0xff2de0cd.toInt(), 0xff008575.toInt(), 0xff54dfcb.toInt(), 0xff007869.toInt(), 0xff54dfcb.toInt())),
        "teal" to Entry("systemTealColor", intArrayOf(0xff00c3d0.toInt(), 0xff00d2e0.toInt(), 0xff00b3bf.toInt(), 0xff2dd7e0.toInt(), 0xff008198.toInt(), 0xff3bddec.toInt(), 0xff00778c.toInt(), 0xff3bddec.toInt())),
        "cyan" to Entry("systemCyanColor", intArrayOf(0xff00c0e8.toInt(), 0xff3cd3fe.toInt(), 0xff00abcf.toInt(), 0xff47d8fc.toInt(), 0xff007eae.toInt(), 0xff6dd9ff.toInt(), 0xff0074a1.toInt(), 0xff6dd9ff.toInt())),
        "blue" to Entry("systemBlueColor", intArrayOf(0xff0088ff.toInt(), 0xff0091ff.toInt(), 0xff0078f0.toInt(), 0xff0a99ff.toInt(), 0xff1e6ef4.toInt(), 0xff5cb8ff.toInt(), 0xff0459e0.toInt(), 0xff5cb8ff.toInt())),
        "indigo" to Entry("systemIndigoColor", intArrayOf(0xff6155f5.toInt(), 0xff6b5dff.toInt(), 0xff5c50e6.toInt(), 0xff7163ff.toInt(), 0xff564ade.toInt(), 0xffa7aaff.toInt(), 0xff483dbf.toInt(), 0xffa7aaff.toInt())),
        "purple" to Entry("systemPurpleColor", intArrayOf(0xffcb30e0.toInt(), 0xffdb34f2.toInt(), 0xffb72bc9.toInt(), 0xffe647fc.toInt(), 0xffb02fc2.toInt(), 0xffea8dff.toInt(), 0xff9827a8.toInt(), 0xffea8dff.toInt())),
        "pink" to Entry("systemPinkColor", intArrayOf(0xffff2d55.toInt(), 0xffff375f.toInt(), 0xfff5234b.toInt(), 0xffff4169.toInt(), 0xffe7124d.toInt(), 0xffff8ac4.toInt(), 0xffd4113e.toInt(), 0xffff8ac4.toInt()), darkTertiary = 20),
        "brown" to Entry("systemBrownColor", intArrayOf(0xffac7f5e.toInt(), 0xffb78a66.toInt(), 0xff9e7354.toInt(), 0xffc29672.toInt(), 0xff956d51.toInt(), 0xffdba679.toInt(), 0xff876144.toInt(), 0xffdba679.toInt())),
        "white" to Entry("white", intArrayOf(0xffffffff.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt())),
        "gray" to Entry("systemGrayColor", intArrayOf(0xff8e8e93.toInt(), 0xff98989d.toInt(), 0xff848489.toInt(), 0xffa2a2a7.toInt(), 0xff69696e.toInt(), 0xff98989d.toInt(), 0xff69696e.toInt(), 0xff98989d.toInt())),
        "black" to Entry("black", intArrayOf(0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt())),
        "primary" to Entry("labelColor", intArrayOf(0xd8000000.toInt(), 0xd8ffffff.toInt(), 0xff4c4c4c.toInt(), 0xffe5e5e5.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(), 0xff000000.toInt(), 0xffffffff.toInt()), lightHierarchy = false),
        "secondary" to Entry("secondaryLabelColor", intArrayOf(0x7f000000.toInt(), 0x8cffffff.toInt(), 0xff808080.toInt(), 0xff7c7c7c.toInt(), 0xc1000000.toInt(), 0xb2ffffff.toInt(), 0xc1000000.toInt(), 0xb2ffffff.toInt()), lightHierarchy = false),
        "tertiary" to Entry("tertiaryLabelColor", intArrayOf(0x3f000000.toInt(), 0x3fffffff.toInt(), 0xffbfbfbf.toInt(), 0xff414141.toInt(), 0x8e000000.toInt(), 0x7fffffff.toInt(), 0x8e000000.toInt(), 0x7fffffff.toInt()), lightHierarchy = false),
        "quatenary" to Entry("quaternaryLabelColor", intArrayOf(0x19000000.toInt(), 0x19ffffff.toInt(), 0xffe6e6e6.toInt(), 0xff232323.toInt(), 0x59000000.toInt(), 0x4cffffff.toInt(), 0x59000000.toInt(), 0x4cffffff.toInt()), lightHierarchy = false),
        "quinary" to Entry("quinaryLabelColor", intArrayOf(0x0c000000.toInt(), 0x00000000.toInt(), 0xfff2f2f2.toInt(), 0xff111111.toInt(), 0x26000000.toInt(), 0x26ffffff.toInt(), 0x26000000.toInt(), 0x26ffffff.toInt()), lightHierarchy = false, darkHierarchy = false),
    )

    private val platformEntries = entries.values.associateBy { it.platformName }
    private val labTransform by lazy {
        ColorSpace.connect(
            ColorSpace.get(ColorSpace.Named.SRGB),
            ColorSpace.get(ColorSpace.Named.CIE_LAB),
        )
    }

    fun resolve(
        name: String,
        tint: Int,
        darkTheme: Boolean = false,
        appearance: SymbolAppearance = SymbolAppearance.Standard,
    ): Int {
        if (name == "tintColor" || name == "controlAccentColor") return tint
        val entry = entries[name] ?: platformEntries[name]
        if (entry != null) return entry.colors[appearance.ordinal * 2 + if (darkTheme) 1 else 0]
        if (name.startsWith('#')) {
            val value = name.drop(1).toLongOrNull(16)
            if (value != null) {
                return when (name.length) {
                    7 -> (0xff000000L or value).toInt()
                    9 -> value.toInt()
                    else -> -1
                }
            }
        }
        return -1
    }

    fun hierarchyOpacity(
        level: Int,
        colorName: String? = null,
        darkTheme: Boolean = false,
        appearance: SymbolAppearance = SymbolAppearance.Standard,
    ): Float {
        val entry = colorName?.let { entries[it] ?: platformEntries[it] }
        if (entry != null) {
            if (if (darkTheme) !entry.darkHierarchy else !entry.lightHierarchy) return 1f
            return when (level) {
                0 -> 1f
                1 -> 0.5f
                2 -> (if (darkTheme) entry.darkTertiary else entry.lightTertiary) / 100f
                else -> 1f
            }
        }
        // Native fallback dictionary has no quaternary entry; missing entries resolve to 100%.
        return when (level) {
            1 -> 0.5f
            2 -> 0.25f
            else -> 1f
        }
    }

    fun paletteIndex(level: Int, minimumLevel: Int, paletteCount: Int): Int = when {
        paletteCount <= 0 -> -1
        paletteCount == 1 -> 0
        paletteCount == 2 -> if (level <= minimumLevel) 0 else 1
        else -> level.coerceIn(0, 2)
    }

    fun gradient(color: Int): IntArray {
        // The native algorithm branches on GenericLab L*, rather than RGB luminance or HSV value.
        val lab = labTransform.transform(
            ((color ushr 16) and 255) / 255f,
            ((color ushr 8) and 255) / 255f,
            (color and 255) / 255f,
        )
        return gradient(color, lab[0] / 100f)
    }

    fun gradient(color: Int, labLightness: Float): IntArray {
        val red = ((color ushr 16) and 255) / 255f
        val green = ((color ushr 8) and 255) / 255f
        val blue = (color and 255) / 255f
        val minimum = minOf(red, green, blue)
        val maximum = maxOf(red, green, blue)
        fun saturationLimit(component: Float): Float {
            val difference = component - labLightness
            if (difference == 0f) return 0.2f
            val boundary = if (difference * 0.2f > 0f) 1f - component else -component
            return (boundary / difference).coerceIn(0f, 0.2f)
        }
        val saturation = minOf(saturationLimit(minimum), saturationLimit(maximum))
        val blend = when {
            labLightness <= 0.25f -> 0.2f
            labLightness <= 0.7f -> 0.25f
            labLightness <= 0.82f -> 0.34f
            else -> -0.11f
        }
        fun component(value: Float): Int {
            val saturated = value + (value - labLightness) * saturation
            val blended = saturated + blend * (if (blend < 0f) saturated else 1f - saturated)
            return (blended * 255f).roundToInt().coerceIn(0, 255)
        }
        val modified = (color and 0xff000000.toInt()) or
            (component(red) shl 16) or (component(green) shl 8) or component(blue)
        return if (labLightness <= 0.82f) intArrayOf(modified, color) else intArrayOf(color, modified)
    }

    fun gradientPoints(angleDegrees: Int, left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        fun unitPoint(angle: Long): Pair<Float, Float> {
            var value = ((angle % 360 + 360) % 360).toFloat()
            if (value < 45f) value += 360f
            return when {
                value < 135f -> (1f - (value - 45f) / 90f) to 0f
                value < 225f -> 0f to ((value - 135f) / 90f)
                value < 315f -> ((value - 225f) / 90f) to 1f
                else -> 1f to (1f - (value - 315f) / 90f)
            }
        }
        val start = unitPoint(angleDegrees.toLong() + 90)
        val end = unitPoint(angleDegrees.toLong() - 90)
        val width = right - left
        val height = bottom - top
        return floatArrayOf(
            left + start.first * width, top + start.second * height,
            left + end.first * width, top + end.second * height,
        )
    }

}
