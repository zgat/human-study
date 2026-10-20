package com.zga.meridianatlas.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.zga.meridianatlas.model.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.atlasPreferences by preferencesDataStore("viewer_preferences")
data class SavedPreferences(val mode: AtlasMode, val quality: ModelQuality,
    val states: Map<AtlasMode, LayerState>, val meridians: Set<String>)
class AtlasPreferences(private val context: Context) {
    private val key = stringPreferencesKey("state_v1")
    suspend fun read(): SavedPreferences? = runCatching {
        val raw = context.atlasPreferences.data.first()[key] ?: return null
        val item = JSONObject(raw)
        val profileVersion = item.optInt("profileVersion", 1)
        val states = AtlasMode.entries.associateWith { mode ->
            val layer = item.getJSONObject("layers").getJSONObject(mode.name)
            val state = LayerState(layer.getBoolean("skin"), layer.getBoolean("muscle"), layer.getBoolean("organ"),
                layer.getBoolean("bone"), layer.getBoolean("lines"), layer.getBoolean("points"),
                layer.getBoolean("labels"), layer.getBoolean("details"),
                layer.optJSONArray("disabled").strings().toSet(), layer.optJSONArray("hidden").strings().toSet(),
                layer.optString("isolate").takeIf { it.isNotBlank() && it != "null" },
                cutawaySide = runCatching { CutawaySide.valueOf(layer.optString("cutawaySide", "NONE")) }.getOrDefault(CutawaySide.NONE),
                internalRelations = layer.optBoolean("internalRelations", false),
                connective = layer.optBoolean("connective", mode == AtlasMode.OVERVIEW),
                spaces = layer.optBoolean("spaces", false),
                fasciaChains = layer.optBoolean("fasciaChains", false),
                fasciaTargets = layer.optBoolean("fasciaTargets", true),
                enabledFascialConnections = layer.optJSONArray("fascialConnections").strings().toSet(),
                fasciaRelations = layer.optBoolean("fasciaRelations", true),
                fasciaContext = layer.optBoolean("fasciaContext", false))
            val defaultsMigrated = if (profileVersion < 2) VisibilityPolicy.migrateLegacyDefault(mode, state) else state
            if (profileVersion < 3) VisibilityPolicy.migrateLegacyHalfBody(mode, defaultsMigrated) else defaultsMigrated
        }
        SavedPreferences(AtlasMode.valueOf(item.getString("mode")), ModelQuality.valueOf(item.getString("quality")),
            states, item.optJSONArray("meridians").strings().toSet())
    }.getOrNull()

    suspend fun write(value: SavedPreferences) {
        val layers = JSONObject()
        value.states.forEach { (mode, state) -> layers.put(mode.name, JSONObject().apply {
            put("skin", state.skin); put("muscle", state.muscle); put("organ", state.organ); put("bone", state.bone)
            put("lines", state.lines); put("points", state.points); put("labels", state.labels); put("details", state.details)
            put("disabled", JSONArray(state.disabledRegionLayers.toList())); put("hidden", JSONArray(state.hiddenIds.toList()))
            put("isolate", state.isolateId)
            put("cutawaySide", state.cutawaySide.name); put("internalRelations", state.internalRelations)
            put("connective", state.connective); put("spaces", state.spaces)
            put("fasciaChains", state.fasciaChains); put("fasciaTargets", state.fasciaTargets)
            put("fasciaRelations", state.fasciaRelations); put("fasciaContext", state.fasciaContext)
            put("fascialConnections", JSONArray(state.enabledFascialConnections.toList()))
        }) }
        val json = JSONObject().put("profileVersion", 4).put("mode", value.mode.name).put("quality", value.quality.name)
            .put("layers", layers).put("meridians", JSONArray(value.meridians.toList())).toString()
        context.atlasPreferences.edit { it[key] = json }
    }
}
