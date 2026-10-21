package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class OrbitLightingTest {
    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
    private fun length(v: Vec3) = sqrt(dot(v, v))
    private fun unit(v: Vec3): Vec3 = length(v).let { Vec3(v.x / it, v.y / it, v.z / it) }
    private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    private fun assertVector(expected: Vec3, actual: Vec3, tolerance: Float = .000002f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test fun frontBackAndSideViewsRotateTheSameRigInsteadOfExchangingUnequalWorldLights() {
        val front = OrbitLighting.directions(OrbitPose())
        for (yaw in listOf(PI.toFloat() / 2f, PI.toFloat(), -PI.toFloat() / 2f, 2f * PI.toFloat())) {
            val actual = OrbitLighting.directions(OrbitPose(yaw = yaw))
            front.zip(actual).forEach { (a, b) ->
                assertVector(Vec3(a.x * cos(yaw) + a.z * sin(yaw), a.y,
                    -a.x * sin(yaw) + a.z * cos(yaw)), b)
            }
        }
        assertTrue(front[0].z < 0f && front[1].z < 0f && front[2].z > 0f)
    }

    @Test fun everyOrbitIncludingBothPolesHasAFiniteOrthonormalBasisAndUnitLightRays() {
        for (yawStep in -12..12) for (pitchStep in -12..12) {
            val pose = OrbitPose(yaw = yawStep * PI.toFloat() / 6f, pitch = pitchStep * PI.toFloat() / 24f)
            val basis = OrbitLighting.basis(pose)
            for (v in listOf(basis.right, basis.up, basis.towardViewer) + OrbitLighting.directions(pose)) {
                assertTrue(v.x.isFinite() && v.y.isFinite() && v.z.isFinite())
                assertEquals(1f, length(v), .000002f)
            }
            assertEquals(0f, dot(basis.right, basis.up), .000002f)
            assertEquals(0f, dot(basis.up, basis.towardViewer), .000002f)
            assertEquals(0f, dot(basis.towardViewer, basis.right), .000002f)
        }
    }

    @Test fun poleApproachAndYawWrapDoNotFlipTheLightingOrRequireAWorldUpCrossProduct() {
        for (sign in listOf(-1f, 1f)) {
            val pole = sign * PI.toFloat() / 2f
            val near = OrbitLighting.directions(OrbitPose(yaw = .7f, pitch = pole - sign * .00001f))
            val at = OrbitLighting.directions(OrbitPose(yaw = .7f, pitch = pole))
            near.zip(at).forEach { (a, b) -> assertVector(a, b, .00002f) }
        }
        val before = OrbitLighting.directions(OrbitPose(yaw = PI.toFloat() - .00001f, pitch = .4f))
        val after = OrbitLighting.directions(OrbitPose(yaw = -PI.toFloat() + .00001f, pitch = .4f))
        before.zip(after).forEach { (a, b) -> assertVector(a, b, .00003f) }
    }

    @Test fun basisMatchesTheViewersActualLookAtUpHintOnBothSidesOfItsPoleThreshold() {
        for (yaw in listOf(-2f, 0f, 1.4f)) for (pitch in listOf(-PI.toFloat() / 2f,
            -1.56f, -1.55f, -.4f, 0f, .4f, 1.55f, 1.56f, PI.toFloat() / 2f)) {
            val pose = OrbitPose(Vec3(.04f, 1.2f, .07f), yaw, pitch, .8f)
            val eye = pose.eye()
            val back = unit(Vec3(eye.x - pose.target.x, eye.y - pose.target.y, eye.z - pose.target.z))
            val upHint = if (abs(cos(pitch)) < .02f) Vec3(-sin(yaw) * sin(pitch), 0f, -cos(yaw) * sin(pitch))
                else Vec3(0f, 1f, 0f)
            val right = unit(cross(upHint, back))
            val up = unit(cross(back, right))
            val actual = OrbitLighting.basis(pose)
            assertVector(right, actual.right, .000006f)
            assertVector(up, actual.up, .000006f)
            assertVector(back, actual.towardViewer, .000006f)
        }
    }

    @Test fun everyViewRetainsTheSameCameraRelativeLightAnglesAndDiffuseBalance() {
        fun received(pose: OrbitPose): Float {
            val basis = OrbitLighting.basis(pose)
            val localNormal = unit(Vec3(.22f, -.13f, 1f))
            val worldNormal = Vec3(
                basis.right.x * localNormal.x + basis.up.x * localNormal.y + basis.towardViewer.x * localNormal.z,
                basis.right.y * localNormal.x + basis.up.y * localNormal.y + basis.towardViewer.y * localNormal.z,
                basis.right.z * localNormal.x + basis.up.z * localNormal.y + basis.towardViewer.z * localNormal.z)
            return OrbitLighting.directions(pose).mapIndexed { index, ray ->
                val local = unit(OrbitLighting.lights[index].cameraDirection)
                assertEquals(local.x, dot(ray, basis.right), .000002f)
                assertEquals(local.y, dot(ray, basis.up), .000002f)
                assertEquals(local.z, dot(ray, basis.towardViewer), .000002f)
                max(0f, -dot(worldNormal, ray)) * OrbitLighting.lights[index].intensity
            }.sum()
        }
        val expected = received(OrbitPose())
        for (yaw in listOf(-3f, -1f, 0f, 1f, 3f)) for (pitch in listOf(-PI.toFloat() / 2f, -.8f, 0f, .8f, PI.toFloat() / 2f)) {
            assertEquals(expected, received(OrbitPose(yaw = yaw, pitch = pitch)), .03f)
        }
    }

    @Test fun translationAndZoomLeaveDirectionsUnchangedAndAllLightsRemainNeutralWithOneCaster() {
        val a = OrbitPose(yaw = .9f, pitch = -.5f)
        assertEquals(OrbitLighting.directions(a), OrbitLighting.directions(a.copy(
            target = Vec3(100_000f, -10_000f, 3f), distance = .00001f)))
        assertEquals(Vec3(1f, 1f, 1f), OrbitLighting.color)
        assertEquals(OrbitLighting.ambientIrradiance.x, OrbitLighting.ambientIrradiance.y, 0f)
        assertEquals(OrbitLighting.ambientIrradiance.y, OrbitLighting.ambientIrradiance.z, 0f)
        assertEquals(1, OrbitLighting.lights.count { it.shadows })
        assertTrue(OrbitLighting.lights.all { it.intensity > 0f && it.intensity.isFinite() })
        assertTrue(OrbitLighting.ambientIntensity > 0f)
        assertTrue(OrbitLighting.lights[1].intensity / OrbitLighting.lights[0].intensity > .45f)
        // The light rig is spread around the viewing direction, rather than one coincident headlight.
        assertTrue(abs(OrbitLighting.lights[0].cameraDirection.x) > .4f)
        assertTrue(abs(OrbitLighting.lights[1].cameraDirection.x) > .4f)
    }

    @Test fun undefinedAnglesFailBeforeNativeLightDirectionsAreUpdated() {
        for (pose in listOf(OrbitPose(yaw = Float.NaN), OrbitPose(yaw = Float.POSITIVE_INFINITY),
            OrbitPose(pitch = Float.NaN), OrbitPose(pitch = Float.NEGATIVE_INFINITY))) {
            try {
                OrbitLighting.directions(pose)
                fail("Undefined angles must not enter the native light manager")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
}
