package com.nevoit.sfcompose.symbols

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolResourceConfigurationComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun configurationChangesReloadTheRealLocaleAppearanceAndDirectionalResources() {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val environment = mutableStateOf(Configuration().apply {
            setLocales(LocaleList(Locale.forLanguageTag("en-US")))
            uiMode = Configuration.UI_MODE_NIGHT_NO
        })
        val layoutDirection = mutableStateOf(LayoutDirection.Ltr)
        val loaded = AtomicReference<Pair<SymbolResourceConfiguration, List<SymbolInstance>>?>()
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides environment.value,
                LocalLayoutDirection provides layoutDirection.value) {
                val configuration = rememberSymbolResourceConfiguration()
                BasicText("${configuration.localeTags.firstOrNull()}|${configuration.darkTheme}|${configuration.rightToLeft}",
                    Modifier.testTag("resource-configuration"))
                LaunchedEffect(configuration) {
                    val symbols = listOf(
                        store.load("0.circle.ar", configuration = configuration),
                        store.load("circle.bottomhalf.filled.inverse", configuration = configuration),
                        store.load("arrow.forward", configuration = configuration),
                    )
                    loaded.set(configuration to symbols)
                }
            }
        }
        compose.waitUntil(10_000) { loaded.get() != null }
        compose.onNodeWithTag("resource-configuration").assertTextEquals("en-US|false|false")
        val first = loaded.get()!!.second
        val arabicVariant = runBlocking { store.variants("0.circle.ar").single { it.localeTag == "ar" }.id }
        val darkVariant = runBlocking { store.variants("circle.bottomhalf.filled.inverse").single { it.appearance == 1 }.id }
        compose.runOnIdle {
            environment.value = Configuration(environment.value).apply {
                setLocales(LocaleList(Locale.forLanguageTag("ar-EG")))
                uiMode = Configuration.UI_MODE_NIGHT_YES
            }
            layoutDirection.value = LayoutDirection.Rtl
        }
        compose.onNodeWithTag("resource-configuration").assertTextEquals("ar-EG|true|true")
        val requested = SymbolResourceConfiguration(listOf("ar-EG"), rightToLeft = true, darkTheme = true)
        compose.waitUntil(10_000) { loaded.get()?.first == requested }
        val current = loaded.get()!!.second
        assertEquals(arabicVariant, current[0].variantId)
        assertEquals(darkVariant, current[1].variantId)
        assertTrue(current[2].mirroredForRightToLeft)
        assertTrue(first[0].variantId != current[0].variantId)
        assertTrue(first[1].variantId != current[1].variantId)
    }
}
