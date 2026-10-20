package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.ProjectionAnchor
import com.zga.meridianatlas.model.Vec3
import kotlin.math.ceil
import kotlin.math.sqrt

/** Display samples only: source routes, branch boundaries and acupoint anchors stay unchanged. */
internal object RouteCurveSampler {
    private const val SPACING_METRES = .006f
    private const val MAX_STEPS_PER_SEGMENT = 128

    fun sample(points: List<Vec3>): List<Vec3> {
        if (points.size < 2) return points
        val result = ArrayList<Vec3>()
        for (index in 0 until points.lastIndex) {
            val a = points[maxOf(0, index - 1)]
            val b = points[index]
            val c = points[index + 1]
            val d = points[minOf(points.lastIndex, index + 2)]
            // The control-polygon estimate also samples a short chord with long tangents.
            val estimate = maxOf(distance(b, c), (distance(a, b) + distance(b, c) + distance(c, d)) * .5f)
            val steps = ceil(estimate / SPACING_METRES).toInt().coerceIn(1, MAX_STEPS_PER_SEGMENT)
            repeat(steps) { step ->
                val t = step.toFloat() / steps
                fun value(p: Float, q: Float, r: Float, s: Float): Float = .5f *
                    ((2*q) + (-p+r)*t + (2*p-5*q+4*r-s)*t*t + (-p+3*q-3*r+s)*t*t*t)
                result += Vec3(value(a.x,b.x,c.x,d.x), value(a.y,b.y,c.y,d.y), value(a.z,b.z,c.z,d.z))
            }
        }
        result += points.last()
        return result
    }

    private fun distance(a: Vec3, b: Vec3): Float = sqrt(
        (a.x-b.x)*(a.x-b.x) + (a.y-b.y)*(a.y-b.y) + (a.z-b.z)*(a.z-b.z))
}

/** Owned by the single projection worker. Surface searches happen once per anchor-list revision. */
internal class SurfaceRouteProjector(private val skin: SkinOccluder) {
    private var source: List<ProjectionAnchor>? = null
    private var prepared = emptyList<ProjectionAnchor>()

    fun conform(anchors: List<ProjectionAnchor>): List<ProjectionAnchor> {
        if (source === anchors) return prepared
        prepared = anchors.map { anchor ->
            if (!anchor.id.startsWith("route:") || !anchor.occlusionTest) anchor
            else skin.closestSurface(anchor.position)?.let { surface ->
                // Skin has inner and outer layers. Do not infer outward orientation from its
                // winding; two-sided ray depth decides visibility, including the far limb.
                anchor.copy(position = surface, normal = null)
            } ?: anchor.copy(normal = null)
        }
        source = anchors
        return prepared
    }
}
