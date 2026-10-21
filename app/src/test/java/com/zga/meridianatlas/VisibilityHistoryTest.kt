package com.zga.meridianatlas

import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasUiState
import org.junit.Assert.*
import org.junit.Test

class VisibilityHistoryTest {
    private val mode = AtlasMode.OVERVIEW
    private fun structure(id: String, system: String, region: String = "trunk", depth: Int = 0) =
        AnatomyStructure(id, id, id, system, region, depth, id, Vec3(0f, 1f, 0f))
    private val parts = listOf(structure("skin", "skin"), structure("bone", "bone"), structure("heart", "organ"),
        structure("shallow", "muscle", depth = 1), structure("deep-a", "muscle", depth = 3),
        structure("deep-b", "muscle", depth = 3), structure("arm", "muscle", "upper_limb", 1))

    private fun annotations(layer: LayerState) = layer.copy(lines = false, points = false, labels = false,
        details = false, internalRelations = true, fasciaChains = true, fasciaTargets = false,
        enabledFascialConnections = setOf("reference-a"), fasciaRelations = false, fasciaContext = true)

    @Test fun systemRegionAndGroupedHideUndoActualVisibleStructuresInOrder() {
        val history = VisibilityHistory()
        val first = LayerState(skin = false)
        val systemOff = first.copy(muscle = false)
        val regional = systemOff.copy(muscle = true, disabledRegionLayers = setOf("trunk:1"))
        val hidden = VisibilityPolicy.hideOnly(regional, setOf("deep-a", "deep-b"))
        history.record(mode, first, systemOff)
        history.record(mode, systemOff, regional)
        history.record(mode, regional, hidden)
        assertEquals(setOf("bone", "heart", "arm"), VisibilityPolicy.visibleIds(parts, hidden))
        val undoHide = history.undo(mode, hidden)!!
        assertEquals(setOf("bone", "heart", "deep-a", "deep-b", "arm"), VisibilityPolicy.visibleIds(parts, undoHide))
        val undoRegion = history.undo(mode, undoHide)!!
        assertEquals(setOf("bone", "heart"), VisibilityPolicy.visibleIds(parts, undoRegion))
        val undoSystem = history.undo(mode, undoRegion)!!
        assertEquals(parts.map { it.id }.toSet() - "skin", VisibilityPolicy.visibleIds(parts, undoSystem))
        assertFalse(history.canUndo(mode, undoSystem))
    }

    @Test fun undoCoversEachAnatomicalFieldIncludingLocalFocusAndHalfBody() {
        val before = LayerState()
        val changes: List<(LayerState) -> LayerState> = listOf(
            { it.copy(skin = false) }, { it.copy(muscle = false) }, { it.copy(organ = false) },
            { it.copy(bone = false) }, { it.copy(connective = true) }, { it.copy(spaces = true) },
            { it.copy(disabledRegionLayers = setOf("trunk:3")) }, { it.copy(hiddenIds = setOf("deep-a")) },
            { it.copy(isolateId = "deep-a") }, { it.copy(focusIds = setOf("deep-a", "bone")) },
            { it.copy(cutawaySide = CutawaySide.RIGHT) })
        for ((index, change) in changes.withIndex()) {
            val history = VisibilityHistory()
            val after = change(before)
            assertTrue("Anatomical field $index must be a history step", history.record(mode, before, after))
            assertEquals("Undo field $index while keeping later annotations", annotations(before), history.undo(mode, annotations(after)))
        }
    }

    @Test fun annotationAndFasciaReferenceChangesDoNotCreateAnatomySteps() {
        val history = VisibilityHistory()
        val before = LayerState()
        assertFalse(history.record(mode, before, annotations(before)))
        assertEquals(0, history.depth(mode))
        assertNull(history.undo(mode, annotations(before)))
    }

    @Test fun defaultsRestoreEveryModeWithoutChangingAnnotationChoices() {
        for (mode in AtlasMode.entries) {
            val history = VisibilityHistory()
            val restricted = annotations(LayerState(skin = false, muscle = false, organ = false, bone = false,
                connective = true, spaces = true, disabledRegionLayers = setOf("trunk:1"),
                hiddenIds = setOf("arm"), isolateId = "deep-a", focusIds = setOf("deep-a"), cutawaySide = CutawaySide.LEFT))
            val restored = history.restoreDefaults(mode, restricted)
            assertEquals(annotations(VisibilityPolicy.defaults(mode)), restored)
            assertNull(restored.isolateId)
            assertTrue(restored.focusIds.isEmpty() && restored.hiddenIds.isEmpty() && restored.disabledRegionLayers.isEmpty())
            assertEquals(CutawaySide.NONE, restored.cutawaySide)
            assertTrue(history.canUndo(mode, restored))
            assertEquals(restricted, history.undo(mode, restored))
        }
    }

