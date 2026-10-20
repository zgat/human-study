@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.zga.meridianatlas.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zga.meridianatlas.BuildConfig
import com.zga.meridianatlas.R
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import kotlin.math.abs
import kotlin.math.hypot

private val Ink = Color(0xff0c151d)
private val PanelColor = Color(0xff14222d)
private val Accent = Color(0xff65ddc3)
private val Muted = Color(0xff8da4b2)
private val LineColor = Color(0xff263b49)
private val Amber = Color(0xffeac27b)
private const val InternalRelationsDescription = "虚线为脏腑关系示意，非精确体内走行"
private enum class Panel { CATALOGUE, LAYERS, DETAILS, ABOUT, LICENSES, FASCIA }
private val regionNames = linkedMapOf("head_neck" to "头颈", "trunk" to "躯干", "upper_limb" to "上肢", "lower_limb" to "下肢", "whole" to "全身")

@Composable
fun MeridianAtlasApp(model: AtlasViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Amber,
        primaryContainer = Color(0xff1a3d38), onPrimaryContainer = Accent,
        secondaryContainer = Color(0xff273d49), onSecondaryContainer = Color(0xffedf5f8),
        background = Ink, surface = PanelColor, surfaceVariant = LineColor,
        onBackground = Color(0xffedf5f8), onSurface = Color(0xffedf5f8), onSurfaceVariant = Muted, outline = LineColor)) {
        CompositionLocalProvider(LocalContentColor provides Color(0xffedf5f8)) {
        if (state.loading || state.data == null) {
            Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.AccessibilityNew, null, Modifier.size(54.dp), Accent)
                    Text(stringResource(R.string.app_name), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.app_subtitle), fontSize = 12.sp, color = Muted)
                    if (state.error == null) { CircularProgressIndicator(color = Accent); Text("正在载入离线图谱", color = Muted) }
                    else { Text(state.error ?: "", color = Amber, modifier = Modifier.padding(24.dp)); Button(onClick = model::reload) { Text("重新读取") } }
                }
            }
        } else AtlasWorkspace(state, model)
        }
    }
}

