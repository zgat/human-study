package com.zga.meridianatlas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal enum class SelectionCardSummary { FULL, COMPACT, MINIMAL }
private class CardPositionMemory {
    var box: LabelRect? = null
}

/** Measures cards inside the unchanged model viewport; camera and anatomy never move for UI. */
@Composable
internal fun FloatingSelectionCard(
    selectionKey: String,
    primary: CardProtectionPoint?,
    softPoints: List<CardProtectionPoint>,
    obstacles: List<LabelRect>,
    onBounds: (Rect?) -> Unit,
    content: @Composable (SelectionCardSummary, Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val memory = remember(selectionKey, constraints.maxWidth, constraints.maxHeight) { CardPositionMemory() }
        var summary by remember(selectionKey, constraints.maxWidth, constraints.maxHeight) {
            mutableStateOf(SelectionCardSummary.FULL)
        }
        // Keep exactly one real card and one set of accessible actions. Measuring multiple
        // subcompositions can leave invisible duplicate cards in the semantics tree.
        Layout(content = {
            content(summary, Modifier.fillMaxWidth().testTag("selection-card")
                .onGloballyPositioned { onBounds(it.boundsInParent()) })
        }, modifier = Modifier.fillMaxSize()) { measurables, viewport ->
            val width = viewport.maxWidth
            val height = viewport.maxHeight
            val inset = 8.dp.toPx()
            val maxCardWidth = when (summary) {
                SelectionCardSummary.FULL -> 420.dp.roundToPx()
                SelectionCardSummary.COMPACT -> 320.dp.roundToPx()
                SelectionCardSummary.MINIMAL -> 224.dp.roundToPx()
            }.coerceAtMost((width - 2 * inset).roundToInt()).coerceAtLeast(1)
            val card = measurables.single().measure(Constraints(maxWidth = maxCardWidth))
            val box = placeSelectionCard(width.toFloat(), height.toFloat(), card.width.toFloat(),
                card.height.toFloat(), primary, softPoints, obstacles, memory.box, inset)
            if (box != null) {
                memory.box = box
            } else if (summary != SelectionCardSummary.MINIMAL) {
                // Bounded to two retries; never draw an unsafe full card while measuring a
                // shorter summary. Keep the safe summary until selection/viewport changes.
                summary = SelectionCardSummary.entries[summary.ordinal + 1]
            }
            layout(width, height) {
                if (box == null) onBounds(null)
                else card.place(box.left.roundToInt(), box.top.roundToInt())
            }
        }
    }
}

/** Keeps the name, limitation cue and full-details/close actions in short viewports. */
@Composable
internal fun CompactSelectionSummary(title: String, subtitle: String, description: String,
    hasNotice: Boolean, minimal: Boolean, onExpand: () -> Unit, onClose: () -> Unit,
    onHide: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle + if (hasNotice) " · 定位待校对" else "", fontSize = 10.sp,
                    color = if (hasNotice) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onHide != null && !minimal) IconButton(onClick = onHide, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.VisibilityOff, "隐藏此结构", Modifier.size(17.dp))
            }
            IconButton(onClick = onExpand, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Info, "查看完整说明", Modifier.size(18.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, "取消选中", Modifier.size(18.dp))
            }
        }
        if (!minimal) Text(description, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
