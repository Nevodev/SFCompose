package com.nevoit.sfcompose.symbols

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemDrawTimingNativeTest {
    private val oracle by lazy {
        javaClass.getResourceAsStream("/system-draw-timing-native.json")!!.bufferedReader().use {
            JSONObject(it.readText())
        }
    }

    @Test
    fun actualSystemKeyframeLengthsMatchOriginalConsumerAtThreePointSizes() = runBlocking {
        val fixture = javaClass.getResourceAsStream("/system-draw-length-native.json")!!.bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("records")
        }
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        for (index in 0 until fixture.length()) {
            val case = fixture.getJSONObject(index)
            val filename = case.getString("name")
            val scaleName = if (filename.contains(".medium.")) "medium" else "small"
            val name = filename.substringBefore(".$scaleName.regular.")
            val scale = if (scaleName == "medium") SymbolScale.Medium else SymbolScale.Small
            val symbol = store.load(name, SymbolWeight.Regular, scale)
            val part = case.getInt("part")
            val node = symbol.nodes.filterIsInstance<SymbolNode.Layer>().single {
                it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome) &&
                    it.components.contentEquals(intArrayOf(part))
            }
            val samples = symbol.draw.timingLengthSamples(node.source, node.components,
                case.getDouble("pointSize").toFloat())
            val expected = case.getDouble("nativeLayerLengthUnscaled") * 1000.0 / 13.0
            val label = "$name/$scaleName/part=$part/pointSize=${case.getDouble("pointSize") }"
            if (expected == 0.0) {
                assertEquals(label, null, samples)
            } else {
                assertEquals(label, expected, samples!!.maxOrNull()!!, .0002)
            }
        }
    }

    @Test
    fun concatenatedNativeLayerUsesItsCombinedLengthAndAttachmentTail() = runBlocking {
        val store = SymbolAssetStore(RuntimeEnvironment.getApplication().assets)
        val symbol = store.load("homepod.mini.arrow.forward", SymbolWeight.Regular, SymbolScale.Small)
        val layer = symbol.nodes.filterIsInstance<SymbolNode.Layer>().single {
            it.source.supportsSymbolMode(SymbolRenderingMode.Monochrome) &&
                it.source.optInt("systemNativeLayerIndex") == 2
        }
        assertEquals(2, layer.components.size)
        assertEquals(listOf(2, 3), layer.source.getJSONArray("sourcePathIndices").let {
            (0 until it.length()).map(it::getInt)
        })
        assertTrue(symbol.draw.hasSystemAttachments(layer.source))
        val fixtures = javaClass.getResourceAsStream("/system-combined-layer-length-native.json")!!.bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("records")
        }
        for (index in 0 until fixtures.length()) {
            val expected = fixtures.getJSONObject(index)
            val pointSize = expected.getDouble("pointSize").toFloat()
            val lengths = symbol.draw.timingLengthSamples(layer.source, layer.components, pointSize)!!
            assertEquals(expected.getDouble("normalizedLength"), lengths.maxOrNull()!!, .0002)
            val duration = DrawTimingSchedule.systemDuration(expected.getDouble("normalizedLength")).toFloat()
            val timing = SymbolDrawTiming(symbol, false, pointSize = pointSize)
            val tail = timing.systemState(layer.source, duration + 1f / 60f)!!
            assertEquals(duration, tail.duration, .0000002f)
            assertEquals(.9999f, tail.from, 0f)
            assertEquals(.75f, tail.scale, .000002f)
            val terminal = timing.systemState(layer.source, duration + .034f)!!
            assertEquals(1f, terminal.from, 0f)
            assertEquals(0f, terminal.scale, 0f)
            assertEquals(1f, timing.opacity(layer.source, .1f), 0f)
        }
    }

    @Test
    fun systemDurationMatchesOriginalRenderBoxAcrossPointSizes() {
        val cases = oracle.getJSONArray("durations")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val normalizedLength = case.getDouble("length") / case.getDouble("pointSize") * 1000.0
            assertEquals(case.getDouble("duration"), DrawTimingSchedule.systemDuration(normalizedLength), .0000002)
        }
    }

    @Test
    fun systemByLayerGroupsMatchOriginalOrderMaximumAndFallbackDelay() {
        val cases = oracle.getJSONArray("schedules")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val on = case.getBoolean("on")
            val reverse = case.getBoolean("sourceReversed") xor (!on && case.getBoolean("reverse"))
            val schedule = DrawTimingSchedule(mapOf(0 to .5, 1 to null, 2 to .7), setOf(1),
                on, reverse, SymbolEffectLayerMode.ByLayer, systemTiming = true)
            val groups = case.getJSONArray("groups")
            for (group in 0 until groups.length()) {
                val expected = groups.getJSONObject(group)
                val start = expected.getDouble("start").toFloat()
                val duration = expected.getDouble("duration").toFloat()
                if (expected.getInt("draw") != 0) {
                    assertEquals(0f, schedule.progress(group, start)!!, .000002f)
                    assertEquals(1f, schedule.progress(group, start + duration)!!, .000002f)
                    assertTrue(schedule.progress(group, start + .001f)!! > 0f)
                } else {
                    assertEquals(.5f, schedule.fallbackProgress(group, start + duration * .5f), .000002f)
                }
            }
        }
    }

    @Test
    fun layersSharingAGroupRetainTheirOwnNativeDuration() {
        val schedule = DrawTimingSchedule(mapOf(0 to .5, 1 to .7), emptySet(), true, false,
            SymbolEffectLayerMode.ByLayer, systemTiming = true)
        assertEquals(1f, schedule.progress(0, .3f, .3)!!, 0f)
        assertTrue(schedule.progress(0, .3f, .5)!! < 1f)
        assertEquals(0f, schedule.progress(1, .09f, .7)!!, .000002f)
    }

    @Test
    fun systemAttachmentStateRetainsTheNativeOffTailAndReverseEndpoint() {
        val schedule = DrawTimingSchedule(mapOf(0 to .5), emptySet(), false, false,
            SymbolEffectLayerMode.ByLayer, systemTiming = true, drawEndOffsets = mapOf(0 to (.5 + 1.0 / 30.0)))
        val middle = schedule.systemState(0, .25f, .5, hasAttachments = true)!!
        assertEquals(.9999f * DrawTimingSchedule.systemCurve(.5f, false), middle.from, .0000001f)
        assertEquals(1f, middle.to, 0f)
        val tail = schedule.systemState(0, (.5 + 1.0 / 60.0).toFloat(), .5, hasAttachments = true)!!
        assertEquals(.9999f, tail.from, .0000001f)
        assertEquals(.75f, tail.scale, .000002f)
        val reversed = schedule.systemState(0, .25f, .5, reverse = true, hasAttachments = true)!!
        assertEquals(0f, reversed.from, 0f)
        assertEquals(1f - middle.from, reversed.to, .0000001f)
        val finished = schedule.systemState(0, .534f, .5, hasAttachments = true)!!
        assertEquals(1f, finished.from, 0f)
        assertEquals(0f, finished.scale, 0f)
        assertEquals((.5 + 1.0 / 30.0).toFloat(), schedule.drawDurationSeconds, 0f)
    }

    @Test
    fun attachmentHoldsMatchOriginalEmittedKeyframeEndpoints() {
        val cases = oracle.getJSONArray("drawTracks")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.getBoolean("on") || !case.getBoolean("attachments")) continue
            val track = case.getJSONArray("tracks").getJSONObject(0)
            val terms = track.getJSONArray("terms")
            val duration = terms.getJSONObject(0).getDouble("duration")
            val hold = terms.getJSONObject(1).getDouble("value").toFloat()
            val tailDuration = terms.getJSONObject(1).getDouble("duration")
            val reverse = case.getBoolean("reverse")
            val schedule = DrawTimingSchedule(mapOf(0 to duration), emptySet(), false, false,
                SymbolEffectLayerMode.ByLayer, systemTiming = true)
            val state = schedule.systemState(0, (duration + tailDuration / 2).toFloat(),
                duration, reverse, hasAttachments = true)!!
            assertEquals(hold, if (reverse) state.to else state.from, 0f)
            assertEquals(.75f, state.scale, .000002f)
        }
    }

    @Test
    fun systemDrawCurvesMatchOriginalPresetSamples() {
        val curves = oracle.getJSONArray("curves")
        for (index in 0 until curves.length()) {
            val curve = curves.getJSONObject(index)
            val preset = curve.getInt("preset")
            if (preset !in 17..18) continue
            val schedule = DrawTimingSchedule(mapOf(0 to 1.0), emptySet(), preset == 17, false,
                SymbolEffectLayerMode.ByLayer, systemTiming = true)
            val samples = curve.getJSONArray("samples")
            for (sample in 0 until samples.length()) {
                val expected = samples.getJSONObject(sample)
                assertEquals(expected.getDouble("progress").toFloat(),
                    schedule.progress(0, expected.getDouble("time").toFloat())!!, .00005f)
            }
        }
    }
}
