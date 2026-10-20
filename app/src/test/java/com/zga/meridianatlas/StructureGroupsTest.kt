package com.zga.meridianatlas

import com.zga.meridianatlas.model.*
import org.junit.Assert.*
import org.junit.Test

class StructureGroupsTest {
    private fun part(id: String, nameZh: String = "回肠近侧部", nameEn: String = "proximal part of ileum",
        concept: String = "FMA14964", dataset: String = "BodyParts3D", system: String = "organ",
        region: String = "trunk", layer: Int = 0, min: Vec3? = null, max: Vec3? = null,
        center: Vec3 = Vec3(0f, 1f, 0f)) = AnatomyStructure(id, nameZh, nameEn, system,
        region, layer, id, center, sourceConceptId = concept, sourceDataset = dataset,
        boundsMin = min, boundsMax = max)

    private val ileum = (0 until 10).map { index -> part("ileum-$index",
        min = Vec3(index / 100f, .9f, .1f), max = Vec3(index / 100f + .02f, 1.1f, .15f)) }

    @Test fun anyOfTenMeshPiecesResolvesToOneStableSelection() {
        val groups = StructureGroups(ileum)
        assertEquals(1, groups.entries.size)
        val group = groups.entries.single()
        assertEquals("ileum-0", group.id)
        assertEquals(ileum.map { it.id }.toSet(), group.memberIds)
        ileum.forEach { member ->
            assertSame(group, groups.find(member.id))
            assertEquals(group.id, groups.canonicalId(member.id))
            assertEquals(group.memberIds, groups.memberIds(member.id))
        }
        assertEquals(10, group.members.size)
        assertEquals("ileum-0", group.representative.nodeName)
    }

    @Test fun framingUsesAllBoundsRatherThanTheSelectedPieceCenter() {
        val groups = StructureGroups(listOf(
            part("a", min = Vec3(-.2f, .8f, .1f), max = Vec3(0f, 1f, .2f)),
            part("b", min = Vec3(0f, 1f, .2f), max = Vec3(.4f, 1.2f, .3f))))
        val group = groups.entries.single()
        assertEquals(Vec3(-.2f, .8f, .1f), group.representative.boundsMin)
        assertEquals(Vec3(.4f, 1.2f, .3f), group.representative.boundsMax)
        assertEquals(.1f, group.representative.center.x, .00001f)
        assertEquals(1f, group.representative.center.y, .00001f)
        assertEquals(.2f, group.representative.center.z, .00001f)
        assertTrue(group.focusDistance > .45f)
        assertTrue("Tall viewports must step back to keep the entire group in view",
            group.focusDistance(.6f) > group.focusDistance)
        assertEquals(group.focusDistance, group.focusDistance(1.8f), 0f)
        val small = StructureGroups(listOf(part("tiny"))).entries.single()
        assertEquals(.45f, small.focusDistance, 0f)
        val large = StructureGroups(listOf(part("large", min = Vec3(-2f, 0f, -2f),
            max = Vec3(2f, 2f, 2f)))).entries.single()
        assertEquals(2.8f, large.focusDistance, 0f)
    }

    @Test fun sourceIdentityAndNamesSeparateSidesConceptsDatasetsAndSystems() {
        val parts = listOf(part("base"), part("same"),
            part("left", nameZh = "左肺", nameEn = "left lung"),
            part("right", nameZh = "右肺", nameEn = "right lung"),
            part("other-concept", concept = "FMA999"),
            part("other-source", dataset = "Other dataset"),
            part("other-english", nameEn = "different anatomy"),
            part("other-system", system = "muscle"))
        val groups = StructureGroups(parts)
        assertEquals(7, groups.entries.size)
        assertEquals(setOf("base", "same"), groups.memberIds("base"))
        listOf("left", "right", "other-concept", "other-source", "other-english", "other-system")
            .forEach { assertEquals(setOf(it), groups.memberIds(it)) }
    }

