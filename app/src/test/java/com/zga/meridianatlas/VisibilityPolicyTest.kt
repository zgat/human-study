package com.zga.meridianatlas

import com.zga.meridianatlas.model.*
import org.junit.Assert.*
import org.junit.Test

class VisibilityPolicyTest {
    private fun structure(id: String, system: String, region: String = "trunk", depth: Int = 0) =
        AnatomyStructure(id, id, id, system, region, depth, id, Vec3(0f, 1f, 0f))
    private val structures = listOf(structure("skin", "skin"), structure("bone", "bone"),
        structure("heart", "organ"), structure("superficial", "muscle", depth = 1),
        structure("deep", "muscle", depth = 3), structure("arm", "muscle", "upper_limb", 1))
    @Test fun hidingAllMusclesRevealsOrgansWithoutLosingRegionalChoices() {
        val regional = LayerState(skin = false, disabledRegionLayers = setOf("trunk:1"))
        assertEquals(setOf("bone", "heart"), VisibilityPolicy.visibleIds(structures, regional.copy(muscle = false)))
        assertEquals(setOf("bone", "heart", "deep", "arm"), VisibilityPolicy.visibleIds(structures, regional))
    }
    @Test fun muscleDepthIsRegionalRatherThanGlobal() {
        val state = LayerState(disabledRegionLayers = setOf("trunk:1"))
        val ids = VisibilityPolicy.visibleIds(structures, state)
        assertFalse("superficial" in ids)
        assertTrue("arm" in ids)
        assertTrue("deep" in ids)
    }
    @Test fun isolateAndHideAreAppliedAfterMasterSwitches() {
        assertEquals(setOf("deep"), VisibilityPolicy.visibleIds(structures, LayerState(isolateId = "deep")))
        assertTrue(VisibilityPolicy.visibleIds(structures, LayerState(isolateId = "deep", muscle = false)).isEmpty())
        assertTrue(VisibilityPolicy.visibleIds(structures, LayerState(isolateId = "deep", hiddenIds = setOf("deep"))).isEmpty())
    }
    @Test fun muscleModeStartsWithOnlyMuscleStructures() {
        val ids = VisibilityPolicy.visibleIds(structures, VisibilityPolicy.defaults(AtlasMode.MUSCLES))
        assertEquals(setOf("superficial", "deep", "arm"), ids)
    }
    @Test fun overviewPresetsExposeTheirNamedSystems() {
        val organs = VisibilityPolicy.overviewPreset(LayerState(isolateId = "deep"), OverviewPreset.ORGANS)
        assertEquals(setOf("bone", "heart"), VisibilityPolicy.visibleIds(structures, organs))
        val bones = VisibilityPolicy.overviewPreset(organs, OverviewPreset.BONES)
        assertEquals(setOf("bone"), VisibilityPolicy.visibleIds(structures, bones))
    }
    @Test fun depthPresetSelectsActualRegionalMusclesAndKeepsOptionalBoneReference() {
        val base = VisibilityPolicy.defaults(AtlasMode.MUSCLES)
        val shallow = VisibilityPolicy.muscleDepth(base, structures, 1)
        assertEquals(setOf("superficial", "arm"), VisibilityPolicy.visibleIds(structures, shallow))
        val deep = VisibilityPolicy.muscleDepth(shallow.copy(bone = true), structures, 3)
        assertEquals(setOf("bone", "deep"), VisibilityPolicy.visibleIds(structures, deep))
    }
    @Test fun legacyMigrationUpdatesOnlyTheOldUntouchedMuscleDefault() {
        val old = LayerState(skin = false, organ = false, lines = false, points = false)
        assertFalse(VisibilityPolicy.migrateLegacyDefault(AtlasMode.MUSCLES, old).bone)
        val custom = old.copy(disabledRegionLayers = setOf("trunk:1"))
        assertEquals(custom, VisibilityPolicy.migrateLegacyDefault(AtlasMode.MUSCLES, custom))
    }
    @Test fun modesOnlyPickAndDescribeRelevantStructureTypes() {
        assertTrue(VisibilityPolicy.isRelevantStructure(AtlasMode.OVERVIEW, structures.first { it.id == "bone" }))
        assertTrue(VisibilityPolicy.isRelevantStructure(AtlasMode.MUSCLES, structures.first { it.id == "deep" }))
        assertFalse(VisibilityPolicy.isRelevantStructure(AtlasMode.MUSCLES, structures.first { it.id == "bone" }))
        assertFalse(VisibilityPolicy.isRelevantStructure(AtlasMode.MERIDIANS, structures.first { it.id == "skin" }))
    }
    @Test fun hidingOneMuscleLeavesAllOtherStructuresAndSettingsUntouched() {
        val before = LayerState(skin = false, disabledRegionLayers = setOf("trunk:1"), cutawaySide = CutawaySide.LEFT)
        val after = VisibilityPolicy.hideOnly(before, "deep")
        assertEquals(VisibilityPolicy.visibleIds(structures, before) - "deep", VisibilityPolicy.visibleIds(structures, after))
        assertEquals(before, after.copy(hiddenIds = before.hiddenIds))
    }
    @Test fun meridianCutawayRetainsPointControlsAndEnablesInnerStructures() {
        val before = LayerState(points = false, labels = false, lines = true, isolateId = "deep", hiddenIds = setOf("heart"))
        for (side in listOf(CutawaySide.LEFT, CutawaySide.RIGHT)) {
            val half = VisibilityPolicy.meridianView(before, true, side)
            assertTrue(half.skin); assertTrue(half.organ); assertTrue(half.bone); assertFalse(half.muscle)
            assertEquals(side, half.cutawaySide)
            assertEquals(setOf("skin", "heart", "bone"), VisibilityPolicy.visibleIds(structures, half))
            assertFalse(half.points); assertFalse(half.labels); assertTrue(half.lines)
            assertNull(half.isolateId)
        }
        val full = VisibilityPolicy.meridianView(before, true)
        assertEquals(setOf("heart", "bone"), VisibilityPolicy.visibleIds(structures, full))
        assertEquals(CutawaySide.NONE, full.cutawaySide)
        val surface = VisibilityPolicy.meridianView(full, false)
        assertEquals(setOf("skin"), VisibilityPolicy.visibleIds(structures, surface))
    }

    @Test fun connectiveTissuesAreIndependentOfOrgansMusclesAndAnatomicalSpaces() {
        val parts = structures + structure("linea-alba", "connective") + structure("reference", "space")
        val organs = VisibilityPolicy.overviewPreset(LayerState(), OverviewPreset.ORGANS)
        assertEquals(setOf("bone", "heart"), VisibilityPolicy.visibleIds(parts, organs))
        assertEquals(setOf("bone", "heart", "linea-alba"), VisibilityPolicy.visibleIds(parts, organs.copy(connective = true)))
        assertFalse("linea-alba" in VisibilityPolicy.visibleIds(parts, VisibilityPolicy.defaults(AtlasMode.MUSCLES)))
        assertFalse("reference" in VisibilityPolicy.visibleIds(parts, organs.copy(connective = true)))
        assertTrue("reference" in VisibilityPolicy.visibleIds(parts, organs.copy(spaces = true)))
    }
}
