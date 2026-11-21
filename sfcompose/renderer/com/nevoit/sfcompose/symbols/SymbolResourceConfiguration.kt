package com.nevoit.sfcompose.symbols

import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale
import org.json.JSONObject

/**
 * Resource-selection preferences for [SymbolAssetStore.load].
 *
 * Generated-entry components observe these preferences automatically. For direct store loading,
 * use [rememberSymbolResourceConfiguration] in Compose or [SymbolResourceConfiguration.from]
 * with an Android configuration.
 * An explicit `variantId` on a load request overrides these preferences, including automatic mirroring.
 *
 * @property localeTags Preferred BCP 47 language tags in priority order; empty selects neutral resources.
 * @property rightToLeft Requests source RTL variants or source-permitted mirroring, rather than
 *   unconditionally mirroring every symbol. Literal directions can remain unchanged.
 * @property darkTheme Requests a matching dark resource variant where available.
 */
data class SymbolResourceConfiguration(
    val localeTags: List<String> = emptyList(),
    val rightToLeft: Boolean = false,
    val darkTheme: Boolean = false,
) {
    companion object {
        /**
         * Reads locale order, layout direction and night mode from an Android [Configuration].
         *
         * In Compose, prefer [rememberSymbolResourceConfiguration] so a locally overridden layout
         * direction is also observed.
         */
        fun from(configuration: Configuration): SymbolResourceConfiguration = SymbolResourceConfiguration(
            localeTags = List(configuration.locales.size()) { configuration.locales[it].toLanguageTag() },
            rightToLeft = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            darkTheme = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        )
    }
}

internal class SymbolResourceSelection(
    val flippableVariants: Set<String> = emptySet(),
    val rightToLeftNames: Map<String, String> = emptyMap(),
)

internal fun readSymbolResourceSelection(data: JSONObject, expectedSourceSha256: String? = null): SymbolResourceSelection {
    require(data.getInt("formatVersion") == 1) { "Unsupported symbol resource selection format" }
    require(expectedSourceSha256 == null || data.getJSONObject("source").getString("carSha256") == expectedSourceSha256) {
        "Symbol resource selection data does not match the vector catalog"
    }
    val flippable = data.getJSONArray("flippableVariants")
    val names = data.getJSONObject("rightToLeftNames")
    return SymbolResourceSelection(
        (0 until flippable.length()).map(flippable::getString).toSet(),
        names.keys().asSequence().associateWith(names::getString),
    )
}

/**
 * Observes Android locales and night mode together with the current Compose layout direction.
 *
 * Pass the result as `configuration` to [SymbolAssetStore.load] in a loading effect keyed by this
 * value. Resource selection happens during loading; changing draw-time colors does not reload it.
 */
@Composable
fun rememberSymbolResourceConfiguration(): SymbolResourceConfiguration {
    val configuration = LocalConfiguration.current
    val direction = LocalLayoutDirection.current
    val value = SymbolResourceConfiguration.from(configuration).copy(rightToLeft = direction == LayoutDirection.Rtl)
    return remember(value) { value }
}

internal fun selectSymbolVariant(
    variants: List<SymbolResourceVariant>,
    configuration: SymbolResourceConfiguration,
    catalog: SymbolCatalog,
): SymbolResourceVariant? {
    if (variants.isEmpty()) return null
    val localeIdentifiers = configuration.localeTags.flatMap(::symbolLocaleFallbacks)
        .mapNotNull { tag -> catalog.localizations.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value }
        .distinct() + 0
    val appearance = catalog.appearances[if (configuration.darkTheme) "NSAppearanceNameDarkAqua" else "NSAppearanceNameSystem"] ?: 0
    val directions = if (configuration.rightToLeft) listOf(4, 0, 5) else listOf(5, 0, 4)
    // Only compatible locale/appearance fallbacks participate. Unknown variants never win by file order.
    return variants.filter { it.localization in localeIdentifiers && it.appearance in listOf(appearance, 0) }
        .minWithOrNull(compareBy<SymbolResourceVariant>(
            { localeIdentifiers.indexOf(it.localization) },
            { if (it.appearance == appearance) 0 else 1 },
            { directions.indexOf(it.direction).takeIf { index -> index >= 0 } ?: directions.size },
            { it.id },
        )) ?: variants.firstOrNull { it.asset == null }
}

internal fun symbolLocaleFallbacks(tag: String): List<String> {
    val locale = Locale.forLanguageTag(tag.replace('_', '-'))
    val language = locale.language
    if (language.isEmpty() || language == "und") return emptyList()
    val script = locale.script.ifEmpty {
        if (language == "zh") when (locale.country) {
            "TW", "HK", "MO" -> "Hant"
            else -> "Hans"
        } else ""
    }
    return buildList {
        add(locale.toLanguageTag())
        if (script.isNotEmpty()) add("$language-$script")
        if (locale.country.isNotEmpty()) add("$language-${locale.country}")
        add(language)
    }.distinct()
}
