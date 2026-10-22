package com.zga.meridianatlas

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.data.AtlasPreferences
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasUiState
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Real catalogue, detail, layer and workspace actions. The runner owns the user's preference backup.
 * Model methods below only establish the initial fixture; acceptance operations use actual UI.
 * One live Filament SurfaceView is retained throughout each case, including compact constraints.
 */
@RunWith(AndroidJUnit4::class)
class VisibilityRestoreDeviceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val failureEvidence = QaFailureRule()
    @get:Rule(order = 2) val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasViewModel
    private lateinit var viewer: AnatomySurfaceView
    private var requestedSize by mutableStateOf<Pair<Int, Int>?>(null)
    private val observations = JSONArray()
    private val runToken = System.currentTimeMillis()
    private val density get() = compose.activity.resources.displayMetrics.density

    private data class Pose(val target: Vec3, val yaw: Float, val pitch: Float, val distance: Float)
    private data class Context(val state: AtlasUiState, val viewport: Rect, val pose: Pose)

    @Before fun ready() {
        ui {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
        }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        ui {
            AtlasMode.entries.forEach { model.mode(it); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.OVERVIEW)
            model.quality(ModelQuality.LOW)
            model.setMeridians(model.state.value.enabledMeridians, false)
            compose.activity.setContent {
                Box(Modifier.fillMaxSize()) {
                    val requested = requestedSize
                    val bounds = if (requested == null) Modifier.fillMaxSize()
                        else Modifier.size(requested.first.dp, requested.second.dp)
                    Box(bounds.testTag("visibility-workspace-fixture")) { MeridianAtlasApp(model) }
                }
            }
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
        viewer = ui { singleSurface() }
        awaitNative("The real model must render before interaction assertions", 180_000) {
            field<Long>("totalFrames") > 0L && field<Set<String>?>("visibleIds") != null
        }
        ui { viewer.applyCameraPreset(CameraPreset.RESET, animated = false) }
        compose.waitForIdle()
        actualVisibleIds()
    }

    @After fun retainEvidence() {
        val folder = File(instrumentation.targetContext.filesDir, "qa/visibility-restore").apply { mkdirs() }
        File(folder, "${testName.methodName}-$runToken.json").writeText(JSONObject()
            .put("case", testName.methodName)
            .put("nativeWidthDp", compose.activity.resources.configuration.screenWidthDp)
            .put("observations", observations)
            .put("evidenceBoundary", "Actual UI actions, live renderer visibility IDs/camera/viewport and DataStore readback. Does not itself prove every mesh pixel or restore the runner's user-preference backup.")
            .toString(2))
        ui { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun nativeTabletSystemRestoreAndUndoKeepTheSelectedPointAndWorkspace() {
        assertTrue("This case must execute on a real tablet, rather than skip", compose.activity.resources.configuration.screenWidthDp >= 840)
        chooseMode(AtlasMode.MERIDIANS)
        locatePoint("lu1")
        assertWorkspaceRails()
        exerciseInformationAndDetails()
        captureLayout("native-tablet-workspace")
        toggleLayer("中文名称")
        toggleLayer("脏腑关系线")
        assertFalse(model.state.value.layer.labels)
        assertTrue(model.state.value.layer.internalRelations)
        val before = context("native-before-bone")
        val beforeVisible = actualVisibleIds()
        quickSystem("骨骼")
        assertTrue(model.state.value.layer.bone)
        assertTrue("The renderer must actually enable bone structures", actualVisibleIds().size > beforeVisible.size)
        assertContext(before, "native-system-toggle")
        val changed = context("native-bone-enabled")
        restore()
        assertAnatomy(VisibilityPolicy.defaults(AtlasMode.MERIDIANS))
        assertContext(changed, "native-restore")
        assertFocusEnded()
        compose.onNodeWithContentDescription("一键还原组织").assertIsNotEnabled()
        undo()
        assertAnatomy(changed.state.layer)
        assertContext(changed, "native-undo-restore")
        assertFocusEnded()
        undo()
        assertAnatomy(before.state.layer)
        assertContext(before, "native-undo-system-toggle")
        assertEquals(beforeVisible, actualVisibleIds())
        awaitSavedCurrentLayer("native-restored-baseline")
    }

    @Test fun actualRegionalChipAndMuscleMasterSwitchesUndoInOrder() {
        setCompact(420, 760)
        chooseMode(AtlasMode.MUSCLES)
        val before = context("regional-before")
        val beforeVisible = actualVisibleIds()
        val firstDepth = data().structures.filter { it.system == "muscle" && it.region == "head_neck" }.minOf { it.layer }
        val key = "head_neck:$firstDepth"
        val affected = data().structures.filter { it.system == "muscle" && it.region == "head_neck" && it.layer == firstDepth }
            .map { it.id }.toSet().intersect(beforeVisible)
        assertTrue("The real regional layer must contain visible meshes", affected.isNotEmpty())
        openLayers()
        layerList().performScrollToNode(hasText("头颈"))
        compose.onAllNodes(hasText(depthName(firstDepth)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .onFirst().assertIsDisplayed().performClick()
        compose.waitForIdle()
        back()
        assertEquals(setOf(key), model.state.value.layer.disabledRegionLayers)
        assertEquals(beforeVisible - affected, actualVisibleIds())
        assertContext(before, "regional-layer-disabled")
        val regional = context("regional-retained")
        quickSystem("肌肉")
        assertFalse(model.state.value.layer.muscle)
        assertTrue(actualVisibleIds().isEmpty())
        quickSystem("肌肉")
        assertTrue(model.state.value.layer.muscle)
        assertEquals(setOf(key), model.state.value.layer.disabledRegionLayers)
        assertAnatomy(regional.state.layer)
        undo()
        assertFalse(model.state.value.layer.muscle)
        assertEquals(setOf(key), model.state.value.layer.disabledRegionLayers)
        undo()
        assertAnatomy(regional.state.layer)
        undo()
        assertAnatomy(before.state.layer)
        assertEquals(beforeVisible, actualVisibleIds())
        assertContext(before, "regional-undo-sequence")
        awaitSavedCurrentLayer("regional-baseline")
    }

    @Test fun groupedOrganHideAndIsolationRestoreAllPiecesWithoutMovingCamera() {
        val group = data().structureGroups.entries.single { it.representative.nameEn == "proximal part of ileum" }
        assertTrue("Use the real multi-piece organ group", group.memberIds.size > 1)
        locateStructure(group)
        val beforeHide = context("group-before-hide")
        val beforeVisible = actualVisibleIds()
        assertTrue(beforeVisible.containsAll(group.memberIds))
        openDetails()
        compose.onNode(hasText("隐藏同名组织") and hasClickAction() and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(model.state.value.selectedStructure)
        assertTrue(model.state.value.layer.hiddenIds.containsAll(group.memberIds))
        assertEquals(beforeVisible - group.memberIds, actualVisibleIds())
        assertViewportAndPose(beforeHide, "group-hide")
        val hidden = context("group-hidden")
        undo()
        assertAnatomy(beforeHide.state.layer)
        assertContext(hidden, "group-undo-hide-keeps-current-selection")
        assertEquals(beforeVisible, actualVisibleIds())
        assertFocusEnded()
        awaitSavedCurrentLayer("group-hide-undone-baseline")

        locateStructure(group)
        val beforeIsolate = context("group-before-isolate")
        openDetails()
        compose.onNode(hasText("单独显示整体") and hasClickAction() and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
        compose.waitForIdle()
        awaitStopped()
        assertEquals(group.id, model.state.value.layer.isolateId)
        assertEquals(group.memberIds, actualVisibleIds())
        val isolated = context("group-isolated")
        restore()
        assertAnatomy(VisibilityPolicy.defaults(AtlasMode.OVERVIEW))
        assertContext(isolated, "group-restore-defaults")
        assertTrue(actualVisibleIds().containsAll(group.memberIds))
        assertFocusEnded()
        undo()
        assertAnatomy(isolated.state.layer)
        assertEquals(group.memberIds, actualVisibleIds())
        assertContext(isolated, "group-undo-restore")
        undo()
        assertAnatomy(beforeIsolate.state.layer)
        assertContext(isolated, "group-undo-isolation-keeps-selected-group")
        awaitSavedCurrentLayer("group-isolation-undone-baseline")
    }

    @Test fun halfBodyPresetsUndoAsOneStepAndRetainSideRouteAndAnnotationControls() {
        chooseMode(AtlasMode.MERIDIANS)
        locatePoint("gb23")
        choosePointSide("右侧")
        toggleLayer("中文名称")
        toggleLayer("脏腑关系线")
        val before = context("half-before")
        assertTrue("GB" in before.state.effectiveMeridians)
        for ((title, side) in listOf("左半身透视" to CutawaySide.LEFT, "右半身透视" to CutawaySide.RIGHT)) {
            quickTool(title)
            assertEquals(side, model.state.value.layer.cutawaySide)
            assertTrue(model.state.value.layer.skin && model.state.value.layer.organ && model.state.value.layer.muscle)
            assertContext(before, "half-$side")
            assertSelectedInstance("gb23", 1)
            actualVisibleIds()
            val half = context("half-$side-enabled")
            restore()
            assertAnatomy(VisibilityPolicy.defaults(AtlasMode.MERIDIANS))
            assertContext(half, "half-$side-restored")
            assertFocusEnded()
            undo()
            assertAnatomy(half.state.layer)
            assertContext(half, "half-$side-undo-restore")
            assertSelectedInstance("gb23", 1)
            undo()
            assertAnatomy(before.state.layer)
            assertContext(before, "half-$side-undo-preset")
            assertSelectedInstance("gb23", 1)
        }
        awaitSavedCurrentLayer("half-baseline")
    }

    @Test fun modeHistoriesAreIndependentAndUndoEndsTemporaryOralFocusBeforeSaving() {
        quickSystem("骨骼")
        val overview = AnatomyVisibilitySnapshot.capture(model.state.value.layer)
        assertFalse(model.state.value.layer.bone)
        chooseMode(AtlasMode.MUSCLES)
        quickSystem("骨骼")
        val muscles = AnatomyVisibilitySnapshot.capture(model.state.value.layer)
        assertTrue(model.state.value.layer.bone)
        chooseMode(AtlasMode.OVERVIEW)
        assertEquals(overview, AnatomyVisibilitySnapshot.capture(model.state.value.layer))
        undo()
        assertTrue(model.state.value.layer.bone)
        assertEquals(muscles, AnatomyVisibilitySnapshot.capture(model.state.value.layers.getValue(AtlasMode.MUSCLES)))
        chooseMode(AtlasMode.MUSCLES)
        undo()
        assertFalse(model.state.value.layer.bone)
        assertTrue(model.state.value.layers.getValue(AtlasMode.OVERVIEW).bone)

        chooseMode(AtlasMode.MERIDIANS)
        val baseline = model.state.value.layer
        awaitSavedCurrentLayer("oral-before-focus")
        val oral = data().points.single { it.nameZh == "金津玉液" }
        locatePoint(oral.id)
        assertTrue(model.state.value.layer.focusIds.isNotEmpty())
        assertFalse(model.state.value.layer.skin)
        assertTrue(model.state.value.canRestore)
        val focused = context("oral-temporary-focus")
        awaitSavedLayer(AtlasMode.MERIDIANS, baseline, "oral-temporary-saves-original-baseline")
        undo()
        assertAnatomy(baseline)
        assertContext(focused, "oral-undo-keeps-selection-and-local-camera")
        assertFocusEnded()
        assertSelectedInstance(oral.id, 0)
        awaitSavedCurrentLayer("oral-undo-persists-current-baseline")
        compose.onNodeWithContentDescription("一键还原组织").assertIsNotEnabled()
    }

    @Test fun compactRealWorkspaceToolbarSearchInformationDetailsAndRestoreKeepViewport() {
        setCompact(320, 568)
        chooseMode(AtlasMode.MERIDIANS)
        locatePoint("lu1")
        assertWorkspaceRails()
        captureLayout("compact-320x568-workspace")
        exerciseInformationAndDetails()
        val before = context("compact-before-organ")
        quickSystem("内脏")
        assertTrue(model.state.value.layer.organ)
        val changed = context("compact-organ-enabled")
        assertContext(before, "compact-system-toggle")
        restore()
        assertAnatomy(VisibilityPolicy.defaults(AtlasMode.MERIDIANS))
        assertContext(changed, "compact-restore")
        compose.onNodeWithContentDescription("一键还原组织").assertIsNotEnabled()
        undo()
        assertAnatomy(changed.state.layer)
        assertContext(changed, "compact-undo-restore")
        undo()
        assertAnatomy(before.state.layer)
        assertContext(before, "compact-undo-organ-toggle")
        assertWorkspaceRails()
        awaitSavedCurrentLayer("compact-baseline")
    }

    private fun chooseMode(mode: AtlasMode) {
        compose.onAllNodes(hasText(mode.title) and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
        assertEquals(mode, model.state.value.mode)
        actualVisibleIds()
    }

    private fun locatePoint(id: String) {
        val point = data().points.single { it.id == id }
        openCatalogueIfNeeded(compose)
        compose.onNodeWithContentDescription("搜索经络与穴位").performTextReplacement(point.codes.first())
        compose.onNodeWithContentDescription("定位${point.nameZh}").assertIsDisplayed().performClick()
        compose.waitForIdle()
        awaitStopped()
        assertEquals(id, model.state.value.selectedPoint)
        assertSelectedInstance(id, 0)
    }

    private fun locateStructure(group: StructureGroup) {
        openCatalogueIfNeeded(compose)
        compose.onNodeWithContentDescription("搜索解剖结构").performTextReplacement(group.id)
        compose.onAllNodes(hasText(group.representative.nameZh) and hasText(group.representative.nameEn) and hasClickAction())
            .onLast().assertIsDisplayed().performClick()
        compose.waitForIdle()
        awaitStopped()
        assertEquals(group.id, model.state.value.selectedStructure)
        actualVisibleIds()
    }

    private fun choosePointSide(label: String) {
        openDetails()
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick().assertIsSelected()
        compose.waitForIdle()
        awaitStopped()
        back()
    }

    private fun quickSystem(name: String) {
        compose.onNodeWithContentDescription("显示$name").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        actualVisibleIds()
    }
    private fun quickTool(title: String) {
        compose.onAllNodes(hasText(title) and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        awaitStopped()
        actualVisibleIds()
    }
    private fun undo() {
        compose.onNodeWithContentDescription("撤销上一步显示操作").assertIsEnabled().performClick()
        compose.waitForIdle()
        actualVisibleIds()
    }
    private fun restore() {
        compose.onNodeWithContentDescription("一键还原组织").assertIsEnabled().performClick()
        compose.waitForIdle()
        actualVisibleIds()
    }
    private fun openLayers() { compose.onNodeWithContentDescription("图层控制").performClick(); compose.waitForIdle() }
    private fun layerList() = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
    private fun toggleLayer(title: String) {
        openLayers()
        layerList().performScrollToNode(hasText(title))
        compose.onNode(hasText(title) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        back()
    }
    private fun openDetails() {
        compose.onNodeWithContentDescription("所选项目说明").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }
    private fun exerciseInformationAndDetails() {
        val before = context("toolbar-before-modals")
        compose.onNodeWithContentDescription("关于与数据来源").assertIsDisplayed().performClick()
        compose.onNodeWithText("关于人体研究").assertIsDisplayed()
        assertContext(before, "toolbar-information-open")
        back()
        assertContext(before, "toolbar-information-dismissed")
        openDetails()
        compose.onNodeWithText("功能与传统用途（学习）").performScrollTo().assertIsDisplayed()
        assertContext(before, "toolbar-details-open")
        back()
        assertContext(before, "toolbar-details-dismissed")
    }

    private fun assertWorkspaceRails() {
        compose.onNodeWithTag("workspace-toolbar").assertDoesNotExist()
        compose.onNodeWithContentDescription("复位").assertDoesNotExist()
        val viewport = ui { viewportOnUi() }
        val tolerance = 1.5f * density
        fun screen(node: SemanticsNode): RectF {
            val location = node.positionOnScreen
            return RectF(location.x, location.y, location.x + node.size.width, location.y + node.size.height)
        }
        fun fits(outer: RectF, inner: RectF) =
            inner.left >= outer.left - tolerance && inner.right <= outer.right + tolerance &&
                inner.top >= outer.top - tolerance && inner.bottom <= outer.bottom + tolerance
        fun contained(outer: RectF, inner: RectF, message: String) = assertTrue(message, fits(outer, inner))
        fun json(bounds: RectF) = JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
        val leftRail = screen(compose.onNodeWithTag("view-controls").assertIsDisplayed().fetchSemanticsNode())
        val rightRail = screen(compose.onNodeWithTag("workspace-actions").assertIsDisplayed().fetchSemanticsNode())
        val modelBounds = RectF(viewport)
        contained(modelBounds, leftRail, "The direction rail must fit inside the actual model viewport")
        contained(modelBounds, rightRail, "The workspace actions must fit inside the actual model viewport")
        assertTrue("Directions must stay on the left side", leftRail.centerX() < modelBounds.centerX())
        assertTrue("Workspace actions must stay on the right side", rightRail.centerX() > modelBounds.centerX())
        assertTrue("The two rails must not overlap", leftRail.right <= rightRail.left + tolerance)
        val directionActions = listOf("正", "背", "左", "右").map { title ->
            compose.onNode(hasText(title) and hasClickAction() and hasAnyAncestor(hasTestTag("view-controls")))
                .assertHasClickAction()
        }
        val hasCatalogue = compose.onAllNodesWithContentDescription("图谱目录").fetchSemanticsNodes().isNotEmpty()
        val descriptions = listOf("关于与数据来源") + (if (hasCatalogue) listOf("图谱目录") else emptyList()) +
            listOf("撤销上一步显示操作", "一键还原组织")
        val workspaceActions = descriptions.map { description ->
            compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("workspace-actions")))
                .assertHasClickAction()
        }
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("view-controls"))).assertCountEquals(4)
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("workspace-actions"))).assertCountEquals(descriptions.size)
        // Record the un-clipped layout before scrolling either rail. Short viewports
        // may require scrolling to an action; every item must still be reachable.
        val directions = directionActions.map { screen(it.fetchSemanticsNode()) }
        val actions = workspaceActions.map { screen(it.fetchSemanticsNode()) }
        listOf(directions, actions).forEach { rail ->
            rail.zipWithNext().forEach { (above, below) ->
                assertTrue("Rail targets must follow their intended top-to-bottom order without overlap", above.bottom <= below.top + tolerance)
            }
        }
        listOf(leftRail to directionActions, rightRail to workspaceActions).forEach { (rail, targets) ->
            targets.forEach { target ->
                if (!fits(rail, screen(target.fetchSemanticsNode()))) target.performScrollTo()
                target.assertIsDisplayed()
                contained(rail, screen(target.fetchSemanticsNode()), "Every rail action must be reachable inside the actual viewport")
            }
            if (!fits(rail, screen(targets.first().fetchSemanticsNode()))) targets.first().performScrollTo()
        }
        val fixture = screen(compose.onNodeWithTag("visibility-workspace-fixture").fetchSemanticsNode())
        val topInset = ui {
            checkNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
                .getInsets(WindowInsetsCompat.Type.systemBars()).top
        }
        assertEquals("The model viewport must start directly after the system bar, without a separate toolbar",
            fixture.top + topInset, viewport.top.toFloat(), tolerance)
        // The optional selection explanation is a separate lower control, outside the right rail.
        compose.onNodeWithContentDescription("所选项目说明").assertIsDisplayed().assertHasClickAction()
        observations.put(JSONObject().put("stage", "workspace-rails-layout")
            .put("viewportScreen", json(modelBounds)).put("fixtureScreen", json(fixture)).put("systemTopInsetPx", topInset)
            .put("leftRailScreen", json(leftRail)).put("rightRailScreen", json(rightRail))
            .put("directionTargets", JSONArray(directions.map(::json)))
            .put("actionDescriptions", JSONArray(descriptions)).put("actionTargets", JSONArray(actions.map(::json))))
    }

    private fun setCompact(width: Int, height: Int) {
        ui { requestedSize = width to height }
        compose.waitForIdle()
        assertSame(viewer, ui { singleSurface() })
        val fixture = compose.onNodeWithTag("visibility-workspace-fixture").fetchSemanticsNode().boundsInWindow
        assertEquals(width * density, fixture.width, 1.5f)
        assertEquals(height * density, fixture.height, 1.5f)
        assertEquals(width * density, ui { viewer.width.toFloat() }, 1.5f)
    }

    private fun context(stage: String): Context {
        compose.waitForIdle()
        val visible = actualVisibleIds()
        val value = ui { Context(model.state.value, viewportOnUi(), poseOnUi()) }
        observations.put(JSONObject().put("stage", stage).put("mode", value.state.mode.name)
            .put("selectedPoint", value.state.selectedPoint).put("selectedStructure", value.state.selectedStructure)
            .put("routes", JSONArray(value.state.enabledMeridians.toList())).put("effectiveRoutes", JSONArray(value.state.effectiveMeridians.toList()))
            .put("anatomy", anatomyJson(value.state.layer)).put("visibleStructureCount", visible.size)
            .put("canUndo", value.state.canUndoVisibility).put("canRestoreFocus", value.state.canRestore)
            .put("viewportScreen", JSONArray(listOf(value.viewport.left, value.viewport.top, value.viewport.right, value.viewport.bottom)))
            .put("pose", poseJson(value.pose)))
        return value
    }

    private fun assertContext(expected: Context, stage: String) {
        val current = context(stage)
        assertEquals("$stage mode", expected.state.mode, current.state.mode)
        assertEquals("$stage point selection", expected.state.selectedPoint, current.state.selectedPoint)
        assertEquals("$stage structure selection", expected.state.selectedStructure, current.state.selectedStructure)
        assertEquals("$stage route choices", expected.state.enabledMeridians, current.state.enabledMeridians)
        assertEquals("$stage effective routes", expected.state.effectiveMeridians, current.state.effectiveMeridians)
        assertEquals("$stage selected route", expected.state.selectedMeridian, current.state.selectedMeridian)
        assertEquals("$stage active route", expected.state.activeMeridian, current.state.activeMeridian)
        assertEquals("$stage annotations and fascia controls", AnatomyVisibilitySnapshot.capture(current.state.layer).applyTo(expected.state.layer), current.state.layer)
        assertViewportAndPose(expected, stage)
    }
    private fun assertViewportAndPose(expected: Context, stage: String) {
        compose.waitForIdle()
        assertSame("$stage must keep one actual loaded SurfaceView", viewer, ui { singleSurface() })
        val actual = ui { Context(model.state.value, viewportOnUi(), poseOnUi()) }
        assertEquals("$stage viewport", expected.viewport, actual.viewport)
        assertEquals("$stage target x", expected.pose.target.x, actual.pose.target.x, .00001f)
        assertEquals("$stage target y", expected.pose.target.y, actual.pose.target.y, .00001f)
        assertEquals("$stage target z", expected.pose.target.z, actual.pose.target.z, .00001f)
        assertEquals("$stage yaw", expected.pose.yaw, actual.pose.yaw, .00001f)
        assertEquals("$stage pitch", expected.pose.pitch, actual.pose.pitch, .00001f)
        assertEquals("$stage zoom", expected.pose.distance, actual.pose.distance, .00001f)
        assertFalse("$stage must not start another camera flight", ui { viewer.isCameraMoving })
    }
    private fun assertAnatomy(expected: LayerState) {
        assertEquals(AnatomyVisibilitySnapshot.capture(expected), AnatomyVisibilitySnapshot.capture(model.state.value.layer))
    }
    private fun assertFocusEnded() {
        val mode = model.state.value.mode
        assertFalse(model.state.value.canRestore)
        val snapshots = AtlasViewModel::class.java.getDeclaredField("focusSnapshots").apply { isAccessible = true }
        assertFalse("The actual stale layer baseline must be consumed", ui { (snapshots.get(model) as Map<*, *>).containsKey(mode) })
        assertFalse("The actual old return-camera bookmark must be discarded", ui { field<Map<*, *>>("cameraBookmarks").containsKey(mode.name) })
        compose.onNodeWithContentDescription("恢复定位前显示").assertDoesNotExist()
    }
    private fun assertSelectedInstance(id: String, index: Int) {
        awaitNative("The renderer must retain the actual selected raw instance") {
            "point:$id|$index" in field<Set<String>>("priorityAnchorIds") &&
                field<List<ProjectedAnchor>>("currentProjections").any { it.id == "point:$id|$index" }
        }
    }
    private fun actualVisibleIds(): Set<String> {
        compose.waitForIdle()
        val value = model.state.value
        val expected = VisibilityPolicy.visibleIds(data().structures, value.layer, data().structureGroups)
        awaitNative("Compose visibility must reach the actual renderer") { field<Set<String>?>("visibleIds") == expected }
        return ui { field<Set<String>>("visibleIds").toSet() }
    }

    private fun awaitSavedCurrentLayer(stage: String) {
        val current = model.state.value
        awaitSavedLayer(current.mode, current.layer, stage, current.enabledMeridians)
    }
    private fun awaitSavedLayer(mode: AtlasMode, expected: LayerState, stage: String, expectedRoutes: Set<String>? = null) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            val saved = runBlocking { withTimeout(5_000) { AtlasPreferences(instrumentation.targetContext).read() } }
            if (saved != null && saved.states[mode] == expected.copy(focusIds = emptySet()) && (expectedRoutes == null || saved.meridians == expectedRoutes)) {
                observations.put(JSONObject().put("stage", stage).put("dataStoreReadbackMatches", true)
                    .put("savedRoutes", JSONArray(saved.meridians.toList())).put("savedAnatomy", anatomyJson(saved.states.getValue(mode))))
                return
            }
            assertTrue("$stage: debounced DataStore layer must match the intended stable baseline", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(40)
        }
    }

    private fun captureLayout(name: String) {
        compose.waitForIdle()
        assertTrue("Capture only the focused application", ui { compose.activity.hasWindowFocus() })
        assertEquals(instrumentation.targetContext.packageName,
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue("The application must remain foreground during capture", ui { compose.activity.hasWindowFocus() })
            val folder = File(instrumentation.targetContext.filesDir, "qa/visibility-restore").apply { mkdirs() }
            val file = File(folder, "$name-$runToken.png")
            file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            observations.put(JSONObject().put("stage", name).put("screenshotFile", file.name).put("bytes", file.length()))
        } finally { bitmap.recycle() }
    }

    private fun awaitStopped() {
        compose.waitForIdle()
        awaitNative("The real camera must settle before acceptance", 30_000) { !viewer.isCameraMoving }
        compose.waitForIdle()
    }
    private fun awaitNative(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!ui(condition)) {
            assertTrue(message, SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(12)
        }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun data() = checkNotNull(model.state.value.data)
    private fun depthName(depth: Int) = when (depth) { 1 -> "浅层"; 2 -> "中层"; 3 -> "深层"; else -> "第 $depth 层" }
    private fun singleSurface(): AnatomySurfaceView {
        val result = mutableListOf<AnatomySurfaceView>()
        fun visit(view: View) {
            if (view is AnatomySurfaceView && view.isShown) result += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(compose.activity.window.decorView)
        assertEquals("Use exactly one real renderer", 1, result.size)
        return result.single()
    }
    private fun viewportOnUi(): Rect {
        val p = IntArray(2); viewer.getLocationOnScreen(p)
        return Rect(p[0], p[1], p[0] + viewer.width, p[1] + viewer.height)
    }
    private fun poseOnUi(): Pose {
        val value = field<Any>("pose")
        fun number(name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(value)
        return Pose(value.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(value) as Vec3,
            number("yaw"), number("pitch"), number("distance"))
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = AnatomySurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(viewer) as T
    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }
    private fun poseJson(value: Pose) = JSONObject().put("target", JSONArray(listOf(value.target.x, value.target.y, value.target.z)))
        .put("yaw", value.yaw).put("pitch", value.pitch).put("distance", value.distance)
    private fun anatomyJson(layer: LayerState) = JSONObject().put("skin", layer.skin).put("muscle", layer.muscle)
        .put("organ", layer.organ).put("bone", layer.bone).put("connective", layer.connective).put("spaces", layer.spaces)
        .put("disabledRegions", JSONArray(layer.disabledRegionLayers.toList())).put("hiddenIds", JSONArray(layer.hiddenIds.toList()))
        .put("isolateId", layer.isolateId).put("focusIds", JSONArray(layer.focusIds.toList())).put("cutaway", layer.cutawaySide.name)
}
