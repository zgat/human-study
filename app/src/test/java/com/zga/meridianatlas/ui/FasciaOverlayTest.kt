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
            fasciaTargets = false, enabledFascialConnections = setOf("connection")))
        assertTrue(shown.targets.isEmpty())
        assertEquals(listOf(a,b), shown.anchors.map { it.position })
        assertTrue("Internal relation diagrams must not be snapped to skin", shown.anchors.none { it.occlusionTest })
    }
}
