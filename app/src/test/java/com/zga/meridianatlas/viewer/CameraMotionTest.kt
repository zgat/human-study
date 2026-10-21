package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class CameraMotionTest {
    private fun radians(degrees: Float) = (degrees.toDouble() * PI / 180.0).toFloat()

    @Test fun exactEndpointsAndOutOfRangeProgressReturnTheOriginalPoses() {
        val from = OrbitPose(Vec3(-1f, .5f, 2f), radians(179f), -.2f, 4f)
        val to = OrbitPose(Vec3(.4f, 1.2f, -.3f), radians(-179f), .4f, .25f)
        val motion = CameraMotion(from, to)
        for (fraction in listOf(Float.NEGATIVE_INFINITY, -5f, 0f)) {
            assertSame(from, motion.poseAt(fraction))
        }
        for (fraction in listOf(1f, 5f, Float.POSITIVE_INFINITY)) {
            assertSame(to, motion.poseAt(fraction))
        }
    }

    @Test fun yawCrossesTheSignedPiBoundaryAlongTheShortArcInEitherDirection() {
        for ((start, end) in listOf(179f to -179f, -179f to 179f)) {
            val motion = CameraMotion(OrbitPose(yaw = radians(start)), OrbitPose(yaw = radians(end)))
            val middle = motion.poseAt(.5f)
            assertEquals(PI.toFloat(), abs(middle.yaw), .000001f)
            assertTrue("The camera must remain behind the target while crossing +/- PI", middle.eye().z < -2.99f)
            var previous = motion.poseAt(0f)
            for (step in 1..100) {
                val current = motion.poseAt(step / 100f)
                // Compare directions, allowing the exact final yaw to use the other +/- PI representation.
                assertTrue(abs(sin(current.yaw) - sin(previous.yaw)) < .002f)
                assertTrue(abs(cos(current.yaw) - cos(previous.yaw)) < .002f)
                previous = current
            }
        }
    }

    @Test fun halfProgressUsesLinearTargetAndPitchAndGeometricMeanDistance() {
        val from = OrbitPose(Vec3(-2f, 1f, 4f), -.4f, -.3f, .25f)
        val to = OrbitPose(Vec3(6f, 3f, -2f), .6f, .5f, 4f)
        val middle = CameraMotion(from, to).poseAt(.5f)
        assertEquals(Vec3(2f, 2f, 1f), middle.target)
        assertEquals(.1f, middle.yaw, .000001f)
        assertEquals(.1f, middle.pitch, .000001f)
        assertEquals(1f, middle.distance, .000001f)
        assertTrue("Zoom must use a distance ratio, not the arithmetic mean", middle.distance < 2f)
    }

    @Test fun easingSlowsBothEndsWhileTheTransitionRemainsMonotoneWithoutOvershoot() {
        val motion = CameraMotion(OrbitPose(target = Vec3(0f, 0f, 0f), pitch = -.5f, distance = .1f),
            OrbitPose(target = Vec3(1f, 2f, 3f), pitch = .5f, distance = 10f))
        assertTrue(motion.poseAt(.1f).target.x < .1f)
        assertTrue(motion.poseAt(.9f).target.x > .9f)
        assertTrue(motion.poseAt(.01f).target.x < .0001f)
        assertTrue(1f - motion.poseAt(.99f).target.x < .0001f)
        var previous = motion.from
        for (step in 1..1000) {
            val pose = motion.poseAt(step / 1000f)
            assertTrue(pose.target.x in previous.target.x..1f)
            assertTrue(pose.pitch in previous.pitch..0.5f)
            assertTrue(pose.distance in previous.distance..10f)
            previous = pose
        }
    }

    @Test fun oppositeViewsOfTheSameTargetOrbitAroundItWithoutCrossingTheCenterOrPoles() {
        val target = Vec3(.2f, 1.1f, -.1f)
        val motion = CameraMotion(OrbitPose(target, 0f, .25f, 2f),
            OrbitPose(target, PI.toFloat(), .25f, 2f))
        for (step in 0..100) {
            val pose = motion.poseAt(step / 100f)
            val eye = pose.eye()
            val dx = eye.x - target.x; val dy = eye.y - target.y; val dz = eye.z - target.z
            assertEquals(target, pose.target)
            assertEquals(.25f, pose.pitch, 0f)
            assertEquals(2f, sqrt(dx * dx + dy * dy + dz * dz), .000001f)
            assertTrue("Opposite views must not take an eye-vector shortcut through a pole", sqrt(dx * dx + dz * dz) > 1.9f)
        }
    }

    @Test fun redirectingFromTheDisplayedPoseIsContinuousAndDoesNotMutateEitherMotion() {
        val from = OrbitPose(Vec3(-.2f, 1f, 0f), radians(170f), -.2f, 3f)
        val firstDestination = OrbitPose(Vec3(.3f, .4f, .2f), radians(-170f), .4f, .2f)
        val secondDestination = OrbitPose(Vec3(-.1f, 1.6f, -.2f), radians(130f), .1f, .4f)
        val first = CameraMotion(from, firstDestination)
        val displayed = first.poseAt(.43f)
        val redirected = CameraMotion(displayed, secondDestination)
        assertSame(displayed, redirected.poseAt(0f))
        assertSame(secondDestination, redirected.poseAt(1f))
        val firstRedirectedFrame = redirected.poseAt(.001f)
        assertEquals(displayed.target.x, firstRedirectedFrame.target.x, .000001f)
        assertEquals(displayed.yaw, firstRedirectedFrame.yaw, .000001f)
        assertEquals(displayed.distance, firstRedirectedFrame.distance, .000001f)
        assertSame(from, first.from)
        assertSame(firstDestination, first.to)
        assertEquals(displayed, first.poseAt(.43f))
    }

    @Test fun everyFinitePositiveDistanceIncludingExtremeRatiosRemainsFiniteAndPositive() {
        for ((start, end) in listOf(Float.MIN_VALUE to Float.MAX_VALUE, Float.MAX_VALUE to Float.MIN_VALUE,
            .08f to 100f, 100f to .08f)) {
            val motion = CameraMotion(OrbitPose(distance = start), OrbitPose(distance = end))
            for (step in 0..1000) {
                val distance = motion.poseAt(step / 1000f).distance
                assertTrue(distance.isFinite() && distance > 0f)
                assertTrue(distance in minOf(start, end)..maxOf(start, end))
            }
        }
    }

    @Test fun invalidPosesAndUndefinedProgressAreRejectedBeforeTheyReachTheCamera() {
        val invalid = listOf(OrbitPose(distance = 0f), OrbitPose(distance = -1f),
            OrbitPose(distance = Float.POSITIVE_INFINITY), OrbitPose(distance = Float.NaN),
            OrbitPose(target = Vec3(Float.NaN, 0f, 0f)), OrbitPose(yaw = Float.POSITIVE_INFINITY),
            OrbitPose(pitch = Float.NaN))
        invalid.forEach { pose ->
            assertInvalid { CameraMotion(pose, OrbitPose()) }
            assertInvalid { CameraMotion(OrbitPose(), pose) }
        }
        assertInvalid { CameraMotion(OrbitPose(), OrbitPose()).poseAt(Float.NaN) }
    }

    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("An invalid camera sample must be rejected") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }
}
