package com.zga.meridianatlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zga.meridianatlas.model.*

private val catalogueRegions = linkedMapOf(
    "head_neck" to "头颈", "trunk" to "躯干", "upper_limb" to "上肢",
    "lower_limb" to "下肢", "whole" to "全身"
)
private val catalogueSystems = linkedMapOf("skin" to "皮肤", "muscle" to "肌肉", "connective" to "筋膜·腱韧带",
    "organ" to "器官", "bone" to "骨骼", "space" to "解剖腔隙")

@Composable
internal fun AtlasCatalogue(
    data: AtlasData,
    state: AtlasUiState,
    query: String,
    onQuery: (String) -> Unit,
    onMeridian: (String) -> Unit,
    onToggleMeridian: (String) -> Unit,
    onSetMeridians: (Set<String>, Boolean) -> Unit,
    onPoint: (Acupoint) -> Unit,
    onStructure: (AnatomyStructure) -> Unit,
    requestSearchFocus: Int = 0
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    val title = when (state.mode) {
        AtlasMode.MERIDIANS -> "经络与穴位"
        AtlasMode.MUSCLES -> "肌肉目录"
        AtlasMode.OVERVIEW -> "解剖目录"
    }
    val searchDescription = when (state.mode) {
        AtlasMode.MERIDIANS -> "搜索经络与穴位"
        AtlasMode.MUSCLES -> "搜索肌肉"
        AtlasMode.OVERVIEW -> "搜索解剖结构"
    }
    val placeholder = when (state.mode) {
        AtlasMode.MERIDIANS -> "穴名、拼音、别名、编号或经络名称"
        AtlasMode.MUSCLES -> "肌肉名称、英文或编号"
        AtlasMode.OVERVIEW -> "结构名称、英文或编号"
    }
    val icon = when (state.mode) {
        AtlasMode.MERIDIANS -> Icons.Outlined.Route
        AtlasMode.MUSCLES -> Icons.Outlined.FitnessCenter
        AtlasMode.OVERVIEW -> Icons.Outlined.AccountTree
    }
    val subtitle = when (state.mode) {
        AtlasMode.MERIDIANS -> "${data.points.distinctBy { it.id }.size} 穴 · ${data.meridians.size} 条路线"
        AtlasMode.MUSCLES -> "${data.structureGroups.entries.count { it.representative.system == "muscle" }} 个肌肉结构 · 分区分层"
        AtlasMode.OVERVIEW -> "${data.structureGroups.entries.size} 个结构 · 解剖系统"
    }
    LaunchedEffect(requestSearchFocus) {
        if (requestSearchFocus > 0) {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    val choosePoint: (Acupoint) -> Unit = {
        focus.clearFocus(); keyboard?.hide(); onPoint(it)
    }
    val chooseStructure: (AnatomyStructure) -> Unit = {
        focus.clearFocus(); keyboard?.hide(); onStructure(it)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(searchFocus)
                .semantics { contentDescription = searchDescription },
            placeholder = { Text(placeholder, fontSize = 12.sp, maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Outlined.Close, "清空搜索")
                }
            },
            shape = RoundedCornerShape(14.dp)
        )
        when (state.mode) {
            AtlasMode.MERIDIANS -> MeridianCatalogue(data, state, query, onMeridian,
                onToggleMeridian, onSetMeridians, choosePoint, Modifier.weight(1f))
            AtlasMode.MUSCLES -> StructureCatalogue(data, state, query, true, chooseStructure, Modifier.weight(1f))
            AtlasMode.OVERVIEW -> StructureCatalogue(data, state, query, false, chooseStructure, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MeridianCatalogue(data: AtlasData, state: AtlasUiState, query: String,
    onMeridian: (String) -> Unit, onToggleMeridian: (String) -> Unit,
    onSetMeridians: (Set<String>, Boolean) -> Unit,
    onPoint: (Acupoint) -> Unit, modifier: Modifier = Modifier) {
    val content = remember(data.points, data.meridians) { MeridianCatalogueContent(data) }
    val listState = rememberLazyListState()
    var expandedRoutes by remember { mutableStateOf(setOfNotNull(state.selectedMeridian)) }
    var expandedRegions by remember { mutableStateOf(emptySet<String>()) }
    var showExtraRegions by rememberSaveable { mutableStateOf(true) }
    var groupFilter by rememberSaveable { mutableStateOf("routes") }
    var pendingScrollPoint by remember { mutableStateOf<String?>(null) }
    // A restored older UI may still contain "all"; give it the current route default.
    val activeGroup = if (groupFilter == "extra") "extra" else "routes"
    val searching = query.isNotEmpty()
    // Re-entry creates a new catalogue; while it stays open only a changed point
    // navigates. Explicit group collapses and category choices therefore remain intact.
    LaunchedEffect(state.selectedPoint, content) {
        val destination = content.destination(state.selectedPoint)
        if (destination != null) {
            groupFilter = destination.category
            if (destination.category == "extra") {
                showExtraRegions = true
                expandedRegions = expandedRegions + destination.groupId
            } else expandedRoutes = expandedRoutes + destination.groupId
        }
        pendingScrollPoint = state.selectedPoint
    }
    val rows = remember(content, query, activeGroup, expandedRoutes, expandedRegions, showExtraRegions) {
        content.rows(query, activeGroup, expandedRoutes, expandedRegions, showExtraRegions)
    }
    val pendingKey = content.selectedRowKey(pendingScrollPoint, query)
    val pendingIndex = rows.indexOfFirst { it.key == pendingKey }
    LaunchedEffect(pendingScrollPoint, pendingIndex) {
        if (pendingScrollPoint != null && pendingIndex >= 0) {
            // The index comes from the exact keyed row list supplied to LazyColumn,
            // including route descriptions and preceding expanded groups.
            listState.scrollToItem(pendingIndex)
            pendingScrollPoint = null
        }
    }
    Column(modifier) {
        if (!searching) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("routes" to "经络路线", "extra" to "经外奇穴").forEach { (id, label) ->
                FilterChip(selected = activeGroup == id, onClick = { pendingScrollPoint = null; groupFilter = id },
                    label = { Text(label, fontSize = 11.sp) })
            }
        }
        if (!searching) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val routeCategory = activeGroup == "routes"
            val selectedIds = if (routeCategory) content.routeIds else setOf("__extra__")
            TextButton(onClick = { onSetMeridians(selectedIds, true) },
                modifier = Modifier.semantics { contentDescription = if (routeCategory) "经络全选" else "奇穴全选" }) {
                Text(if (routeCategory) "经络全选" else "奇穴全选", fontSize = 11.sp)
            }
            TextButton(onClick = { onSetMeridians(selectedIds, false) },
                modifier = Modifier.semantics { contentDescription = if (routeCategory) "经络全不选" else "奇穴全不选" }) {
                Text(if (routeCategory) "经络全不选" else "奇穴全不选", fontSize = 11.sp)
            }
            TextButton(onClick = {
                pendingScrollPoint = null
                if (routeCategory) expandedRoutes = content.routeIds
                else { showExtraRegions = true; expandedRegions = content.extraRegions }
            }, modifier = Modifier.semantics { contentDescription = "全部展开" }) { Text("全部展开", fontSize = 11.sp) }
            TextButton(onClick = {
                pendingScrollPoint = null
                if (routeCategory) expandedRoutes = emptySet()
                else { showExtraRegions = false; expandedRegions = emptySet() }
            }, modifier = Modifier.semantics { contentDescription = "全部收起" }) { Text("全部收起", fontSize = 11.sp) }
        }
        LazyColumn(Modifier.weight(1f), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is MeridianCatalogueRow.Caption -> CatalogueCaption(row.text)
                    is MeridianCatalogueRow.Point -> CataloguePointRow(row.point,
                        state.selectedPoint == row.point.id, row.subtitle, onPoint)
                    is MeridianCatalogueRow.RouteHeader -> {
                        val meridian = row.meridian
                        MeridianGroupHeader(meridian, row.expanded, meridian.id in state.enabledMeridians,
                                row.subtitle,
                                onExpand = {
                                    pendingScrollPoint = null
                                    expandedRoutes = if (row.expanded) expandedRoutes - meridian.id else expandedRoutes + meridian.id
                                    if (!row.expanded) onMeridian(meridian.id)
                                }, onToggle = { onToggleMeridian(meridian.id) })
                    }
                    is MeridianCatalogueRow.RouteDescription -> Text(row.meridian.descriptionZh,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 13.dp, end = 10.dp, top = 3.dp, bottom = 6.dp))
                    is MeridianCatalogueRow.ExtraHeader -> {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = .08f)),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable { pendingScrollPoint = null; showExtraRegions = !showExtraRegions }
                                .semantics { contentDescription = if (row.expanded) "收起常用经外奇穴" else "展开常用经外奇穴" }
                                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("常用经外奇穴", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                                    Text("${row.count} 个条目 · 按身体部位展开", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, modifier = Modifier.size(18.dp))
                            }
                            Checkbox(checked = "__extra__" in state.enabledMeridians,
                                onCheckedChange = { onToggleMeridian("__extra__") },
                                modifier = Modifier.semantics { contentDescription = "显示经外奇穴" })
                        }
                    }
                    is MeridianCatalogueRow.RegionHeader -> {
                        CatalogueExpandRow(row.region, "${row.count} 个奇穴", row.expanded,
                            if (row.expanded) "收起${row.region}奇穴" else "展开${row.region}奇穴") {
                            pendingScrollPoint = null
                            expandedRegions = if (row.expanded) expandedRegions - row.region else expandedRegions + row.region
                        }
                    }
                    MeridianCatalogueRow.Empty -> CatalogueEmptyResult()
                }
            }
        }
    }
}

