package com.zga.meridianatlas

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Actual card Surface bounds versus raw selected-instance projections, all in screen pixels.
 * Offscreen/occluded markers fail the premise instead of silently passing an intersection test.
 * Compact constraints reuse the same loaded renderer; no device configuration is changed.
 */
@RunWith(AndroidJUnit4::class)
class SelectionCardOcclusionDeviceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val failureEvidence = QaFailureRule()
    @get:Rule(order = 2) val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasViewModel
    private lateinit var viewer: AnatomySurfaceView
    private var requestedViewport by mutableStateOf<Pair<Int, Int>?>(null)
    private val observations = JSONArray()
    private val density get() = compose.activity.resources.displayMetrics.density

    private data class Pose(val target: Vec3, val yaw: Float, val pitch: Float, val distance: Float)
    private data class NativeSample(val anchor: ProjectedAnchor?, val viewport: Rect, val pose: Pose,
        val frame: Long, val moving: Boolean, val priorityIds: Set<String>)

    @Before fun ready() {
        // This runner deliberately rejects a phone rather than recording a skipped tablet case.
        assertTrue("Run on a real tablet with native width >= 840dp",
            compose.activity.resources.configuration.screenWidthDp >= 840)
        assertTrue("The device must fit the 760dp compact fixture without clipping it",
            compose.activity.resources.configuration.screenHeightDp >= 760)
        ui {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
        }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        ui {
            AtlasMode.entries.forEach { model.mode(it); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.MERIDIANS)
            model.quality(ModelQuality.LOW)
            model.setMeridians(model.state.value.enabledMeridians, false)
            model.layer { it.copy(skin = true, muscle = false, organ = false, bone = false,
                points = true, lines = true, labels = true, details = true, cutawaySide = CutawaySide.NONE) }
            compose.activity.setContent {
                Box(Modifier.fillMaxSize()) {
                    val requested = requestedViewport
                    val bounds = if (requested == null) Modifier.fillMaxSize()
                        else Modifier.size(requested.first.dp, requested.second.dp)
                    Box(bounds.testTag("occlusion-viewport-fixture")) { MeridianAtlasApp(model) }
                }
            }
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
        viewer = ui { checkNotNull(findSurface(compose.activity.window.decorView)) }
        awaitNative("The loaded skin mesh and renderer must be ready", 180_000) {
            field<Any?>("skinOccluder") != null && field<Long>("totalFrames") > 0L
        }
        ui { viewer.applyCameraPreset(CameraPreset.RESET, animated = false) }
        compose.waitForIdle()
    }

    @After fun saveEvidenceAndReleaseWindowFlag() {
        val report = JSONObject().put("case", testName.methodName)
            .put("nativeScreenWidthDp", compose.activity.resources.configuration.screenWidthDp)
            .put("selectedCircleRadiusDp", 12).put("minimumCardGapDp", 4)
            .put("coordinateSpace", "screen pixels: Surface.getLocationOnScreen + raw projection; card.boundsInWindow + decor(screen - inWindow)")
            .put("evidenceBoundary", "Real selected raw instance eligibility and actual tagged card Surface bounds; not screenshot text recognition or medical point calibration")
            .put("observations", observations)
        val folder = File(instrumentation.targetContext.filesDir, "qa/selection-card-occlusion").apply { mkdirs() }
        File(folder, "${testName.methodName}.json").writeText(report.toString(2))
        ui { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun nativeTabletAdjacentChestPointsAndBothDisplayPositionsKeepMarkersClear() {
        val nativeRoot = compose.onNodeWithTag("occlusion-viewport-fixture").fetchSemanticsNode().boundsInWindow
        assertTrue("The native application fixture itself must have tablet width >= 840dp", nativeRoot.width / density >= 840f)
        val nativeBounds = viewport()
        for (id in listOf("gb22", "gb23")) {
            locateThroughCatalogue(id)
            assertClear(id, 0, "native-$id-initial", nativeBounds)
            // activeSide belongs to the composition, not the ViewModel: choose both actual chips.
            for (side in listOf("right", "left")) {
                val index = point(id).instances.indexOfFirst { it.side == side && it.position != null }
                assertTrue("$id must expose its $side position", index >= 0)
                chooseDisplayPosition(id, index)
                assertClear(id, index, "native-$id-$side", nativeBounds)
            }
        }
    }

    @Test fun compactAndNarrowLongNoticeKeepGb23MarkerClear() {
        val originalViewer = viewer
        for ((width, height) in listOf(420 to 760, 320 to 568)) {
            setViewport(width, height)
            assertSame("Responsive constraints must reuse the loaded model", originalViewer, viewer)
            val expectedViewport = viewport()
            locateThroughCatalogue("gb23")
            assertClear("gb23", 0, "${width}x$height-gb23", expectedViewport)
            val notice = point("gb23").coordinateNoticeZh
            assertTrue("Exercise GB23's actual long coordinate notice", notice.length >= 30)
            val beforeDetails = pose()
            compose.onNodeWithContentDescription("查看完整说明").performClick()
            compose.onAllNodesWithText(notice, useUnmergedTree = true).onLast()
                .performScrollTo().assertIsDisplayed()
            assertSurfaceAndPose(expectedViewport, beforeDetails, "${width}x$height-full-notice")
            back()
            assertSurfaceAndPose(expectedViewport, beforeDetails, "${width}x$height-dismiss-notice")
            assertClear("gb23", 0, "${width}x$height-after-notice", expectedViewport)
            compose.onNodeWithContentDescription("取消选中").performClick()
            compose.waitForIdle()
        }
    }

    @Test fun multiPointLastDisplayPositionsAvoidCard() {
        setViewport(420, 760)
        val expectedViewport = viewport()
        for (id in listOf("ex-hn1", "ex-b2")) {
            val last = point(id).instances.indexOfLast { it.position != null }
            assertTrue("The fixture must exercise a non-first multi-point instance", last > 1)
            locateThroughCatalogue(id)
            chooseDisplayPosition(id, last)
            assertClear(id, last, "$id-last-instance-$last", expectedViewport)
        }
    }

    @Test fun rotatedZoomedGb23AndDescriptionControlsKeepViewportAndPose() {
        setViewport(420, 760)
        locateThroughCatalogue("gb23")
        chooseDisplayPosition("gb23", 1)
        val expectedViewport = viewport()
        assertClear("gb23", 1, "gesture-before", expectedViewport)
        val beforeRotation = pose()
        rotateUsingNativeTouch()
        awaitStopped()
        assertTrue("Real single-finger input must change the camera yaw", abs(pose().yaw - beforeRotation.yaw) > .01f)
        assertEquals("Native rotation must retain selection", "gb23", model.state.value.selectedPoint)
        assertClear("gb23", 1, "gesture-rotated", expectedViewport)
        val beforeZoom = pose()
        pinchUsingNativeTouch()
        awaitStopped()
        assertTrue("Real two-finger input must zoom in", pose().distance < beforeZoom.distance * .95f)
        assertClear("gb23", 1, "gesture-zoomed", expectedViewport)

        val stable = pose()
        compose.onNodeWithContentDescription("查看完整说明").performClick()
        compose.onNodeWithText("显示位置").performScrollTo().assertIsDisplayed()
        assertSurfaceAndPose(expectedViewport, stable, "expanded-details")
        back()
        assertSurfaceAndPose(expectedViewport, stable, "closed-details")
        toggleDescriptionThroughLayers()
        assertFalse(model.state.value.layer.details)
        compose.onNodeWithTag("selection-card", useUnmergedTree = true).assertDoesNotExist()
        assertSurfaceAndPose(expectedViewport, stable, "description-disabled")
        toggleDescriptionThroughLayers()
        assertTrue(model.state.value.layer.details)
        assertSurfaceAndPose(expectedViewport, stable, "description-enabled")
        assertClear("gb23", 1, "description-restored", expectedViewport)
        compose.onNodeWithContentDescription("取消选中").performClick()
        compose.waitForIdle()
        assertNull(model.state.value.selectedPoint)
        compose.onNodeWithTag("selection-card", useUnmergedTree = true).assertDoesNotExist()
        assertSurfaceAndPose(expectedViewport, stable, "selection-closed")
    }

    private fun locateThroughCatalogue(id: String) {
        val selected = point(id)
        openCatalogueIfNeeded(compose)
        compose.onNodeWithContentDescription("搜索经络与穴位").performTextReplacement(selected.codes.first())
        compose.onNodeWithContentDescription("定位${selected.nameZh}").assertIsDisplayed().performClick()
        compose.waitForIdle()
        awaitStopped()
        assertEquals(id, model.state.value.selectedPoint)
    }

    private fun chooseDisplayPosition(id: String, index: Int) {
        val instance = point(id).instances[index]
        val expectedViewport = viewport()
        val beforeOpen = pose()
        compose.onNodeWithContentDescription("查看完整说明").performClick()
        assertSurfaceAndPose(expectedViewport, beforeOpen, "$id-open-display-position")
        val label = instance.labelZh ?: (when (instance.side) {
            "left" -> "左侧"; "right" -> "右侧"; "midline", "center" -> "正中"; else -> instance.side
        } + if (instance.ordinal > 1) " ${instance.ordinal}" else "")
        val chip = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
        chip.performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        awaitStopped()
        chip.assertIsSelected()
        assertVector(checkNotNull(instance.position), pose().target, "The real display-position chip must focus instance $index")
        val chosenPose = pose()
        back()
        assertSurfaceAndPose(expectedViewport, chosenPose, "$id-dismiss-display-position")
        awaitNative("The actual selected-instance priority must follow the chosen display chip") {
            "point:$id|$index" in field<Set<String>>("priorityAnchorIds")
        }
    }

    private fun toggleDescriptionThroughLayers() {
        compose.onNodeWithContentDescription("图层控制").performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasText("说明面板"))
        compose.onNode(hasText("说明面板") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        back()
    }

    private fun assertClear(id: String, index: Int, stage: String, expectedViewport: Rect) {
        compose.waitForIdle()
        val anchorId = "point:$id|$index"
        awaitNative("$stage: selected raw marker must be visible, front-facing and not occluded") {
            val raw = field<List<ProjectedAnchor>>("currentProjections").firstOrNull { it.id == anchorId }
            !viewer.isCameraMoving && raw != null && raw.visible && raw.frontFacing && !raw.occluded
        }
        val seenFrames = mutableSetOf<Long>()
        repeat(8) { sampleIndex ->
            compose.waitForIdle() // Commit projection-driven card placement before native observation.
            val cardNode = compose.onNodeWithTag("selection-card", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode()
            val card = cardScreenBounds(cardNode)
            val sample = ui {
                NativeSample(field<List<ProjectedAnchor>>("currentProjections").firstOrNull { it.id == anchorId },
                    viewportOnUi(), poseOnUi(), field("totalFrames"), viewer.isCameraMoving, field("priorityAnchorIds"))
            }
            val raw = sample.anchor
            val x = raw?.xPx?.plus(sample.viewport.left)
            val y = raw?.yPx?.plus(sample.viewport.top)
            val dx = x?.let { max(max(card.left - it, 0f), it - card.right) }
            val dy = y?.let { max(max(card.top - it, 0f), it - card.bottom) }
            val clearance = if (dx != null && dy != null) hypot(dx, dy) else -1f
            observations.put(JSONObject().put("stage", stage).put("sample", sampleIndex).put("selectedAnchorId", anchorId)
                .put("selectedPoint", model.state.value.selectedPoint).put("renderedFrame", sample.frame)
                .put("viewportScreen", rectJson(sample.viewport)).put("cardScreen", rectJson(card))
                .put("rawInstancePresent", raw != null).put("visible", raw?.visible).put("frontFacing", raw?.frontFacing)
                .put("occluded", raw?.occluded).put("xScreenPx", x).put("yScreenPx", y)
                .put("cardClearancePx", clearance).put("requiredClearancePx", 16f * density)
                .put("priorityIds", JSONArray(sample.priorityIds.toList())).put("moving", sample.moving))
            assertSame("$stage must keep the loaded renderer", viewer, ui { findSurface(compose.activity.window.decorView) })
            assertEquals("$stage must not resize/move the actual model viewport", expectedViewport, sample.viewport)
            assertEquals("$stage must retain the actual selected point", id, model.state.value.selectedPoint)
            assertEquals("$stage must use the selected display instance, including non-first sides", setOf(anchorId), sample.priorityIds)
            assertNotNull("$stage: missing raw selected instance", raw)
            assertTrue("$stage: a hidden/offscreen point cannot establish a passing no-overlap result",
                raw!!.visible && raw.frontFacing && !raw.occluded && !sample.moving)
            val circleRadius = 12f * density
            val screenX = checkNotNull(x); val screenY = checkNotNull(y)
            assertTrue("$stage: the full selected circle must fit inside the actual viewport",
                screenX - circleRadius >= sample.viewport.left && screenX + circleRadius <= sample.viewport.right &&
                    screenY - circleRadius >= sample.viewport.top && screenY + circleRadius <= sample.viewport.bottom)
            assertTrue("$stage: the 12dp selected circle needs another 4dp before the real card Surface (clearance=$clearance)",
                clearance + .5f >= 16f * density)
            assertTrue("$stage: real card content must identify the selected point", point(id).nameZh in texts(cardNode))
            compose.onNodeWithContentDescription("查看完整说明").assertIsDisplayed()
            compose.onNodeWithContentDescription("取消选中").assertIsDisplayed()
            seenFrames += sample.frame
            if (sampleIndex < 7) awaitNative("$stage: renderer must keep producing frames") { field<Long>("totalFrames") > sample.frame }
        }
        assertTrue("$stage must observe several distinct rendered frames", seenFrames.size >= 3)
    }

    private fun cardScreenBounds(node: SemanticsNode): RectF {
        val bounds = node.boundsInWindow
        val origin = ui {
            val screen = IntArray(2); val window = IntArray(2)
            compose.activity.window.decorView.getLocationOnScreen(screen)
            compose.activity.window.decorView.getLocationInWindow(window)
            (screen[0] - window[0]) to (screen[1] - window[1])
        }
        return RectF(bounds.left + origin.first, bounds.top + origin.second,
            bounds.right + origin.first, bounds.bottom + origin.second)
    }

    private fun setViewport(width: Int, height: Int) {
        val original = viewer
        ui { requestedViewport = width to height }
        compose.waitForIdle()
        assertSame("Resizing test constraints must not create a new model renderer", original,
            ui { findSurface(compose.activity.window.decorView) })
        val fixture = compose.onNodeWithTag("occlusion-viewport-fixture").fetchSemanticsNode().boundsInWindow
        assertEquals("The fixture must actually have the requested width", width * density, fixture.width, 1.5f)
        assertEquals("The fixture must actually have the requested height", height * density, fixture.height, 1.5f)
        assertEquals("The compact fixture must use its requested real width", width * density, viewport().width().toFloat(), 1.5f)
    }

    private fun assertSurfaceAndPose(expectedViewport: Rect, expectedPose: Pose, stage: String) {
        compose.waitForIdle()
        assertSame("$stage must keep the actual SurfaceView", viewer, ui { findSurface(compose.activity.window.decorView) })
        assertEquals("$stage must not resize/move the actual viewport", expectedViewport, viewport())
        val actual = pose()
        assertVector(expectedPose.target, actual.target, "$stage camera target")
        assertEquals("$stage yaw", expectedPose.yaw, actual.yaw, .00001f)
        assertEquals("$stage pitch", expectedPose.pitch, actual.pitch, .00001f)
        assertEquals("$stage distance", expectedPose.distance, actual.distance, .00001f)
    }

    private fun rotateUsingNativeTouch() {
        val center = gestureCenter(30f * density)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(center))
        repeat(8) { step ->
            SystemClock.sleep(16)
            touch(MotionEvent.ACTION_MOVE, down, listOf(center.first + (step + 1) * 2.5f * density to center.second))
        }
        touch(MotionEvent.ACTION_UP, down, listOf(center.first + 20f * density to center.second))
    }

    private fun pinchUsingNativeTouch() {
        val center = gestureCenter(36f * density)
        val down = SystemClock.uptimeMillis()
        fun points(span: Float) = listOf(center.first - span to center.second, center.first + span to center.second)
        val from = 22f * density; val to = 30f * density
        touch(MotionEvent.ACTION_DOWN, down, points(from).take(1))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, points(from))
        repeat(8) { step ->
            SystemClock.sleep(16)
            touch(MotionEvent.ACTION_MOVE, down, points(from + (to - from) * (step + 1) / 8))
        }
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, points(to))
        touch(MotionEvent.ACTION_UP, down, points(to).take(1))
    }

    private fun gestureCenter(halfWidth: Float): Pair<Float, Float> {
        compose.waitForIdle()
        val bounds = viewport()
        val card = cardScreenBounds(compose.onNodeWithTag("selection-card", useUnmergedTree = true).fetchSemanticsNode())
        for (fy in listOf(.5f, .65f, .35f)) for (fx in listOf(.5f, .35f, .65f)) {
            val x = bounds.left + bounds.width() * fx; val y = bounds.top + bounds.height() * fy
            val touchBox = RectF(x - halfWidth, y - 12f * density, x + halfWidth, y + 12f * density)
            if (touchBox.left > bounds.left + 12f * density && touchBox.right < bounds.right - 60f * density &&
                touchBox.top > bounds.top + 104f * density && touchBox.bottom < bounds.bottom - 80f * density &&
                !RectF.intersects(touchBox, card)) return x to y
        }
        throw AssertionError("No uncovered model area is available for a genuine gesture")
    }

    private fun touch(action: Int, down: Long, points: List<Pair<Float, Float>>) {
        assertTrue("Only inject into this foreground application", ui { compose.activity.hasWindowFocus() })
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
            x = points[index].first; y = points[index].second; pressure = 1f; size = .05f
        } }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue("Native model gesture was rejected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun point(id: String) = model.state.value.data!!.points.single { it.id == id }
    private fun viewport() = ui { viewportOnUi() }
    private fun viewportOnUi(): Rect {
        val location = IntArray(2); viewer.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + viewer.width, location[1] + viewer.height)
    }
    private fun pose() = ui { poseOnUi() }
    private fun poseOnUi(): Pose {
        val value = field<Any>("pose")
        fun number(name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(value)
        return Pose(value.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(value) as Vec3,
            number("yaw"), number("pitch"), number("distance"))
    }
    private fun assertVector(expected: Vec3, actual: Vec3, stage: String) {
        assertEquals("$stage x", expected.x, actual.x, .00001f)
        assertEquals("$stage y", expected.y, actual.y, .00001f)
        assertEquals("$stage z", expected.z, actual.z, .00001f)
    }
    private fun awaitStopped() {
        compose.waitForIdle()
        awaitNative("The real camera must settle before card geometry is accepted", 30_000) { !viewer.isCameraMoving }
        compose.waitForIdle()
    }
    private fun awaitNative(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!ui(condition)) {
            assertTrue(message, SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(12)
        }
    }
    private fun findSurface(view: View): AnatomySurfaceView? {
        if (view is AnatomySurfaceView && view.isShown) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = AnatomySurfaceView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(viewer) as T
    private fun <T> ui(action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }
    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
    private fun rectJson(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun rectJson(value: RectF) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
}
