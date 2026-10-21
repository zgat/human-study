package com.zga.meridianatlas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The workspace uses its top row for actions; the app title lives in About and the launcher. */
@Composable
internal fun WorkspaceToolbar(
    canUndo: Boolean,
    canRestoreAnatomy: Boolean,
    onSearch: () -> Unit,
    onUndo: () -> Unit,
    onRestoreAnatomy: () -> Unit,
    onAbout: () -> Unit,
    onSelectionDetails: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp, vertical = 2.dp)
        .testTag("workspace-toolbar"), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onSearch, modifier = Modifier.weight(1f).height(44.dp)
            .semantics { contentDescription = "搜索图谱" },
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Text("搜索目录", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Undo, "撤销上一步显示操作", Modifier.size(20.dp))
                Text("撤销", fontSize = 9.sp)
            }
        }
        IconButton(onClick = onRestoreAnatomy, enabled = canRestoreAnatomy) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Restore, "一键还原组织", Modifier.size(20.dp))
                Text("还原", fontSize = 9.sp)
            }
        }
        if (onSelectionDetails != null) IconButton(onClick = onSelectionDetails) {
            Icon(Icons.Outlined.Subject, "所选项目说明", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onAbout) {
            Icon(Icons.Outlined.Info, "关于与数据来源")
        }
    }
}