@Composable
private fun AtlasWorkspace(state: AtlasUiState, model: AtlasViewModel) {
    val data = state.data!!
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density
    val viewer = remember { AnatomySurfaceView(context) }
    var projections by remember { mutableStateOf<List<ProjectedAnchor>>(emptyList()) }
    var loadingModel by remember { mutableStateOf(true) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var queries by remember { mutableStateOf(AtlasMode.entries.associateWith { "" }) }
    val query = queries.getValue(state.mode)
    var searchRequest by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var activeSide by remember { mutableIntStateOf(0) }
    var selectedFascialTarget by remember { mutableStateOf<String?>(null) }
    val latestState by rememberUpdatedState(state)
    val latestProjections by rememberUpdatedState(projections)
    val point = data.points.find { it.id == state.selectedPoint }
    val structureGroup = data.structureGroups.find(state.selectedStructure)
    val structure = structureGroup?.representative
    val visibleIds = remember(state.layer, data.structures) { VisibilityPolicy.visibleIds(data.structures, state.layer, data.structureGroups) }
    val displayPoint = point?.takeIf { state.mode == AtlasMode.MERIDIANS }
    val displayStructure = structure?.takeIf { VisibilityPolicy.isRelevantStructure(state.mode, it) &&
        structureGroup?.memberIds?.any { id -> id in visibleIds } == true }
    val visibleStructureCount = remember(data.structureGroups, visibleIds) {
        data.structureGroups.entries.count { group -> group.memberIds.any { it in visibleIds } }
    }
    val fascialSelection = data.fascialConnections.asSequence()
        .filter { state.mode == AtlasMode.OVERVIEW && state.layer.fasciaChains && it.id in state.layer.enabledFascialConnections }
        .mapNotNull { connection -> connection.targets.find { it.id == selectedFascialTarget }?.let { connection to it } }.firstOrNull()
    val showSelectionCard = state.layer.details && (displayPoint != null || displayStructure != null || fascialSelection != null)
    var selectionCardBounds by remember { mutableStateOf<Rect?>(null) }
    val selectedInstance = point?.instances?.filter { it.position != null }?.let { it.getOrNull(activeSide) ?: it.firstOrNull() }
    val overlay = remember(data, state.mode, state.effectiveMeridians, state.selectedMeridian, state.activeMeridian, state.layer.points, state.layer.lines,
        state.layer.internalRelations, state.selectedPoint, state.selectedStructure, state.layer.focusIds, visibleIds) {
        val localOnly = state.layer.focusIds.isNotEmpty() && point != null
        buildOverlay(if (localOnly) data.copy(points = listOf(point!!)) else data,
            state.effectiveMeridians, state.layer.points, state.layer.lines && !localOnly, state.selectedPoint,
            displayStructure, selectedMeridianId = state.selectedPointMeridian ?: state.selectedMeridian,
            showInternalRelations = state.layer.internalRelations, includeHitTargets = state.mode == AtlasMode.MERIDIANS,
            additionalEmphasizedMeridian = state.activeMeridian)
    }
    val fascialOverlay = remember(data.fascialConnections, state.mode, state.layer.fasciaChains,
        state.layer.fasciaTargets, state.layer.fasciaRelations, state.layer.fasciaContext,
        state.layer.enabledFascialConnections, state.layer.skin, state.layer.cutawaySide, visibleIds) {
        val skinVisible = state.layer.skin && data.structures.any { it.system == "skin" && it.id in visibleIds }
        buildFascialOverlay(data.fascialConnections,
            state.layer.copy(skin = skinVisible, fasciaChains = state.mode == AtlasMode.OVERVIEW && state.layer.fasciaChains))
    }
    fun updateQuery(value: String) { queries = queries + (state.mode to value) }
    fun dismissKeyboard() { focusManager.clearFocus(); keyboard?.hide() }
    fun focusStructure(target: AnatomyStructure, isolate: Boolean = false) {
        val group = data.structureGroups.find(target.id) ?: return
        selectedFascialTarget = null
        dismissKeyboard()
        viewer.captureCamera(state.mode.name)
        if (isolate) model.revealStructure(group.representative.id) else model.locateStructure(group.representative.id)
        viewer.focusOnStructure(group)
    }
    fun restoreFocus() {
        val keepPointView = state.selectedPoint != null
        if (model.prepareObservationPreset() && !keepPointView) viewer.restoreCamera(state.mode.name)
    }
    fun observeConnections(connections: List<FascialConnection>) {
        if (connections.isEmpty()) return
        val ids = connections.flatMapTo(linkedSetOf()) { it.structureIds }
        viewer.captureCamera(state.mode.name)
        model.observeFascialStructures(ids)
        model.layer { it.copy(fasciaChains = true,
            enabledFascialConnections = it.enabledFascialConnections + connections.map { connection -> connection.id }) }
        val bounds = data.structures.filter { it.id in ids }.flatMap { part ->
            listOf(part.boundsMin ?: part.center, part.boundsMax ?: part.center)
        } + connections.flatMap { connection -> connection.paths.flatMap { it.points } }
        if (bounds.isNotEmpty()) viewer.focusOnRegion(
            Vec3(bounds.minOf { it.x }, bounds.minOf { it.y }, bounds.minOf { it.z }),
            Vec3(bounds.maxOf { it.x }, bounds.maxOf { it.y }, bounds.maxOf { it.z }),
            connections.first().preferredViewNormal)
    }
    fun focusConnectionTarget(connection: FascialConnection, target: FascialTarget) {
        viewer.captureCamera(state.mode.name)
        model.observeFascialStructures(connection.structureIds.toSet())
        model.layer { it.copy(fasciaChains = true, details = true,
            enabledFascialConnections = it.enabledFascialConnections + connection.id) }
        selectedFascialTarget = target.id
        viewer.focusOn(target.position, .4f, target.normal)
        panel = null
    }
    fun focusPoint(target: Acupoint, instanceIndex: Int = 0) {
        dismissKeyboard()
        viewer.captureCamera(state.mode.name)
        activeSide = instanceIndex
        model.selectPoint(target.id)
        val instances = target.instances.filter { it.position != null }
        val anchor = instances.getOrNull(instanceIndex) ?: instances.firstOrNull()
        anchor?.position?.let { position ->
            if (anchor.viewId in setOf("oral", "nasal")) {
                val localIds = data.structures.filter { item ->
                    val name = item.nameEn.lowercase()
                    val words = if (anchor.viewId == "oral") listOf("tongue", "gingiva", "palate", "tooth", "teeth") else listOf("nasal concha", "nasal cavity", "nasal septum")
                    words.any { name.contains(it) } || item.id == anchor.surfaceId
                }.map { it.id }.toSet()
                model.layer { layer -> layer.copy(skin = false, muscle = true, organ = true, bone = true, cutawaySide = CutawaySide.NONE,
                    isolateId = null, focusIds = localIds, hiddenIds = layer.hiddenIds - localIds) }
            } else if (state.layer.focusIds.isNotEmpty()) {
                model.layer { it.copy(focusIds = emptySet()) }
            }
            viewer.focusOn(position, if (anchor.viewId in setOf("oral", "nasal")) .13f else .36f, anchor.normal)
        }
    }
    DisposableEffect(viewer, lifecycleOwner) {
        viewer.onProjection = { projections = it }
        viewer.onLoadState = { loading, error -> loadingModel = loading; modelError = error }
        viewer.onPick = { id ->
            val current = latestState
            val picked = current.data?.structures?.find { it.id == id }
            if (id == null) { selectedFascialTarget = null; model.clearSelection() }
            else if (picked != null && VisibilityPolicy.isRelevantStructure(current.mode, picked)) {
                selectedFascialTarget = null; model.selectStructure(id)
            }
        }
        viewer.onTap = { x, y ->
            val current = latestState
            val referenceHit = if (current.mode == AtlasMode.OVERVIEW && current.layer.fasciaChains && current.layer.fasciaTargets)
                latestProjections.filter { it.visible && !it.occluded && it.id.startsWith("fascial-target:") }
                    .minByOrNull { hypot((it.xPx-x).toDouble(), (it.yPx-y).toDouble()) } else null
            if (referenceHit != null && hypot((referenceHit.xPx-x).toDouble(), (referenceHit.yPx-y).toDouble()) < 26*density) {
                val targetId = referenceHit.id.substringAfter(":")
                current.data?.fascialConnections?.find { group -> group.targets.any { it.id == targetId } }
                    ?.let { model.observeFascialStructures(it.structureIds.toSet()) }
                selectedFascialTarget = targetId
                model.layer { it.copy(details = true) }
                true
            } else {
            val pointIndex = current.data?.points?.associateBy { it.id }.orEmpty()
            val candidates = latestProjections.filter { anchor ->
                val prefix = if (current.mode == AtlasMode.MERIDIANS) "hitpoint:" else "point:"
                if (!anchor.visible || !anchor.frontFacing || anchor.occluded || !anchor.id.startsWith(prefix)) return@filter false
                val id = anchor.id.substringAfter(":").substringBefore("|")
                val index = anchor.id.substringAfterLast("|").toIntOrNull() ?: 0
                val instance = pointIndex[id]?.instances?.getOrNull(index)
                val concealedLocal = instance?.viewId in setOf("oral", "nasal") && current.layer.focusIds.isEmpty()
                !concealedLocal
            }
            val hit = candidates.minByOrNull { hypot((it.xPx-x).toDouble(), (it.yPx-y).toDouble()) }
            if ((current.mode == AtlasMode.MERIDIANS || current.layer.points) && hit != null &&
                hypot((hit.xPx-x).toDouble(), (hit.yPx-y).toDouble()) < 32*density) {
                val pointId = hit.id.substringAfter(":").substringBefore("|")
                activeSide = hit.id.substringAfterLast("|").toIntOrNull() ?: 0
                viewer.captureCamera(current.mode.name)
                model.selectPoint(pointId, reveal = false)
                true
            } else false
            }
        }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> viewer.resume()
            Lifecycle.Event.ON_PAUSE -> viewer.pause()
            else -> Unit
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewer.resume()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewer.close() }
    }
    LaunchedEffect(state.quality) {
        viewer.loadModel(if (state.quality == ModelQuality.LOW) "models/body-low.glb" else "models/body-standard.glb",
            data.structures.associate { it.nodeName to it.id }, state.quality)
    }
    LaunchedEffect(visibleIds) { viewer.setVisibleStructureIds(visibleIds) }
    LaunchedEffect(state.layer.skin, state.layer.cutawaySide) {
        val exterior = CutawayPolicy.exteriorIds(data.structures)
        viewer.setOpaqueSkinOcclusion(state.layer.skin && state.layer.cutawaySide == CutawaySide.NONE, exterior)
    }
    LaunchedEffect(state.layer.cutawaySide, data.structures) {
        viewer.setCutawaySide(state.layer.cutawaySide.sign, CutawayPolicy.outerIds(data.structures))
    }
    LaunchedEffect(state.layer.skin, state.layer.points, state.layer.lines, state.layer.focusIds) {
        viewer.setReferenceSkin(!state.layer.skin && state.layer.focusIds.isEmpty() && (state.layer.points || state.layer.lines),
            data.structures.filter { it.system == "skin" }.map { it.id }.toSet())
    }
    LaunchedEffect(displayStructure?.id, fascialSelection?.second?.id, visibleIds) {
        val selectionIds = fascialSelection?.second?.structureIds?.toSet()
            ?: displayStructure?.let { data.structureGroups.memberIds(it.id) }.orEmpty()
        viewer.selectStructures(selectionIds.intersect(visibleIds))
    }
    LaunchedEffect(overlay, fascialOverlay) { viewer.setAnchors(overlay.anchors + fascialOverlay.anchors) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            Header(onSearch = { searchRequest++; panel = Panel.CATALOGUE }, onAbout = { panel = Panel.ABOUT })
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
                        AndroidView(factory = { viewer }, modifier = Modifier.fillMaxSize())
                        AtlasOverlay(projections, overlay, state.layer.labels, state.selectedPoint,
                            !state.layer.skin && state.layer.focusIds.isEmpty() && (state.layer.points || state.layer.lines), density,
                            labelExclusion = selectionCardBounds.takeIf { showSelectionCard })
                        FasciaOverlay(projections, fascialOverlay, selectedFascialTarget, state.layer.labels, density,
                            selectionCardBounds.takeIf { showSelectionCard })
                        Column(Modifier.align(Alignment.TopStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("成人男性  /  离线图谱", fontSize = 11.sp, color = Muted)
                            Text(state.mode.title, fontSize = 23.sp, fontWeight = FontWeight.Medium)
                            Text(when (state.mode) { AtlasMode.OVERVIEW -> "观察皮肤、内脏与骨骼的空间关系"; AtlasMode.MERIDIANS -> "点按体表查看附近穴位，联动所属经络"; AtlasMode.MUSCLES -> "点选查看说明，可逐块隐藏肌肉" }, fontSize = 11.sp, color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SmallBadge("$visibleStructureCount 个启用结构", Accent)
                                if (state.layer.points || state.layer.lines) SmallBadge("教学定位 · 待校对", Amber)
                            }
                            if (state.layer.cutawaySide != CutawaySide.NONE) Text("${state.layer.cutawaySide.title} · 按人体自身左右", color = Accent, fontSize = 10.sp)
                        }
                        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("正" to CameraPreset.FRONT, "背" to CameraPreset.BACK, "左" to CameraPreset.LEFT, "右" to CameraPreset.RIGHT).forEach { (title, preset) ->
                                Surface(onClick = { viewer.applyCameraPreset(preset) }, shape = CircleShape, color = PanelColor.copy(alpha = .93f), border = BorderStroke(1.dp, LineColor)) {
                                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Text(title, fontSize = 12.sp, color = Muted) }
                                }
                            }
                            RoundAction(Icons.Outlined.CenterFocusStrong, "复位") { viewer.applyCameraPreset(CameraPreset.RESET) }
                        }
                        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RoundAction(Icons.Outlined.Tune, "图层控制") { panel = Panel.LAYERS }
                            if (!wide) RoundAction(Icons.Outlined.FormatListBulleted, "图谱目录") { searchRequest = 0; panel = Panel.CATALOGUE }
                            if (state.canRestore) RoundAction(Icons.Outlined.Undo, "恢复定位前显示") { selectedFascialTarget = null; model.restore(); viewer.restoreCamera(state.mode.name) }
                        }
                        Text("双指缩放 / 平移 · ${if (state.quality == ModelQuality.LOW) "流畅" else "标准"}",
                            fontSize = 10.sp, color = Muted, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp))
                        if (showSelectionCard) {
                            val cardModifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 64.dp, bottom = 72.dp)
                                .widthIn(max = 420.dp).fillMaxWidth().onGloballyPositioned { selectionCardBounds = it.boundsInParent() }
                            if (fascialSelection != null) FascialSelectionCard(fascialSelection.first, fascialSelection.second,
                                onExpand = { panel = Panel.FASCIA }, onClose = { selectedFascialTarget = null }, modifier = cardModifier)
                            else SelectionCard(displayPoint, displayStructure, onExpand = { dismissKeyboard(); panel = Panel.DETAILS },
                                onClose = model::clearSelection,
                                onHide = if (displayStructure != null) model::hideSelectedStructure else null,
                                groupSize = if (displayStructure != null) structureGroup?.members?.size ?: 1 else 1,
                                modifier = cardModifier)
                        }
                        if (loadingModel || modelError != null) Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .65f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (modelError == null) { CircularProgressIndicator(color = Accent); Text("正在载入真实解剖模型", fontSize = 13.sp) }
                                else { Text(modelError ?: "", color = Amber, modifier = Modifier.padding(24.dp)); Text("请检查模型资源是否完整随包提供", color = Muted) }
                            }
                        }
                    }
                    ModeTools(state, data, model, beforePreset = ::restoreFocus, onFascia = { panel = Panel.FASCIA })
                    QuickLayers(state.layer, model::toggleAnatomySystem)
                }
                if (wide) {
                    VerticalDivider(color = LineColor)
                    Box(Modifier.width(340.dp).fillMaxHeight().background(PanelColor)) {
                        AtlasCatalogue(data, state, query, ::updateQuery,
                            onMeridian = model::selectMeridianForCatalogue, onToggleMeridian = model::toggleMeridian,
                            onSetMeridians = model::setMeridians,
                            onPoint = { focusPoint(it) }, onStructure = { focusStructure(it) }, requestSearchFocus = searchRequest)
                    }
                }
            }
            HorizontalDivider(color = LineColor)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                AtlasMode.entries.forEach { mode ->
                    val selected = mode == state.mode
                    val icon = when (mode) { AtlasMode.OVERVIEW -> Icons.Outlined.AccessibilityNew; AtlasMode.MERIDIANS -> Icons.Outlined.Route; AtlasMode.MUSCLES -> Icons.Outlined.Layers }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (selected) Accent.copy(alpha = .10f) else Color.Transparent)
                        .clickable { dismissKeyboard(); searchRequest = 0; model.mode(mode); if (panel == Panel.CATALOGUE) panel = null }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(icon, mode.title, Modifier.size(22.dp), if (selected) Accent else Muted)
                        Text(mode.title, fontSize = 11.sp, color = if (selected) Accent else Muted)
                    }
                }
            }
        }
        if (panel != null && !(wide && panel == Panel.CATALOGUE)) {
            ModalBottomSheet(onDismissRequest = { panel = null }, containerColor = PanelColor, contentColor = Color.White,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Box(Modifier.fillMaxWidth().heightIn(max = this@BoxWithConstraints.maxHeight * .88f).navigationBarsPadding()) {
                    when (panel) {
                        Panel.CATALOGUE -> AtlasCatalogue(data, state, query, ::updateQuery,
                            onMeridian = model::selectMeridianForCatalogue, onToggleMeridian = model::toggleMeridian,
                            onSetMeridians = model::setMeridians,
                            onPoint = { focusPoint(it); panel = null },
                            onStructure = { focusStructure(it); panel = null }, requestSearchFocus = searchRequest)
                        Panel.LAYERS -> LayersPanel(data, state, model, onReset = { restoreFocus(); model.resetLayers() })
                        Panel.DETAILS -> DetailPanel(point, structure, selectedInstance, onSide = { index -> point?.let { focusPoint(it, index) } },
                            onHide = { model.hideSelectedStructure(); panel = null },
                            onIsolate = { structure?.let { focusStructure(it, isolate = true); panel = null } },
                            memberIds = structureGroup?.members?.map { it.id }.orEmpty())
                        Panel.ABOUT -> AboutPanel(data) { panel = Panel.LICENSES }
                        Panel.LICENSES -> LicensePanel()
                        Panel.FASCIA -> FasciaPanel(data.fascialConnections, state.layer,
                            onMaster = { model.layer { it.copy(fasciaChains = !it.fasciaChains) } },
                            onTargets = { model.layer { it.copy(fasciaTargets = !it.fasciaTargets) } },
                            onToggle = { id -> model.layer { it.copy(fasciaChains = true,
                                enabledFascialConnections = if (id in it.enabledFascialConnections) it.enabledFascialConnections - id else it.enabledFascialConnections + id) } },
                            onObserve = { observeConnections(listOf(it)); panel = null }, onTarget = ::focusConnectionTarget,
                            onSetConnections = { ids -> model.layer { it.copy(fasciaChains = it.fasciaChains || ids.isNotEmpty(),
                                enabledFascialConnections = ids) } },
                            onObserveAll = { observeConnections(it); panel = null }, selectedTargetId = selectedFascialTarget,
                            structures = data.structureGroups.entries,
                            onStructure = { focusStructure(it.representative, isolate = true); panel = null },
                            onRelations = { model.layer { it.copy(fasciaRelations = !it.fasciaRelations) } },
                            onContext = { model.layer { it.copy(fasciaContext = !it.fasciaContext) } })
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable private fun Header(onSearch: () -> Unit, onAbout: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AccessibilityNew, null, Modifier.size(25.dp), Accent)
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(stringResource(R.string.app_name), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.app_subtitle), fontSize = 10.sp, color = Muted, letterSpacing = .5.sp)
        }
        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "搜索图谱", tint = Muted) }
        IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, "关于与数据来源", tint = Muted) }
    }
}
@Composable private fun SmallBadge(text: String, color: Color) {
    Text(text, color = color, fontSize = 9.sp, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = .10f)).padding(horizontal = 7.dp, vertical = 4.dp))
}
@Composable private fun RoundAction(icon: ImageVector, title: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = PanelColor.copy(alpha = .94f), border = BorderStroke(1.dp, LineColor)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, title, Modifier.size(19.dp), Accent) }
    }
}
@Composable private fun QuickLayers(state: LayerState, onToggle: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple("肌肉", state.muscle, "muscle"), Triple("骨骼", state.bone, "bone"),
            Triple("内脏", state.organ, "organ"), Triple("皮肤", state.skin, "skin"),
            Triple("筋膜·腱韧带", state.connective, "connective")).forEach { (name, enabled, key) ->
            FilterChip(selected = enabled, onClick = { onToggle(key) },
                modifier = Modifier.semantics { contentDescription = "显示$name" },
                label = { Text(name, fontSize = 12.sp) }, leadingIcon = {
                Box(Modifier.size(6.dp).background(if (enabled) Accent else Muted, CircleShape))
            }, border = FilterChipDefaults.filterChipBorder(enabled = true, selected = enabled, borderColor = LineColor, selectedBorderColor = Accent.copy(alpha = .45f)),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = .1f), selectedLabelColor = Accent))
        }
    }
}

