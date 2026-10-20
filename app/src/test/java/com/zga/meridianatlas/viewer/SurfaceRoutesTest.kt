package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.ProjectionAnchor
import com.zga.meridianatlas.model.Vec3
import org.junit.Assert.*
import org.junit.Test

class SurfaceRoutesTest {
    @Test fun adaptiveCurveRetainsEveryControlPointAndBoundsMalformedSegmentSize() {
        val points = listOf(Vec3(0f,0f,0f), Vec3(.12f,.01f,0f), Vec3(.13f,.02f,0f))
        val samples = RouteCurveSampler.sample(points)
        assertTrue(samples.containsAll(points))
        assertTrue(samples.size > 20)
        val huge = RouteCurveSampler.sample(listOf(Vec3(-100f,0f,0f), Vec3(100f,0f,0f)))
        assertEquals(129, huge.size)
        assertEquals(emptyList<Vec3>(), RouteCurveSampler.sample(emptyList()))
        assertEquals(listOf(points[0]), RouteCurveSampler.sample(listOf(points[0])))
    }

    @Test fun projectionCacheLeavesCanonicalPointsAndInternalRelationsUntouched() {
        val mesh = SkinOccluder.fromTriangles(floatArrayOf(-1f,-1f,0f, 1f,-1f,0f, 0f,1f,0f))
        val projector = SurfaceRouteProjector(mesh)
        val route = ProjectionAnchor("route:LU|0|0", Vec3(0f,0f,-.02f), Vec3(0f,0f,-1f))
        val point = ProjectionAnchor("point:lu1|0", Vec3(0f,0f,-.02f))
        val relation = ProjectionAnchor("route:LU|1|0", Vec3(0f,0f,-.02f), occlusionTest = false)
        val source = listOf(route, point, relation)
        val prepared = projector.conform(source)
        assertEquals(Vec3(0f,0f,0f), prepared[0].position)
        assertNull(prepared[0].normal)
        assertSame(point, prepared[1])
        assertSame(relation, prepared[2])
        assertEquals(Vec3(0f,0f,-.02f), source[0].position)
        assertSame(prepared, projector.conform(source))
        val changed = projector.conform(listOf(route.copy(position = Vec3(.1f,0f,-.02f))))
        assertEquals(.1f, changed[0].position.x, .00001f)
    }
}
