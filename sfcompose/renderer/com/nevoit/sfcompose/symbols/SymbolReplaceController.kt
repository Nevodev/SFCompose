package com.nevoit.sfcompose.symbols

import android.graphics.Path
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.IdentityHashMap

/**
 * Manages replacements that can be interrupted by another target change.
 *
 * Use [rememberSymbolReplaceController] in Compose. Call [replaceWith] from a target-change handler
 * and draw with [SFSymbolReplace], sharing one monotonic clock in seconds for both operations.
 * The controller does not run a clock or coroutine itself.
 *
 * Returning to the active pair's source reverses its timeline. A third target captures the current
 * vector presentation and starts a new replacement; it does not capture a bitmap. [reset] discards
 * the active transition and shows a symbol immediately.
 *
 * @param initialSymbol Loaded symbol displayed before the first replacement.
 */
class SymbolReplaceController(initialSymbol: SymbolInstance) {
    /** Most recently requested symbol; the current frame may still contain outgoing symbols. */
    var target by mutableStateOf(initialSymbol)
        private set
    internal var revision by mutableIntStateOf(0)
        private set
    private var state: Transition = Still(initialSymbol)
    private var lastChange = 0f
    private var pointSize = 32f
    private var hasRendered = false
    internal var layoutAdvanceRatio = initialSymbol.advance / initialSymbol.unitsPerEm
        private set
    internal var layoutInkRatio = initialSymbol.horizontalInkRatio()
        private set

    /** Current transition duration in seconds; zero when no transition is retained. */
    val durationSeconds: Float get() = state.finish - state.start
    /** Current transition's finish time on the caller's absolute clock; zero when still. */
    val endTimeSeconds: Float get() = state.finish

    /** Returns whether a retained transition has not finished at the supplied absolute time. */
    fun isRunning(atSeconds: Float): Boolean = state !is Still && atSeconds < state.finish

    /**
     * Immediately displays [symbol], discarding the transition and resetting reserved layout extents.
     *
     * Defaults to the current [target]. Also resets target-change time validation for a new timeline.
     */
    fun reset(symbol: SymbolInstance = target) {
        target = symbol
        state = Still(symbol)
        lastChange = 0f
        hasRendered = false
        layoutAdvanceRatio = symbol.advance / symbol.unitsPerEm
        layoutInkRatio = symbol.horizontalInkRatio()
        revision++
    }

    /**
     * Starts or interrupts a replacement at the current absolute clock value.
     *
     * Requesting the same target instance is a no-op. Instances are compared by identity; keep
     * loaded instances rather than constructing a new one for each target change.
     *
     * @param symbol Incoming loaded instance with animation data for the desired replacement.
     * @param atSeconds Finite clock time, no earlier than the previous target change. Use the same
     *   clock for drawing; do not restart it for each replacement.
     * @param direction Outgoing/incoming scale or Draw recipe.
     * @param preferMagic Attempts semantic matching, falling back to the standard recipe. Entry
     *   after an interrupted third-target change uses the standard recipe.
     * @param layered Enables staggered motion-group timing where supported.
     * @throws IllegalArgumentException If [atSeconds] is nonfinite or precedes the last target change.
     */
    fun replaceWith(
        symbol: SymbolInstance,
        atSeconds: Float,
        direction: SymbolReplaceDirection = SymbolReplaceDirection.DownUp,
        preferMagic: Boolean = true,
        layered: Boolean = true,
    ) {
        require(atSeconds.isFinite() && atSeconds >= lastChange) { "Replacement changes require a monotonic clock" }
        advanceTo(atSeconds)
        if (symbol === target) return
        val old = state
        state = when {
            old is Still -> PairTransition(old.symbol, symbol, atSeconds, direction, preferMagic, layered, pointSize,
                old.referencePointSize ?: pointSize.takeIf { hasRendered })
            old is PairTransition && symbol === old.first -> Backward(old, atSeconds, old.local(atSeconds))
            old is Backward && symbol === old.pair.second -> old.pair.resuming(atSeconds, old.local(atSeconds))
            else -> {
                val frozen = capture(old, atSeconds)
                Interrupted(frozen, symbol, atSeconds, direction, layered)
            }
        }
        target = symbol
        layoutAdvanceRatio = maxOf(layoutAdvanceRatio, symbol.advance / symbol.unitsPerEm)
        layoutInkRatio = maxOf(layoutInkRatio, symbol.horizontalInkRatio())
        lastChange = atSeconds
        revision++
    }

    /**
     * Releases completed transition state at [atSeconds], even if no renderer is drawing.
     *
     * @param atSeconds Finite absolute time on the shared clock.
     * @return Whether a transition is still running; useful as a caller-owned frame-loop condition.
     * @throws IllegalArgumentException If [atSeconds] is not finite.
     */
    fun advanceTo(atSeconds: Float): Boolean {
        require(atSeconds.isFinite()) { "Replacement time must be finite" }
        if (state !is Still && atSeconds >= state.finish) {
            state = completed()
            revision++
        }
        return isRunning(atSeconds)
    }

