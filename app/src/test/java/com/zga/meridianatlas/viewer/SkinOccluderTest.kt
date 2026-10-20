package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.Vec3
import com.zga.meridianatlas.model.ProjectionAnchor
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SkinOccluderTest {
    private val origin = Vec3(0f, 0f, 0f)
    private fun plane(z: Float) = floatArrayOf(-1f, -1f, z, 1f, -1f, z, 0f, 1f, z)

    @Test fun nearestHitSurvivesBvhSplitsAndNonUnitDirections() {
        val triangles = (20 downTo 1).flatMap { plane(it.toFloat()).asIterable() }.toFloatArray()
        val mesh = SkinOccluder.fromTriangles(triangles)
        assertEquals(1f, mesh.closestHit(origin, Vec3(0f, 0f, 4f))!!, 0.00001f)
        assertNull(mesh.closestHit(origin, Vec3(1f, 0f, 0f)))
    }

    @Test fun nearerSkinHidesThePointBehindIt() {
        val mesh = SkinOccluder.fromTriangles(plane(1f))
        assertTrue(mesh.isOccluded(origin, Vec3(0f, 0f, 2f)))
        assertFalse(mesh.isOccluded(origin, Vec3(0f, 0f, 0.5f)))
        assertFalse(mesh.isOccluded(origin, Vec3(4f, 0f, 2f)))
    }

    @Test fun surfaceAndEightMillimetreToleranceDoNotHideTheirOwnPoint() {
        val mesh = SkinOccluder.fromTriangles(plane(1f))
        assertFalse(mesh.isOccluded(origin, Vec3(0f, 0f, 1f)))
        assertFalse(mesh.isOccluded(origin, Vec3(0f, 0f, 1.005f)))
        assertTrue(mesh.isOccluded(origin, Vec3(0f, 0f, 1.02f)))
    }

    @Test fun skinTrianglesAreTwoSided() {
        val mesh = SkinOccluder.fromTriangles(plane(1f))
        assertEquals(1f, mesh.closestHit(Vec3(0f, 0f, 2f), Vec3(0f, 0f, -1f))!!, 0.00001f)
    }

    @Test fun surfaceProjectionFindsFaceEdgeAndVertexWithoutExceedingItsLimit() {
        val mesh = SkinOccluder.fromTriangles(floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f))
        fun check(source: Vec3, target: Vec3) {
            val result = mesh.closestSurface(source, 2f)!!
            assertEquals(target.x, result.x, .000001f)
            assertEquals(target.y, result.y, .000001f)
            assertEquals(target.z, result.z, .000001f)
        }
        check(Vec3(.2f,.2f,.03f), Vec3(.2f,.2f,0f))
        check(Vec3(.7f,.7f,.03f), Vec3(.5f,.5f,0f))
        check(Vec3(-.2f,-.2f,.03f), Vec3(0f,0f,0f))
        assertNull(mesh.closestSurface(Vec3(.2f,.2f,.051f)))
    }

    @Test fun surfaceProjectionHandlesDegenerateTriangles() {
        val mesh = SkinOccluder.fromTriangles(floatArrayOf(0f,0f,0f, 1f,0f,0f, .5f,0f,0f))
        assertEquals(Vec3(.25f,0f,0f), mesh.closestSurface(Vec3(.25f,.01f,0f)))
    }

    @Test fun conformingAnInsetRouteRestoresItsNearSurfaceAndStillHidesTheFarSurface() {
        val mesh = SkinOccluder.fromTriangles(plane(.1f) + plane(-.1f))
        val near = Vec3(0f,0f,.08f)
        val far = Vec3(0f,0f,-.08f)
        val eye = Vec3(0f,0f,1f)
        assertTrue(mesh.isOccluded(eye, near))
        assertTrue(mesh.isOccluded(eye, far))
        assertFalse(mesh.isOccluded(eye, mesh.closestSurface(near)!!))
        assertTrue(mesh.isOccluded(eye, mesh.closestSurface(far)!!))
    }

    @Test fun readsLittleEndianMso1Mesh() {
        val bytes = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put(byteArrayOf(77, 83, 79, 49)).putInt(1)
        plane(1f).forEach { bytes.putFloat(it) }
        val mesh = SkinOccluder.readFrom(ByteArrayInputStream(bytes.array()))
        assertEquals(1f, mesh.closestHit(origin, Vec3(0f, 0f, 1f))!!, 0.00001f)
    }

    @Test fun projectedInternalAnchorsCanExplicitlySkipSkinOcclusion() {
        val projection = doubleArrayOf(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0,
            0.0,0.0,-1.0,-1.0, 0.0,0.0,-.02,0.0)
        val view = doubleArrayOf(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0,
            0.0,0.0,1.0,0.0, 0.0,0.0,-3.0,1.0)
        val points = listOf(ProjectionAnchor("body", origin),
            ProjectionAnchor("internal", origin, occlusionTest = false))
        val result = AnchorProjector.project(points, projection, view, Vec3(0f,0f,3f),
            1000, 1000, SkinOccluder.fromTriangles(plane(1f)))
        assertTrue(result[0].visible && result[0].occluded)
        assertTrue(result[1].visible && !result[1].occluded)
    }

    @Test(expected = IOException::class) fun rejectsInvalidBinaryMagic() {
        SkinOccluder.readFrom(ByteArrayInputStream(ByteArray(8)))
    }
}
