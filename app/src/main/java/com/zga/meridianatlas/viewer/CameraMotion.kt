package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.Vec3
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/** A sampled orbit transition. Timing, cancellation and animation settings belong to the caller. */
internal data class CameraMotion(val from: OrbitPose, val to: OrbitPose) {
    init {
        require(from.isFinite() && to.isFinite()) { "Camera poses must have finite coordinates and angles" }
        require(from.distance > 0f && to.distance > 0f) { "Camera distances must be positive" }
    }

    private val yawDelta = shortestAngle(to.yaw.toDouble() - from.yaw.toDouble())
    private val fromLogDistance = ln(from.distance.toDouble())
    private val toLogDistance = ln(to.distance.toDouble())

    /** Accepts raw progress; ease once here, including when redirecting from a sampled current pose. */
    fun poseAt(fraction: Float): OrbitPose {
        require(!fraction.isNaN()) { "Camera progress must not be NaN" }
        // Keep the exact endpoint representation, including yaw's equivalent +/- 2 PI forms.
        if (fraction <= 0f) return from
        if (fraction >= 1f) return to
        val t = fraction.toDouble()
        val eased = (t * t * t * (t * (t * 6.0 - 15.0) + 10.0)).coerceIn(0.0, 1.0)
        return OrbitPose(
            target = Vec3(
                lerp(from.target.x, to.target.x, eased),
                lerp(from.target.y, to.target.y, eased),
                lerp(from.target.z, to.target.z, eased),
            ),
            yaw = (from.yaw.toDouble() + yawDelta * eased).toFloat(),
            // Interpolate orbit angles rather than eye vectors, so opposite views retain their radius.
            pitch = lerp(from.pitch, to.pitch, eased),
            distance = exp(fromLogDistance + (toLogDistance - fromLogDistance) * eased).toFloat(),
        )
    }

    private fun OrbitPose.isFinite(): Boolean = target.x.isFinite() && target.y.isFinite() &&
        target.z.isFinite() && yaw.isFinite() && pitch.isFinite() && distance.isFinite()

    private fun lerp(a: Float, b: Float, t: Double): Float =
        (a.toDouble() + (b.toDouble() - a.toDouble()) * t).toFloat()

    private fun shortestAngle(angle: Double): Double {
        val turn = 2.0 * PI
        val remainder = angle % turn
        return when {
            remainder > PI -> remainder - turn
            remainder < -PI -> remainder + turn
            else -> remainder
        }
    }
}
