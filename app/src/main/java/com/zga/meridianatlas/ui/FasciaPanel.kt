package com.zga.meridianatlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zga.meridianatlas.model.*

/** Named fascia sheets, aponeuroses and retinacula, rather than every connective tissue. */
internal fun fasciaAnatomyCatalogue(structures: List<StructureGroup>): List<StructureGroup> =
    structures.filter { group -> group.members.all { it.system == "connective" &&
        it.tissueType in setOf("fascia", "aponeurosis", "retinaculum") } }
        .sortedWith(compareBy({ fasciaRegionNames.keys.indexOf(it.representative.region) }, { it.representative.nameZh }))

private val fasciaRegionNames = linkedMapOf("head_neck" to "头颈", "trunk" to "躯干",
    "upper_limb" to "上肢", "lower_limb" to "下肢", "whole" to "全身")

@Composable
internal fun FasciaPanel(connections: List<FascialConnection>, layer: LayerState,
    onMaster: () -> Unit, onTargets: () -> Unit, onToggle: (String) -> Unit,
    onObserve: (FascialConnection) -> Unit, onTarget: (FascialConnection, FascialTarget) -> Unit,
    onSetConnections: ((Set<String>) -> Unit)? = null,
    onObserveAll: ((List<FascialConnection>) -> Unit)? = null,
    selectedTargetId: String? = null,
    structures: List<StructureGroup> = emptyList(),
    onStructure: ((StructureGroup) -> Unit)? = null,
    onRelations: (() -> Unit)? = null,
    onContext: (() -> Unit)? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ids = remember(connections) { connections.map { it.id }.toSet() }
    val catalogue = remember(structures) { fasciaAnatomyCatalogue(structures) }
    var tab by remember { mutableIntStateOf(if (selectedTargetId == null) 0 else 1) }
    var query by remember { mutableStateOf("") }
    var region by remember { mutableStateOf<String?>(null) }
    var expanded by remember(ids) { mutableStateOf(setOfNotNull(connections.firstOrNull()?.id)) }
    val structureList = rememberLazyListState()
    val connectionList = rememberLazyListState()
    LaunchedEffect(selectedTargetId) {
        val selectedIndex = connections.indexOfFirst { group -> group.targets.any { it.id == selectedTargetId } }
        if (selectedIndex >= 0) {
            tab = 1
            expanded = expanded + connections[selectedIndex].id
            connectionList.scrollToItem(selectedIndex + 1)
        }
    }
    fun setConnections(value: Set<String>) {
        if (onSetConnections != null) onSetConnections(value)
        else (layer.enabledFascialConnections union value).filter {
            (it in layer.enabledFascialConnections) != (it in value)
        }.forEach(onToggle)
    }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp)) {
            Text("筋膜解剖与连接", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("真实结构优先 · 文献连接可选", color = muted, fontSize = 12.sp)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("解剖结构") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("文献连接") })
        }
        if (tab == 0) {
            val found = catalogue.filter { group ->
                (region == null || group.representative.region == region) &&
                    (query.isBlank() || group.members.any { member ->
                        listOf(member.nameZh, member.nameEn, member.id).any { it.contains(query.trim(), ignoreCase = true) }
                    })
            }
            LazyColumn(Modifier.fillMaxWidth(), state = structureList, contentPadding = PaddingValues(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text("按模型中实际存在的筋膜、腱膜和支持带浏览，分别标明组织类型；不将肌腱、其他韧带或示意线充作筋膜网格。可观察每个结构的形态与位置；结构连续不等于已证实的全身传力路线。", fontSize = 12.sp, lineHeight = 19.sp)
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("搜索筋膜、腱膜、支持带") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = region == null, onClick = { region = null }, label = { Text("全部区域") })
                        fasciaRegionNames.filterKeys { key -> catalogue.any { it.representative.region == key } }.forEach { (key, name) ->
                            FilterChip(selected = region == key, onClick = { region = key }, label = { Text(name) })
                        }
                    }
                    Text("${found.size} / ${catalogue.size} 个解剖结构 · ${catalogue.sumOf { it.members.size }} 个独立网格", color = muted, fontSize = 11.sp)
                    Text("“观察结构”会单独显露真实网格并自动取景；可用恢复显示返回。薄片、开口及缺失区保留源模型形态，未补画为连续包膜。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
                }
                items(found, key = { it.id }) { group ->
                    val structure = group.representative
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider()
                        Text(structure.nameZh, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("${fasciaRegionNames[structure.region] ?: structure.region} · ${structure.tissueTypeZh.ifBlank { when (structure.tissueType) { "aponeurosis" -> "腱膜"; "retinaculum" -> "支持带"; else -> "筋膜" } }}" +
                            if (group.members.size > 1) " · ${group.members.size} 个网格合并观察" else "", color = muted, fontSize = 11.sp)
                        if (structure.nameEn.isNotBlank()) Text(structure.nameEn, color = muted, fontSize = 10.sp)
                        if (structure.descriptionZh.isNotBlank()) Text(structure.descriptionZh, fontSize = 12.sp, lineHeight = 19.sp)
                        Text("模型来源：${structure.sourceDataset.ifBlank { "见素材授权清单" }}", color = muted, fontSize = 11.sp)
                        if (structure.sourceConceptNoteZh.isNotBlank()) Text(structure.sourceConceptNoteZh, color = muted, fontSize = 11.sp, lineHeight = 18.sp)
                        Text("展示范围：源模型几何与名称，不表示组织受力、筋膜滑动或临床靶点。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
                        OutlinedButton(onClick = { onStructure?.invoke(group) }, enabled = onStructure != null,
                            modifier = Modifier.semantics { contentDescription = "观察筋膜结构${group.id}" }) { Text("观察结构") }
                    }
                }
                if (found.isEmpty()) item { Text("没有匹配的筋膜、腱膜或支持带结构", color = muted) }
                item { FasciaScopeNote() }
            }
        } else LazyColumn(Modifier.fillMaxWidth(), state = connectionList, contentPadding = PaddingValues(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("文献记录的局部连接", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("本层逐段区分解剖连续、实验加载和人体间接观察；不同证据不能互相替代。本版引用的命名与运动医学共识未提供统一的全身标准力线或通用临床靶点图。", fontSize = 12.sp, lineHeight = 19.sp)
                FasciaSwitch("显示连接图层", layer.fasciaChains, onMaster)
                if (onRelations != null) FasciaSwitch("显示局部连接示意线", layer.fasciaRelations, onRelations)
                if (onContext != null) FasciaSwitch("组织范围辅助线（非力线）", layer.fasciaContext, onContext)
                FasciaSwitch("显示连接参考区", layer.fasciaTargets, onTargets)
                Text("示意线、范围辅助线与菱形参考区独立开关；关闭标记不会清除已选对象。范围辅助线默认关闭，只帮助观察组织的几何范围。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
                Text("已选 ${layer.enabledFascialConnections.intersect(ids).size} / ${connections.size} 组", color = muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { setConnections(layer.enabledFascialConnections + ids) },
                        modifier = Modifier.semantics { contentDescription = "全选筋膜文献连接" }) { Text("全选") }
                    OutlinedButton(onClick = { setConnections(layer.enabledFascialConnections - ids) },
                        modifier = Modifier.semantics { contentDescription = "全不选筋膜文献连接" }) { Text("全不选") }
                }
                if (onObserveAll != null) OutlinedButton(enabled = layer.enabledFascialConnections.intersect(ids).isNotEmpty(),
                    onClick = { onObserveAll(connections.filter { it.id in layer.enabledFascialConnections }) }) { Text("观察已选结构") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { expanded = ids }) { Text("全部展开") }
                    TextButton(onClick = { expanded = emptySet() }) { Text("全部收起") }
                }
                Text("虚线：文献局部连接的无方向示意\n实线：组织范围辅助线（非力线）\n菱形：模型表面的连接参考区，非治疗靶点", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
                Text("完整皮肤会遮住深层连接；“观察这组结构”会显露对应组织并自动取景。示意线未计算受力大小、方向或作用效果。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
            }
            items(connections, key = { it.id }) { connection ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { expanded = if (connection.id in expanded) expanded - connection.id else expanded + connection.id },
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text("${if (connection.id in expanded) "▾" else "▸"} ${connection.nameZh}", Modifier.fillMaxWidth(),
                                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = androidx.compose.ui.graphics.Color(connection.color))
                        }
                        Checkbox(connection.id in layer.enabledFascialConnections, onCheckedChange = { onToggle(connection.id) },
                            modifier = Modifier.semantics { contentDescription = "显示${connection.nameZh}文献连接" })
                    }
                    if (connection.bodyRegionsZh.isNotEmpty()) Text(connection.bodyRegionsZh.joinToString(" / "), color = muted, fontSize = 11.sp)
                    Text(connection.descriptionZh, fontSize = 12.sp, lineHeight = 19.sp)
                    OutlinedButton(onClick = { onObserve(connection) },
                        modifier = Modifier.semantics { contentDescription = "观察筋膜连接组${connection.id}" }) { Text("观察这组结构") }
                    if (connection.id in expanded) {
                        Text("连接参考区 · ${connection.targets.size} 处", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        connection.targets.groupBy { it.side }.forEach { (side, targets) ->
                            Text(when (side) { "left" -> "左侧"; "right" -> "右侧"; "midline" -> "正中"; else -> "跨侧 / 其他" }, fontSize = 11.sp, color = muted)
                            targets.forEach { target ->
                                TextButton(onClick = { onTarget(connection, target) }, contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.semantics { contentDescription = "定位连接参考区${target.id}" }) { Text(target.nameZh, fontSize = 12.sp) }
                            }
                        }
                        Text("逐项证据与条件", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        connection.evidenceReferences.forEach { evidence -> FasciaEvidenceCard(evidence, connection.sourceRefs) }
                        if (connection.evidenceReferences.isEmpty()) Text("本组仅提供组织范围观察；辅助线不是经实验验证的传力路径。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
                        Text("模型覆盖", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(connection.geometryCoverageZh, fontSize = 11.sp, color = muted, lineHeight = 18.sp)
                        if (connection.unmappedStructures.isNotEmpty()) Text("无独立网格：${connection.unmappedStructures.joinToString("、")}", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                        val cited = connection.evidenceReferences.flatMap { it.sourceIds }.toSet()
                        connection.sourceRefs.filter { it.id !in cited }.forEach { source -> FasciaSource(source) }
                    }
                }
            }
            item { FasciaScopeNote() }
        }
    }
}

@Composable
private fun FasciaSwitch(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Text(title, Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked, onCheckedChange = { onToggle() }, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun FasciaEvidenceCard(evidence: FascialEvidence, sources: List<FascialSource>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when (evidence.kind) {
                "anatomical_continuity" -> "解剖连续"
                "experimental_loading" -> "实验加载"
                "human_indirect_observation" -> "人体间接观察"
                else -> "文献记录"
            }, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            Text(evidence.scopeZh, fontSize = 12.sp, lineHeight = 19.sp)
            listOf("对象 / 样本" to evidence.sampleZh, "条件" to evidence.conditionsZh,
                "测量 / 观察" to evidence.measurementZh, "限制" to evidence.limitationsZh).forEach { (label, body) ->
                if (body.isNotBlank()) Text("$label：$body", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
            }
            sources.filter { it.id in evidence.sourceIds }.forEach { source -> FasciaSource(source) }
        }
    }
}

@Composable
private fun FasciaSource(source: FascialSource) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val uriHandler = LocalUriHandler.current
    var cannotOpen by remember(source.url) { mutableStateOf(false) }
    SelectionContainer { Text("${source.year} · ${source.title}", fontSize = 10.sp, color = muted, lineHeight = 16.sp) }
    SelectionContainer { Text(source.url, fontSize = 10.sp, color = muted) }
    TextButton(onClick = { cannotOpen = runCatching { uriHandler.openUri(source.url) }.isFailure }, contentPadding = PaddingValues(0.dp)) { Text("查看原文与出处", fontSize = 11.sp) }
    if (cannotOpen) Text("当前无法打开链接，可复制上方地址查看。", fontSize = 10.sp, color = muted)
    if (source.noteZh.isNotBlank()) Text(source.noteZh, fontSize = 10.sp, color = muted, lineHeight = 16.sp)
}

@Composable
private fun FasciaScopeNote() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    HorizontalDivider()
    Text("命名与证据边界", fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
    Text("结构名称参照 FIPAT《Terminologia Anatomica》第 2 版；筋膜与广义筋膜系统的区分参照筋膜命名共识。命名共识不提供统一的全身力线图；局部连续与传力研究不能直接推出固定全身路线、活体受力方向或治疗效果。模型参考区不等同于肌筋膜触发点。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
    SelectionContainer { Text("https://fipat.library.dal.ca/ta2/\nhttps://doi.org/10.1002/ca.23423", color = muted, fontSize = 10.sp) }
}

@Composable
internal fun FascialSelectionCard(connection: FascialConnection, target: FascialTarget,
    onExpand: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    summary: SelectionCardSummary = SelectionCardSummary.FULL) {
    Surface(onClick = onExpand, modifier = modifier, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .97f), tonalElevation = 5.dp) {
        if (summary != SelectionCardSummary.FULL) CompactSelectionSummary(target.nameZh, connection.nameZh,
            target.descriptionZh, false, summary == SelectionCardSummary.MINIMAL, onExpand, onClose)
        else Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(target.nameZh, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClose, contentPadding = PaddingValues(4.dp)) { Text("关闭") }
            }
            Text(connection.nameZh, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            Text(target.descriptionZh, maxLines = 2, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onExpand, contentPadding = PaddingValues(0.dp)) { Text("连接证据与条件", fontSize = 12.sp) }
        }
    }
}
