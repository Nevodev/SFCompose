package com.nevoit.shrinkprototype

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nevoit.sfcompose.symbols.AnimatedSFSymbol
import com.nevoit.sfcompose.symbols.LocalSFSymbolColor
import com.nevoit.sfcompose.symbols.SFSymbol
import com.nevoit.sfcompose.symbols.SFSymbolReplace
import com.nevoit.sfcompose.symbols.SFSymbols
import com.nevoit.sfcompose.symbols.SymbolAnimation
import com.nevoit.sfcompose.symbols.SymbolAnimationState
import com.nevoit.sfcompose.symbols.SymbolDisplayMode
import com.nevoit.sfcompose.symbols.SymbolDrawEffect
import com.nevoit.sfcompose.symbols.SymbolRenderingMode
import com.nevoit.sfcompose.symbols.SymbolScale
import com.nevoit.sfcompose.symbols.SymbolWeight
import com.nevoit.sfcompose.symbols.rememberSFSymbol
import com.nevoit.sfcompose.symbols.rememberSymbolAnimationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymbolEntryComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun publicEntriesReloadWeightScaleLocaleAndDirection() {
        val weight = mutableStateOf(SymbolWeight.Regular)
        val scale = mutableStateOf(SymbolScale.Medium)
        val configuration = mutableStateOf(Configuration().apply { setLocales(LocaleList(Locale.US)) })
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val loaded = AtomicReference<Triple<Pair<SymbolWeight, SymbolScale>, String?, String>?>()
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value, LocalLayoutDirection provides direction.value) {
                val wifi = rememberSFSymbol(SFSymbols.Wifi.DynamicWeight, weight.value, scale.value)
                val zero = rememberSFSymbol(SFSymbols.Zero.Circle.Ar)
                val airtag = rememberSFSymbol(SFSymbols.Airtag.Radiowaves.Forward.Fill)
                SideEffect {
                    if (wifi != null && zero != null && airtag != null) {
                        loaded.set(Triple(wifi.weight to wifi.scale, zero.variantId, airtag.resourceName))
                    }
                }
                Column {
                    BasicText("${weight.value}/${scale.value}/${direction.value}", Modifier.testTag("request"))
                    SFSymbol(SFSymbols.Person.Fill, contentDescription = "Test person")
                }
            }
        }
        compose.waitUntil(10_000) { loaded.get()?.first == (SymbolWeight.Regular to SymbolScale.Medium) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Test person").fetchSemanticsNodes().isNotEmpty() }
        val initial = loaded.get()!!
        assertEquals("airtag.radiowaves.forward.fill", initial.third)
        compose.runOnIdle {
            weight.value = SymbolWeight.Black
            scale.value = SymbolScale.Large
            configuration.value = Configuration(configuration.value).apply { setLocales(LocaleList(Locale.forLanguageTag("ar-EG"))) }
            direction.value = LayoutDirection.Rtl
        }
        compose.onNodeWithTag("request").assertTextEquals("Black/Large/Rtl")
        val expected = Triple(SymbolWeight.Black to SymbolScale.Large, "0599de6c9c7ee497002e3847", "airtag.radiowaves.forward.fill.rtl")
        try {
            compose.waitUntil(10_000) { loaded.get() == expected }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            assertEquals(expected, loaded.get())
            throw failure
        }
    }

    @Test
    fun staticEntryMatchesAnimatedEntryAtRest() {
        compose.setContent {
            Column {
                SFSymbol(SFSymbols.Airplay.Audio, contentDescription = "Static AirPlay",
                    modifier = Modifier.testTag("static-airplay"), pointSize = 64.dp)
                AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, contentDescription = "Animated AirPlay",
                    modifier = Modifier.testTag("animated-airplay"), pointSize = 64.dp)
            }
        }
        for (description in listOf("Static AirPlay", "Animated AirPlay")) {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }
        val static = compose.onNodeWithTag("static-airplay").captureToImage().asAndroidBitmap()
        val animated = compose.onNodeWithTag("animated-airplay").captureToImage().asAndroidBitmap()
        assertTrue("Both resource paths should render the same resting pixels", static.sameAs(animated))
    }

    @Test
    fun defaultSquareAllowsOverflowAndKeepsItsOuterSizeWhenPaddingChanges() {
        val padding = mutableStateOf(0.dp)
        compose.setContent {
            Column {
                Box(Modifier.size(width = 140.dp, height = 80.dp).testTag("square-area").background(Color.White),
                    contentAlignment = Alignment.Center) {
                    SFSymbol(SFSymbols.Cellularbars, "Square symbol", Modifier.testTag("square-symbol"),
                        pointSize = 80.dp, padding = padding.value)
                }
                SFSymbol(SFSymbols.Cellularbars, "Intrinsic symbol",
                    Modifier.testTag("intrinsic-symbol"), pointSize = 80.dp,
                    displayMode = SymbolDisplayMode.IntrinsicWidth, padding = padding.value)
            }
        }
        for (description in listOf("Square symbol", "Intrinsic symbol")) {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }
        val square = compose.onNodeWithTag("square-symbol")
        val intrinsic = compose.onNodeWithTag("intrinsic-symbol")
        val initialSquare = square.fetchSemanticsNode().boundsInRoot
        val initialIntrinsic = intrinsic.fetchSemanticsNode().boundsInRoot
        assertEquals(initialSquare.width, initialSquare.height, .01f)
        assertTrue(initialIntrinsic.width > initialIntrinsic.height)

        val area = compose.onNodeWithTag("square-area")
        fun inkColumns(): List<Int> {
            val bitmap = area.captureToImage().asAndroidBitmap()
            return (0 until bitmap.width).filter { x ->
                (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) and 0xff < 128 }
            }
        }
        val initialInk = inkColumns()
        val areaLeft = area.fetchSemanticsNode().boundsInRoot.left
        assertTrue("Wide ink can extend outside the square (ink=${initialInk.firstOrNull()}..${initialInk.lastOrNull()}, " +
            "slot=${initialSquare.left - areaLeft}..${initialSquare.right - areaLeft})", initialInk.any {
            it + areaLeft < initialSquare.left || it + areaLeft >= initialSquare.right
        })
        compose.runOnIdle { padding.value = 10.dp }
        assertEquals(initialSquare, square.fetchSemanticsNode().boundsInRoot)
        assertEquals(initialIntrinsic, intrinsic.fetchSemanticsNode().boundsInRoot)
        assertTrue(inkColumns().size in 1 until initialInk.size)
    }

    @Test
    fun defaultTintFollowsSymbolLocalAndExplicitTintWins() {
        val color = mutableStateOf(Color.Blue)
        compose.setContent {
            CompositionLocalProvider(LocalSFSymbolColor provides color.value) {
                Column {
                    val slot = Modifier.requiredSize(64.dp).background(Color.White)
                    SFSymbol(SFSymbols.Wifi, "Local static", slot.testTag("local-static"), pointSize = 64.dp)
                    AnimatedSFSymbol(SFSymbols.Wifi.Animated, "Local animated", slot.testTag("local-animated"),
                        pointSize = 64.dp)
                    SFSymbolReplace(SFSymbols.Wifi.Animated, "Local replacement", slot.testTag("local-replace"),
                        pointSize = 64.dp)
                    SFSymbol(SFSymbols.Wifi, "Explicit tint", slot.testTag("local-explicit"),
                        pointSize = 64.dp, tint = Color.Green)
                }
            }
        }
        for (description in listOf("Local static", "Local animated", "Local replacement", "Explicit tint")) {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }

        fun assertInk(vararg expected: Pair<String, Int>) {
            for ((tag, color) in expected) {
                val bitmap = compose.onNodeWithTag("local-$tag").captureToImage().asAndroidBitmap()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Expected tint for $tag", color in pixels)
            }
        }
        assertInk("static" to android.graphics.Color.BLUE, "animated" to android.graphics.Color.BLUE,
            "replace" to android.graphics.Color.BLUE, "explicit" to android.graphics.Color.GREEN)
        compose.runOnIdle { color.value = Color.Red }
        assertInk("static" to android.graphics.Color.RED, "animated" to android.graphics.Color.RED,
            "replace" to android.graphics.Color.RED, "explicit" to android.graphics.Color.GREEN)
    }

    @Test
    fun generatedEntrypointsApplyPaletteAndGradients() {
        val palette = listOf(Color.Red, Color.Blue)
        val gradients = mutableStateOf(false)
        compose.setContent {
            Column {
                val slot = Modifier.requiredSize(80.dp).background(Color.White)
                SFSymbol(SFSymbols.Airtag.Radiowaves.Forward.Fill, "Static palette", slot.testTag("palette-static"),
                    pointSize = 80.dp, mode = SymbolRenderingMode.Palette, palette = palette, gradients = gradients.value)
                AnimatedSFSymbol(SFSymbols.Airtag.Radiowaves.Forward.Fill.Animated, "Animated palette",
                    slot.testTag("palette-animated"), pointSize = 80.dp,
                    mode = SymbolRenderingMode.Palette, palette = palette, gradients = gradients.value)
                SFSymbolReplace(SFSymbols.Airtag.Radiowaves.Forward.Fill.Animated, "Replace palette",
                    slot.testTag("palette-replace"), pointSize = 80.dp,
                    mode = SymbolRenderingMode.Palette, palette = palette, gradients = gradients.value)
            }
        }
        for (description in listOf("Static palette", "Animated palette", "Replace palette")) {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }
        val nodes = listOf("static", "animated", "replace").map { compose.onNodeWithTag("palette-$it") }
        val solids = nodes.map { it.captureToImage().asAndroidBitmap() }
        for (bitmap in solids) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { it == android.graphics.Color.RED })
            assertTrue(pixels.any { it == android.graphics.Color.BLUE })
        }
        compose.runOnIdle { gradients.value = true }
        for ((node, solid) in nodes.zip(solids)) {
            assertTrue("Setting gradients must change the rendered pixels", !node.captureToImage().asAndroidBitmap().sameAs(solid))
        }
    }

    @Test
    fun replacementFollowsTargetAndCanReverseMidFlight() {
        val selected = mutableStateOf(false)
        compose.setContent {
            Column {
                val size = Modifier.requiredSize(96.dp).background(Color.White)
                BasicText("Toggle person", Modifier.testTag("toggle-person").clickable { selected.value = !selected.value })
                AnimatedSFSymbol(SFSymbols.Person.Animated, "Outline reference", size.testTag("outline"), pointSize = 96.dp)
                AnimatedSFSymbol(SFSymbols.Person.Fill.Animated, "Fill reference", size.testTag("fill"), pointSize = 96.dp)
                SFSymbolReplace(
                    symbol = if (selected.value) SFSymbols.Person.Fill.Animated else SFSymbols.Person.Animated,
                    contentDescription = "Person toggle",
                    modifier = size.testTag("replace"),
                    pointSize = 96.dp,
                )
            }
        }
        for (description in listOf("Outline reference", "Fill reference", "Person toggle")) {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }

        fun image(tag: String) = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val outline = image("outline")
        val fill = image("fill")
        assertTrue("The first target should appear at rest", outline.sameAs(image("replace")))
        assertTrue("The targets should have different geometry", !outline.sameAs(fill))

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("toggle-person").performClick()
        assertTrue(selected.value)
        compose.mainClock.advanceTimeBy(160)
        val intermediate = image("replace")
        assertTrue("The replacement should leave the initial outline", !intermediate.sameAs(outline))
        assertTrue("The replacement should not have reached the fill yet", !intermediate.sameAs(fill))

        compose.onNodeWithTag("toggle-person").performClick()
        assertTrue(!selected.value)
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("Changing the target mid-flight should return to the outline", outline.sameAs(image("replace")))

        compose.onNodeWithTag("toggle-person").performClick()
        assertTrue(selected.value)
        compose.mainClock.advanceTimeBy(1_000)
        val end = image("replace")
        assertEquals(fill.width, end.width)
        assertEquals(fill.height, end.height)
        val mismatchedInk = (0 until end.height).sumOf { y ->
            (0 until end.width).count { x ->
                (end.getPixel(x, y) and 0xff < 128) != (fill.getPixel(x, y) and 0xff < 128)
            }
        }
        assertTrue("The replacement should settle on the fill ($mismatchedInk mismatched pixels)",
            mismatchedInk < end.width * end.height / 100)
    }

    @Test
    fun generatedDrawEntryPlaysToCompletionAndReplays() {
        val replay = mutableStateOf(0)
        compose.setContent {
            Column {
                BasicText(replay.value.toString(), Modifier.testTag("draw-replay").clickable { replay.value++ })
                AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, contentDescription = "AirPlay",
                    modifier = Modifier.testTag("draw-icon").background(Color.White), pointSize = 96.dp,
                    animation = SymbolAnimation(drawEffect = SymbolDrawEffect.On), replayKey = replay.value, padding = 8.dp)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("AirPlay").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4_000)
        fun inkPixels(): Int {
            val bitmap = compose.onNodeWithTag("draw-icon").captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.count { (it ushr 24) > 128 && (it and 0xff) < 128 }
        }
        val complete = inkPixels()
        assertTrue("Animated icon should draw visible pixels at the end", complete > 20)

        compose.onNodeWithTag("draw-replay").performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("draw-replay").assertTextEquals("1")
        val replayed = inkPixels()
        assertTrue("Replay should restart with an incomplete drawing ($replayed < $complete)", replayed < complete)
        compose.mainClock.advanceTimeBy(4_000)
        assertEquals(complete, inkPixels())
    }

    @Test
    fun delayedDrawHoldsItsFirstFrameAndReplaysWithoutRecomposingTheCaller() {
        val replay = mutableStateOf(0)
        val compositions = AtomicInteger()
        compose.setContent {
            val key = replay.value
            SideEffect { compositions.incrementAndGet() }
            Column {
                BasicText(key.toString(), Modifier.testTag("delayed-replay").clickable { replay.value++ })
                AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, contentDescription = "Delayed AirPlay",
                    modifier = Modifier.testTag("delayed-draw").background(Color.White), pointSize = 96.dp,
                    animation = SymbolAnimation(drawEffect = SymbolDrawEffect.On, startDelayMillis = 1_000),
                    replayKey = key)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Delayed AirPlay").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.autoAdvance = false
        fun image() = compose.onNodeWithTag("delayed-draw").captureToImage().asAndroidBitmap()
        fun ink(bitmap: android.graphics.Bitmap): Int = (0 until bitmap.height).sumOf { y ->
            (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) and 0xff < 128 }
        }
        compose.mainClock.advanceTimeBy(5_000)
        val complete = image()

        repeat(2) {
            compose.onNodeWithTag("delayed-replay").performClick()
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithTag("delayed-replay").assertTextEquals(replay.value.toString())
            val first = image()
            val baseline = compositions.get()
            compose.mainClock.advanceTimeBy(700)
            val held = image()
            assertTrue("Delay must hold the time-zero drawing (first=${ink(first)}, held=${ink(held)}, complete=${ink(complete)})",
                first.sameAs(held))
            assertEquals("Waiting must not recompose the caller", baseline, compositions.get())
            compose.mainClock.advanceTimeBy(4_000)
            assertTrue("The delayed drawing must still complete", complete.sameAs(image()))
            assertTrue("Replay must have started from an incomplete drawing", !first.sameAs(complete))
            assertEquals("Frame updates must not recompose the caller", baseline, compositions.get())
        }
    }

    @Test
    fun completedDrawStaysCompleteAcrossRemovalAndReplaysWhileMounted() {
        val shown = mutableStateOf(true)
        val state = AtomicReference<SymbolAnimationState>()
        val compositions = AtomicInteger()
        compose.setContent {
            val animationState = rememberSymbolAnimationState()
            state.set(animationState)
            SideEffect { compositions.incrementAndGet() }
            Column {
                BasicText("toggle", Modifier.testTag("state-toggle").clickable { shown.value = !shown.value })
                BasicText("replay", Modifier.testTag("state-replay").clickable { animationState.replay() })
                if (shown.value) {
                    // The slot is larger than pointSize so the retained measured slot is part of the playback key.
                    AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, contentDescription = "State AirPlay",
                        modifier = Modifier.requiredSize(160.dp).background(Color.White).testTag("state-icon"),
                        pointSize = 80.dp, padding = 8.dp,
                        animation = SymbolAnimation(drawEffect = SymbolDrawEffect.On), state = animationState)
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("State AirPlay").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4_000)
        fun image() = compose.onNodeWithTag("state-icon").captureToImage().asAndroidBitmap()
        val complete = image()
        val completeTime = state.get().elapsedSeconds
        assertTrue("The draw should have progressed ($completeTime)", completeTime > 0f)

        compose.onNodeWithTag("state-toggle").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertTrue(compose.onAllNodesWithContentDescription("State AirPlay").fetchSemanticsNodes().isEmpty())
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("Hiding must not advance the retained clock", completeTime, state.get().elapsedSeconds, .001f)

        compose.onNodeWithTag("state-toggle").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertTrue(compose.onAllNodesWithContentDescription("State AirPlay").fetchSemanticsNodes().isNotEmpty())
        assertEquals("A finished draw must not restart", completeTime, state.get().elapsedSeconds, .001f)
        assertTrue("A finished draw must keep its last frame", complete.sameAs(image()))

        compose.onNodeWithTag("state-replay").performClick()
        compose.mainClock.advanceTimeBy(48)
        val restarted = state.get().elapsedSeconds
        assertTrue("Replay must restart the retained state ($restarted/$completeTime)", restarted < completeTime)
        assertTrue("Replay must begin from an incomplete drawing", !complete.sameAs(image()))
        val beforeFrames = compositions.get()
        compose.mainClock.advanceTimeBy(600)
        assertEquals("Playback frames must not recompose the caller", beforeFrames, compositions.get())
        compose.mainClock.advanceTimeBy(4_000)
        assertTrue("Replay must finish on the same pixels", complete.sameAs(image()))
        assertEquals(completeTime, state.get().elapsedSeconds, .001f)
    }

    @Test
    fun hiddenDrawPausesResumesAndReplaysFromZeroWhenAbsent() {
        val shown = mutableStateOf(true)
        val state = AtomicReference<SymbolAnimationState>()
        compose.setContent {
            val animationState = rememberSymbolAnimationState()
            state.set(animationState)
            Column {
                BasicText("toggle", Modifier.testTag("hidden-toggle").clickable { shown.value = !shown.value })
                BasicText("replay", Modifier.testTag("hidden-replay").clickable { animationState.replay() })
                if (shown.value) {
                    AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, contentDescription = "Hidden AirPlay",
                        modifier = Modifier.requiredSize(160.dp).background(Color.White).testTag("hidden-icon"),
                        pointSize = 80.dp, padding = 8.dp,
                        animation = SymbolAnimation(drawEffect = SymbolDrawEffect.On), state = animationState)
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Hidden AirPlay").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.autoAdvance = false
        fun image() = compose.onNodeWithTag("hidden-icon").captureToImage().asAndroidBitmap()

        // Measure this draw's full span, then restart and pause at a fraction of it.
        compose.mainClock.advanceTimeBy(4_000)
        val complete = image()
        val completeTime = state.get().elapsedSeconds
        assertTrue("The draw should have progressed ($completeTime)", completeTime > 0f)
        compose.onNodeWithTag("hidden-replay").performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.mainClock.advanceTimeBy((completeTime * 250f).toLong())

        compose.onNodeWithTag("hidden-toggle").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertTrue(compose.onAllNodesWithContentDescription("Hidden AirPlay").fetchSemanticsNodes().isEmpty())
        val paused = state.get().elapsedSeconds
        assertTrue("The draw should be mid-flight ($paused/$completeTime)", paused > 0f && paused < completeTime)
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals("Hidden playback must freeze", paused, state.get().elapsedSeconds, .001f)

        compose.onNodeWithTag("hidden-toggle").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertTrue(compose.onAllNodesWithContentDescription("Hidden AirPlay").fetchSemanticsNodes().isNotEmpty())
        compose.mainClock.advanceTimeBy(200)
        val resumed = state.get().elapsedSeconds
        assertTrue("Re-entry must continue from the retained time ($resumed/$paused)", resumed > paused)

        compose.mainClock.advanceTimeBy(4_000)
        assertEquals("The resumed draw should complete", completeTime, state.get().elapsedSeconds, .001f)
        assertTrue("The resumed draw must settle on its final pixels", complete.sameAs(image()))

        compose.onNodeWithTag("hidden-toggle").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertTrue(compose.onAllNodesWithContentDescription("Hidden AirPlay").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("hidden-replay").performClick()
        compose.mainClock.advanceTimeBy(48)

        compose.onNodeWithTag("hidden-toggle").performClick()
        compose.mainClock.advanceTimeBy(48)
        val restarted = state.get().elapsedSeconds
        assertTrue("Replay while absent must restart on re-entry ($restarted/$completeTime)", restarted < completeTime)
        assertTrue("Re-entry after replay must not show the completed frame", !complete.sameAs(image()))
        compose.mainClock.advanceTimeBy(4_000)
        assertTrue("The replayed draw must complete again", complete.sameAs(image()))
    }

    @Test
    fun autoPlayFalseRestsOnStaticPixelsUntilStateReplay() {
        val state = AtomicReference<SymbolAnimationState>()
        val compositions = AtomicInteger()
        compose.setContent {
            val animationState = rememberSymbolAnimationState()
            state.set(animationState)
            SideEffect { compositions.incrementAndGet() }
            Column {
                BasicText("replay", Modifier.testTag("autoplay-replay").clickable { animationState.replay() })
                val slot = Modifier.requiredSize(96.dp).background(Color.White)
                SFSymbol(SFSymbols.Airplay.Audio, "AutoPlay static reference",
                    slot.testTag("autoplay-static"), pointSize = 96.dp)
                AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, contentDescription = "AutoPlay AirPlay",
                    modifier = slot.testTag("autoplay-icon"), pointSize = 96.dp,
                    animation = SymbolAnimation(drawEffect = SymbolDrawEffect.Off),
                    state = animationState, autoPlay = false)
            }
        }
        for (description in listOf("AutoPlay static reference", "AutoPlay AirPlay")) {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.mainClock.autoAdvance = false
        fun image(tag: String) = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        fun ink(bitmap: android.graphics.Bitmap): Int = (0 until bitmap.height).sumOf { y ->
            (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) and 0xff < 128 }
        }

        compose.mainClock.advanceTimeBy(2_000)
        val reference = image("autoplay-static")
        assertTrue("autoPlay=false must rest on the same pixels as the static entry",
            reference.sameAs(image("autoplay-icon")))
        assertEquals("A resting symbol must not run a clock", 0f, state.get().elapsedSeconds, .001f)
        val idleCompositions = compositions.get()
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue("Advancing an idle clock must not change the resting pixels",
            reference.sameAs(image("autoplay-icon")))
        assertEquals("An idle symbol must not recompose its caller", idleCompositions, compositions.get())

        compose.onNodeWithTag("autoplay-replay").performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.mainClock.advanceTimeBy(4_000)
        val finishedTime = state.get().elapsedSeconds
        assertTrue("state.replay() must run the clock to completion ($finishedTime)", finishedTime > 0f)
        val finished = image("autoplay-icon")
        assertTrue("A triggered draw must leave the resting frame", !reference.sameAs(finished))
        assertTrue("The Off draw must remove most of the resting ink (${ink(finished)}/${ink(reference)})",
            ink(finished) < ink(reference) / 2)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("A completed draw must stop its clock", finishedTime, state.get().elapsedSeconds, .001f)
    }

    @Test
    fun replayKeyChangePlaysWhileAutoPlayFalseAndIdleRemovalDoesNotTrigger() {
        val replay = mutableStateOf(0)
        val shown = mutableStateOf(true)
        val state = AtomicReference<SymbolAnimationState>()
        compose.setContent {
            val animationState = rememberSymbolAnimationState()
            state.set(animationState)
            Column {
                BasicText(replay.value.toString(), Modifier.testTag("idle-key").clickable { replay.value++ })
                BasicText("toggle", Modifier.testTag("idle-toggle").clickable { shown.value = !shown.value })
                val slot = Modifier.requiredSize(96.dp).background(Color.White)
                SFSymbol(SFSymbols.Airplay.Audio, "Idle static reference",
                    slot.testTag("idle-static"), pointSize = 96.dp)
                if (shown.value) {
                    AnimatedSFSymbol(SFSymbols.Airplay.Audio.Animated, contentDescription = "Idle AirPlay",
                        modifier = slot.testTag("idle-icon"), pointSize = 96.dp,
                        animation = SymbolAnimation(drawEffect = SymbolDrawEffect.On, startDelayMillis = 500),
                        replayKey = replay.value, state = animationState, autoPlay = false)
                }
            }
        }
        for (description in listOf("Idle static reference", "Idle AirPlay")) {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.mainClock.autoAdvance = false
        fun image(tag: String) = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        fun ink(bitmap: android.graphics.Bitmap): Int = (0 until bitmap.height).sumOf { y ->
            (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) and 0xff < 128 }
        }

        compose.mainClock.advanceTimeBy(2_000)
        val reference = image("idle-static")
        assertTrue("autoPlay=false must still rest on the static pixels", reference.sameAs(image("idle-icon")))
        assertEquals("An untriggered symbol must not run a clock", 0f, state.get().elapsedSeconds, .001f)

        compose.onNodeWithTag("idle-toggle").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertTrue(compose.onAllNodesWithContentDescription("Idle AirPlay").fetchSemanticsNodes().isEmpty())
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("idle-toggle").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertTrue(compose.onAllNodesWithContentDescription("Idle AirPlay").fetchSemanticsNodes().isNotEmpty())
        assertTrue("Reinserting an idle symbol must keep the resting pixels", reference.sameAs(image("idle-icon")))
        assertEquals("Removal must not trigger the retained idle state", 0f, state.get().elapsedSeconds, .001f)

        compose.onNodeWithTag("idle-key").performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("idle-key").assertTextEquals("1")
        assertEquals("startDelayMillis must hold the trigger", 0f, state.get().elapsedSeconds, .001f)
        val delayed = image("idle-icon")
        assertTrue("The replay key must begin from an incomplete drawing", !reference.sameAs(delayed))
        assertTrue("A held DrawOn frame must have fewer ink pixels than the resting symbol",
            ink(delayed) < ink(reference))

        compose.mainClock.advanceTimeBy(700)
        val started = state.get().elapsedSeconds
        assertTrue("The replay key must start playback ($started)", started > 0f)
        val early = image("idle-icon")
        assertTrue("The replay key must draw an incomplete frame", !reference.sameAs(early))
        assertTrue("The first frames must draw fewer ink pixels than the resting symbol",
            ink(early) < ink(reference))

        compose.mainClock.advanceTimeBy(4_000)
        val finishedTime = state.get().elapsedSeconds
        assertTrue("The triggered draw should run to completion ($finishedTime)", finishedTime > 0f)
        assertTrue("The finished drawing must cover the resting ink (${ink(image("idle-icon"))}/${ink(reference)})",
            ink(image("idle-icon")) >= ink(reference) - ink(reference) / 20)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("A completed draw must stop its clock", finishedTime, state.get().elapsedSeconds, .001f)
    }
}
