package com.zga.meridianatlas.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zga.meridianatlas.data.*
import com.zga.meridianatlas.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AtlasUiState(
    val loading: Boolean = true, val error: String? = null, val data: AtlasData? = null,
    val mode: AtlasMode = AtlasMode.OVERVIEW, val quality: ModelQuality = ModelQuality.STANDARD,
    val layers: Map<AtlasMode, LayerState> = AtlasMode.entries.associateWith(VisibilityPolicy::defaults),
    val enabledMeridians: Set<String> = emptySet(), val selectedMeridian: String? = null,
    val selectedPoint: String? = null, val selectedStructure: String? = null,
    val canRestore: Boolean = false, val activeMeridian: String? = null
) {
    val layer: LayerState get() = layers.getValue(mode)
    val selectedPointMeridian: String? get() = data?.points?.firstOrNull { it.id == selectedPoint }?.meridianId

    /** A route checkbox changes the browsing set, not the selected point's anatomical context. */
    val effectiveMeridians: Set<String> get() = MeridianSelectionPolicy.effective(enabledMeridians, selectedPointMeridian)
}

class AtlasViewModel(application: Application) : AndroidViewModel(application) {
    private data class FocusSnapshot(val mode: AtlasMode, val layer: LayerState, val meridians: Set<String>? = null)
    private val repository = AtlasRepository(application)
    private val preferences = AtlasPreferences(application)
    private val mutable = MutableStateFlow(AtlasUiState())
    val state = mutable.asStateFlow()
    private var saveJob: Job? = null
    private val focusSnapshots = mutableMapOf<AtlasMode, FocusSnapshot>()
    init { reload() }
    fun reload() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val data = repository.load()
                val saved = preferences.read()
                val defaultMeridian = data.meridians.firstOrNull()?.id
                val enabled = saved?.meridians ?: setOfNotNull(defaultMeridian)
                mutable.value = AtlasUiState(loading = false, data = data,
                    mode = saved?.mode ?: AtlasMode.OVERVIEW, quality = saved?.quality ?: ModelQuality.STANDARD,
                    layers = saved?.states ?: AtlasMode.entries.associateWith(VisibilityPolicy::defaults),
                    enabledMeridians = enabled, selectedMeridian = enabled.lastOrNull { it != "__extra__" })
            } catch (error: Exception) {
                mutable.update { it.copy(loading = false, error = error.message ?: "本地资源读取失败") }
            }
        }
    }
    private fun persist() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(350)
            val value = mutable.value
            val stableLayers = value.layers.mapValues { (mode, layer) -> focusSnapshots[mode]?.layer?.copy(
                fasciaChains = layer.fasciaChains, fasciaTargets = layer.fasciaTargets,
                enabledFascialConnections = layer.enabledFascialConnections) ?: layer }
            val stableMeridians = focusSnapshots[AtlasMode.MERIDIANS]?.meridians ?: value.enabledMeridians
            preferences.write(SavedPreferences(value.mode, value.quality, stableLayers, stableMeridians))
        }
    }
    fun mode(mode: AtlasMode) { mutable.update { it.copy(mode = mode, canRestore = mode in focusSnapshots) }; persist() }
    fun quality(quality: ModelQuality) { mutable.update { it.copy(quality = quality) }; persist() }
    fun layer(transform: (LayerState) -> LayerState) {
        mutable.update { it.copy(layers = it.layers + (it.mode to transform(it.layer))) }; persist()
    }
    fun toggleAnatomySystem(system: String) {
        val value = mutable.value
        val enabled = !VisibilityPolicy.systemVisible(value.layer, system)
        focusSnapshots[value.mode]?.let { snapshot ->
            focusSnapshots[value.mode] = snapshot.copy(layer = VisibilityPolicy.setSystemVisible(snapshot.layer, system, enabled))
        }
        layer { VisibilityPolicy.setSystemVisible(it, system, enabled) }
    }
    fun toggleMeridian(id: String) {
        setMeridians(setOf(id), id !in mutable.value.enabledMeridians)
    }
    fun setMeridians(ids: Set<String>, checked: Boolean) {
        val value = mutable.value
        val validIds = ids.intersect(value.data?.meridians?.map { it.id }.orEmpty().toSet() + "__extra__")
        if (validIds.isEmpty()) return
        val withRoutes = validIds.any { it != "__extra__" }
        focusSnapshots[AtlasMode.MERIDIANS]?.let { snapshot -> snapshot.meridians?.let { stable ->
            focusSnapshots[AtlasMode.MERIDIANS] = snapshot.copy(
                meridians = MeridianSelectionPolicy.setEnabled(stable, validIds, checked),
                layer = if (!checked) snapshot.layer else snapshot.layer.copy(lines = snapshot.layer.lines || withRoutes, points = true))
        } }
        mutable.update { value ->
            val enabled = MeridianSelectionPolicy.setEnabled(value.enabledMeridians, validIds, checked)
            val active = validIds.lastOrNull { checked && it != "__extra__" }
                ?: value.activeMeridian?.takeIf { it in enabled }
                ?: enabled.lastOrNull { it != "__extra__" }
            value.copy(enabledMeridians = enabled, activeMeridian = active,
                selectedMeridian = value.selectedPointMeridian ?: active)
        }
        if (checked) layer { it.copy(lines = it.lines || withRoutes, points = true) }
        persist()
    }
    fun selectMeridianForCatalogue(id: String) { mutable.update { it.copy(selectedMeridian = it.selectedPointMeridian ?: id, activeMeridian = id) } }
    fun chooseMeridian(id: String) {
        mutable.update { it.copy(selectedMeridian = it.selectedPointMeridian ?: id, activeMeridian = id, enabledMeridians = it.enabledMeridians + id) }
        layer { it.copy(lines = true, points = true) }
    }
    fun selectPoint(id: String, reveal: Boolean = true) {
        val value = mutable.value
        val point = value.data?.points?.find { it.id == id } ?: return
        focusSnapshots.putIfAbsent(value.mode, FocusSnapshot(value.mode, value.layer,
            value.enabledMeridians.takeIf { value.mode == AtlasMode.MERIDIANS }))
        mutable.update { it.copy(selectedPoint = id, selectedStructure = null, canRestore = true,
            selectedMeridian = point.meridianId, activeMeridian = point.meridianId,
            enabledMeridians = if (value.mode == AtlasMode.MERIDIANS) it.enabledMeridians + setOfNotNull(point.meridianId) else it.enabledMeridians) }
        layer { it.copy(points = true, lines = if (point.meridianId != null) true else it.lines, details = true,
            isolateId = if (reveal) null else it.isolateId, focusIds = if (reveal) emptySet() else it.focusIds) }
    }
    fun selectStructure(id: String?) {
        val group = mutable.value.data?.structureGroups?.find(id)
        if (id != null && group == null) return
        mutable.update { it.copy(selectedStructure = group?.id, selectedPoint = null) }
        if (group != null) layer { it.copy(details = true) }
    }
    fun revealStructure(id: String) {
        val value = mutable.value
        val group = value.data?.structureGroups?.find(id) ?: return
        val structure = group.representative
        val memberLayers = group.members.map { "${it.region}:${it.layer}" }.toSet()
        focusSnapshots.putIfAbsent(value.mode, FocusSnapshot(value.mode, value.layer))
        mutable.update { it.copy(selectedStructure = group.id, selectedPoint = null, canRestore = true) }
        layer { it.copy(skin = structure.system == "skin", muscle = structure.system == "muscle" || it.muscle,
            organ = structure.system == "organ" || it.organ, bone = structure.system == "bone" || it.bone,
            connective = structure.system == "connective" || it.connective,
            spaces = structure.system == "space" || it.spaces,
            hiddenIds = it.hiddenIds - group.memberIds, disabledRegionLayers = it.disabledRegionLayers - memberLayers,
            isolateId = group.id, focusIds = emptySet(), details = true) }
    }
    fun locateStructure(id: String) {
        val value = mutable.value
        val group = value.data?.structureGroups?.find(id) ?: return
        val structure = group.representative
        val memberLayers = group.members.map { "${it.region}:${it.layer}" }.toSet()
        focusSnapshots.putIfAbsent(value.mode, FocusSnapshot(value.mode, value.layer))
        mutable.update { it.copy(selectedStructure = group.id, selectedPoint = null, canRestore = true) }
        layer { it.copy(skin = structure.system == "skin", muscle = if (structure.system in setOf("organ", "bone", "connective", "space")) false else structure.system == "muscle" || it.muscle,
            organ = if (structure.system in setOf("bone", "connective", "space")) false else structure.system == "organ" || it.organ,
            bone = structure.system == "bone" || it.bone, connective = structure.system == "connective" || it.connective,
            spaces = structure.system == "space" || it.spaces,
            hiddenIds = it.hiddenIds - group.memberIds, disabledRegionLayers = it.disabledRegionLayers - memberLayers,
            isolateId = null, focusIds = emptySet(), details = true) }
    }
    fun hideSelectedStructure() {
        val value = mutable.value
        val ids = value.data?.structureGroups?.find(value.selectedStructure)?.memberIds ?: return
        val mode = value.mode
        focusSnapshots[mode]?.let { snapshot -> focusSnapshots[mode] = snapshot.copy(layer = VisibilityPolicy.hideOnly(snapshot.layer, ids)) }
        layer { VisibilityPolicy.hideOnly(it, ids) }
        mutable.update { it.copy(selectedStructure = null) }
    }

    fun observeFascialStructures(ids: Set<String>) {
        val value = mutable.value
        val valid = value.data?.structures?.filter { it.id in ids }.orEmpty()
        if (valid.isEmpty()) return
        focusSnapshots.putIfAbsent(value.mode, FocusSnapshot(value.mode, value.layer))
        mutable.update { it.copy(canRestore = true) }
        layer { it.copy(skin = false, muscle = true, organ = false,
            bone = valid.any { part -> part.system == "bone" }, connective = true, spaces = false,
            hiddenIds = it.hiddenIds - ids, disabledRegionLayers = emptySet(), isolateId = null,
            focusIds = ids, cutawaySide = CutawaySide.NONE, fasciaChains = true) }
    }

    /**
     * A new observation preset finishes temporary anatomical isolation, without cancelling the
     * point being studied. Keep the current annotation controls and route choices; the caller
     * applies its new anatomical preset afterwards and restores the camera only when true.
     */
    fun prepareObservationPreset(): Boolean {
        val snapshot = focusSnapshots.remove(mutable.value.mode) ?: return false
        mutable.update { value ->
            val restoredLayer = snapshot.layer.copy(points = value.layer.points, lines = value.layer.lines,
                labels = value.layer.labels, details = value.layer.details,
                internalRelations = value.layer.internalRelations,
                fasciaChains = value.layer.fasciaChains, fasciaTargets = value.layer.fasciaTargets,
                enabledFascialConnections = value.layer.enabledFascialConnections)
            value.copy(layers = value.layers + (value.mode to restoredLayer), canRestore = false,
                selectedMeridian = value.selectedPointMeridian ?: value.selectedMeridian)
        }
        persist()
        return true
    }

    fun restore() {
        val snapshot = focusSnapshots.remove(mutable.value.mode)
        if (snapshot != null) mutable.update { it.copy(mode = snapshot.mode,
            layers = it.layers + (snapshot.mode to snapshot.layer), enabledMeridians = snapshot.meridians ?: it.enabledMeridians,
            selectedPoint = null, selectedStructure = null, canRestore = false) }
        persist()
    }
    fun clearSelection() { mutable.update { it.copy(selectedPoint = null, selectedStructure = null) } }
    fun resetLayers() { layer { VisibilityPolicy.defaults(mutable.value.mode) } }
}