    @Test fun restoringDefaultsShowsTheNamedModeSystemsRatherThanEnablingEverySystem() {
        val history = VisibilityHistory()
        val restricted = LayerState(muscle = false, hiddenIds = parts.map { it.id }.toSet())
        val muscles = history.restoreDefaults(AtlasMode.MUSCLES, restricted)
        assertEquals(setOf("shallow", "deep-a", "deep-b", "arm"), VisibilityPolicy.visibleIds(parts, muscles))
        val meridians = history.restoreDefaults(AtlasMode.MERIDIANS, restricted)
        assertEquals(setOf("skin"), VisibilityPolicy.visibleIds(parts, meridians))
        val overview = history.restoreDefaults(AtlasMode.OVERVIEW, restricted)
        assertEquals(parts.map { it.id }.toSet(), VisibilityPolicy.visibleIds(parts, overview))
    }

    @Test fun resettingAnAlreadyDefaultAnatomyDoesNotAddAnUndoStep() {
        val history = VisibilityHistory()
        for (mode in AtlasMode.entries) {
            val current = annotations(VisibilityPolicy.defaults(mode))
            assertEquals(current, history.restoreDefaults(mode, current))
            assertEquals(0, history.depth(mode))
            assertFalse(history.canUndo(mode, current))
        }
    }

    @Test fun historiesAreIndependentAcrossModes() {
        val history = VisibilityHistory()
        val overview = VisibilityPolicy.defaults(mode)
        val muscle = VisibilityPolicy.defaults(AtlasMode.MUSCLES)
        history.record(mode, overview, overview.copy(organ = false))
        history.record(AtlasMode.MUSCLES, muscle, muscle.copy(hiddenIds = setOf("arm")))
        assertNull(history.undo(AtlasMode.MERIDIANS, LayerState()))
        assertEquals(overview, history.undo(mode, overview.copy(organ = false)))
        assertTrue(history.canUndo(AtlasMode.MUSCLES, muscle.copy(hiddenIds = setOf("arm"))))
        assertEquals(muscle, history.undo(AtlasMode.MUSCLES, muscle.copy(hiddenIds = setOf("arm"))))
    }

    @Test fun onlyTheMostRecentThirtyTwoStepsAreRetainedPerMode() {
        val history = VisibilityHistory()
        var current = LayerState()
        repeat(40) { index ->
            val next = current.copy(hiddenIds = current.hiddenIds + "part-$index")
            history.record(mode, current, next)
            current = next
        }
        assertEquals(32, history.depth(mode))
        repeat(32) { current = history.undo(mode, current)!! }
        assertEquals((0 until 8).map { "part-$it" }.toSet(), current.hiddenIds)
        assertNull(history.undo(mode, current))
    }

    @Test fun aNewChangeAfterUndoStartsFromTheCurrentVisibility() {
        val history = VisibilityHistory()
        val first = LayerState()
        val second = first.copy(hiddenIds = setOf("arm"))
        val third = second.copy(isolateId = "deep-a")
        history.record(mode, first, second)
        history.record(mode, second, third)
        val current = history.undo(mode, third)!!
        val alternate = current.copy(bone = false)
        history.record(mode, current, alternate)
        assertEquals(second, history.undo(mode, alternate))
        assertEquals(first, history.undo(mode, second))
        assertNull(history.undo(mode, first))
    }

    @Test fun temporaryFocusRestorationAndPresetAreOneUndoStep() {
        val history = VisibilityHistory()
        val initial = LayerState(skin = false, isolateId = "deep-a", hiddenIds = setOf("arm"))
        var layers = mapOf(mode to initial)
        history.transaction({ layers }) {
            val restored = LayerState()
            history.record(mode, layers.getValue(mode), restored)
            layers = layers + (mode to restored)
            val preset = VisibilityPolicy.overviewPreset(restored, OverviewPreset.BONES)
            history.record(mode, restored, preset)
            layers = layers + (mode to preset)
        }
        assertEquals(setOf("bone"), VisibilityPolicy.visibleIds(parts, layers.getValue(mode)))
        assertEquals(1, history.depth(mode))
        val undone = history.undo(mode, layers.getValue(mode))!!
        assertEquals(AnatomyVisibilitySnapshot.capture(initial), AnatomyVisibilitySnapshot.capture(undone))
        // Overview presets also disable annotation layers; anatomy-only undo keeps those current choices.
        assertFalse(undone.lines)
        assertFalse(undone.points)
    }

