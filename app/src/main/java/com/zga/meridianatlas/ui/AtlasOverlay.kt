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
import com.zga.meridianatlas.viewer.RouteCurveSampler
import kotlin.math.max

data class OverlayPoint(val anchorId: String, val pointId: String, val name: String, val color: Long,
    val meridianId: String? = null)
data class OverlayLine(val anchorIds: List<String>, val color: Long, val internal: Boolean,
    val meridianId: String? = null)
data class OverlayLabel(val anchorId: String, val name: String)
data class OverlayData(val anchors: List<ProjectionAnchor>, val points: List<OverlayPoint>, val lines: List<OverlayLine>,
    val structureLabel: OverlayLabel? = null, val selectedMeridianId: String? = null,
    val emphasizedMeridianIds: Set<String> = setOfNotNull(selectedMeridianId))

fun buildOverlay(data: AtlasData, enabledMeridians: Set<String>, showPoints: Boolean,
    showLines: Boolean, selectedPoint: String?, selectedStructure: AnatomyStructure? = null,
    selectedMeridianId: String? = null, showInternalRelations: Boolean = false,
    includeHitTargets: Boolean = false, additionalEmphasizedMeridian: String? = null): OverlayData {
    val anchors = mutableListOf<ProjectionAnchor>()
    val points = mutableListOf<OverlayPoint>()
    val lines = mutableListOf<OverlayLine>()
    val meridians = data.meridians.associateBy { it.id }
    val canonicalPoints = data.points.distinctBy { it.id }
    val focusedMeridian = canonicalPoints.firstOrNull { it.id == selectedPoint }?.meridianId ?: selectedMeridianId
    val associated = data.meridians.filter { it.id in enabledMeridians }.flatMap { it.pointIds }.toSet()
    if (showPoints) canonicalPoints.filter { it.meridianId in enabledMeridians || it.id in associated ||
            (it.kind == "extra" && "__extra__" in enabledMeridians) || it.id == selectedPoint }.forEach { point ->
        point.instances.forEachIndexed { index, instance -> instance.position?.let { position ->
            val id = "point:${point.id}|$index"
            anchors += ProjectionAnchor(id, position, instance.normal, instance.viewId !in setOf("oral", "nasal"))
            points += OverlayPoint(id, point.id, point.nameZh, meridians[point.meridianId]?.color ?: 0xffeac27bL,
                point.meridianId)
        } }
    }
    if (includeHitTargets) canonicalPoints.forEach { point ->
        point.instances.forEachIndexed { index, instance -> instance.position?.let { position ->
            // Hit-only targets make an unshown route's points discoverable without adding dots.
            anchors += ProjectionAnchor("hitpoint:${point.id}|$index", position, instance.normal,
                instance.viewId !in setOf("oral", "nasal"))
        } }
    }
    if (showLines) data.meridians.filter { it.id in enabledMeridians }.forEach { meridian ->
        meridian.paths.forEachIndexed { pathIndex, route ->
            if (route.internal && !showInternalRelations) return@forEachIndexed
            val samples = RouteCurveSampler.sample(route.points)
            val ids = samples.mapIndexed { index, position ->
                val id = "route:${meridian.id}|$pathIndex|$index"
                // Surface samples are conformed to the real skin on the projection worker.
                // A whole-body radial normal is incorrect on arms, legs and concave surfaces.
                anchors += ProjectionAnchor(id, position, normal = null, occlusionTest = !route.internal)
                id
            }
            lines += OverlayLine(ids, meridian.color, route.internal, meridian.id)
        }
    }
    val structureLabel = selectedStructure?.let {
        val id = "structure:${it.id}"
        anchors += ProjectionAnchor(id, it.center, occlusionTest = false)
        OverlayLabel(id, it.nameZh)
    }
    return OverlayData(anchors, points, lines, structureLabel, focusedMeridian,
        setOfNotNull(focusedMeridian, additionalEmphasizedMeridian))
}

/** Names follow an explicit point selection, independently of which routes are enabled. */
internal fun acupointLabelIds(points: List<OverlayPoint>, selectedPoint: String?): Set<String> {
    val selected = points.firstOrNull { it.pointId == selectedPoint } ?: return emptySet()
    return points.asSequence().filter {
        if (selected.meridianId != null) it.meridianId == selected.meridianId else it.pointId == selected.pointId
    }.map { it.pointId }.toSet()
}

