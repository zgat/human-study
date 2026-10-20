package com.zga.meridianatlas.ui

import com.zga.meridianatlas.model.*
import org.junit.Assert.*
import org.junit.Test

/** Small fixed examples exercise selection and projection policy without a renderer or device. */
class OverlayPolicyTest {
    private val front = Vec3(0f, 0f, 1f)
    private val surface = listOf(Vec3(.1f, 1.2f, .2f), Vec3(.2f, 1.1f, .2f))
    private val internal = listOf(Vec3(0f, 1.2f, .05f), Vec3(0f, 1.0f, .05f))

    private fun instance(id: String, x: Float = 0f, view: String = "body", normal: Vec3 = front) =
        PointInstance(id, "left", 1, Vec3(x, 1f, .2f), normal, view)

    private fun point(id: String, meridian: String?, instances: List<PointInstance>) = Acupoint(
        id = id, kind = if (meridian == null) "extra" else "meridian", nameZh = id, pinyin = id,
        aliases = emptyList(), codes = listOf(id), meridianId = meridian, region = "测试部位",
        locationZh = "", anatomyZh = "", sourceRefs = emptyList(), instances = instances,
        reviewStatus = "provisional")

    private fun fixture(): AtlasData {
        val points = listOf(
            point("lu1", "LU", listOf(instance("lu1-left", .1f), instance("lu1-right", -.1f))),
            point("lu2", "LU", listOf(instance("lu2", .2f))),
            point("li1", "LI", listOf(instance("li1", .3f))),
            point("extra-paired", null, listOf(instance("extra-left", .4f), instance("extra-right", -.4f))),
            point("extra-oral", null, listOf(instance("oral", view = "oral", normal = Vec3(0f, -1f, 0f)))),
            point("extra-nasal", null, listOf(instance("nasal", view = "nasal", normal = Vec3(0f, 0f, -1f)))),
            point("unmapped", null, listOf(PointInstance("unmapped", "center", 1, null)))
        )
        val lung = Meridian("LU", "手太阴肺经", "肺经", "primary", 0xffffbb66L, "",
            listOf("lu1", "lu2"), listOf(RoutePath("surface", "left", false, surface),
                RoutePath("relations", "left", true, internal)), emptyList())
        val intestine = Meridian("LI", "手阳明大肠经", "大肠经", "primary", 0xff66bbffL, "",
            listOf("li1"), listOf(RoutePath("surface", "left", false, surface)), emptyList())
        return AtlasData(emptyList(), points, listOf(lung, intestine))
    }

    private fun visiblePoints(selected: String? = null) = buildOverlay(fixture(),
        setOf("LU", "LI", "__extra__"), showPoints = true, showLines = false, selectedPoint = selected).points

    @Test fun namesStayEmptyUntilAnActualPointIsSelected() {
        val points = visiblePoints()
        assertTrue(points.isNotEmpty())
        assertEquals(emptySet<String>(), acupointLabelIds(points, null))
        assertEquals(emptySet<String>(), acupointLabelIds(points, "unknown"))
    }

    @Test fun selectingLungPointNamesOnlyItsOwningMeridianAndDeduplicatesSides() {
        val points = visiblePoints("lu1")
        assertEquals(2, points.count { it.pointId == "lu1" })
        assertEquals(setOf("lu1", "lu2"), acupointLabelIds(points, "lu1"))
        assertTrue(points.filter { it.pointId in setOf("lu1", "lu2") }.all { it.meridianId == "LU" })
    }

    @Test fun selectingAnExtraPointDoesNotNameOtherNullMeridianEntries() {
        val points = visiblePoints("extra-paired")
        assertEquals(2, points.count { it.pointId == "extra-paired" })
        assertEquals(setOf("extra-paired"), acupointLabelIds(points, "extra-paired"))
    }