@Composable
private fun MeridianGroupHeader(meridian: Meridian, expanded: Boolean, enabled: Boolean,
    subtitle: String, onExpand: () -> Unit, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f)),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onExpand)
            .semantics {
                contentDescription = if (expanded) "收起${meridian.nameZh}" else "展开${meridian.nameZh}"
                stateDescription = if (expanded) "已展开" else "已收起"
            }.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.width(3.dp).height(30.dp).background(Color(meridian.color), RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f)) {
                Text(meridian.nameZh, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null, modifier = Modifier.size(18.dp))
        }
        Checkbox(checked = enabled, onCheckedChange = { onToggle() },
            modifier = Modifier.semantics { contentDescription = "显示${meridian.nameZh}路线" })
    }
}

@Composable
private fun StructureCatalogue(data: AtlasData, state: AtlasUiState, query: String, musclesOnly: Boolean,
    onStructure: (AnatomyStructure) -> Unit, modifier: Modifier = Modifier) {
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    var depth by rememberSaveable { mutableStateOf<Int?>(null) }
    var system by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(data.structureGroups, query, musclesOnly, region, depth, system) {
        data.structureGroups.entries.filter { group ->
            (if (musclesOnly) group.members.any { it.system == "muscle" && (region == null || it.region == region) &&
                (depth == null || it.layer == depth) } else system == null || group.representative.system == system) &&
                group.members.any { SearchIndex.matches(query, it.nameZh, it.nameEn, it.id) }
        }
    }
    Column(modifier) {
        if (musclesOnly) {
            CatalogueFilters("区域", listOf(null to "全部") + catalogueRegions.entries
                .filter { entry -> data.structures.any { it.system == "muscle" && it.region == entry.key } }
                .map { it.key to it.value }, region) { region = it }
            CatalogueFilters("深度", listOf(null to "全部", 1 to "浅层", 2 to "中层", 3 to "深层"), depth) { depth = it }
        } else CatalogueFilters("系统", listOf(null to "全部") + catalogueSystems.map { it.key to it.value }, system) { system = it }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            item("structure-count") { CatalogueCaption((if (musclesOnly) "${filtered.size} 个肌肉结构" else "${filtered.size} 个解剖结构") +
                " · ${filtered.sumOf { it.members.size }} 个模型片段") }
            if (musclesOnly) items(filtered, key = { "muscle:${it.representative.id}" }) { group ->
                CatalogueStructureRow(group, state.selectedStructure == group.representative.id, onStructure)
            } else catalogueSystems.forEach { (id, title) ->
                val systemGroups = filtered.filter { it.representative.system == id }
                if (systemGroups.isNotEmpty()) {
                    item("system:$id") {
                        Text("$title · ${systemGroups.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 9.dp, top = 13.dp, bottom = 4.dp))
                    }
                    items(systemGroups, key = { "structure:${it.representative.id}" }) { group ->
                        CatalogueStructureRow(group, state.selectedStructure == group.representative.id, onStructure)
                    }
                }
            }
            if (filtered.isEmpty()) item("no-structures") { CatalogueEmptyResult() }
        }
    }
}

