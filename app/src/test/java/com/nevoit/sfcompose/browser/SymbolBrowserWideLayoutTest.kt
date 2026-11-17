package com.nevoit.sfcompose.browser

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import com.nevoit.sfcompose.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolBrowserWideLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun wideBrowserKeepsTheOriginalCategoryListBesideTheGrid() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("category-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("category-picker").assertDoesNotExist()
        compose.onNodeWithTag("catalog-search").performTextReplacement("heart")
        val visible = runBlocking {
            SymbolBrowserCatalog.load(RuntimeEnvironment.getApplication().assets).search("heart").take(24)
        }
        compose.waitUntil(15_000) { visible.all {
            compose.onAllNodesWithTag("glyph-${it.name}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        } }
        val output = File("build/reports/symbols/browser/wide.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