@Composable private fun ModeTools(state: AtlasUiState, data: AtlasData, model: AtlasViewModel, beforePreset: () -> Unit, onFascia: () -> Unit) {
    Column {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state.mode) {
            AtlasMode.OVERVIEW -> {
            OverviewPreset.entries.forEach { preset ->
                val selected = state.layer.isolateId == null && state.layer.focusIds.isEmpty() && when (preset) {
                    OverviewPreset.SURFACE -> state.layer.skin
                    OverviewPreset.ORGANS -> !state.layer.skin && !state.layer.muscle && state.layer.organ
                    OverviewPreset.BONES -> !state.layer.skin && !state.layer.muscle && !state.layer.organ && state.layer.bone
                }
                FilterChip(selected = selected, onClick = { beforePreset(); model.layer { VisibilityPolicy.overviewPreset(it, preset) } }, label = { Text(preset.title) })
            }
            FilterChip(selected = state.layer.fasciaChains, onClick = onFascia, label = { Text("筋膜解剖") })
            }
            AtlasMode.MUSCLES -> {
                val keys = data.structures.filter { it.system == "muscle" }.map { "${it.region}:${it.layer}" }.toSet()
                listOf(null to "全部肌肉", 1 to "浅层", 2 to "中层", 3 to "深层").forEach { (depth, title) ->
                    val disabled = if (depth == null) emptySet() else keys.filter { it.substringAfterLast(':').toIntOrNull() != depth }.toSet()
                    val selected = state.layer.muscle && state.layer.isolateId == null && state.layer.focusIds.isEmpty() && state.layer.disabledRegionLayers == disabled
                    FilterChip(selected = selected, onClick = { beforePreset(); model.layer { VisibilityPolicy.muscleDepth(it, data.structures, depth) } }, label = { Text(title) })
                }
            }
            AtlasMode.MERIDIANS -> {
                listOf(Triple("经络线", state.layer.lines, "lines"), Triple("穴位标记", state.layer.points, "points"),
                    Triple("中文名称", state.layer.labels, "labels")).forEach { (title, selected, key) ->
                    FilterChip(selected = selected, onClick = { model.layer { when (key) {
                        "lines" -> it.copy(lines = !it.lines); "points" -> it.copy(points = !it.points)
                        else -> it.copy(labels = !it.labels)
                    } } }, label = { Text(title) })
                }
            }
        }
    }
    if (state.mode == AtlasMode.MERIDIANS) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.layer.internalRelations,
                onClick = { model.layer(::toggleInternalRelations) }, label = { Text("脏腑关系线") })
            FilterChip(selected = state.layer.skin && !state.layer.organ && state.layer.cutawaySide == CutawaySide.NONE,
                onClick = { beforePreset(); model.layer { VisibilityPolicy.meridianView(it, false) } }, label = { Text("体表") })
            FilterChip(selected = !state.layer.skin && state.layer.organ && state.layer.cutawaySide == CutawaySide.NONE,
                onClick = { beforePreset(); model.layer { VisibilityPolicy.meridianView(it, true) } }, label = { Text("内脏透视") })
            listOf(CutawaySide.LEFT, CutawaySide.RIGHT).forEach { side ->
                FilterChip(selected = state.layer.cutawaySide == side,
                    onClick = { beforePreset(); model.layer { VisibilityPolicy.meridianView(it, true, side) } }, label = { Text(side.title) })
            }
        }
        val hasEnabledRoute = data.meridians.any { it.id in state.effectiveMeridians }
        Text((if (state.layer.internalRelations && !hasEnabledRoute) "先勾选经络" else "点选穴位后显示同经名称") + "\n" + InternalRelationsDescription,
            color = if (state.layer.internalRelations && !hasEnabledRoute) Amber else Muted, fontSize = 10.sp, lineHeight = 14.sp,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, bottom = 4.dp))
    }
    if (state.mode == AtlasMode.OVERVIEW) Text(when {
        !state.layer.fasciaChains -> "筋膜解剖：真实组织与局部文献连接"
        state.layer.enabledFascialConnections.isEmpty() -> "筋膜解剖可查看真实结构；文献连接需另行勾选"
        else -> "虚线：局部关系示意 · 菱形：参考区 · 组织范围辅助线可单独开启"
    }, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable private fun SelectionCard(point: Acupoint?, structure: AnatomyStructure?, onExpand: () -> Unit, onClose: () -> Unit,
    onHide: (() -> Unit)? = null, groupSize: Int = 1, modifier: Modifier = Modifier) {
    Surface(onClick = onExpand, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = PanelColor.copy(alpha = .97f), border = BorderStroke(1.dp, LineColor), shadowElevation = 8.dp) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(point?.nameZh ?: structure?.nameZh ?: "", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(point?.codes?.joinToString(" / ")?.ifBlank { "经外奇穴" } ?:
                        if (groupSize > 1) "$groupSize 个片段 · 整体操作" else "解剖结构", color = Accent, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "取消选中", tint = Muted, modifier = Modifier.size(18.dp)) }
            }
            Text(point?.locationZh ?: structure?.descriptionZh?.ifBlank { structure.nameEn } ?: "", maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onHide != null) TextButton(onClick = onHide, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (groupSize > 1) "隐藏同名组织" else if (structure?.system == "muscle") "隐藏这一块" else "隐藏此结构", fontSize = 12.sp)
                }
                TextButton(onClick = onExpand, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("完整说明", fontSize = 12.sp)
                    Icon(Icons.Outlined.KeyboardArrowUp, "查看完整说明", Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun toggleInternalRelations(layer: LayerState): LayerState =
    layer.copy(internalRelations = !layer.internalRelations, lines = layer.lines || !layer.internalRelations)

@Composable private fun LayersPanel(data: AtlasData, state: AtlasUiState, model: AtlasViewModel, onReset: () -> Unit) {
    val layer = state.layer
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
        item { Text("图层控制", fontSize = 22.sp, fontWeight = FontWeight.SemiBold); Text("${state.mode.title} · 各模式独立保存", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 18.dp)) }
        item { SectionLabel("解剖系统") }
        item { ToggleRow("皮肤", "关闭后显露下层结构", layer.skin) { model.toggleAnatomySystem("skin") } }
        item { ToggleRow("肌肉总开关", "重新开启时保留分层和单肌设置", layer.muscle) { model.toggleAnatomySystem("muscle") } }
        item { ToggleRow("内脏", "同名组织的模型片段整体选择与隐藏", layer.organ) { model.toggleAnatomySystem("organ") } }
        item { ToggleRow("骨骼", "观察骨性标志与深部结构", layer.bone) { model.toggleAnatomySystem("bone") } }
        item { ToggleRow("筋膜·腱韧带", "筋膜、腱膜、肌腱、韧带等分别标注组织类型", layer.connective) { model.toggleAnatomySystem("connective") } }
        if (data.structures.any { it.system == "space" }) item { ToggleRow("解剖腔隙参考", "脑室等腔隙的区域模型，单独显示", layer.spaces) { model.toggleAnatomySystem("space") } }
        item { HorizontalDivider(Modifier.padding(vertical = 16.dp), color = LineColor); SectionLabel("肌肉区域与深度") }
        regionNames.filterKeys { it != "whole" }.forEach { (region, name) ->
            val depths = data.structures.filter { it.system == "muscle" && it.region == region }.map { it.layer }.distinct().sorted()
            if (depths.isNotEmpty()) item {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(name, fontSize = 13.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { depths.forEach { depth ->
                        val key = "$region:$depth"
                        FilterChip(selected = key !in layer.disabledRegionLayers, onClick = { model.layer { value -> value.copy(disabledRegionLayers =
                            if (key in value.disabledRegionLayers) value.disabledRegionLayers - key else value.disabledRegionLayers + key) } }, label = { Text(depthName(depth)) })
                    } }
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 16.dp), color = LineColor); SectionLabel("经络与文字") }
        item { ToggleRow("经络路线", "选中经络加粗突出，实线表示体表路线", layer.lines) { model.layer { it.copy(lines = !it.lines) } } }
        item { ToggleRow("脏腑关系线", InternalRelationsDescription, layer.internalRelations) { model.layer(::toggleInternalRelations) } }
        item { ToggleRow("穴位标记", "保持体表与局部锚点位置", layer.points) { model.layer { it.copy(points = !it.points) } } }
        item { ToggleRow("经外奇穴", "按部位查看 51 个常用奇穴", "__extra__" in state.enabledMeridians) { model.toggleMeridian("__extra__") } }
        item { ToggleRow("中文名称", "点选穴位后显示同经名称，默认仅显示点和线", layer.labels) { model.layer { it.copy(labels = !it.labels) } } }
        item { ToggleRow("说明面板", "控制选中对象的中文说明", layer.details) { model.layer { it.copy(details = !it.details) } } }
        item { HorizontalDivider(Modifier.padding(vertical = 16.dp), color = LineColor); SectionLabel("模型精度") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = state.quality == ModelQuality.STANDARD, onClick = { model.quality(ModelQuality.STANDARD) }, label = { Text("标准") })
            FilterChip(selected = state.quality == ModelQuality.LOW, onClick = { model.quality(ModelQuality.LOW) }, label = { Text("流畅") })
        }; Text("两档使用相同坐标与结构编号", fontSize = 11.sp, color = Muted) }
        item { Row(Modifier.padding(top = 18.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReset) { Text("重置本模式") }
            if (layer.hiddenIds.isNotEmpty() || layer.isolateId != null || layer.focusIds.isNotEmpty()) OutlinedButton(onClick = { model.layer { it.copy(hiddenIds = emptySet(), isolateId = null, focusIds = emptySet()) } }) { Text("恢复隐藏结构") }
        } }
    }
}
@Composable private fun SectionLabel(title: String) { Text(title, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp)) }
@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp); Text(subtitle, color = Muted, fontSize = 11.sp) }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable private fun DetailPanel(point: Acupoint?, structure: AnatomyStructure?, instance: PointInstance?,
    onSide: (Int) -> Unit, onHide: () -> Unit, onIsolate: () -> Unit, memberIds: List<String> = emptyList()) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(point?.nameZh ?: structure?.nameZh ?: "未选择对象", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        if (point != null) {
            Text(point.codes.joinToString(" / ").ifBlank { "经外奇穴" } + "  ·  " + point.pinyin, color = Accent)
            SmallBadge(if (point.reviewStatus in setOf("reviewed", "verified")) "已完成定位校对" else "教学示意 · 三维定位待校对", Amber)
            DetailText("定位", point.locationZh)
            if (point.anatomyZh.isNotBlank()) DetailText("解剖关系", point.anatomyZh)
            DetailText("功能与传统用途（学习）", point.traditionalIndicationsZh.ifBlank { "该穴位的传统用途资料尚待补充。" })
            if (point.functionSourceRefs.isNotEmpty()) DetailText("用途资料来源", point.functionSourceRefs.joinToString("\n"))
            if (point.aliases.isNotEmpty()) DetailText("别名", point.aliases.joinToString("、"))
            if (point.instances.size > 1) {
                SectionLabel("显示位置")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    point.instances.filter { it.position != null }.forEachIndexed { index, item ->
                        FilterChip(selected = instance?.id == item.id, onClick = { onSide(index) }, label = { Text(item.labelZh ?: "${sideName(item.side)}${if (item.ordinal > 1) " ${item.ordinal}" else ""}") })
                    }
                }
            }
            DetailText("来源", point.sourceRefs.joinToString("\n").ifBlank { "GB/T 12346—2021；GB/T 40997—2021" })
        }
        if (structure != null) {
            Text(structure.nameEn, color = Muted)
            Text("${systemName(structure.system)} · ${regionNames[structure.region] ?: structure.region}${if (structure.system == "muscle") " · ${depthName(structure.layer)}" else ""}", color = Accent)
            if (memberIds.size > 1) Text("${memberIds.size} 个模型片段 · 选中、高亮、隐藏及单独显示作用于同名组织整体", color = Accent, fontSize = 12.sp)
            if (structure.tissueTypeZh.isNotBlank()) Text("组织类型：${structure.tissueTypeZh}", color = Muted, fontSize = 12.sp)
            if (structure.sourceConceptNoteZh.isNotBlank()) Text(structure.sourceConceptNoteZh, color = Muted, fontSize = 12.sp)
            if (structure.descriptionZh.isNotBlank()) DetailText("说明", structure.descriptionZh)
            if (structure.originZh.isNotBlank()) DetailText("起点", structure.originZh)
            if (structure.insertionZh.isNotBlank()) DetailText("止点", structure.insertionZh)
            if (structure.functionZh.isNotBlank()) DetailText("作用", structure.functionZh)
            if (structure.system == "muscle" && structure.originZh.isBlank()) Text("该部件起止点和作用尚未完成中文内容校对。", color = Amber, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onIsolate) { Text(if (memberIds.size > 1) "单独显示整体" else "单独显示") }; OutlinedButton(onClick = onHide) { Text(if (memberIds.size > 1) "隐藏同名组织" else "隐藏此结构") } }
            DetailText(if (memberIds.size > 1) "模型片段编号" else "结构编号", memberIds.ifEmpty { listOf(structure.id) }.joinToString("、"))
        }
        Spacer(Modifier.height(20.dp))
    }
}
@Composable private fun DetailText(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { SectionLabel(title); Text(content, color = Color(0xffcfdee7), fontSize = 14.sp, lineHeight = 23.sp) }
}
@Composable private fun AboutPanel(data: AtlasData, onLicenses: () -> Unit) {
    val mapped = data.points.count { it.instances.any { anchor -> anchor.position != null } }
    val reviewed = data.points.count { it.reviewStatus in setOf("reviewed", "verified") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.about_app_title, stringResource(R.string.app_name)), fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.about_app_summary, BuildConfig.VERSION_NAME), color = Accent)
        DetailText("覆盖", "${data.points.size} 个标准穴位条目，${data.meridians.size} 类经络路线，${data.structureGroups.entries.size} 个解剖结构（${data.structures.size} 个模型片段）。\n三维已映射 $mapped 条；定位已校对 $reviewed 条。")
        DetailText("穴位依据", "GB/T 12346—2021《经穴名称与定位》：362 个经穴。\nGB/T 40997—2021《经外奇穴名称与定位》：51 个常用经外奇穴。\n奇经路线依据中医经络教学资料编排，均为教学示意。")
        DetailText("模型来源", "BodyParts3D, © The Database Center for Life Science, CC Attribution 4.0 International.\n补充肌肉来自 Z-Anatomy；合并模型按 CC BY-SA 4.0 提供。坐标转换、分类、移动端优化与逐项来源见离线许可资料。")
        OutlinedButton(onClick = onLicenses) { Text("查看离线来源与许可") }
        DetailText("使用边界", "三维标记和经络线是学习用定位示意，定位校对状态逐条记录。口腔等局部结构以实际提供的网格为限。本版本不提供针刺操作指导。")
        DetailText("传统主治资料", "传统主治用于理解中医文献中的穴位用途，不等同于现代临床疗效证据。各条目保留资料来源，内容仍待专业审校。")
        DetailText("交互", "单指旋转，双指缩放和平移。点选结构查看说明，使用图层面板逐层隐藏，使用目录搜索并定位穴位。")
    }
}
@Composable private fun LicensePanel() {
    val context = LocalContext.current
    val files = remember { context.assets.list("licenses")?.toList().orEmpty() }
    var selected by remember { mutableStateOf(files.firstOrNull { it.contains("NOTICE") } ?: files.firstOrNull()) }
    val text = remember(selected) { selected?.let { name -> runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("读取失败") }.orEmpty() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text("来源与许可", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            files.forEach { name -> FilterChip(selected = name == selected, onClick = { selected = name }, label = { Text(name, fontSize = 10.sp) }) }
        }
        SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), fontSize = 12.sp, lineHeight = 20.sp, color = Color(0xffcfdee7)) }
    }
}
private fun systemName(system: String): String = when (system) { "skin" -> "皮肤"; "muscle" -> "肌肉"; "organ" -> "器官"; "bone" -> "骨骼"; "connective" -> "筋膜·腱韧带"; "space" -> "解剖腔隙"; else -> system }
private fun depthName(layer: Int): String = when (layer) { 1 -> "浅层"; 2 -> "中层"; 3 -> "深层"; else -> "第 $layer 层" }
private fun sideName(side: String): String = when (side) { "left" -> "左侧"; "right" -> "右侧"; "midline", "center" -> "正中"; else -> side }
