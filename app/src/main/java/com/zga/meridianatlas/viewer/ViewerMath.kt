package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.ProjectedAnchor
import com.zga.meridianatlas.model.ProjectionAnchor
import com.zga.meridianatlas.model.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.atan
import kotlin.math.sqrt

/** Camera and annotation math uses the model's original metre / Y-up coordinate system. */
internal data class OrbitPose(
    val target: Vec3 = Vec3(0f, 0.9f, 0f),
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val distance: Float = 3f,
) {
    fun eye(): Vec3 = Vec3(
        target.x + sin(yaw) * cos(pitch) * distance,
        target.y + sin(pitch) * distance,
        target.z + cos(yaw) * cos(pitch) * distance,
    )

    fun pan(dx: Float, dy: Float, viewportHeight: Int): OrbitPose {
        val unitsPerPixel = 2f * distance * tan(Math.toRadians(22.5).toFloat()) /
            viewportHeight.coerceAtLeast(1)
        val rightX = cos(yaw)
        val rightZ = -sin(yaw)
        val upX = -sin(yaw) * sin(pitch)
        val upY = cos(pitch)
        val upZ = -cos(yaw) * sin(pitch)
        return copy(target = Vec3(
            target.x + (-dx * rightX + dy * upX) * unitsPerPixel,
            target.y + dy * upY * unitsPerPixel,
            target.z + (-dx * rightZ + dy * upZ) * unitsPerPixel,
        ))
    }
}

internal object AnchorProjector {
    /** Matrices returned by Filament are column-major. Screen coordinates are physical pixels. */
    fun project(
        anchors: List<ProjectionAnchor>, projection: DoubleArray, view: DoubleArray,
        eye: Vec3, width: Int, height: Int,
        occluder: SkinOccluder? = null,
    ): List<ProjectedAnchor> = anchors.map { anchor ->
        val p = anchor.position
        val vx = view[0] * p.x + view[4] * p.y + view[8] * p.z + view[12]
        val vy = view[1] * p.x + view[5] * p.y + view[9] * p.z + view[13]
        val vz = view[2] * p.x + view[6] * p.y + view[10] * p.z + view[14]
        val vw = view[3] * p.x + view[7] * p.y + view[11] * p.z + view[15]
        val cx = projection[0] * vx + projection[4] * vy + projection[8] * vz + projection[12] * vw
        val cy = projection[1] * vx + projection[5] * vy + projection[9] * vz + projection[13] * vw
        val cw = projection[3] * vx + projection[7] * vy + projection[11] * vz + projection[15] * vw
        val nx = if (cw > 0.000001) (cx / cw).toFloat() else 0f
        val ny = if (cw > 0.000001) (cy / cw).toFloat() else 0f
        val front = anchor.normal?.let {
            it.x * (eye.x - p.x) + it.y * (eye.y - p.y) + it.z * (eye.z - p.z) >= 0f
        } ?: true
        // A route segment may cross the viewport while either endpoint is offscreen.
        // Keep valid projected route endpoints and let Canvas clip the segment at its edge.
        val inViewport = nx in -1f..1f && ny in -1f..1f
        val visible = cw > 0.000001 && -vz >= 0.01 &&
            (inViewport || anchor.id.startsWith("route:") || anchor.id.startsWith("fascia-route:"))
        ProjectedAnchor(
            id = anchor.id,
            xPx = (nx + 1f) * width / 2f,
            yPx = (1f - ny) * height / 2f,
            depth = (-vz).toFloat(),
            frontFacing = front,
            visible = visible,
            occluded = visible && anchor.occlusionTest && occluder?.isOccluded(eye, p) == true,
        )
    }
}

/** Frame a whole observed region within the narrower viewport field of view. */
internal fun regionFocusDistance(min: Vec3, max: Vec3, aspectRatio: Float): Float {
    val dx = max.x - min.x; val dy = max.y - min.y; val dz = max.z - min.z
    val radius = sqrt(dx * dx + dy * dy + dz * dz) / 2f
    val halfFov = atan(tan(Math.PI.toFloat() / 8f) * aspectRatio.coerceIn(.2f, 1f))
    return (radius / sin(halfFov) * 1.15f).coerceAtLeast(.35f)
}
