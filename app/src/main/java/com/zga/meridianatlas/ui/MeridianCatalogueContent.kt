package com.zga.meridianatlas.ui

import com.zga.meridianatlas.model.Acupoint
import com.zga.meridianatlas.model.AtlasData
import com.zga.meridianatlas.model.Meridian
import com.zga.meridianatlas.model.SearchIndex

/** The visible rows own both lazy-list keys and selection destinations. */
internal class MeridianCatalogueContent(data: AtlasData) {
    val points = data.points.distinctBy { it.id }
    val meridians = data.meridians
    val routeIds = meridians.map { it.id }.toSet()
    val extraRegions = points.filter { it.kind == "extra" }.map { it.region }.toSet()
    private val byId = points.associateBy { it.id }
    private val primaryNames = meridians.associate { it.id to it.nameZh }
    private val linkedPoints = meridians.associate { route ->
        route.id to (route.pointIds.mapNotNull(byId::get) + points.filter { it.meridianId == route.id })
            .distinctBy { it.id }
    }
    private val routeTerms = points.associate { point ->
        point.id to meridians.filter { it.id == point.meridianId || point.id in it.pointIds }
            .joinToString(" ") { "${it.nameZh} ${it.shortName} ${it.id}" }
    }

    fun destination(pointId: String?): CatalogueDestination? {
        val point = pointId?.let(byId::get) ?: return null
        if (point.kind == "extra") return CatalogueDestination("extra", point.region, "extra:${point.id}")
        // A crossing point belongs in its primary meridian, even if an extraordinary
        // vessel containing it appears earlier in the route list.
        val primaryId = point.meridianId?.takeIf { it in routeIds } ?: return null
        return CatalogueDestination("routes", primaryId, "route:$primaryId:${point.id}")
    }

    fun rows(query: String, category: String, expandedRoutes: Set<String>,
        expandedRegions: Set<String>, showExtraRegions: Boolean): List<MeridianCatalogueRow> = buildList {
        if (query.isNotEmpty()) {
            val results = points.filter { point -> SearchIndex.matches(query, point.nameZh, point.pinyin,
                point.id, point.codes.joinToString(" "), point.aliases.joinToString(" "),
                routeTerms[point.id].orEmpty(), point.region) }
            add(MeridianCatalogueRow.Caption("search-count", "${results.size} 个穴位 · 按名称与归经查找"))
            results.forEach { point -> add(MeridianCatalogueRow.Point("search:${point.id}", point,
                primaryNames[point.meridianId] ?: "经外奇穴 · ${point.region}")) }
            if (results.isEmpty()) add(MeridianCatalogueRow.Empty)
        } else if (category == "routes") {
            add(MeridianCatalogueRow.Caption("route-heading", "经络路线 · 展开查看所属穴位与交会穴"))
            meridians.forEach { route ->
                val linked = linkedPoints.getValue(route.id)
                val sixExtra = route.routeType == "extraordinary" && route.id.uppercase() !in setOf("GV", "CV")
                val expanded = route.id in expandedRoutes
                add(MeridianCatalogueRow.RouteHeader(route, expanded,
                    if (sixExtra) "${linked.size} 个关联交会穴" else "${linked.size} 个所属穴位"))
                if (expanded) {
                    add(MeridianCatalogueRow.RouteDescription(route))
                    linked.forEach { point -> add(MeridianCatalogueRow.Point("route:${route.id}:${point.id}", point,
                        if (sixExtra) "交会穴 · ${primaryNames[point.meridianId].orEmpty()}" else null)) }
                }
            }
        } else {
            val extra = points.filter { it.kind == "extra" }
            add(MeridianCatalogueRow.ExtraHeader(extra.size, showExtraRegions))
            if (showExtraRegions) extra.groupBy { it.region }.forEach { (region, grouped) ->
                val expanded = region in expandedRegions
                add(MeridianCatalogueRow.RegionHeader(region, grouped.size, expanded))
                if (expanded) grouped.forEach { point ->
                    add(MeridianCatalogueRow.Point("extra:${point.id}", point, null))
                }
            }
        }
    }

    fun selectedRowKey(pointId: String?, query: String): String? =
        if (query.isNotEmpty()) pointId?.let { "search:$it" } else destination(pointId)?.key
}

internal data class CatalogueDestination(val category: String, val groupId: String, val key: String)

internal sealed interface MeridianCatalogueRow {
    val key: String
    data class Caption(override val key: String, val text: String) : MeridianCatalogueRow
    data class Point(override val key: String, val point: Acupoint, val subtitle: String?) : MeridianCatalogueRow
    data class RouteHeader(val meridian: Meridian, val expanded: Boolean, val subtitle: String) : MeridianCatalogueRow {
        override val key = "route:${meridian.id}"
    }
    data class RouteDescription(val meridian: Meridian) : MeridianCatalogueRow {
        override val key = "route-description:${meridian.id}"
    }
    data class ExtraHeader(val count: Int, val expanded: Boolean) : MeridianCatalogueRow {
        override val key = "extra-heading"
    }
    data class RegionHeader(val region: String, val count: Int, val expanded: Boolean) : MeridianCatalogueRow {
        override val key = "extra-region:$region"
    }
    data object Empty : MeridianCatalogueRow { override val key = "no-points" }
}