    @Test fun nestedTransactionsStillRecordOneStepAndPreserveReturnValues() {
        val history = VisibilityHistory()
        val initial = LayerState()
        var layers = mapOf(mode to initial)
        val result = history.transaction({ layers }) {
            history.transaction({ layers }) {
                layers = layers + (mode to initial.copy(muscle = false))
                "applied"
            }
        }
        assertEquals("applied", result)
        assertEquals(1, history.depth(mode))
        assertEquals(initial, history.undo(mode, layers.getValue(mode)))
    }

    @Test fun aTransactionWithNoNetAnatomyChangeDoesNotPolluteHistory() {
        val history = VisibilityHistory()
        val initial = LayerState()
        var layers = mapOf(mode to initial)
        history.transaction({ layers }) {
            layers = mapOf(mode to initial.copy(skin = false))
            layers = mapOf(mode to annotations(initial))
        }
        assertEquals(0, history.depth(mode))
        assertFalse(history.canUndo(mode, layers.getValue(mode)))
    }

    @Test fun aPartlyAppliedTransactionRemainsUndoableAfterAnException() {
        val history = VisibilityHistory()
        val initial = LayerState()
        var layers = mapOf(mode to initial)
        try {
            history.transaction({ layers }) {
                layers = mapOf(mode to initial.copy(hiddenIds = setOf("arm")))
                throw IllegalStateException("operation failed after changing visibility")
            }
            fail("Expected the original operation failure")
        } catch (expected: IllegalStateException) {
            assertEquals("operation failed after changing visibility", expected.message)
        }
        assertEquals(initial, history.undo(mode, layers.getValue(mode)))
        assertTrue(history.record(mode, initial, initial.copy(muscle = false)))
    }

    @Test fun capturedSetsDoNotChangeWhenTheOriginalMutableSetChanges() {
        val history = VisibilityHistory()
        val ids = mutableSetOf("arm")
        val regions = mutableSetOf("trunk:1")
        val focus = mutableSetOf("deep-a")
        val initial = LayerState(hiddenIds = ids, disabledRegionLayers = regions, focusIds = focus)
        val after = initial.copy(hiddenIds = emptySet(), disabledRegionLayers = emptySet(), focusIds = emptySet())
        history.record(mode, initial, after)
        ids += "bone"; regions += "upper_limb:1"; focus += "deep-b"
        val undo = history.undo(mode, after)!!
        assertEquals(setOf("arm"), undo.hiddenIds)
        assertEquals(setOf("trunk:1"), undo.disabledRegionLayers)
        assertEquals(setOf("deep-a"), undo.focusIds)
    }

    @Test fun alreadyCurrentCheckpointsAreNotOfferedAsSuccessfulUndo() {
        val history = VisibilityHistory()
        val initial = LayerState()
        history.record(mode, initial, initial.copy(muscle = false))
        assertFalse(history.canUndo(mode, initial))
        assertNull(history.undo(mode, initial))
        assertEquals(0, history.depth(mode))
    }

    @Test fun clearingOneModeDoesNotRemoveAnotherModesHistory() {
        val history = VisibilityHistory()
        val first = LayerState()
        val next = first.copy(bone = false)
        history.record(mode, first, next)
        history.record(AtlasMode.MUSCLES, first, next)
        history.clear(mode)
        assertFalse(history.canUndo(mode, next))
        assertTrue(history.canUndo(AtlasMode.MUSCLES, next))
        history.clear()
        assertFalse(history.canUndo(AtlasMode.MUSCLES, next))
    }

    @Test fun resetAvailabilityDependsOnAnatomyRatherThanLabelsOrSelectedObjects() {
        for (mode in AtlasMode.entries) {
            val current = AtlasUiState(mode = mode,
                layers = AtlasMode.entries.associateWith { annotations(VisibilityPolicy.defaults(it)) },
                selectedPoint = "gb23", selectedStructure = "deep-a", enabledMeridians = setOf("GB"))
            assertFalse(current.canResetAnatomy)
            val hidden = current.copy(layers = current.layers + (mode to current.layer.copy(hiddenIds = setOf("arm"))))
            assertTrue(hidden.canResetAnatomy)
            val restored = hidden.copy(layers = hidden.layers + (mode to AnatomyVisibilitySnapshot.defaults(mode, hidden.layer)))
            assertFalse(restored.canResetAnatomy)
            assertEquals(current.selectedPoint, restored.selectedPoint)
            assertEquals(current.selectedStructure, restored.selectedStructure)
            assertEquals(current.enabledMeridians, restored.enabledMeridians)
            assertEquals(current.mode, restored.mode)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun zeroCapacityIsRejected() { VisibilityHistory(0) }
    @Test(expected = IllegalArgumentException::class) fun negativeCapacityIsRejected() { VisibilityHistory(-1) }
}
