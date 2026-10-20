package com.zga.meridianatlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    onObserve: (FascialConnection) -> Unit, onTarget: (FascialConnection, FascialTarget) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("筋膜与连接链", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("力线教学 · 解剖连接参考区", color = muted, fontSize = 12.sp)
        }
        item {
            Text("筋膜网格是解剖结构；虚线是研究中的连接关系示意，未计算受力大小或方向。菱形标记代表连接参考区，并非肌筋膜触发点或治疗靶点。", fontSize = 12.sp, lineHeight = 19.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onMaster)) {
                Text("显示连接链", Modifier.weight(1f)); Switch(layer.fasciaChains, onCheckedChange = { onMaster() })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onTargets)) {
                Text("显示连接参考区", Modifier.weight(1f)); Switch(layer.fasciaTargets, onCheckedChange = { onTargets() })
            }
            Text("先勾选研究链，再点“观察这组结构”显露真实组织；点参考区可定位并查看解释。", color = muted, fontSize = 11.sp)
        }
        items(connections, key = { it.id }) { connection ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(connection.nameZh, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Checkbox(connection.id in layer.enabledFascialConnections, onCheckedChange = { onToggle(connection.id) },
                        modifier = Modifier.semantics { contentDescription = "显示${connection.nameZh}连接链" })
                }
                Text(connection.descriptionZh, fontSize = 12.sp, lineHeight = 19.sp)
                OutlinedButton(onClick = { onObserve(connection) }) { Text("观察这组结构") }
                Text("连接参考区", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                connection.targets.forEach { target ->
                    TextButton(onClick = { onTarget(connection, target) }, contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.semantics { contentDescription = "定位连接参考区${target.id}" }) {
                        Text(target.nameZh, fontSize = 12.sp)
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
                }
            }
        }
        item {
            HorizontalDivider()
            Text("采用的命名与学习范围", fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
            Text("结构名称参照 FIPAT《Terminologia Anatomica》第 2 版；筋膜与广义筋膜系统的区分参照 FNC 2019 共识。这里展示少量有研究来源的局部连接，未收录整套《解剖列车》线路，也没有通用临床靶点坐标标准。", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
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
