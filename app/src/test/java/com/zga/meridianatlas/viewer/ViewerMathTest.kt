package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.*
import org.junit.Assert.*
import org.junit.Test

class ViewerMathTest {
    private val projection = doubleArrayOf(2.41421356,0.0,0.0,0.0, 0.0,2.41421356,0.0,0.0,
        0.0,0.0,-1.0002,-1.0, 0.0,0.0,-.020002,0.0)
    private val view = doubleArrayOf(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0, 0.0,0.0,-3.0,1.0)
    @Test fun cameraProjectionMatchesScreenCoordinatesAndRejectsBehindEye() {
        val points = listOf(ProjectionAnchor("center", Vec3(0f,0f,0f)), ProjectionAnchor("right", Vec3(.2f,0f,0f)),
            ProjectionAnchor("up", Vec3(0f,.2f,0f)), ProjectionAnchor("behind", Vec3(0f,0f,5f)))
        val result = AnchorProjector.project(points, projection, view, Vec3(0f,0f,3f), 1000,1000)
        assertEquals(500f,result[0].xPx,.01f); assertEquals(500f,result[0].yPx,.01f)
        assertTrue(result[1].xPx > 500f); assertTrue(result[2].yPx < 500f)
        assertFalse(result[3].visible)
    }
    @Test fun backFacingMarkersAreSeparateFromFrustumVisibility() {
        val points = listOf(ProjectionAnchor("front",Vec3(0f,0f,0f),Vec3(0f,0f,1f)),
            ProjectionAnchor("back",Vec3(0f,0f,0f),Vec3(0f,0f,-1f)))
        val result = AnchorProjector.project(points,projection,view,Vec3(0f,0f,3f),1000,1000)
        assertTrue(result.all { it.visible }); assertTrue(result[0].frontFacing); assertFalse(result[1].frontFacing)
    }
    @Test fun panningScalesWithCameraDistance() {
        val near = OrbitPose(target=Vec3(0f,0f,0f), distance=1f).pan(100f,0f,1000)
        val far = OrbitPose(target=Vec3(0f,0f,0f), distance=2f).pan(100f,0f,1000)
        assertEquals(near.target.x*2f,far.target.x,.00001f)
        assertEquals(0f,near.target.y,0f)
    }

    @Test fun offscreenRouteEndpointsRemainAvailableForCanvasToClipCrossingSegments() {
        val points = listOf(ProjectionAnchor("route:LU|0|0", Vec3(-2f,0f,0f)),
            ProjectionAnchor("route:LU|0|1", Vec3(0f,0f,0f)),
            ProjectionAnchor("point:lu1|0", Vec3(-2f,0f,0f)),
            ProjectionAnchor("route:LU|0|2", Vec3(0f,0f,5f)))
        val result = AnchorProjector.project(points, projection, view, Vec3(0f,0f,3f),1000,1000)
        assertTrue(result[0].xPx < 0f)
        assertTrue(result[0].visible && result[1].visible)
        assertFalse(result[2].visible)
        assertFalse(result[3].visible)
        val fractionToLeftEdge = -result[0].xPx/(result[1].xPx-result[0].xPx)
        assertTrue(fractionToLeftEdge > 0f && fractionToLeftEdge < 1f)
        assertEquals(500f, result[0].yPx + fractionToLeftEdge*(result[1].yPx-result[0].yPx), .001f)
    }

    @Test fun offscreenFascialSegmentsRemainInTheirOwnCoordinatesAndClipAtTheViewport() {
        val points = listOf(ProjectionAnchor("fascia-route:group|path|0", Vec3(-2f,0f,0f)),
            ProjectionAnchor("fascia-route:group|path|1", Vec3(0f,0f,0f)),
            ProjectionAnchor("fascial-target:region", Vec3(-2f,0f,0f)),
            ProjectionAnchor("fascia-route:group|path|2", Vec3(0f,0f,5f)))
        val result = AnchorProjector.project(points, projection, view, Vec3(0f,0f,3f),1000,1000)
        assertTrue(result[0].visible && result[1].visible)
        assertTrue(result[0].xPx < 0f)
        assertFalse(result[2].visible)
        assertFalse(result[3].visible)
    }

    @Test fun observedRegionFitsTheNarrowerFieldOfViewWithoutTheSmallStructureDistanceCap() {
        val min = Vec3(-.4f,0f,-.2f); val max = Vec3(.4f,1.7f,.2f)
        val wide = regionFocusDistance(min, max, 1f)
        val narrow = regionFocusDistance(min, max, .4f)
        assertTrue(narrow > wide)
        assertTrue("A long selected chain must be allowed a full-body camera distance", narrow > 2.8f)
        // A sphere covering all eight box corners must fit within the narrower half-angle.
        val radius = kotlin.math.sqrt(.8f*.8f + 1.7f*1.7f + .4f*.4f) / 2f
        val halfFov = kotlin.math.atan(kotlin.math.tan(Math.PI.toFloat()/8f)*.4f)
        assertTrue(radius / narrow < kotlin.math.sin(halfFov))
        assertEquals(.35f, regionFocusDistance(min, min, .4f), .00001f)
    }
}
