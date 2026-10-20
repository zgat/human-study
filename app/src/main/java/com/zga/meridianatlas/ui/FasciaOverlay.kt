package com.zga.meridianatlas.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.zga.meridianatlas.model.*

internal data class FascialOverlayTarget(val anchorId: String, val target: FascialTarget, val color: Long)
internal data class FascialOverlayLine(val anchorIds: List<String>, val color: Long)
internal data class FascialOverlayData(val anchors: List<ProjectionAnchor>, val targets: List<FascialOverlayTarget>,
    val lines: List<FascialOverlayLine>)

internal fun buildFascialOverlay(connections: List<FascialConnection>, layer: LayerState): FascialOverlayData {
    if (!layer.fasciaChains) return FascialOverlayData(emptyList(), emptyList(), emptyList())
    val anchors = mutableListOf<ProjectionAnchor>()
    val targets = mutableListOf<FascialOverlayTarget>()
    val lines = mutableListOf<FascialOverlayLine>()
    connections.filter { it.id in layer.enabledFascialConnections }.forEach { connection ->
        connection.paths.forEach { path ->
            // These are deliberately straight, unoriented relation diagrams, never skin
            // routes, reconstructed fascia sheets or measured force vectors.
            val ids = path.points.mapIndexed { index, point ->
                val id = "route:fascia:${connection.id}|${path.id}|$index"
                anchors += ProjectionAnchor(id, point, occlusionTest = false)
                id
            }
            lines += FascialOverlayLine(ids, connection.color)
        }
        if (layer.fasciaTargets) connection.targets.forEach { target ->
            val id = "fascial-target:${target.id}"
            anchors += ProjectionAnchor(id, target.position, occlusionTest = false)
            targets += FascialOverlayTarget(id, target, connection.color)
        }
    }
    return FascialOverlayData(anchors, targets, lines)
}

@Composable
internal fun FasciaOverlay(projected: List<ProjectedAnchor>, data: FascialOverlayData, selected: String?,
    labels: Boolean, density: Float, labelExclusion: Rect?) {
    Canvas(Modifier.fillMaxSize()) {
        val byId = projected.associateBy { it.id }
        data.lines.forEach { line ->
            val path = Path(); var started = false
            line.anchorIds.forEach { id ->
                val anchor = byId[id]
                if (anchor == null || !anchor.visible) started = false
                else if (!started) { path.moveTo(anchor.xPx, anchor.yPx); started = true }
                else path.lineTo(anchor.xPx, anchor.yPx)
            }
            drawPath(path, Color(line.color).copy(alpha = .9f), style = Stroke(1.1f*density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7*density, 4*density))))
        }
        data.targets.forEach { item -> byId[item.anchorId]?.takeIf { it.visible }?.let { anchor ->
            val active = item.target.id == selected
            val r = (if (active) 6.5f else 4f)*density
            val diamond = Path().apply {
                moveTo(anchor.xPx, anchor.yPx-r); lineTo(anchor.xPx+r, anchor.yPx)
                lineTo(anchor.xPx, anchor.yPx+r); lineTo(anchor.xPx-r, anchor.yPx); close()
            }
            drawPath(diamond, Color(0xff0c151d))
            drawPath(diamond, Color(item.color), style = Stroke((if (active) 2f else 1.3f)*density))
            if (active) drawCircle(Color(item.color).copy(alpha = .16f), 13*density, Offset(anchor.xPx, anchor.yPx))
            if (active && labels) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11*density; color = item.color.toInt() }
                val x = (anchor.xPx+12*density).coerceIn(4*density, (size.width-p.measureText(item.target.nameZh)-4*density).coerceAtLeast(4*density))
                val y = (anchor.yPx-12*density).coerceIn(18*density, size.height-4*density)
                val box = Rect(x, y-16*density, x+p.measureText(item.target.nameZh), y+3*density)
                if (labelExclusion == null || !labelExclusion.overlaps(box)) drawContext.canvas.nativeCanvas.drawText(item.target.nameZh, x, y, p)
            }
        } }
    }
}
