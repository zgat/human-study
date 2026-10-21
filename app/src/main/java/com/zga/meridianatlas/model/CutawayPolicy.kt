package com.zga.meridianatlas.model

/** Anatomical surfaces removed by the sagittal cut; renderer clips their actual fragments. */
object CutawayPolicy {
    private val facialSurfaceNames = setOf(
        "left cornea", "right cornea", "left sclera", "right sclera",
        "left iris", "right iris", "lip", "gingiva of upper jaw", "gingiva of lower jaw"
    )
    private val toothName = Regex("\\b(tooth|teeth)\\b")
    private val ocularChamberName = Regex("anterior chamber of (left|right) eyeball")

    fun isFacialSurface(structure: AnatomyStructure): Boolean =
        structure.system == "organ" && structure.nameEn.trim().lowercase() in facialSurfaceNames

    fun outerIds(structures: List<AnatomyStructure>): Set<String> = structures.asSequence().filter {
        it.system == "skin" || it.system == "muscle" || isFacialSurface(it)
    }.map { it.id }.toSet()

    /** Structures allowed outside fully opaque skin; this does not enable their layer. */
    fun exteriorIds(structures: List<AnatomyStructure>): Set<String> = structures.asSequence().filter {
        it.system == "skin" || isFacialSurface(it) ||
            (it.system == "bone" && toothName.containsMatchIn(it.nameEn.lowercase())) ||
            (it.system == "space" && ocularChamberName.matches(it.nameEn.trim().lowercase()))
    }.map { it.id }.toSet()

    /**
     * A visible skin shell covers underlying tissue even when separately registered meshes
     * protrude through it. In a half view the surface cut already removes exposed-side
     * muscles; their remaining, skin-covered half must not leak through the intact shell.
     * Deep organs and bones stay whole and visible through the opening. This display rule
     * changes neither source geometry nor enabled layers or anatomical registration.
     */
    fun isOccludedBySkin(id: String, opaqueSkinVisible: Boolean, halfBody: Boolean,
        skinIds: Set<String>, exteriorIds: Set<String>, cutSurfaceIds: Set<String>): Boolean =
        opaqueSkinVisible && id !in skinIds && id !in exteriorIds &&
            (!halfBody || id in cutSurfaceIds)
}