@Composable
private fun <T> CatalogueFilters(label: String, values: List<Pair<T?, String>>, selected: T?, onSelect: (T?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        values.forEach { (value, title) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) },
                label = { Text(title, fontSize = 11.sp) })
        }
    }
}

@Composable
private fun CataloguePointRow(point: Acupoint, selected: Boolean, subtitle: String?, onPoint: (Acupoint) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
        .clickable { onPoint(point) }.semantics { contentDescription = "定位${point.nameZh}" }
        .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(point.codes.joinToString(" / ").ifBlank { "奇穴" }, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 53.dp, max = 100.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(point.nameZh, fontSize = 14.sp)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (point.instances.none { it.position != null }) Text("未定位", fontSize = 9.sp, color = MaterialTheme.colorScheme.secondary)
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun CatalogueStructureRow(group: StructureGroup, selected: Boolean, onStructure: (AnatomyStructure) -> Unit) {
    val structure = group.representative
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
            else MaterialTheme.colorScheme.background.copy(alpha = .35f))
        .clickable { onStructure(structure) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(structure.nameZh, fontSize = 13.sp)
            val region = catalogueRegions[structure.region] ?: structure.region
            val depth = when (structure.layer) { 1 -> "浅层"; 2 -> "中层"; 3 -> "深层"; else -> "第 ${structure.layer} 层" }
            Text("${catalogueSystems[structure.system] ?: structure.system} · $region" +
                if (structure.system == "muscle") " · $depth" else "", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (group.members.size > 1) Text("${group.members.size} 个片段 · 整体操作", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary)
            Text(structure.nameEn, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun CatalogueExpandRow(title: String, subtitle: String, expanded: Boolean, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = description }.padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 13.sp)
        Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

@Composable
private fun CatalogueCaption(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp))
}

@Composable
private fun CatalogueEmptyResult() {
    Text("没有匹配结果", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
}