    @Test fun sharedNameCanSpanRegionsDepthsAndTheBodyMidline() {
        val parts = listOf(part("a", region = "head_neck", layer = 1, center = Vec3(-.1f, 1f, 0f)),
            part("b", region = "trunk", layer = 3, center = Vec3(.1f, 1f, 0f)))
        val group = StructureGroups(parts).entries.single()
        assertEquals(setOf("head_neck", "trunk"), group.members.map { it.region }.toSet())
        assertEquals(0f, group.representative.center.x, 0f)
        assertEquals(setOf("a", "b"), group.memberIds)
    }

    @Test fun namesWithoutConceptIdsStillGroupButEmptyNamesNeverMerge() {
        val parts = listOf(part("a", concept = ""), part("b", concept = ""),
            part("unnamed-a", nameZh = "", nameEn = "", concept = ""),
            part("unnamed-b", nameZh = "", nameEn = "", concept = ""))
        val groups = StructureGroups(parts)
        assertEquals(3, groups.entries.size)
        assertEquals(setOf("a", "b"), groups.memberIds("b"))
        assertEquals(setOf("unnamed-a"), groups.memberIds("unnamed-a"))
    }

    @Test fun legacyHiddenOrIsolatedPieceControlsItsWholeGroup() {
        val other = part("heart", nameZh = "心脏", nameEn = "heart", concept = "FMA7088")
        val parts = ileum + other
        val groups = StructureGroups(parts)
        val state = LayerState(skin = false, muscle = false, bone = false)
        assertEquals(setOf("heart"), VisibilityPolicy.visibleIds(parts,
            state.copy(hiddenIds = setOf("ileum-7")), groups))
        assertEquals(ileum.map { it.id }.toSet(), VisibilityPolicy.visibleIds(parts,
            state.copy(isolateId = "ileum-9"), groups))
        assertTrue(VisibilityPolicy.visibleIds(parts,
            state.copy(isolateId = "ileum-2", hiddenIds = setOf("ileum-8")), groups).isEmpty())
        val hidden = VisibilityPolicy.hideOnly(state, groups.memberIds("ileum-4"))
        assertEquals(ileum.map { it.id }.toSet(), hidden.hiddenIds)
        assertEquals(state, hidden.copy(hiddenIds = emptySet()))
    }

    @Test fun groupingKeepsSystemDepthAndFocusVisibilityRules() {
        val parts = listOf(part("shallow", system = "muscle", layer = 1),
            part("deep", system = "muscle", layer = 3))
        val groups = StructureGroups(parts)
        assertEquals(1, groups.entries.size)
        val state = LayerState(skin = false, organ = false, bone = false,
            isolateId = "shallow", disabledRegionLayers = setOf("trunk:1"))
        assertEquals(setOf("deep"), VisibilityPolicy.visibleIds(parts, state, groups))
        assertTrue(VisibilityPolicy.visibleIds(parts, state.copy(muscle = false), groups).isEmpty())
        assertTrue(VisibilityPolicy.visibleIds(parts, state.copy(focusIds = setOf("shallow")), groups).isEmpty())
        assertEquals(setOf("shallow"), VisibilityPolicy.visibleIds(parts,
            state.copy(disabledRegionLayers = emptySet(), focusIds = setOf("shallow")), groups))
    }

    @Test fun unknownPersistedIdsRemainSafeAndUnchanged() {
        val groups = StructureGroups(ileum)
        assertNull(groups.find(null))
        assertNull(groups.find("future"))
        assertNull(groups.canonicalId("future"))
        assertTrue(groups.memberIds("future").isEmpty())
        assertEquals(setOf("future") + ileum.map { it.id }, groups.expand(setOf("future", "ileum-8")))
        assertTrue(VisibilityPolicy.visibleIds(ileum, LayerState(isolateId = "future"), groups).isEmpty())
    }
}
