package com.zga.meridianatlas.model

data class Vec3(val x: Float, val y: Float, val z: Float)
enum class ModelQuality { LOW, STANDARD }
enum class CameraPreset { FRONT, BACK, LEFT, RIGHT, RESET }
data class ProjectionAnchor(val id: String, val position: Vec3, val normal: Vec3? = null,
    val occlusionTest: Boolean = true)
data class ProjectedAnchor(val id: String, val xPx: Float, val yPx: Float, val depth: Float,
    val frontFacing: Boolean, val visible: Boolean, val occluded: Boolean = false)
data class FrameStats(val fps: Float, val frameTimeMs: Float, val frameCount: Long = 0L)

data class AnatomyStructure(
    val id: String, val nameZh: String, val nameEn: String, val system: String,
    val region: String, val layer: Int, val nodeName: String,
    val center: Vec3, val descriptionZh: String = "", val originZh: String = "",
    val insertionZh: String = "", val functionZh: String = "",
    val tissueType: String = "", val tissueTypeZh: String = "", val sourceConceptNoteZh: String = "",
    val sourceConceptId: String = "", val sourceDataset: String = "",
    val boundsMin: Vec3? = null, val boundsMax: Vec3? = null
)
data class PointInstance(val id: String, val side: String, val ordinal: Int,
    val position: Vec3?, val normal: Vec3? = null, val viewId: String = "body",
    val status: String = "unmapped", val landmarkRefs: List<String> = emptyList(), val surfaceId: String? = null,
    val labelZh: String? = null)
data class Acupoint(
    val id: String, val kind: String, val nameZh: String, val pinyin: String,
    val aliases: List<String>, val codes: List<String>, val meridianId: String?,
    val region: String, val locationZh: String, val anatomyZh: String,
    val sourceRefs: List<String>, val instances: List<PointInstance>,
    val reviewStatus: String,
    val traditionalIndicationsZh: String = "",
    val functionSourceRefs: List<String> = emptyList(),
    val functionReviewStatus: String = "pending"
)
data class RoutePath(val id: String, val side: String, val internal: Boolean,
    val points: List<Vec3>, val reviewStatus: String = "provisional")
data class Meridian(val id: String, val nameZh: String, val shortName: String,
    val routeType: String, val color: Long, val descriptionZh: String,
    val pointIds: List<String>, val paths: List<RoutePath>, val sourceRefs: List<String>)
enum class AtlasMode(val title: String) { OVERVIEW("综合图谱"), MERIDIANS("经络穴位"), MUSCLES("肌肉解剖") }
enum class OverviewPreset(val title: String) { SURFACE("体表"), ORGANS("内脏透视"), BONES("骨骼") }
enum class CutawaySide(val sign: Int, val title: String) { NONE(0, "关闭半身透视"), LEFT(1, "左半身透视"), RIGHT(-1, "右半身透视") }
data class LayerState(
    val skin: Boolean = true, val muscle: Boolean = true, val organ: Boolean = true,
    val bone: Boolean = true, val lines: Boolean = true, val points: Boolean = true,
    val labels: Boolean = true, val details: Boolean = true,
    val disabledRegionLayers: Set<String> = emptySet(), val hiddenIds: Set<String> = emptySet(),
    val isolateId: String? = null, val focusIds: Set<String> = emptySet(),
    val cutawaySide: CutawaySide = CutawaySide.NONE, val internalRelations: Boolean = false,
    val connective: Boolean = false, val spaces: Boolean = false,
    val fasciaChains: Boolean = false, val fasciaTargets: Boolean = true,
    val enabledFascialConnections: Set<String> = emptySet()
)
data class AtlasData(val structures: List<AnatomyStructure>, val points: List<Acupoint>,
    val meridians: List<Meridian>, val sourceNotes: String = "",
    val fascialConnections: List<FascialConnection> = emptyList()) {
    val structureGroups: StructureGroups by lazy { StructureGroups(structures) }
}
