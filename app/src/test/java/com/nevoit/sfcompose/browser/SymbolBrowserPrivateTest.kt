package com.nevoit.sfcompose.browser

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.nevoit.sfcompose.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolBrowserPrivateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun combinedCatalogRoutesEachSymbolToItsOwnResources() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = BrowserSymbolStore(context)
        val publicCatalog = store.publicStore.catalog()
        val privateCatalog = store.privateStore.catalog()
        val browser = SymbolBrowserCatalog.load(context.assets, privateCatalog.names)
        assertEquals(publicCatalog.names + privateCatalog.names, browser.search("").map { it.name })
        assertEquals(privateCatalog.names, browser.search("", "private").map { it.name })
        assertEquals(store.catalog().names.size, browser.symbols.size)
        assertSame(store.privateStore.load("apps.ipad.badge.icloud"), store.load("apps.ipad.badge.icloud"))
        assertSame(store.publicStore.load("heart"), store.load("heart"))
    }

    @Test
    fun privateInspectorKeepsPreviewFixedAndPreservesControlsAcrossTabs() {
        checkPrivateInspector("compact")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun portraitInspectorKeepsPreviewFixedAndPreservesControlsAcrossTabs() {
        checkPrivateInspector("portrait")
    }

    private fun checkPrivateInspector(layout: String) {
        compose.onNodeWithText("SF Symbols").assertDoesNotExist()
        search("apps.ipad.badge.icloud")
        screenshot("private-browser-$layout")
        compose.onNodeWithTag("symbol-apps.ipad.badge.icloud").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("apps.ipad.badge.icloud").fetchSemanticsNodes().size == 4
        }
        compose.onNodeWithText("匹配名称").assertDoesNotExist()
        compose.onNodeWithText("复制名称").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("apps.ipad.badge.icloud", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        screenshot("private-symbol-$layout")
        val bounds = compose.onNodeWithTag("inspector-preview").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("inspector-controls-tab-2").performClick()
        compose.onNodeWithTag("inspector-controls").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 500f) }
        assertTrue(compose.onNodeWithTag("inspector-controls").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertEquals(bounds, compose.onNodeWithTag("inspector-preview").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("inspector-controls-tab-3").performClick()
        compose.onNodeWithTag("variable-value").performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
        compose.onNodeWithTag("inspector-controls-tab-1").performClick()
        compose.onNodeWithTag("inspector-controls-tab-3").performClick()
        assertEquals(.4f, compose.onNodeWithTag("variable-value").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        assertEquals(bounds, compose.onNodeWithTag("inspector-preview").fetchSemanticsNode().boundsInRoot)
        screenshot("private-inspector-$layout")
    }

    @Test
    fun crossCatalogReplacementKeepsItsCanvasFixedWhenControlsScroll() {
        search("heart")
        compose.onNodeWithText("选择替换").performClick()
        compose.onNodeWithTag("symbol-heart").performClick()
        search("apps.ipad.badge.icloud")
        compose.onNodeWithTag("symbol-apps.ipad.badge.icloud").performClick()
        compose.onNodeWithTag("open-replace").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("heart → apps.ipad.badge.icloud").fetchSemanticsNodes().isNotEmpty()
        }
        val bounds = compose.onNodeWithTag("replace-canvas").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("replace-controls").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
        assertTrue(compose.onNodeWithTag("replace-controls").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertEquals(bounds, compose.onNodeWithTag("replace-canvas").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("replace-canvas").assertIsDisplayed()
        compose.onNodeWithTag("replace-controls-tab-1").performClick()
        assertEquals(bounds, compose.onNodeWithTag("replace-canvas").fetchSemanticsNode().boundsInRoot)
        screenshot("private-replacement")
    }

    private fun search(name: String) {
        compose.onNodeWithTag("catalog-search").performTextReplacement(name)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("glyph-$name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        val file = File("build/reports/symbols/browser/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
