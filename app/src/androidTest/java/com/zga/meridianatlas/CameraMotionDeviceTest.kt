package com.zga.meridianatlas

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Native frame-clock observations. Does not change device animation settings or call Compose idle mid-flight. */
@RunWith(AndroidJUnit4::class)
class CameraMotionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasViewModel
    private lateinit var viewer: AnatomySurfaceView
    private var durationScale = 1f

    private data class Pose(val target: Vec3, val yaw: Float, val pitch: Float, val distance: Float)
    private data class Sample(val elapsedMs: Long, val pose: Pose, val moving: Boolean, val renderedFrames: Long)
    private data class Trace(val start: Pose, val samples: List<Sample>) {
        val end get() = samples.last().pose
    }

    @Before fun ready() {
        durationScale = Settings.Global.getFloat(instrumentation.targetContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("camera_motion_animation_scale", durationScale.toString())
            putString("camera_motion_animators_enabled", ValueAnimator.areAnimatorsEnabled().toString())
        })
        assumeTrue("System motion is disabled; animated-interval assertions do not apply (scale=$durationScale)",
            durationScale > 0f && ValueAnimator.areAnimatorsEnabled())
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        compose.runOnUiThread {
            AtlasMode.entries.forEach { model.mode(it); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.MERIDIANS)
            model.quality(ModelQuality.LOW)
            compose.activity.setContent {
                Box(Modifier.sizeIn(maxWidth = 420.dp, maxHeight = 760.dp).fillMaxSize()) { MeridianAtlasApp(model) }
            }
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
        viewer = surface()
        ui { viewer.applyCameraPreset(CameraPreset.RESET, animated = false) }
        compose.waitForIdle()
    }

    @Test fun catalogueLocationHasObservableIntermediateFramesAndKeepsPointAndRouteHighlighted() {
        compose.runOnUiThread {
            if ("ST" in model.state.value.enabledMeridians) model.toggleMeridian("ST")
            model.layer { it.copy(points = false, lines = false) }
        }
        compose.waitForIdle()
        val initial = pose()
        captureScreen("catalogue-st12-start")
        val choose = catalogueAction("quepen", "缺盆")
        var capturedIntermediate = false
        var movingProjectionChecks = 0
        val trace = animate("catalogue-st12", choose) {
            assertEquals("st12", model.state.value.selectedPoint)
            assertTrue("ST" in model.state.value.effectiveMeridians)
            assertTrue(model.state.value.layer.points && model.state.value.layer.lines)
            var intermediate = false
            ui {
                intermediate = viewer.isCameraMoving && difference(initial, poseOnUi()) > 1e-4
                if (checkCurrentPointProjection("st12") && intermediate) movingProjectionChecks++
            }
            if (intermediate && !capturedIntermediate) {
                capturedIntermediate = true
                // A screenshot can itself span several frames. Pose samples, rather than
                // this auxiliary capture, establish the animation's intermediate state.
                captureScreen("catalogue-st12-middle")
            }
        }
        val expected = pointPosition("st12")
        assertVector(expected, trace.end.target)
        assertEquals(.36f, trace.end.distance, 1e-5f)
        compose.waitForIdle()
        assertTrue("Keep one original screenshot requested during an intermediate pose", capturedIntermediate)
        captureScreen("catalogue-st12-end")
        compose.onNodeWithContentDescription("取消选中").assertIsDisplayed()
        ui {
            @Suppress("UNCHECKED_CAST")
            val anchors = field("anchors") as List<ProjectionAnchor>
            assertTrue("Renderer must retain the selected point", anchors.any { it.id.startsWith("point:st12|") })
            assertTrue("Renderer must retain its meridian line", anchors.any { it.id.startsWith("route:ST|") })
        }
        val deadline = SystemClock.uptimeMillis() + 30_000
        var checkedFinalProjection = false
        while (!checkedFinalProjection && SystemClock.uptimeMillis() < deadline) {
            ui { checkedFinalProjection = checkCurrentPointProjection("st12") }
            if (!checkedFinalProjection) SystemClock.sleep(20)
        }
        assertTrue("The selected point must eventually have a current-camera hit projection", checkedFinalProjection)
        instrumentation.sendStatus(0, Bundle().apply {
            // New route geometry can invalidate the worker cache during the short flight.
            // Report actual coverage instead of pretending cached occlusion was recomputed per frame.
            putString("camera_motion_live_projection_checks", movingProjectionChecks.toString())
        })
    }

    @Test fun retargetTouchCancellationAndPauseResumeKeepTheCurrentlyRenderedPose() {
        val first = Vec3(.12f, 1.4f, .15f)
        val second = Vec3(-.1f, .72f, .12f)
        ui { viewer.focusOn(first, .38f, Vec3(0f, 0f, 1f)) }
        awaitIntermediate()
        val retarget = animate("retarget-current-pose", {
            viewer.focusOn(second, .5f, Vec3(0f, 0f, 1f))
        })
        assertVector(second, retarget.end.target)
        assertEquals(.5f, retarget.end.distance, 1e-5f)
        assertTargetsBetween(retarget.start.target, second, retarget.samples)

        ui { viewer.focusOn(first, .38f, Vec3(0f, 0f, 1f)) }
        awaitIntermediate()
        lateinit var cancelled: Pose
        ui {
            cancelled = poseOnUi()
            val time = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 12f, 12f, 0)
            val cancel = MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, 12f, 12f, 0)
            try {
                viewer.onTouchEvent(down)
                assertFalse("Touch DOWN must stop the camera immediately", viewer.isCameraMoving)
                assertPose(cancelled, poseOnUi(), "Touch must keep the current pose")
                viewer.onTouchEvent(cancel) // End the gesture without picking another structure.
            } finally { down.recycle(); cancel.recycle() }
        }
        SystemClock.sleep((450 * durationScale).toLong() + 120)
        assertPose(cancelled, pose(), "Cancelled motion must not restart on later frames")
        assertFalse(moving())

        ui { viewer.focusOn(Vec3(.07f, 1.1f, .14f), .7f, Vec3(0f, 0f, 1f)) }
        awaitIntermediate()
        lateinit var frozen: Pose
        ui { viewer.pause(); frozen = poseOnUi() }
        try {
            SystemClock.sleep((450 * durationScale).toLong() + 120)
            assertPose(frozen, pose(), "Pause must freeze elapsed animation time")
        } finally { ui {
            viewer.resume()
            assertPose(frozen, poseOnUi(), "Resume must not jump back to the motion's original start")
        } }
        val resumed = observeUntilStopped()
        assertVector(Vec3(.07f, 1.1f, .14f), resumed.last().pose.target)
        assertTargetsBetween(frozen.target, resumed.last().pose.target, resumed)
        assertTrue("Resume must continue through an intermediate pose", resumed.any {
            difference(it.pose, frozen) > 1e-4 && difference(it.pose, resumed.last().pose) > 1e-4
        })
        record("pause-resume", frozen, resumed)
    }

    @Test fun orientationButtonsKeepLocalFramingRestoreSmoothlyAndOnlyResetReturnsToWholeBody() {
        ui { viewer.focusOn(Vec3(0f, 1.08f, .1f), 1.2f, Vec3(0f, 0f, 1f), animated = false) }
        val original = pose()
        animate("preset-test-catalogue", catalogueAction("quepen", "缺盆"))
        val local = pose()
        for ((title, expectedYaw) in listOf("背" to PI.toFloat(), "正" to 0f,
            "左" to (PI.toFloat() / 2), "右" to (-PI.toFloat() / 2))) {
            val trace = animate("orientation-$title", semanticsAction(
                compose.onNode(hasText(title) and hasClickAction())))
            assertVector(local.target, trace.end.target)
            assertEquals(local.distance, trace.end.distance, 1e-5f)
            assertEquals(0f, angularDifference(trace.end.yaw, expectedYaw), 1e-4f)
            assertEquals(0f, trace.end.pitch, 1e-4f)
            assertEquals("st12", model.state.value.selectedPoint)
            trace.samples.forEach {
                assertVector(local.target, it.pose.target)
                assertEquals(local.distance, it.pose.distance, 1e-5f)
            }
        }
        val restored = animate("restore-before-location", semanticsAction(
            compose.onNodeWithContentDescription("恢复定位前显示")))
        assertPose(original, restored.end, "Restore must return to the captured pre-location view")
        ui { viewer.focusOn(pointPosition("st12"), .36f, Vec3(0f, 0f, 1f), animated = false) }
        val reset = animate("reset-whole-body", semanticsAction(compose.onNodeWithContentDescription("复位")))
        lateinit var home: Pose
        ui { home = readPose(field("homePose")) }
        assertPose(home, reset.end, "RESET must return to whole-body framing")
        assertTrue(reset.end.distance > local.distance)
    }

    @Test fun observationPresetReleasesTheOldBookmarkBeforeTheNextCatalogueLocation() {
        val first = animate("bookmark-first-point", catalogueAction("quepen", "缺盆"))
        compose.onNode(hasText("内脏透视") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("st12", model.state.value.selectedPoint)
        assertFalse("An observation preset ends the first temporary location", model.state.value.canRestore)
        assertFalse(moving())
        assertPose(first.end, pose(), "The preset must preserve the highlighted point's current view")

        ui { viewer.focusOn(Vec3(-.08f, 1.02f, .16f), .9f, Vec3(1f, 0f, 1f), animated = false) }
        val newObservation = pose()
        assertTrue(difference(newObservation, first.end) > .1)
        val second = animate("bookmark-next-point", catalogueAction("LU1", "中府"))
        assertEquals("lu1", model.state.value.selectedPoint)
        assertTrue(model.state.value.canRestore)
        assertVector(pointPosition("lu1"), second.end.target)

        val restored = animate("bookmark-restore-new-view", semanticsAction(
            compose.onNodeWithContentDescription("恢复定位前显示")))
        assertPose(newObservation, restored.end, "Restore must use the view before LU1, not the obsolete first bookmark")
        assertFalse(model.state.value.canRestore)
        assertNull(model.state.value.selectedPoint)
        lateinit var home: Pose
        ui { home = readPose(field("homePose")) }
        assertTrue("The restored custom view must differ from the initial whole-body view", difference(home, restored.end) > .1)
    }

    /** Fetch the real catalogue callback before native timing starts. performClick's idle wait
     * would hide the first part of a 450 ms renderer animation behind the modal dismissal. */
    private fun catalogueAction(query: String, name: String): () -> Unit {
        compose.onNodeWithContentDescription("搜索图谱").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(query)
        val node = compose.onNodeWithContentDescription("定位$name")
        node.assertIsDisplayed()
        return semanticsAction(node)
    }

    private fun semanticsAction(node: SemanticsNodeInteraction): () -> Unit {
        val action = checkNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        return { check(action()) }
    }

    private fun animate(name: String, action: () -> Unit, during: () -> Unit = {}): Trace {
        lateinit var start: Pose
        var startFrame = 0L
        ui {
            start = poseOnUi()
            startFrame = field("totalFrames") as Long
            action()
            assertTrue("$name must start a native camera animation", viewer.isCameraMoving)
            assertPose(start, poseOnUi(), "$name must not teleport before its first frame")
        }
        val samples = observeUntilStopped(during)
        assertTrue("$name must expose at least one intermediate rendered pose", samples.any {
            it.renderedFrames > startFrame && difference(it.pose, start) > 1e-4 &&
                difference(it.pose, samples.last().pose) > 1e-4
        })
        record(name, start, samples)
        return Trace(start, samples)
    }

    private fun observeUntilStopped(during: () -> Unit = {}): List<Sample> {
        val start = SystemClock.uptimeMillis()
        val timeout = max(5_000L, (450 * durationScale).toLong() + 4_000L)
        val samples = mutableListOf<Sample>()
        var keepMoving: Boolean
        do {
            lateinit var sample: Sample
            ui { sample = Sample(SystemClock.uptimeMillis() - start, poseOnUi(), viewer.isCameraMoving,
                field("totalFrames") as Long) }
            samples += sample
            during()
            assertTrue("Native camera animation did not finish within $timeout ms", sample.elapsedMs < timeout)
            if (sample.moving) SystemClock.sleep(12)
            keepMoving = sample.moving
        } while (keepMoving)
        return samples
    }

    private fun awaitIntermediate() {
        val start = pose()
        val deadline = SystemClock.uptimeMillis() + max(2_000L, (450 * durationScale).toLong())
        while (SystemClock.uptimeMillis() < deadline) {
            if (difference(start, pose()) > 1e-4 && moving()) return
            SystemClock.sleep(8)
        }
        fail("No intermediate native animation frame was observed")
    }

    private fun pointPosition(id: String): Vec3 = checkNotNull(model.state.value.data!!.points.single { it.id == id }
        .instances.first { it.position != null }.position)
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun field(name: String): Any = checkNotNull(AnatomySurfaceView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(viewer))
    private fun poseOnUi() = readPose(field("pose"))
    private fun pose(): Pose { lateinit var value: Pose; ui { value = poseOnUi() }; return value }
    private fun moving(): Boolean { var value = false; ui { value = viewer.isCameraMoving }; return value }
    private fun readPose(value: Any): Pose {
        fun number(name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(value)
        val target = value.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(value) as Vec3
        return Pose(target, number("yaw"), number("pitch"), number("distance"))
    }
    private fun angularDifference(a: Float, b: Float): Float =
        (((a - b + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI).toFloat()
    private fun difference(a: Pose, b: Pose): Double = abs(a.target.x - b.target.x).toDouble() +
        abs(a.target.y - b.target.y) + abs(a.target.z - b.target.z) + abs(a.distance - b.distance) +
        abs(angularDifference(a.yaw, b.yaw)) + abs(a.pitch - b.pitch)
    private fun assertVector(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-5f); assertEquals(expected.y, actual.y, 1e-5f)
        assertEquals(expected.z, actual.z, 1e-5f)
    }
    private fun assertPose(expected: Pose, actual: Pose, message: String) =
        assertTrue("$message: expected=$expected actual=$actual", difference(expected, actual) < 1e-4)
    private fun assertTargetsBetween(start: Vec3, end: Vec3, samples: List<Sample>) {
        for (sample in samples) for ((value, a, b) in listOf(
            Triple(sample.pose.target.x, start.x, end.x), Triple(sample.pose.target.y, start.y, end.y),
            Triple(sample.pose.target.z, start.z, end.z))) {
            assertTrue("Motion must continue from its current target without visiting an obsolete queued target",
                value >= min(a, b) - 1e-5f && value <= max(a, b) + 1e-5f)
        }
    }

    private fun surface(): AnatomySurfaceView {
        var result: AnatomySurfaceView? = null
        ui {
            fun find(view: View): AnatomySurfaceView? {
                if (view is AnatomySurfaceView && view.isShown) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            result = find(compose.activity.window.decorView)
        }
        return checkNotNull(result)
    }

    /** Compare a hit target's pixel coordinates with the actual camera matrices on the same UI turn. */
    private fun checkCurrentPointProjection(id: String): Boolean {
        val point = model.state.value.data!!.points.single { it.id == id }
        val index = point.instances.indexOfFirst { it.position != null }
        val p = checkNotNull(point.instances[index].position)
        val projected = viewer.projectionsForHitTest().firstOrNull { it.id == "hitpoint:$id|$index" } ?: return false
        val camera = field("camera") as Camera
        val view = DoubleArray(16); val projection = DoubleArray(16)
        camera.getViewMatrix(view); camera.getProjectionMatrix(projection)
        fun multiply(matrix: DoubleArray, vector: DoubleArray) = DoubleArray(4) { row ->
            (0..3).sumOf { column -> matrix[column * 4 + row] * vector[column] }
        }
        val clip = multiply(projection, multiply(view,
            doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble(), 1.0)))
        assertTrue("The tracked chest point must remain in front of the moving camera", clip[3] > 0)
        assertEquals((clip[0] / clip[3] + 1) * viewer.width / 2, projected.xPx.toDouble(), .6)
        assertEquals((1 - clip[1] / clip[3]) * viewer.height / 2, projected.yPx.toDouble(), .6)
        return true
    }

    private fun captureScreen(name: String) {
        val before = pose()
        val started = SystemClock.uptimeMillis()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation screenshot unavailable" }
        val after = pose()
        var frames = 0L
        ui { frames = field("totalFrames") as Long }
        val folder = File(instrumentation.targetContext.filesDir, "qa/camera-motion").apply { mkdirs() }
        try {
            File(folder, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        // Capture latency is auxiliary evidence and is excluded from frame-rate claims.
        record("$name-capture", before, listOf(Sample(SystemClock.uptimeMillis() - started,
            after, moving(), frames)))
    }

    private fun record(name: String, start: Pose, samples: List<Sample>) {
        fun json(pose: Pose) = JSONObject().put("target", JSONArray(listOf(pose.target.x, pose.target.y, pose.target.z)))
            .put("yaw", pose.yaw).put("pitch", pose.pitch).put("distance", pose.distance)
        val root = JSONObject().put("animationScale", durationScale).put("start", json(start))
            .put("samples", JSONArray(samples.map { JSONObject().put("elapsedMs", it.elapsedMs)
                .put("pose", json(it.pose)).put("moving", it.moving).put("renderedFrames", it.renderedFrames) }))
        val folder = File(instrumentation.targetContext.filesDir, "qa/camera-motion").apply { mkdirs() }
        File(folder, "$name.json").writeText(root.toString(2))
    }
}
