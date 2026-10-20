package com.zga.meridianatlas

import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.MeridianCatalogueContent
import com.zga.meridianatlas.ui.MeridianCatalogueRow
import org.junit.Assert.*
import org.junit.Test

class MeridianCatalogueContentTest {
    private fun point(id: String, route: String? = "LU", kind: String = "standard", region: String = "上肢") =
        Acupoint(id, kind, id, id, emptyList(), listOf(id), route, region, "", "",
            emptyList(), emptyList(), "reviewed")

    private fun route(id: String, points: List<String>, type: String = "primary") =
        Meridian(id, "$id 经", id, type, 0L, "$id 路线说明", points, emptyList(), emptyList())

    private val lu1 = point("LU1")
    private val lu2 = point("LU2")
    private val li1 = point("LI1", "LI")
    private val extraHead = point("EX-HN1", null, "extra", "头颈")
    private val extraArm = point("EX-UE1", null, "extra", "上肢")
    private val content = MeridianCatalogueContent(AtlasData(emptyList(),
        listOf(lu1, lu2, li1, extraHead, extraArm, lu1),
        listOf(route("YINQIAO", listOf("LU2"), "extraordinary"),
            route("LI", listOf("LI1")), route("LU", listOf("LU1", "LU2", "LU1")))))

    @Test fun crossingPointNavigatesToPrimaryMeridianInsteadOfEarlierAssociatedVessel() {
        val destination = content.destination("LU2")!!
        assertEquals("routes", destination.category)
        assertEquals("LU", destination.groupId)
        assertEquals("route:LU:LU2", destination.key)
        assertNull(content.destination("missing"))
        assertNull(content.destination(null))
    }

    @Test fun destinationIndexUsesExactRowsIncludingOtherExpandedGroupsAndDescriptions() {
        val rows = content.rows("", "routes", setOf("YINQIAO", "LI", "LU"), emptySet(), true)
        val key = content.destination("LU2")!!.key
        assertEquals(10, rows.indexOfFirst { it.key == key })
        assertEquals(lu2, (rows[10] as MeridianCatalogueRow.Point).point)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(2, rows.filterIsInstance<MeridianCatalogueRow.Point>().count { it.point.id == "LU2" })
    }

    @Test fun extraPointDestinationOpensMatchingRegionAndHasAVisibleRow() {
        val destination = content.destination(extraArm.id)!!
        assertEquals("extra", destination.category)
        assertEquals("上肢", destination.groupId)
        val rows = content.rows("", destination.category, emptySet(), setOf(destination.groupId), true)
        assertEquals(3, rows.indexOfFirst { it.key == destination.key })
        assertEquals(setOf("头颈", "上肢"), content.extraRegions)
        assertFalse(content.rows("", "extra", emptySet(), content.extraRegions, false)
            .any { it is MeridianCatalogueRow.Point })
    }

    @Test fun collapsingAGroupDoesNotLeaveAnInvalidSelectedRowIndex() {
        val routeRows = content.rows("", "routes", emptySet(), emptySet(), true)
        assertEquals(-1, routeRows.indexOfFirst { it.key == content.destination("LU2")!!.key })
        assertEquals(4, routeRows.size)
        val extraRows = content.rows("", "extra", emptySet(), emptySet(), true)
        assertEquals(-1, extraRows.indexOfFirst { it.key == content.destination(extraHead.id)!!.key })
        assertEquals(3, extraRows.size)
    }

    @Test fun searchIgnoresCategoryAndAssociatedRoutesDoNotDuplicateCanonicalResults() {
        val extraResult = content.rows("EX-UE1", "routes", emptySet(), emptySet(), false)
            .filterIsInstance<MeridianCatalogueRow.Point>()
        assertEquals(listOf(extraArm), extraResult.map { it.point })
        val standardResult = content.rows("LU2", "extra", emptySet(), emptySet(), false)
            .filterIsInstance<MeridianCatalogueRow.Point>()
        assertEquals(listOf(lu2), standardResult.map { it.point })
        assertEquals("search:LU2", content.selectedRowKey("LU2", "LU2"))
        val associationResult = content.rows("YINQIAO", "extra", emptySet(), emptySet(), false)
            .filterIsInstance<MeridianCatalogueRow.Point>()
        assertEquals(listOf(lu2), associationResult.map { it.point })
    }
}
