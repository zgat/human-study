package com.zga.meridianatlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zga.meridianatlas.model.*

@Composable
internal fun FasciaPanel(connections: List<FascialConnection>, layer: LayerState,
    onMaster: () -> Unit, onTargets: () -> Unit, onToggle: (String) -> Unit,
    onObserve: (FascialConnection) -> Unit, onTarget: (FascialConnection, FascialTarget) -> Unit,
    onSetConnections: ((Set<String>) -> Unit)? = null,
    onObserveAll: ((List<FascialConnection>) -> Unit)? = null,
    selectedTargetId: String? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ids = remember(connections) { connections.map { it.id }.toSet() }
    var expanded by remember(ids) { mutableStateOf(setOfNotNull(connections.firstOrNull()?.id)) }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedTargetId) {
        val selectedIndex = connections.indexOfFirst { group -> group.targets.any { it.id == selectedTargetId } }
        if (selectedIndex >= 0) {
            expanded = expanded + connections[selectedIndex].id
            listState.scrollToItem(selectedIndex + 2)
        }
    }
    fun setConnections(value: Set<String>) {
        if (onSetConnections != null) onSetConnections(value)
        else (layer.enabledFascialConnections union value).filter {
            (it in layer.enabledFascialConnections) != (it in value)
        }.forEach(onToggle)
    }
    LazyColumn(Modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("筋膜与连接链", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("力线教学 · 解剖连接参考区", color = muted, fontSize = 12.sp)
        }
        item {
            Text("筋膜网格是解剖结构；虚线是连接关系示意，未计算受力大小或方向。局部研究连接与较长的链条走向分开标明。菱形代表模型表面的连接参考区，并非肌筋膜触发点或治疗靶点。", fontSize = 12.sp, lineHeight = 19.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onMaster)) {
                Text("显示连接链", Modifier.weight(1f)); Switch(layer.fasciaChains, onCheckedChange = { onMaster() })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onTargets)) {
                Text("显示连接参考区", Modifier.weight(1f)); Switch(layer.fasciaTargets, onCheckedChange = { onTargets() })
            }
            Text("已选 ${layer.enabledFascialConnections.intersect(ids).size} / ${connections.size} 组", color = muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { setConnections(layer.enabledFascialConnections + ids) },
                    modifier = Modifier.semantics { contentDescription = "全选筋膜连接链" }) { Text("全选") }
                OutlinedButton(onClick = { setConnections(layer.enabledFascialConnections - ids) },
                    modifier = Modifier.semantics { contentDescription = "全不选筋膜连接链" }) { Text("全不选") }
            }
            if (onObserveAll != null) OutlinedButton(
                enabled = layer.enabledFascialConnections.intersect(ids).isNotEmpty(),
                onClick = { onObserveAll(connections.filter { it.id in layer.enabledFascialConnections }) },
            ) { Text("观察已选结构") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = ids }) { Text("全部展开") }
                TextButton(onClick = { expanded = emptySet() }) { Text("全部收起") }
            }
            Text("实线：真实网格上的组织范围参照\n虚线：文献中的局部连接关系\n菱形：连接参考区", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
            Text("勾选后显示点线；“观察这组结构”会显露相关组织并调整视角。点参考区可定位并查看解释。完整皮肤可能遮住深层连接，隐藏皮肤后可查看关系示意。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
        }
        items(connections, key = { it.id }) { connection ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { expanded = if (connection.id in expanded) expanded - connection.id else expanded + connection.id },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("${if (connection.id in expanded) "▾" else "▸"} ${connection.nameZh}",
                            Modifier.fillMaxWidth(), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            color = androidx.compose.ui.graphics.Color(connection.color))
                    }
                    Checkbox(connection.id in layer.enabledFascialConnections, onCheckedChange = { onToggle(connection.id) },
                        modifier = Modifier.semantics { contentDescription = "显示${connection.nameZh}连接链" })
                }
                Text(when (connection.coverageKind) {
                    "local_research_relation" -> "局部研究连接"
                    "anatomical_chain_context" -> "组织范围与链条参照"
                    else -> "连接关系参照"
                } + if (connection.bodyRegionsZh.isEmpty()) "" else " · ${connection.bodyRegionsZh.joinToString(" / ")}",
                    color = muted, fontSize = 11.sp)
                Text(connection.descriptionZh, fontSize = 12.sp, lineHeight = 19.sp)
                OutlinedButton(onClick = { onObserve(connection) },
                    modifier = Modifier.semantics { contentDescription = "观察筋膜连接组${connection.id}" }) { Text("观察这组结构") }
                if (connection.id in expanded) {
                    Text("连接参考区 · ${connection.targets.size} 处", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    connection.targets.groupBy { it.side }.forEach { (side, targets) ->
                        Text(when (side) { "left" -> "左侧"; "right" -> "右侧"; "midline" -> "正中"; else -> "跨侧 / 其他" },
                            fontSize = 11.sp, color = muted)
                        targets.forEach { target ->
                            TextButton(onClick = { onTarget(connection, target) }, contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.semantics { contentDescription = "定位连接参考区${target.id}" }) {
                                Text(target.nameZh, fontSize = 12.sp)
                            }
                        }
                    }
                    Text("证据与覆盖", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text(connection.evidenceZh, fontSize = 11.sp, color = muted, lineHeight = 18.sp)
                    Text(connection.geometryCoverageZh, fontSize = 11.sp, color = muted, lineHeight = 18.sp)
                    if (connection.unmappedStructures.isNotEmpty()) Text("无独立网格：${connection.unmappedStructures.joinToString("、")}", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                    connection.sourceRefs.forEach { source ->
                        SelectionContainer {
                            Text("${source.year} · ${source.evidenceType}\n${source.title}\n${source.url}", fontSize = 10.sp, color = muted, lineHeight = 16.sp)
                        }
                        if (source.noteZh.isNotBlank()) Text(source.noteZh, fontSize = 10.sp, color = muted, lineHeight = 16.sp)
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("采用的命名与学习范围", fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
            Text("结构名称参照 FIPAT《Terminologia Anatomica》第 2 版；筋膜与广义筋膜系统的区分参照 FNC 2019 共识。每组分别说明文献证据、模型覆盖和缺少的结构；长链走向是教学关系示意，不能据此推出整条链在活体中的受力方向、大小或治疗效果。没有通用临床靶点坐标标准。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
            SelectionContainer { Text("https://fipat.library.dal.ca/ta2/\nhttps://doi.org/10.1002/ca.23423", color = muted, fontSize = 10.sp) }
        }
    }
}

@Composable
internal fun FascialSelectionCard(connection: FascialConnection, target: FascialTarget,
    onExpand: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onExpand, modifier = modifier, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .97f), tonalElevation = 5.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(target.nameZh, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClose, contentPadding = PaddingValues(4.dp)) { Text("关闭") }
            }
            Text(connection.nameZh, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            Text(target.descriptionZh, maxLines = 2, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onExpand, contentPadding = PaddingValues(0.dp)) { Text("连接说明与出处", fontSize = 12.sp) }
        }
    }
}
