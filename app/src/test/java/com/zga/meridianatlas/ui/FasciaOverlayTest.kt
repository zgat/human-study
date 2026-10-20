package com.zga.meridianatlas.ui

import com.zga.meridianatlas.model.*
import org.junit.Assert.*
import org.junit.Test

class FasciaOverlayTest {
    private val a = Vec3(.1f, .8f, .03f)
    private val b = Vec3(-.1f, .9f, -.04f)
    private val fixture = FascialConnection("connection", "研究连接", "连接关系", "研究来源", 0xff65ddc3,
        listOf("muscle", "fascia"), listOf(FascialPath("path", "cross", listOf(a,b))),
        listOf(FascialTarget("region", "连接参考区", a, listOf("fascia"), "代表区域")), emptyList())

    @Test fun studyDisplayIsExplicitAndDoesNotActivateOtherConnections() {
        assertTrue(buildFascialOverlay(listOf(fixture), LayerState()).anchors.isEmpty())
        assertTrue(buildFascialOverlay(listOf(fixture), LayerState(fasciaChains = true)).anchors.isEmpty())
        val other = fixture.copy(id = "unselected")
        val shown = buildFascialOverlay(listOf(fixture, other), LayerState(fasciaChains = true,
            enabledFascialConnections = setOf("connection")))
        assertEquals(1, shown.lines.size)
        assertEquals(1, shown.targets.size)
    }

    @Test fun markerSwitchLeavesUnorientedReferenceLinesAndTheirCoordinatesUntouched() {
        val shown = buildFascialOverlay(listOf(fixture), LayerState(fasciaChains = true,
            skin = false, fasciaTargets = false, enabledFascialConnections = setOf("connection")))
        assertTrue(shown.targets.isEmpty())
        assertEquals(a, shown.anchors.first().position)
        assertEquals(b, shown.anchors.last().position)
        assertTrue("Internal relation diagrams must not be snapped to skin", shown.anchors.none { it.occlusionTest })
        assertTrue("Fascia must bypass the skin-route conformance namespace", shown.anchors.none { it.id.startsWith("route:") })
        assertEquals("connection", shown.targetConnectionIds["region"])
    }

    @Test fun linearDisplaySamplingKeepsCornersAndDoesNotCurveOrMoveSourcePoints() {
        val corner = Vec3(.1f, .9f, .03f)
        val source = listOf(a, corner, b)
        val sampled = sampleFascialRelation(source)
        assertEquals(a, sampled.first())
        assertEquals(b, sampled.last())
        assertTrue(corner in sampled)
        assertEquals(listOf(a, corner, b), source)
        val bend = sampled.indexOf(corner)
        assertTrue(sampled.take(bend).all { it.x == a.x && it.z == a.z })
        sampled.zipWithNext().forEach { (p, q) ->
            val length = kotlin.math.sqrt((p.x-q.x)*(p.x-q.x)+(p.y-q.y)*(p.y-q.y)+(p.z-q.z)*(p.z-q.z))
            assertTrue(length <= .00601f)
        }
    }

    @Test fun realSurfaceContextSamplesAndNormalsStayExact() {
        val normals = listOf(Vec3(1f,0f,0f), Vec3(-1f,0f,0f))
        val connection = fixture.copy(paths = listOf(FascialPath("context", "cross", listOf(a,b),
            kind = "anatomical_context_schematic", normals = normals)))
        val shown = buildFascialOverlay(listOf(connection), LayerState(fasciaChains = true,
            fasciaTargets = false, fasciaContext = true, enabledFascialConnections = setOf("connection")))
        assertEquals(listOf(a,b), shown.anchors.map { it.position })
        assertEquals(normals, shown.anchors.map { it.normal })
        assertEquals("anatomical_context_schematic", shown.lines.single().kind)
    }

    @Test fun realTissueExtentIsOptionalAndOffByDefaultRatherThanImplyingAForceRoute() {
        val context = fixture.paths.single().copy(id = "range", kind = "anatomical_context_schematic")
        val connection = fixture.copy(paths = fixture.paths + context)
        val layer = LayerState(fasciaChains = true, enabledFascialConnections = setOf("connection"))
        assertFalse(layer.fasciaContext)
        assertTrue(layer.fasciaRelations)
        val shown = buildFascialOverlay(listOf(connection), layer)
        assertEquals(listOf("unoriented_connection_schematic"), shown.lines.map { it.kind })
        assertEquals(1, shown.targets.size)
    }

