package com.zga.meridianatlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        AtlasMode.MUSCLES -> "${data.structures.count { it.system == "muscle" }} 个肌肉系统部件 · 分区分层"
        AtlasMode.OVERVIEW -> "${data.structures.size} 个结构 · 解剖系统"
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
                onToggleMeridian, choosePoint, Modifier.weight(1f))
            AtlasMode.MUSCLES -> StructureCatalogue(data, state, query, true, chooseStructure, Modifier.weight(1f))
            AtlasMode.OVERVIEW -> StructureCatalogue(data, state, query, false, chooseStructure, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MeridianCatalogue(data: AtlasData, state: AtlasUiState, query: String,
    onMeridian: (String) -> Unit, onToggleMeridian: (String) -> Unit,
    onPoint: (Acupoint) -> Unit, modifier: Modifier = Modifier) {
    val canonicalPoints = remember(data.points) { data.points.distinctBy { it.id } }
    val byId = remember(canonicalPoints) { canonicalPoints.associateBy { it.id } }
    val routeTerms = remember(data) {
        canonicalPoints.associate { point ->
            point.id to data.meridians.filter { it.id == point.meridianId || point.id in it.pointIds }
                .joinToString(" ") { "${it.nameZh} ${it.shortName} ${it.id}" }
        }
    }
    val primaryNames = remember(data.meridians) { data.meridians.associate { it.id to it.nameZh } }
    var expandedRoutes by remember { mutableStateOf(setOfNotNull(state.selectedMeridian)) }
    var expandedRegions by remember { mutableStateOf(emptySet<String>()) }
    var showExtraRegions by rememberSaveable { mutableStateOf(true) }
    var groupFilter by rememberSaveable { mutableStateOf("routes") }
    // A restored older UI may still contain "all"; give it the current route default.
    val activeGroup = if (groupFilter == "extra") "extra" else "routes"
    val searching = query.isNotEmpty()
    val results = remember(canonicalPoints, routeTerms, query) {
        // The canonical list owns result identity; extraordinary-route associations are
        // searchable metadata and never create a second result for the same acupoint.
        canonicalPoints.filter { point -> SearchIndex.matches(query, point.nameZh, point.pinyin,
            point.id, point.codes.joinToString(" "), point.aliases.joinToString(" "),
            routeTerms[point.id].orEmpty(), point.region) }
    }
    Column(modifier) {
        if (!searching) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("routes" to "经络路线", "extra" to "经外奇穴").forEach { (id, label) ->
                FilterChip(selected = activeGroup == id, onClick = { groupFilter = id },
                    label = { Text(label, fontSize = 11.sp) })
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (searching) {
                item("search-count") { CatalogueCaption("${results.size} 个穴位 · 按名称与归经查找") }
                items(results, key = { "search:${it.id}" }) { point ->
                    CataloguePointRow(point, state.selectedPoint == point.id,
                        primaryNames[point.meridianId] ?: "经外奇穴 · ${point.region}", onPoint)
                }
                if (results.isEmpty()) item("no-points") { CatalogueEmptyResult() }
            } else {
                if (activeGroup == "routes") {
                    item("route-heading") { CatalogueCaption("经络路线 · 展开查看所属穴位与交会穴") }
                    data.meridians.forEach { meridian ->
                        val linked = (meridian.pointIds.mapNotNull(byId::get) +
                            canonicalPoints.filter { it.meridianId == meridian.id }).distinctBy { it.id }
                        val sixExtra = meridian.routeType == "extraordinary" && meridian.id.uppercase() !in setOf("GV", "CV")
                        val expanded = meridian.id in expandedRoutes
                        item("route:${meridian.id}") {
                            MeridianGroupHeader(meridian, expanded, meridian.id in state.enabledMeridians,
                                if (sixExtra) "${linked.size} 个关联交会穴" else "${linked.size} 个所属穴位",
                                onExpand = {
                                    expandedRoutes = if (expanded) expandedRoutes - meridian.id else expandedRoutes + meridian.id
                                    if (!expanded) onMeridian(meridian.id)
                                }, onToggle = { onToggleMeridian(meridian.id) })
                        }
                        if (expanded) {
                            item("route-description:${meridian.id}") {
                                Text(meridian.descriptionZh, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(start = 13.dp, end = 10.dp, top = 3.dp, bottom = 6.dp))
                            }
                            items(linked, key = { "route:${meridian.id}:${it.id}" }) { point ->
                                CataloguePointRow(point, state.selectedPoint == point.id,
                                    if (sixExtra) "交会穴 · ${primaryNames[point.meridianId].orEmpty()}" else null, onPoint)
                            }
                        }
                    }
                }
                if (activeGroup == "extra") {
                    val extraPoints = canonicalPoints.filter { it.kind == "extra" }
                    item("extra-heading") {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = .08f)),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable { showExtraRegions = !showExtraRegions }
                                .semantics { contentDescription = if (showExtraRegions) "收起常用经外奇穴" else "展开常用经外奇穴" }
                                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("常用经外奇穴", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                                    Text("${extraPoints.size} 个条目 · 按身体部位展开", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (showExtraRegions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, modifier = Modifier.size(18.dp))
                            }
                            Checkbox(checked = "__extra__" in state.enabledMeridians,
                                onCheckedChange = { onToggleMeridian("__extra__") },
                                modifier = Modifier.semantics { contentDescription = "显示经外奇穴" })
                        }
                    }
                    if (showExtraRegions) extraPoints.groupBy { it.region }.forEach { (region, points) ->
                        val expanded = region in expandedRegions
                        item("extra-region:$region") {
                            CatalogueExpandRow(region, "${points.size} 个奇穴", expanded,
                                if (expanded) "收起${region}奇穴" else "展开${region}奇穴") {
                                expandedRegions = if (expanded) expandedRegions - region else expandedRegions + region
                            }
                        }
                        if (expanded) items(points, key = { "extra:${it.id}" }) { point ->
                            CataloguePointRow(point, state.selectedPoint == point.id, null, onPoint)
                        }
                    }
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
    val filtered = remember(data.structures, query, musclesOnly, region, depth, system) {
        data.structures.filter { structure ->
            (if (musclesOnly) structure.system == "muscle" && (region == null || structure.region == region) &&
                (depth == null || structure.layer == depth) else system == null || structure.system == system) &&
                SearchIndex.matches(query, structure.nameZh, structure.nameEn, structure.id)
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
            item("structure-count") { CatalogueCaption(if (musclesOnly) "${filtered.size} 个肌肉系统部件" else "${filtered.size} 个解剖结构") }
            if (musclesOnly) items(filtered, key = { "muscle:${it.id}" }) { structure ->
                CatalogueStructureRow(structure, state.selectedStructure == structure.id, onStructure)
            } else catalogueSystems.forEach { (id, title) ->
                val group = filtered.filter { it.system == id }
                if (group.isNotEmpty()) {
                    item("system:$id") {
                        Text("$title · ${group.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 9.dp, top = 13.dp, bottom = 4.dp))
                    }
                    items(group, key = { "structure:${it.id}" }) { structure ->
                        CatalogueStructureRow(structure, state.selectedStructure == structure.id, onStructure)
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
private fun CatalogueStructureRow(structure: AnatomyStructure, selected: Boolean, onStructure: (AnatomyStructure) -> Unit) {
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