    @Test fun internalRelationsHaveNoLineOrAnchorUntilExplicitlyEnabled() {
        val data = fixture()
        val ordinary = buildOverlay(data, setOf("LU"), false, true, null)
        assertEquals(1, ordinary.lines.size)
        assertFalse(ordinary.lines.single().internal)
        assertTrue(ordinary.anchors.map { it.position }.containsAll(surface))
        assertTrue(ordinary.anchors.all { it.occlusionTest })

        val enabled = buildOverlay(data, setOf("LU"), false, true, null, showInternalRelations = true)
        assertEquals(2, enabled.lines.size)
        val ids = enabled.lines.single { it.internal }.anchorIds.toSet()
        val relationAnchors = enabled.anchors.filter { it.id in ids }
        assertTrue(relationAnchors.map { it.position }.containsAll(internal))
        assertTrue(relationAnchors.all { !it.occlusionTest && it.normal == null })
    }

    @Test fun disablingAllLinesAlsoSuppressesExplicitInternalRelations() {
        val overlay = buildOverlay(fixture(), setOf("LU"), false, false, null, showInternalRelations = true)
        assertTrue(overlay.lines.isEmpty())
        assertTrue(overlay.anchors.isEmpty())
    }

    @Test fun hitTargetsCoverUnshownRoutesWithoutCreatingVisibleDots() {
        val expected = setOf("hitpoint:lu1|0", "hitpoint:lu1|1", "hitpoint:lu2|0", "hitpoint:li1|0",
            "hitpoint:extra-paired|0", "hitpoint:extra-paired|1", "hitpoint:extra-oral|0", "hitpoint:extra-nasal|0")
        for (showPoints in listOf(false, true)) {
            val overlay = buildOverlay(fixture(), emptySet(), showPoints, true, null, includeHitTargets = true)
            assertEquals(expected, overlay.anchors.map { it.id }.toSet())
            assertEquals(expected.size, overlay.anchors.size)
            assertTrue(overlay.points.isEmpty())
            assertTrue(overlay.lines.isEmpty())
        }
        assertTrue(buildOverlay(fixture(), emptySet(), true, true, null).anchors.isEmpty())
    }

    @Test fun hitTargetsKeepSurfaceNormalsAndLocalViewOcclusionRules() {
        val anchors = buildOverlay(fixture(), emptySet(), false, false, null,
            includeHitTargets = true).anchors.associateBy { it.id }
        assertEquals(front, anchors.getValue("hitpoint:lu1|0").normal)
        assertTrue(anchors.getValue("hitpoint:lu1|0").occlusionTest)
        assertEquals(Vec3(0f, -1f, 0f), anchors.getValue("hitpoint:extra-oral|0").normal)
        assertFalse(anchors.getValue("hitpoint:extra-oral|0").occlusionTest)
        assertFalse(anchors.getValue("hitpoint:extra-nasal|0").occlusionTest)
        assertFalse(anchors.keys.any { "unmapped" in it })
    }

    @Test fun repeatedCanonicalEntriesCannotDuplicateHitOrVisibleAnchorIds() {
        val original = fixture()
        val duplicate = original.copy(points = original.points + original.points.first())
        val overlay = buildOverlay(duplicate, setOf("LU"), true, false, null, includeHitTargets = true)
        assertEquals(3, overlay.points.size)
        assertEquals(8, overlay.anchors.count { it.id.startsWith("hitpoint:") })
        assertEquals(overlay.anchors.size, overlay.anchors.map { it.id }.toSet().size)
    }

    @Test fun activeRouteMetadataSupportsPointSelectionAndExplicitRouteChanges() {
        val inferred = buildOverlay(fixture(), setOf("LU", "LI"), true, true, "lu1")
        assertEquals("LU", inferred.selectedMeridianId)
        assertEquals(setOf("LU", "LI"), inferred.lines.mapNotNull { it.meridianId }.toSet())
        val explicit = buildOverlay(fixture(), setOf("LU", "LI"), true, true, "lu1", selectedMeridianId = "LI",
            additionalEmphasizedMeridian = "LI")
        assertEquals("LU", explicit.selectedMeridianId)
        assertEquals(setOf("LU", "LI"), explicit.emphasizedMeridianIds)
        assertEquals("LI", buildOverlay(fixture(), setOf("LU", "LI"), true, true, null,
            selectedMeridianId = "LI").selectedMeridianId)
    }
}
