package com.zga.meridianatlas.model

/** Anatomy only: applying a snapshot keeps today's annotation and reference-overlay choices. */
data class AnatomyVisibilitySnapshot(
    val skin: Boolean, val muscle: Boolean, val organ: Boolean, val bone: Boolean,
    val connective: Boolean, val spaces: Boolean, val disabledRegionLayers: Set<String>,
    val hiddenIds: Set<String>, val isolateId: String?, val focusIds: Set<String>,
    val cutawaySide: CutawaySide
) {
    fun applyTo(current: LayerState): LayerState = current.copy(
        skin = skin, muscle = muscle, organ = organ, bone = bone, connective = connective, spaces = spaces,
        disabledRegionLayers = disabledRegionLayers.toSet(), hiddenIds = hiddenIds.toSet(),
        isolateId = isolateId, focusIds = focusIds.toSet(), cutawaySide = cutawaySide)

    companion object {
        fun capture(layer: LayerState) = AnatomyVisibilitySnapshot(layer.skin, layer.muscle, layer.organ,
            layer.bone, layer.connective, layer.spaces, layer.disabledRegionLayers.toSet(),
            layer.hiddenIds.toSet(), layer.isolateId, layer.focusIds.toSet(), layer.cutawaySide)

        fun defaults(mode: AtlasMode, current: LayerState): LayerState = capture(VisibilityPolicy.defaults(mode)).applyTo(current)
        fun isDefault(mode: AtlasMode, current: LayerState): Boolean = capture(current) == capture(VisibilityPolicy.defaults(mode))
    }
}

/** Bounded, session-only undo stacks. Selection, camera, routes and mode are not stored here. */
class VisibilityHistory(val maxStepsPerMode: Int = 32) {
    init { require(maxStepsPerMode > 0) { "Visibility history must retain at least one step" } }
    private val steps = mutableMapOf<AtlasMode, ArrayDeque<AnatomyVisibilitySnapshot>>()
    private var transactionDepth = 0

    fun depth(mode: AtlasMode): Int = steps[mode]?.size ?: 0
    fun canUndo(mode: AtlasMode, current: LayerState): Boolean {
        val now = AnatomyVisibilitySnapshot.capture(current)
        return steps[mode]?.any { it != now } == true
    }

    fun record(mode: AtlasMode, before: LayerState, after: LayerState): Boolean =
        if (transactionDepth > 0) false else record(mode, AnatomyVisibilitySnapshot.capture(before), AnatomyVisibilitySnapshot.capture(after))

    private fun record(mode: AtlasMode, before: AnatomyVisibilitySnapshot, after: AnatomyVisibilitySnapshot): Boolean {
        if (before == after) return false
        val history = steps.getOrPut(mode) { ArrayDeque() }
        history.addLast(before)
        while (history.size > maxStepsPerMode) history.removeFirst()
        return true
    }

    /** Ignore an already-current checkpoint if a non-history operation reached it meanwhile. */
    fun undo(mode: AtlasMode, current: LayerState): LayerState? {
        check(transactionDepth == 0) { "Cannot undo inside a visibility transaction" }
        val history = steps[mode] ?: return null
        val now = AnatomyVisibilitySnapshot.capture(current)
        while (history.isNotEmpty()) {
            val previous = history.removeLast()
            if (previous != now) return previous.applyTo(current)
        }
        return null
    }

    fun restoreDefaults(mode: AtlasMode, current: LayerState): LayerState {
        val restored = AnatomyVisibilitySnapshot.defaults(mode, current)
        record(mode, current, restored)
        return restored
    }

    /** A preset can restore temporary visibility and then apply its own layers as one step. */
    fun <T> transaction(layers: () -> Map<AtlasMode, LayerState>, change: () -> T): T {
        val outermost = transactionDepth == 0
        val before = if (outermost) layers().mapValues { AnatomyVisibilitySnapshot.capture(it.value) } else emptyMap()
        transactionDepth++
        try { return change() }
        finally {
            transactionDepth--
            if (outermost) {
                val after = layers()
                before.forEach { (mode, snapshot) -> after[mode]?.let {
                    record(mode, snapshot, AnatomyVisibilitySnapshot.capture(it))
                } }
            }
        }
    }

    fun clear(mode: AtlasMode? = null) {
        check(transactionDepth == 0) { "Cannot clear history inside a visibility transaction" }
        if (mode == null) steps.clear() else steps.remove(mode)
    }
}
