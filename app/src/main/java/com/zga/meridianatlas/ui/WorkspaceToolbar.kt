package com.zga.meridianatlas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Match the model's existing circular view controls without reserving a toolbar row.
private val WorkspacePanelColor = Color(0xff14222d)
private val WorkspaceLineColor = Color(0xff263b49)
private val WorkspaceMuted = Color(0xff8da4b2)

/** Fixed action order; the scroll container keeps every action reachable on short viewports. */
@Composable
internal fun WorkspaceActions(
    canUndo: Boolean,
    canRestoreAnatomy: Boolean,
    onAbout: () -> Unit,
    onCatalogue: (() -> Unit)?,
    onUndo: () -> Unit,
    onRestoreAnatomy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.testTag("workspace-actions").verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WorkspaceActionButton("关于", "关于与数据来源", onClick = onAbout)
        if (onCatalogue != null) WorkspaceActionButton("目录", "图谱目录", onClick = onCatalogue)
        WorkspaceActionButton("撤销", "撤销上一步显示操作", onUndo, enabled = canUndo)
        WorkspaceActionButton("还原", "一键还原组织", onRestoreAnatomy, enabled = canRestoreAnatomy)
    }
}

/** Same 36dp circular face and Surface touch-target policy as the original view controls. */
@Composable
internal fun WorkspaceActionButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        shape = CircleShape, color = WorkspacePanelColor.copy(alpha = .93f),
        border = BorderStroke(1.dp, WorkspaceLineColor)) {
        Box(Modifier.size(36.dp).testTag("workspace-action-face-$label"), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, color = WorkspaceMuted.copy(alpha = if (enabled) 1f else .42f))
        }
    }
}
