package com.zga.meridianatlas.data

import com.zga.meridianatlas.model.*
import org.json.JSONObject

internal fun decodeFascialConnection(item: JSONObject): FascialConnection {
    val color = runCatching { when (val raw = item.get("color")) {
        is Number -> raw.toLong() and 0xffffffffL
        else -> android.graphics.Color.parseColor(raw.toString()).toLong() and 0xffffffffL
    } }
        .getOrDefault(0xff65ddc3L)
    return FascialConnection(item.getString("id"), item.getString("nameZh"), item.getString("descriptionZh"),
        item.getString("evidenceZh"), color, item.optJSONArray("structureIds").strings(),
        item.optJSONArray("paths").objects().map { path ->
            FascialPath(path.getString("id"), path.optString("side"),
                path.optJSONArray("points").let { points -> if (points == null) emptyList() else
                    (0 until points.length()).mapNotNull { points.optJSONArray(it).vec() } },
                path.optJSONArray("targetIds").strings(), path.optString("kind"), path.optString("reviewStatus"),
                normals = path.optJSONArray("normals").let { normals -> if (normals == null) emptyList() else
                    (0 until normals.length()).mapNotNull { normals.optJSONArray(it).vec() } },
                evidenceIds = path.optJSONArray("evidenceIds").strings(), meaningZh = path.optString("meaningZh"))
        }, item.optJSONArray("targets").objects().map { target ->
            FascialTarget(target.getString("id"), target.getString("nameZh"),
                requireNotNull(target.optJSONArray("position").vec()), target.optJSONArray("structureIds").strings(),
                target.getString("descriptionZh"), target.optJSONArray("normal").vec(), target.optString("side"),
                target.optString("viewId", "body"), target.optString("reviewStatus", "provisional"),
                target.optDouble("regionRadiusM", .012).toFloat(), target.optJSONObject("sourceGeometry")?.toString().orEmpty())
        }, item.optJSONArray("sourceRefs").objects().map { source ->
            FascialSource(source.getString("id"), source.getString("title"), source.getString("url"),
                source.getInt("year"), source.getString("evidenceType"), source.optString("noteZh"))
        }, item.optString("geometryCoverageZh"), item.optJSONArray("unmappedStructures").strings(),
        item.optString("reviewStatus", "provisional"),
        coverageKind = item.optString("coverageKind", "local_research_relation"),
        bodyRegionsZh = item.optJSONArray("bodyRegionsZh").strings(),
        preferredViewNormal = item.optJSONArray("preferredViewNormal").vec(),
        evidenceReferences = item.optJSONArray("evidenceReferences").objects().map { evidence ->
            FascialEvidence(evidence.getString("id"), evidence.getString("kind"),
                evidence.optJSONArray("sourceIds").strings(), evidence.getString("scopeZh"),
                evidence.getString("sampleZh"), evidence.getString("conditionsZh"), evidence.getString("measurementZh"),
                evidence.getString("limitationsZh"), evidence.optJSONArray("relatedPathIds").strings())
        })
}
