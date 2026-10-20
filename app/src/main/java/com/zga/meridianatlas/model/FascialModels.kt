package com.zga.meridianatlas.model

/** Research-backed structural relations, without force vectors or clinical target claims. */
data class FascialSource(
    val id: String,
    val title: String,
    val url: String,
    val year: Int,
    val evidenceType: String,
    val noteZh: String = ""
)

data class FascialTarget(
    val id: String,
    val nameZh: String,
    val position: Vec3,
    val structureIds: List<String>,
    val descriptionZh: String,
    val normal: Vec3? = null,
    val side: String = "midline",
    val viewId: String = "body",
    val reviewStatus: String = "provisional",
    val regionRadiusM: Float = .012f,
    /** Exact node/primitive/triangle/barycentric provenance retained from content JSON. */
    val sourceGeometry: String = ""
)

data class FascialPath(
    val id: String,
    val side: String,
    val points: List<Vec3>,
    val targetIds: List<String> = emptyList(),
    val kind: String = "unoriented_connection_schematic",
    val reviewStatus: String = "provisional"
)

data class FascialConnection(
    val id: String,
    val nameZh: String,
    val descriptionZh: String,
    val evidenceZh: String,
    val color: Long,
    val structureIds: List<String>,
    val paths: List<FascialPath>,
    val targets: List<FascialTarget>,
    val sourceRefs: List<FascialSource>,
    val geometryCoverageZh: String = "",
    val unmappedStructures: List<String> = emptyList(),
    val reviewStatus: String = "provisional"
)