    internal fun tracks(atSeconds: Float, renderPointSize: Float): List<SymbolReplaceTrack> {
        require(atSeconds.isFinite() && renderPointSize.isFinite() && renderPointSize > 0f)
        pointSize = renderPointSize
        hasRendered = true
        state.prepare(pointSize)
        if (state !is Still && atSeconds >= state.finish) {
            // Target and reserved metrics already describe the completed image. Drop old vector
            // states without a snapshot write from drawing, even when the caller omits advanceTo.
            state = completed()
        }
        return state.tracks(atSeconds, pointSize)
    }

    internal fun layoutSymbols(): List<SymbolInstance> = state.symbols()

    private fun completed() = Still(target, (state as? Backward)?.pair?.sourcePointSize)

    private fun capture(transition: Transition, time: Float): List<FrozenTrack> = transition.tracks(time, pointSize)
        .filter { it.opacity > 0f }.mapNotNull { track ->
            val states = SymbolRenderingMode.entries.associateWith { mode ->
                IdentityHashMap<JSONObject, SymbolReplaceLayerState>().apply {
                    fun visit(nodes: List<SymbolNode>) {
                        for (node in nodes.filter { it.source.supportsSymbolMode(mode) }) {
                            if (node is SymbolNode.Group && node.source.optString("type") != "shapeGroup") {
                                visit(node.children)
                            } else {
                                val current = track.layerState(mode, node.source, pointSize)
                                val path = current.pathOverride?.let(::Path) ?: current.drawProgress?.let { progress ->
                                    requireNotNull(track.symbol.draw.pathFor(node.source, resolvedPath(track.symbol, node), progress,
                                        isOn = current.drawOn, removeOtherSubpaths = !node.source.has("systemClipStrokeKeyframes"),
                                        pointSize = pointSize, systemDrawState = current.systemDrawState,
                                        systemMode = when (mode) {
                                            SymbolRenderingMode.Monochrome -> "monochrome"
                                            SymbolRenderingMode.Multicolor -> "multicolor"
                                            else -> "hierarchical"
                                        }, adaptiveCaps = node.source.has("systemClipStrokeKeyframes"),
                                        recoverMissingAttachmentAnchors = true)) {
                                        "Unable to preserve Draw geometry while interrupting ${track.symbol.name}"
                                    }.let(::Path)
                                }
                                put(node.source, current.copy(pathOverride = path, drawProgress = null, systemDrawState = null))
                            }
                        }
                    }
                    visit(track.symbol.nodes)
                }
            }
            if (states.values.none { values -> values.values.any { it.opacity > 0f } }) null
            else FrozenTrack(track.symbol, states, track.opacity, track.scale, pointSize,
                track.expiresAt.takeIf { it.isFinite() })
        }

    private sealed interface Transition {
        val start: Float
        val finish: Float
        fun prepare(pointSize: Float) {}
        fun tracks(time: Float, pointSize: Float): List<SymbolReplaceTrack>
        fun symbols(): List<SymbolInstance>
    }

    private class Still(val symbol: SymbolInstance, val referencePointSize: Float? = null) : Transition {
        override val start = 0f
        override val finish = 0f
        override fun tracks(time: Float, pointSize: Float) = listOf(SymbolReplaceTrack(symbol,
            scale = referencePointSize?.div(pointSize) ?: 1f))
        override fun symbols() = listOf(symbol)
    }

    private class PairTransition(
        val first: SymbolInstance, val second: SymbolInstance, override val start: Float,
        val direction: SymbolReplaceDirection, val magic: Boolean, val layered: Boolean, initialPointSize: Float,
        val sourcePointSize: Float? = null,
    ) : Transition {
        private var currentPointSize = initialPointSize
        private val plans = LruCache<Pair<SymbolRenderingMode, Float>, SymbolReplacePlan>(12)
        private fun plan(mode: SymbolRenderingMode, pointSize: Float) = plans.getOrBuild(mode to pointSize) {
            SymbolReplacePlan(first, second, direction, magic, layered, mode, pointSize)
        }
        override val finish get() = start + SymbolRenderingMode.entries.maxOf { plan(it, currentPointSize).durationSeconds }
        override fun prepare(pointSize: Float) { currentPointSize = pointSize }
        fun local(time: Float) = (time - start).coerceAtLeast(0f)
        fun resuming(time: Float, local: Float) = PairTransition(first, second, time - local, direction, magic, layered,
            currentPointSize, sourcePointSize)
        override fun tracks(time: Float, pointSize: Float): List<SymbolReplaceTrack> {
            currentPointSize = pointSize
            val local = local(time)
            return listOf(first to false, second to true).map { (symbol, incoming) ->
                SymbolReplaceTrack(symbol, scale = if (!incoming && sourcePointSize != null) sourcePointSize / pointSize else 1f,
                    state = { mode, source, size -> plan(mode, size).layerState(source, incoming, local) })
            }
        }
        override fun symbols() = listOf(first, second)
    }

