package com.zga.meridianatlas.model

import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.sqrt

/** One anatomical selection can contain several independently stored mesh nodes. */
data class StructureGroup(
    val representative: AnatomyStructure,
    val members: List<AnatomyStructure>
) {
    val id: String get() = representative.id
    val memberIds: Set<String> = members.mapTo(linkedSetOf()) { it.id }

    // Frame the combined bounds using the viewer's 45-degree vertical field of view.
    // The small-structure minimum retains the existing comfortable viewing distance.
    val focusDistance: Float get() = focusDistance(1f)

    /** Respect the narrower field of view when a phone's model window is tall. */
    fun focusDistance(aspectRatio: Float): Float {
        val min = representative.boundsMin ?: representative.center
        val max = representative.boundsMax ?: representative.center
        val x = max.x - min.x
        val y = max.y - min.y
        val z = max.z - min.z
        val radius = sqrt(x * x + y * y + z * z) / 2f
        val halfFov = atan(tan(Math.PI.toFloat() / 8f) * aspectRatio.coerceIn(.2f, 1f))
        return (radius / sin(halfFov) * 1.15f).coerceIn(.45f, 2.8f)
    }
}

/** Keeps source geometry intact while presenting equivalent pieces as one structure. */
class StructureGroups(structures: List<AnatomyStructure>) {
    private data class Key(
        val system: String,
        val nameZh: String,
        val nameEn: String,
        val sourceDataset: String,
        val sourceConcept: String,
        val unnamedId: String?
    )

    val entries: List<StructureGroup> = structures.groupBy { structure ->
        Key(structure.system, structure.nameZh, structure.nameEn, structure.sourceDataset,
            structure.sourceConceptId.ifBlank { structure.nameEn },
            structure.id.takeIf { structure.nameZh.isBlank() && structure.nameEn.isBlank() })
    }.values.map { members ->
        val minimums = members.map { it.boundsMin ?: it.center }
        val maximums = members.map { it.boundsMax ?: it.center }
        val min = Vec3(minimums.minOf { it.x }, minimums.minOf { it.y }, minimums.minOf { it.z })
        val max = Vec3(maximums.maxOf { it.x }, maximums.maxOf { it.y }, maximums.maxOf { it.z })
        val center = Vec3((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)
        StructureGroup(members.first().copy(center = center, boundsMin = min, boundsMax = max), members)
    }

    private val byMemberId: Map<String, StructureGroup> = buildMap {
        this@StructureGroups.entries.forEach { group -> group.memberIds.forEach { id -> put(id, group) } }
    }

    fun find(id: String?): StructureGroup? = id?.let(byMemberId::get)
    fun canonicalId(id: String?): String? = find(id)?.id
    fun memberIds(id: String?): Set<String> = find(id)?.memberIds ?: emptySet()

    /** Unknown persisted IDs remain intact, allowing a later data update to resolve them. */
    fun expand(ids: Set<String>): Set<String> = ids.flatMapTo(linkedSetOf()) { id ->
        find(id)?.memberIds ?: setOf(id)
    }
}
