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
import kotlin.math.ceil
import kotlin.math.sqrt

internal data class FascialOverlayTarget(val anchorId: String, val target: FascialTarget, val color: Long,
    val connectionId: String = "")
internal data class FascialOverlayLine(val anchorIds: List<String>, val color: Long,
    val connectionId: String = "", val kind: String = "unoriented_connection_schematic")
internal data class FascialOverlayData(val anchors: List<ProjectionAnchor>, val targets: List<FascialOverlayTarget>,
    val lines: List<FascialOverlayLine>, val targetConnectionIds: Map<String, String> = emptyMap())

/** Linear display sampling preserves authored corners and never invents a curved tissue path. */
internal fun sampleFascialRelation(points: List<Vec3>): List<Vec3> {
    if (points.size < 2) return points
    val sampled = mutableListOf<Vec3>()
    points.zipWithNext().forEach { (a, b) ->
        val dx = b.x-a.x; val dy = b.y-a.y; val dz = b.z-a.z
        val steps = ceil(sqrt(dx*dx+dy*dy+dz*dz) / .006f).toInt().coerceIn(1, 512)
        repeat(steps) { step ->
            val t = step.toFloat()/steps
            sampled += Vec3(a.x+dx*t, a.y+dy*t, a.z+dz*t)
        }
    }
    sampled += points.last()
    return sampled
}

/** Occluded samples break a relation; never bridge hidden stretches of a deep connection. */
internal fun visibleFascialSegments(ids: List<String>, projected: Map<String, ProjectedAnchor>): List<List<ProjectedAnchor>> {
    val segments = mutableListOf<List<ProjectedAnchor>>()
    var active = mutableListOf<ProjectedAnchor>()
    fun finish() { if (active.size > 1) segments += active; active = mutableListOf() }
    ids.forEach { id ->
        val anchor = projected[id]
        if (anchor == null || !anchor.visible || anchor.occluded) finish() else active += anchor
    }
    finish()
    return segments
}

internal fun buildFascialOverlay(connections: List<FascialConnection>, layer: LayerState): FascialOverlayData {
    if (!layer.fasciaChains) return FascialOverlayData(emptyList(), emptyList(), emptyList())
    val anchors = mutableListOf<ProjectionAnchor>()
    val targets = mutableListOf<FascialOverlayTarget>()
    val lines = mutableListOf<FascialOverlayLine>()
    // The existing occlusion mesh is the intact skin. It is valid only while that skin is
    // opaque and complete; using it after observation hides the exposed deep structures.
    val skinOcclusion = layer.skin && layer.cutawaySide == CutawaySide.NONE
    connections.filter { it.id in layer.enabledFascialConnections }.forEach { connection ->
        connection.paths.forEach { path ->
            // Authored polylines describe local relations or real tissue extents, never
            // skin routes, reconstructed fascia sheets or measured force vectors.
            val surfaceContext = path.kind == "anatomical_context_schematic"
            // Context samples come from real triangles; preserve them instead of generating
            // new supposed surface positions between samples. Only abstract local bridges
            // receive linear display samples for finer occlusion boundaries.
            val points = if (surfaceContext) path.points else sampleFascialRelation(path.points)
            val ids = points.mapIndexed { index, point ->
                // A separate namespace bypasses skin-route conformance: these points retain
                // their authored anatomical depth, including when the skin is hidden.
                val id = "fascia-route:${connection.id}|${path.id}|$index"
                val normal = if (surfaceContext && path.normals.size == path.points.size) path.normals[index] else null
                anchors += ProjectionAnchor(id, point, normal, occlusionTest = skinOcclusion)
                id
            }
            lines += FascialOverlayLine(ids, connection.color, connection.id, path.kind)
        }
        if (layer.fasciaTargets) connection.targets.forEach { target ->
            val id = "fascial-target:${target.id}"
            anchors += ProjectionAnchor(id, target.position, occlusionTest = skinOcclusion)
            targets += FascialOverlayTarget(id, target, connection.color, connection.id)
        }
    }
    return FascialOverlayData(anchors, targets, lines, connections.asSequence()
        .filter { it.id in layer.enabledFascialConnections }
        .flatMap { group -> group.targets.asSequence().map { it.id to group.id } }.toMap())
}

@Composable
internal fun FasciaOverlay(projected: List<ProjectedAnchor>, data: FascialOverlayData, selected: String?,
    labels: Boolean, density: Float, labelExclusion: Rect?) {
    Canvas(Modifier.fillMaxSize()) {
        val byId = projected.associateBy { it.id }
        val activeConnection = selected?.let { data.targetConnectionIds[it] }
        data.lines.sortedBy { it.connectionId == activeConnection }.forEach { line ->
            val path = Path()
            visibleFascialSegments(line.anchorIds, byId).forEach { segment ->
                path.moveTo(segment.first().xPx, segment.first().yPx)
                segment.drop(1).forEach { path.lineTo(it.xPx, it.yPx) }
            }
            val active = line.connectionId == activeConnection
            val context = line.kind == "anatomical_context_schematic"
            val dash = if (context) null else PathEffect.dashPathEffect(floatArrayOf(4*density, 3*density))
            drawPath(path, Color(0xff0c151d).copy(alpha = .7f), style = Stroke((if (active) 2.8f else 2.1f)*density, pathEffect = dash))
            drawPath(path, Color(line.color).copy(alpha = if (active) 1f else if (context) .7f else .88f),
                style = Stroke((if (active) 1.7f else 1.1f)*density, pathEffect = dash))
        }
        data.targets.sortedBy { it.target.id == selected }.forEach { item -> byId[item.anchorId]?.takeIf { it.visible && !it.occluded }?.let { anchor ->
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
                val textWidth = p.measureText(item.target.nameZh)
                val x = (anchor.xPx+12*density).coerceIn(8*density, (size.width-textWidth-8*density).coerceAtLeast(8*density))
                val y = (anchor.yPx-12*density).coerceIn(18*density, (size.height-8*density).coerceAtLeast(18*density))
                val box = Rect(x-4*density, y-15*density, x+textWidth+4*density, y+5*density)
                if (labelExclusion == null || !labelExclusion.overlaps(box)) {
                    drawRoundRect(Color(0xec14222d), Offset(box.left, box.top), box.size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4*density))
                    drawContext.canvas.nativeCanvas.drawText(item.target.nameZh, x, y, p)
                }
            }
        } }
        if (data.lines.isNotEmpty()) {
            val text = "实线 · 组织范围参照   虚线 · 局部连接"
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9*density; color = 0xffb4c9d4.toInt() }
            val y = size.height-110*density
            val box = Rect(14*density, y-13*density, 24*density+paint.measureText(text), y+5*density)
            if (y > 140*density && box.right <= size.width-8*density &&
                (labelExclusion == null || !labelExclusion.overlaps(box))) {
                drawRoundRect(Color(0xdc14222d), Offset(box.left, box.top), box.size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4*density))
                drawContext.canvas.nativeCanvas.drawText(text, 19*density, y, paint)
            }
        }
    }
}
