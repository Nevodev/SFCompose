package com.nevoit.sfcompose.browser

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToIndex
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import com.nevoit.sfcompose.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.graphics.Bitmap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolCatalogBrowserTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun search(query: String, expected: String) {
        compose.onNodeWithTag("catalog-search").performTextReplacement(query)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("symbol-$expected").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        val output = File("build/reports/symbols/browser/$name.png")
        output.parentFile!!.mkdirs()
        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun searchOpensInspectorAndKeepsTheQueryOnReturn() {
        search("heart", "heart")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("glyph-heart", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("search-heart")
        compose.onNodeWithTag("symbol-heart").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("heart").fetchSemanticsNodes().size == 4 }
        compose.onNodeWithText("返回符号库").performScrollTo().performClick()
        compose.onNodeWithTag("catalog-search").assertTextContains("heart")
        compose.onNodeWithTag("category-picker").performClick()
        compose.onNodeWithTag("category-weather").performScrollTo().performClick()
        compose.onNodeWithTag("catalog-search").performTextReplacement("")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("glyph-aqi.high", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("category-weather")
    }

    @Test
    fun twoSelectedSymbolsOpenReplaceAndCanBeSwappedAndScrubbed() {
        search("heart", "heart")
        compose.onNodeWithText("选择替换").performClick()
        compose.onNodeWithTag("symbol-heart").performClick()
        search("star", "star")
        compose.onNodeWithTag("symbol-star").performClick()
        compose.onNodeWithTag("replace-source").assertTextEquals("A  heart")
        compose.onNodeWithTag("replace-target").assertTextEquals("B  star")
        compose.onNodeWithTag("open-replace").performClick()
        compose.onNodeWithTag("preview-source").assertTextEquals("A  heart")
        compose.onNodeWithTag("preview-target").assertTextEquals("B  star")
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("heart → star").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("replace-time").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.15f) }
        screenshot("replace-heart-star")
        compose.onNodeWithText("交换 A / B").performScrollTo().performClick()
        compose.onNodeWithTag("preview-source").assertTextEquals("A  star")
        compose.onNodeWithTag("preview-target").assertTextEquals("B  heart")
        compose.onNodeWithText("返回符号库").performScrollTo().performClick()
        compose.onNodeWithTag("replace-source").assertTextEquals("A  star")
        compose.onNodeWithTag("replace-target").assertTextEquals("B  heart")
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithTag("replace-source").assertTextEquals("A  未选择")
    }

    @Test
    fun returningFromInspectorKeepsTheGridPosition() {
        search("heart", "heart")
        val entry = runBlocking {
            SymbolBrowserCatalog.load(RuntimeEnvironment.getApplication().assets).search("heart")[12].name
        }
        compose.onNodeWithTag("symbol-grid").performScrollToIndex(12)
        compose.onNodeWithTag("symbol-$entry").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(entry).fetchSemanticsNodes().size == 4 }
        compose.onNodeWithText("返回符号库").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("symbol-$entry").fetchSemanticsNodes().isNotEmpty() }
    }
}