@Composable
fun AtlasOverlay(projected: List<ProjectedAnchor>, data: OverlayData, showLabels: Boolean,
    selectedPoint: String?, referenceSkin: Boolean, density: Float, labelExclusion: Rect? = null) {
    Canvas(Modifier.fillMaxSize()) {
        val positions = projected.associateBy { it.id }
        // Paint the active route last so its finer contrasting stroke survives crossings.
        data.lines.sortedBy { if (it.meridianId == data.selectedMeridianId) 2 else if (it.meridianId in data.emphasizedMeridianIds) 1 else 0 }.forEach { line ->
            val path = Path()
            var started = false
            line.anchorIds.forEach { id ->
                val anchor = positions[id]
                // Surface visibility comes from the real skin depth, not a radial normal.
                // Offscreen route endpoints remain projected; Canvas clips crossing segments.
                if (anchor == null || !anchor.visible || anchor.occluded) started = false
                else if (!started) { path.moveTo(anchor.xPx, anchor.yPx); started = true }
                else path.lineTo(anchor.xPx, anchor.yPx)
            }
            val emphasized = line.meridianId in data.emphasizedMeridianIds
            val dash = if (line.internal) PathEffect.dashPathEffect(floatArrayOf(5*density, 4*density)) else null
            drawPath(path, Color(0xff0c151d).copy(alpha = .72f),
                style = Stroke(width = (if (emphasized) 2.6f else 1.8f)*density, pathEffect = dash))
            drawPath(path, Color(line.color).copy(alpha = when {
                emphasized -> if (line.internal) .78f else 1f
                line.internal -> .38f
                else -> .68f
            }), style = Stroke(width = (if (emphasized) 1.6f else 1f)*density, pathEffect = dash))
        }
        val visible = data.points.mapNotNull { point -> positions[point.anchorId]?.takeIf { it.visible && it.frontFacing && !it.occluded }?.let { point to it } }
            .sortedBy { if (it.first.pointId == selectedPoint) 0 else 1 }
        visible.asReversed().forEach { (point, anchor) ->
            val selected = point.pointId == selectedPoint
            val related = point.meridianId in data.emphasizedMeridianIds
            val center = Offset(anchor.xPx, anchor.yPx)
            if (selected) drawCircle(Color(point.color).copy(alpha = .15f), 12*density, center)
            drawCircle(Color(0xff0c151d), (if (selected) 5f else if (related) 3.8f else 3.2f)*density, center)
            drawCircle(Color(point.color), (if (selected) 3.5f else if (related) 2.5f else 2.0f)*density, center)
            if (selected) drawCircle(Color(point.color), 8*density, center, style = Stroke(density))
        }
        if (showLabels) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11*density; color = 0xffd9ede8.toInt() }
            val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xde14222d.toInt() }
            val occupied = mutableListOf<android.graphics.RectF>()
            fun excluded(box: android.graphics.RectF): Boolean = labelExclusion?.let {
                box.left < it.right && box.right > it.left && box.top < it.bottom && box.bottom > it.top
            } == true
            data.structureLabel?.let { label -> positions[label.anchorId]?.takeIf { it.visible }?.let { anchor ->
                val width = textPaint.measureText(label.name) + 16*density
                val x = (anchor.xPx+10*density).coerceIn(4*density, max(4*density, size.width-width-4*density))
                val y = (anchor.yPx-24*density).coerceIn(4*density, max(4*density, size.height-25*density))
                val box = android.graphics.RectF(x, y, x+width, y+23*density)
                if (!excluded(box)) {
                    drawContext.canvas.nativeCanvas.drawRoundRect(box, 5*density, 5*density, backgroundPaint)
                    textPaint.color = 0xff63dbc1.toInt()
                    drawContext.canvas.nativeCanvas.drawText(label.name, x+8*density, y+16*density, textPaint)
                    occupied += box
                }
            } }
            val labelIds = acupointLabelIds(data.points, selectedPoint)
            var selectedNameDrawn = false
            visible.asSequence().filter { it.first.pointId in labelIds }.take(100).forEach { (point, anchor) ->
                val selected = point.pointId == selectedPoint
                val width = textPaint.measureText(point.name) + 12*density
                val x = (anchor.xPx+9*density).coerceIn(4*density, max(4*density, size.width-width-4*density))
                val y = (anchor.yPx-19*density).coerceIn(4*density, max(4*density, size.height-25*density))
                val box = android.graphics.RectF(x, y, x+width, y+21*density)
                if (!excluded(box) && ((selected && !selectedNameDrawn) || occupied.none { android.graphics.RectF.intersects(it, box) }) && occupied.size < 28) {
                    drawContext.canvas.nativeCanvas.drawRoundRect(box, 5*density, 5*density, backgroundPaint)
                    textPaint.color = if (selected) point.color.toInt() else 0xffd9ede8.toInt()
                    drawContext.canvas.nativeCanvas.drawText(point.name, x+6*density, y+14.5f*density, textPaint)
                    occupied += box
                    if (selected) selectedNameDrawn = true
                }
            }
        }
        if (referenceSkin) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9*density; color = 0xff8da4b2.toInt() }
            drawContext.canvas.nativeCanvas.drawText("体表参考壳 · 标记保持原位", 18*density, size.height-65*density, paint)
        }
        if (data.lines.any { it.internal }) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9*density; color = 0xff8da4b2.toInt() }
            drawContext.canvas.nativeCanvas.drawText("虚线 · 脏腑关系示意", 18*density,
                size.height-(if (referenceSkin) 83f else 65f)*density, paint)
        }
    }
}
