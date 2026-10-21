package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Neutral, camera-relative atlas illumination. Values are display policy, not tissue optics. */
internal object OrbitLighting {
    internal data class Light(val cameraDirection: Vec3, val intensity: Float, val shadows: Boolean = false)
    internal data class Basis(val right: Vec3, val up: Vec3, val towardViewer: Vec3)

    val color = Vec3(1f, 1f, 1f)
    val ambientIrradiance = Vec3(.58f, .58f, .58f)
    const val ambientIntensity = 16_000f

    // Broad complementary directions reduce contrast without replacing shape with a headlight.
    // The weaker rear rim remains camera-relative too; all three use the same neutral color.
    val lights = listOf(
        Light(Vec3(-.55f, -.60f, -1f), 55_000f, shadows = true),
        Light(Vec3(.70f, -.10f, -1f), 28_000f),
        Light(Vec3(.20f, -.40f, 1f), 12_000f),
    )

    fun basis(pose: OrbitPose): Basis {
        require(pose.yaw.isFinite() && pose.pitch.isFinite())
        // This is the analytical eye-target basis for OrbitPose, avoiding cancellation when
        // target is far from the origin or distance is small. It is also the orthonormalized
        // lookAt basis with the viewer's screen-up fallback at both vertical poles.
        val sy = sin(pose.yaw); val cy = cos(pose.yaw)
        val sp = sin(pose.pitch); val cp = cos(pose.pitch)
        val right = Vec3(cy, 0f, -sy).unit()
        val back = Vec3(sy * cp, sp, cy * cp).unit()
        val up = Vec3(-sy * sp, cp, -cy * sp).unit()
        return Basis(right, up, back)
    }

    /** Filament directions point along the incoming light rays, toward the model. */
    fun directions(pose: OrbitPose): List<Vec3> {
        val basis = basis(pose)
        return lights.map { light ->
            val local = light.cameraDirection
            Vec3(
                basis.right.x * local.x + basis.up.x * local.y + basis.towardViewer.x * local.z,
                basis.right.y * local.x + basis.up.y * local.y + basis.towardViewer.y * local.z,
                basis.right.z * local.x + basis.up.z * local.y + basis.towardViewer.z * local.z,
            ).unit()
        }
    }

    private fun Vec3.unit(): Vec3 {
        val length = sqrt(x * x + y * y + z * z)
        require(length.isFinite() && length > 0f)
        return Vec3(x / length, y / length, z / length)
    }
}
