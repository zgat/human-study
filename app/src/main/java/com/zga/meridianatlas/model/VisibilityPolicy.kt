package com.zga.meridianatlas.model

object VisibilityPolicy {
    fun systemVisible(state: LayerState, system: String): Boolean = when (system) {
        "skin" -> state.skin; "muscle" -> state.muscle; "organ" -> state.organ
        "bone" -> state.bone; "connective" -> state.connective; "space" -> state.spaces
        else -> false
    }

    /** Master switches preserve regional, per-structure and temporary observation choices. */
    fun setSystemVisible(state: LayerState, system: String, visible: Boolean): LayerState = when (system) {
        "skin" -> state.copy(skin = visible); "muscle" -> state.copy(muscle = visible)
        "organ" -> state.copy(organ = visible); "bone" -> state.copy(bone = visible)
        "connective" -> state.copy(connective = visible); "space" -> state.copy(spaces = visible)
        else -> state
    }

    fun hideOnly(state: LayerState, id: String): LayerState = state.copy(hiddenIds = state.hiddenIds + id)
    fun hideOnly(state: LayerState, ids: Set<String>): LayerState = state.copy(hiddenIds = state.hiddenIds + ids)
    fun isRelevantStructure(mode: AtlasMode, structure: AnatomyStructure): Boolean = when (mode) {
        AtlasMode.OVERVIEW -> true
        AtlasMode.MUSCLES -> structure.system == "muscle"
        AtlasMode.MERIDIANS -> false
    }
    fun visibleIds(structures: List<AnatomyStructure>, state: LayerState,
        groups: StructureGroups = StructureGroups(structures)): Set<String> {
        val hiddenIds = groups.expand(state.hiddenIds)
        val isolatedIds = groups.memberIds(state.isolateId)
        return structures.asSequence().filter { structure ->
            val systemEnabled = if (CutawayPolicy.isFacialSurface(structure)) state.skin || state.organ
                else systemVisible(state, structure.system)
            systemEnabled && structure.id !in hiddenIds &&
                (state.focusIds.isEmpty() || structure.id in state.focusIds) &&
                (state.isolateId == null || structure.id in isolatedIds) &&
                (structure.system != "muscle" || "${structure.region}:${structure.layer}" !in state.disabledRegionLayers)
        }.map { it.id }.toSet()
    }
    fun defaults(mode: AtlasMode): LayerState = when (mode) {
        AtlasMode.OVERVIEW -> LayerState(lines = false, points = false, connective = true)
        AtlasMode.MERIDIANS -> LayerState(muscle = false, organ = false, bone = false)
        AtlasMode.MUSCLES -> LayerState(skin = false, organ = false, bone = false, lines = false, points = false)
    }
    fun migrateLegacyDefault(mode: AtlasMode, state: LayerState): LayerState =
        if (mode == AtlasMode.MUSCLES && state == LayerState(skin = false, organ = false, lines = false, points = false))
            defaults(mode) else state

    /** Only preferences saved before the half-body fix should pass through this migration. */
    fun migrateLegacyHalfBody(mode: AtlasMode, state: LayerState): LayerState =
        if (mode == AtlasMode.MERIDIANS && state.cutawaySide != CutawaySide.NONE)
            state.copy(muscle = true) else state

    fun overviewPreset(state: LayerState, preset: OverviewPreset): LayerState = state.copy(
        skin = preset == OverviewPreset.SURFACE, muscle = preset == OverviewPreset.SURFACE,
        organ = preset != OverviewPreset.BONES, bone = true, connective = preset == OverviewPreset.SURFACE,
        lines = false, points = false,
        disabledRegionLayers = emptySet(), hiddenIds = emptySet(), isolateId = null, focusIds = emptySet(), cutawaySide = CutawaySide.NONE)

    fun meridianView(state: LayerState, organs: Boolean, side: CutawaySide = CutawaySide.NONE): LayerState = state.copy(
        skin = !organs || side != CutawaySide.NONE, muscle = organs && side != CutawaySide.NONE,
        organ = organs, bone = organs, connective = false, cutawaySide = if (organs) side else CutawaySide.NONE,
        hiddenIds = emptySet(), isolateId = null, focusIds = emptySet())

    fun muscleDepth(state: LayerState, structures: List<AnatomyStructure>, depth: Int?): LayerState {
        val muscles = structures.filter { it.system == "muscle" }
        val disabled = if (depth == null) emptySet() else muscles.filter { it.layer != depth }
            .map { "${it.region}:${it.layer}" }.toSet()
        return state.copy(skin = false, muscle = true, organ = false, connective = false, lines = false, points = false,
            disabledRegionLayers = disabled, hiddenIds = if (depth == null) state.hiddenIds - muscles.map { it.id }.toSet() else state.hiddenIds,
            isolateId = null, focusIds = emptySet(), cutawaySide = CutawaySide.NONE)
    }
}