    @Test fun independentRelationAndRangeSwitchesNeverRewriteReferenceSelectionOrCoordinates() {
        val context = fixture.paths.single().copy(id = "range", kind = "anatomical_context_schematic")
        val connection = fixture.copy(paths = fixture.paths + context)
        val base = LayerState(fasciaChains = true, skin = false, enabledFascialConnections = setOf("connection"))
        for (relation in listOf(false, true)) for (range in listOf(false, true)) for (markers in listOf(false, true)) {
            val shown = buildFascialOverlay(listOf(connection), base.copy(fasciaRelations = relation,
                fasciaContext = range, fasciaTargets = markers))
            assertEquals(if (relation) 1 else 0, shown.lines.count { it.kind == "unoriented_connection_schematic" })
            assertEquals(if (range) 1 else 0, shown.lines.count { it.kind == "anatomical_context_schematic" })
            assertEquals(if (markers) 1 else 0, shown.targets.size)
            assertEquals("connection", shown.targetConnectionIds["region"])
            if (markers) assertEquals(a, shown.anchors.single { it.id == "fascial-target:region" }.position)
        }
    }

    @Test fun unknownPathKindsAreNotRenderedAsEvidenceOrAnatomicalGeometry() {
        val connection = fixture.copy(paths = listOf(fixture.paths.single().copy(kind = "hypothetical_force_vector")))
        val shown = buildFascialOverlay(listOf(connection), LayerState(fasciaChains = true,
            fasciaRelations = true, fasciaContext = true, enabledFascialConnections = setOf("connection")))
        assertTrue(shown.lines.isEmpty())
        assertEquals(1, shown.targets.size)
    }

    @Test fun anatomyCatalogueIncludesFasciaAponeurosisAndRetinaculaWithoutRelabelingOtherTissues() {
        fun structure(id: String, tissue: String, system: String = "connective") = AnatomyStructure(
            id, id, id, system, "trunk", 0, id, a, tissueType = tissue)
        val groups = StructureGroups(listOf(structure("fascia", "fascia"), structure("sheet", "aponeurosis"),
            structure("support", "retinaculum"), structure("tendon", "tendon"), structure("ligament", "ligament"), structure("muscle", "fascia", "muscle")))
        assertEquals(setOf("fascia", "sheet", "support"), fasciaAnatomyCatalogue(groups.entries).map { it.id }.toSet())
    }

    @Test fun onlyIntactDisplayedSkinUsesTheFixedSkinOcclusionMesh() {
        val layer = LayerState(fasciaChains = true, enabledFascialConnections = setOf("connection"))
        assertTrue(buildFascialOverlay(listOf(fixture), layer).anchors.all { it.occlusionTest })
        assertTrue(buildFascialOverlay(listOf(fixture), layer.copy(skin = false)).anchors.none { it.occlusionTest })
        for (side in listOf(CutawaySide.LEFT, CutawaySide.RIGHT))
            assertTrue(buildFascialOverlay(listOf(fixture), layer.copy(cutawaySide = side)).anchors.none { it.occlusionTest })
    }

    @Test fun hiddenSamplesBreakConnectionsWithoutBridgingAcrossAnOccludedArea() {
        fun point(id: String, visible: Boolean = true, occluded: Boolean = false) =
            ProjectedAnchor(id, 10f, 10f, 1f, true, visible, occluded)
        val projected = listOf(point("a"), point("b"), point("hidden", occluded = true),
            point("c"), point("d"), point("offscreen", visible = false), point("single"))
            .associateBy { it.id }
        val segments = visibleFascialSegments(listOf("a", "b", "hidden", "c", "d", "offscreen", "single", "missing"), projected)
        assertEquals(listOf(listOf("a","b"), listOf("c","d")), segments.map { segment -> segment.map { it.id } })
    }
}