    private class Backward(val pair: PairTransition, override val start: Float, val from: Float) : Transition {
        override val finish = start + from
        override fun prepare(pointSize: Float) { pair.prepare(pointSize) }
        fun local(time: Float) = (from - (time - start).coerceAtLeast(0f)).coerceAtLeast(0f)
        override fun tracks(time: Float, pointSize: Float) = pair.tracks(pair.start + local(time), pointSize)
        override fun symbols() = pair.symbols()
    }

    private class Interrupted(
        val previous: List<FrozenTrack>, val next: SymbolInstance, override val start: Float,
        val direction: SymbolReplaceDirection, val layered: Boolean,
    ) : Transition {
        private val outDuration = if (direction == SymbolReplaceDirection.OffUp) .083f else .25f
        private val incomingPlans = LruCache<Pair<SymbolRenderingMode, Float>, SymbolReplacePlan>(12)
        private fun incomingPlan(mode: SymbolRenderingMode, pointSize: Float) = incomingPlans.getOrBuild(mode to pointSize) {
            SymbolReplacePlan(next, next, direction, preferMagic = false, layered = layered, mode = mode, pointSize = pointSize)
        }
        override val finish = start + if (direction == SymbolReplaceDirection.OffUp) .25f else .5f
        override fun tracks(time: Float, pointSize: Float): List<SymbolReplaceTrack> {
            val elapsed = (time - start).coerceAtLeast(0f)
            val outgoing = previous.mapNotNull { track ->
                val end = minOf(start + outDuration, track.deadline ?: Float.POSITIVE_INFINITY)
                val fraction = if (end <= start) 1f else ((time - start) / (end - start)).coerceIn(0f, 1f)
                if (fraction >= 1f) null else {
                    val eased = SymbolSystemTiming.curve(1, fraction)
                    val opacity = track.opacity * if (direction == SymbolReplaceDirection.DownUp) 1f else 1f - eased
                    val scale = track.scale * track.pointSize / pointSize * when (direction) {
                        SymbolReplaceDirection.DownUp -> 1f - .5f * eased
                        SymbolReplaceDirection.UpUp -> 1f + .25f * eased
                        SymbolReplaceDirection.OffUp -> 1f
                    }
                    SymbolReplaceTrack(track.symbol, opacity, scale, end) { mode, source, _ ->
                        track.states.getValue(mode)[source] ?: SymbolReplaceLayerState(opacity = 0f)
                    }
                }
            }
            val incoming = SymbolReplaceTrack(next, state = { mode, source, size ->
                incomingPlan(mode, size).layerState(source, true, elapsed)
            })
            return outgoing + incoming
        }
        override fun symbols() = previous.map { it.symbol } + next
    }

    private class FrozenTrack(
        val symbol: SymbolInstance, val states: Map<SymbolRenderingMode, IdentityHashMap<JSONObject, SymbolReplaceLayerState>>,
        val opacity: Float, val scale: Float, val pointSize: Float, val deadline: Float?,
    )
}

internal class SymbolReplaceTrack(
    val symbol: SymbolInstance,
    val opacity: Float = 1f,
    val scale: Float = 1f,
    val expiresAt: Float = Float.POSITIVE_INFINITY,
    private val state: ((SymbolRenderingMode, JSONObject, Float) -> SymbolReplaceLayerState)? = null,
) {
    fun layerState(mode: SymbolRenderingMode, source: JSONObject, pointSize: Float) =
        state?.invoke(mode, source, pointSize) ?: SymbolReplaceLayerState()

    fun painterState(mode: SymbolRenderingMode, pointSize: Float): ((JSONObject) -> SymbolReplaceLayerState)? =
        state?.let { lookup -> { source -> lookup(mode, source, pointSize) } }
}

private fun resolvedPath(symbol: SymbolInstance, node: SymbolNode): Path = when (node) {
    is SymbolNode.Layer -> Path().apply { node.components.forEach { addPath(symbol.contours[it]) } }
    is SymbolNode.Group -> Path().apply {
        for (child in node.children) {
            val path = resolvedPath(symbol, child)
            if (node.source.optString("type") == "shapeGroup") {
                check(op(path, if (child.source.optString("shapeGroupBehavior", "draw") == "erase") Path.Op.DIFFERENCE else Path.Op.UNION))
            } else addPath(path)
        }
    }
}
