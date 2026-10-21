package com.zga.meridianatlas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.data.AtlasPreferences
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/** Runs against the matching debug APK. No device configuration is changed. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ResponsiveUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasViewModel

    @Before fun ready() {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.data)
        compose.runOnUiThread {
            AtlasMode.entries.forEach { mode -> model.mode(mode); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.OVERVIEW)
            val quality = InstrumentationRegistry.getArguments().getString("soak_quality")
            model.quality(if (quality == "standard") ModelQuality.STANDARD else ModelQuality.LOW)
        }
    }

    private fun content(compact: Boolean) {
        compose.runOnUiThread {
            compose.activity.setContent {
                if (compact) Box(Modifier.sizeIn(maxWidth = 420.dp, maxHeight = 760.dp).fillMaxSize()) {
                    MeridianAtlasApp(model)
                } else MeridianAtlasApp(model)
            }
        }
        awaitModel()
    }

    private fun awaitModel() {
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
    }

    private fun clickText(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onLast().performClick()
    }
    private fun mode(mode: AtlasMode) { clickText(mode.title); compose.waitForIdle() }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun layers() { compose.onNodeWithContentDescription("图层控制").performClick(); compose.waitForIdle() }
    private fun layerText(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasText(text))
        return compose.onAllNodes(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).onLast()
    }
    private fun search(query: String) {
        compose.onNodeWithContentDescription("搜索图谱").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(query)
        compose.waitForIdle()
    }
    private fun assertState(check: () -> Unit) { compose.runOnIdle { check() } }

    private fun quickTools(vararg names: String) {
        names.forEach { name -> compose.onAllNodes(hasText(name) and hasClickAction()).onFirst().assertExists() }
    }
    private fun clickQuickTool(name: String) {
        compose.onAllNodes(hasText(name) and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun modelSurface(): AnatomySurfaceView {
        var result: AnatomySurfaceView? = null
        compose.runOnUiThread {
            fun find(view: View): AnatomySurfaceView? {
                if (view is AnatomySurfaceView && view.isShown) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            result = find(compose.activity.window.decorView)
        }
        return checkNotNull(result) { "No visible AnatomySurfaceView" }
    }

    private fun surfaceScreenBounds(surface: SurfaceView): Rect {
        val result = Rect()
        compose.runOnUiThread {
            val location = IntArray(2)
            surface.getLocationOnScreen(location)
            check(surface.width > 0 && surface.height > 0) { "Model SurfaceView has no laid-out size" }
            result.set(location[0], location[1], location[0] + surface.width, location[1] + surface.height)
        }
        return result
    }

    private fun assertSurfaceUnchanged(surface: AnatomySurfaceView, expected: Rect, stage: String) {
        compose.waitForIdle()
        assertSame("$stage must retain the actual model SurfaceView", surface, modelSurface())
        assertEquals("$stage must not resize or move the model viewport", expected, surfaceScreenBounds(surface))
    }

    private fun selectionOverlayKeepsSurfaceBounds(compact: Boolean) {
        content(compact)
        mode(AtlasMode.MERIDIANS)
        val surface = modelSurface()
        val bounds = surfaceScreenBounds(surface)
        assertState { assertNull(model.state.value.selectedPoint) }

        compose.runOnUiThread { model.selectPoint("lu1", reveal = false) }
        compose.onNodeWithContentDescription("查看完整说明").assertIsDisplayed()
        assertSurfaceUnchanged(surface, bounds, "Selected point's floating card")

        compose.runOnUiThread { model.layer { it.copy(details = false) } }
        compose.onNodeWithContentDescription("查看完整说明").assertDoesNotExist()
        assertSurfaceUnchanged(surface, bounds, "Disabled Chinese description")
        compose.runOnUiThread { model.layer { it.copy(details = true) } }
        compose.onNodeWithContentDescription("查看完整说明").assertIsDisplayed()
        assertSurfaceUnchanged(surface, bounds, "Re-enabled Chinese description")

        compose.onNodeWithContentDescription("查看完整说明").performClick()
        compose.onNodeWithText("功能与传统用途（学习）").performScrollTo().assertIsDisplayed()
        // A modal can cover the SurfaceView without changing its actual layout. Deliberately
        // use getLocationOnScreen + width/height, not a clipped getGlobalVisibleRect.
        assertSurfaceUnchanged(surface, bounds, "Full-description modal")
        back()
        compose.onNodeWithContentDescription("查看完整说明").assertIsDisplayed()
        assertSurfaceUnchanged(surface, bounds, "Dismissed full-description modal")

        compose.onNodeWithContentDescription("取消选中").performClick()
        assertState { assertNull(model.state.value.selectedPoint) }
        compose.onNodeWithContentDescription("查看完整说明").assertDoesNotExist()
        assertSurfaceUnchanged(surface, bounds, "Cleared selection")
    }

    @Test fun compactSelectionCardAndFullDetailsKeepTheActualModelViewportStable() {
        selectionOverlayKeepsSurfaceBounds(compact = true)
    }

    @Test fun nativeTabletSelectionCardAndFullDetailsKeepTheActualModelViewportStable() {
        assumeTrue("This case requires the device's native tablet width", compose.activity.resources.configuration.screenWidthDp >= 840)
        selectionOverlayKeepsSurfaceBounds(compact = false)
    }

    @Test fun internalRelationsQuickChipKeepsRouteChoicesAndExplainsEmptySelection() {
        content(compact = true)
        mode(AtlasMode.MERIDIANS)
        compose.runOnUiThread {
            model.state.value.enabledMeridians.toList().forEach(model::toggleMeridian)
            model.clearSelection()
            model.layer { it.copy(lines = false, internalRelations = false) }
        }
        clickQuickTool("脏腑关系线")
        assertState {
            assertTrue(model.state.value.layer.internalRelations)
            assertTrue(model.state.value.layer.lines)
            assertTrue("Relations must not silently enable any route", model.state.value.enabledMeridians.isEmpty())
        }
        compose.onNodeWithText("先勾选经络", substring = true).assertIsDisplayed()
        compose.onNodeWithText("虚线为脏腑关系示意，非精确体内走行", substring = true).assertIsDisplayed()

        val route = model.state.value.data!!.meridians.first()
        val checkbox = "显示${route.nameZh}路线"
        compose.onNodeWithContentDescription("图谱目录").performClick()
        compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).onLast()
            .performScrollToNode(hasContentDescription(checkbox))
        compose.onNodeWithContentDescription(checkbox).performClick()
        back()
        assertState {
            assertEquals(setOf(route.id), model.state.value.enabledMeridians)
            assertTrue(model.state.value.layer.internalRelations)
            assertTrue(model.state.value.layer.lines)
        }
        clickQuickTool("脏腑关系线")
        assertState {
            assertFalse(model.state.value.layer.internalRelations)
            assertTrue("Turning off relations must keep surface routes enabled", model.state.value.layer.lines)
            assertEquals(setOf(route.id), model.state.value.enabledMeridians)
        }
    }

    @Test fun compactModesExposeDistinctToolsAndUnifiedPointCatalogue() {
        content(compact = true)
        quickTools("体表", "内脏透视", "骨骼")
        compose.onNodeWithContentDescription("图谱目录").performClick()
        compose.onNodeWithText("解剖目录").assertIsDisplayed()
        compose.onNode(hasText("全部") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("肌肉目录").assertDoesNotExist()
        compose.onNodeWithText("经络与穴位").assertDoesNotExist()
        back()
        compose.onNodeWithText("全部肌肉").assertDoesNotExist()
        assertState { assertFalse(model.state.value.layer.lines); assertFalse(model.state.value.layer.points) }
        mode(AtlasMode.MUSCLES)
        quickTools("全部肌肉", "浅层", "中层", "深层", "肌肉", "骨骼", "内脏")
        compose.onNodeWithContentDescription("图谱目录").performClick()
        compose.onNodeWithText("肌肉目录").assertIsDisplayed()
        compose.onNodeWithText("解剖目录").assertDoesNotExist()
        compose.onNodeWithText("经络与穴位").assertDoesNotExist()
        back()
        compose.onNodeWithText("内脏透视").assertDoesNotExist()
        assertState {
            assertTrue(model.state.value.layer.muscle)
            assertFalse(model.state.value.layer.skin)
            assertFalse(model.state.value.layer.organ)
            assertFalse(model.state.value.layer.bone)
            assertFalse(model.state.value.layer.lines)
            assertFalse(model.state.value.layer.points)
        }
        mode(AtlasMode.MERIDIANS)
        quickTools("经络线", "穴位标记", "中文名称", "皮肤", "肌肉", "骨骼", "内脏", "脏腑关系线", "内脏透视", "左半身透视", "右半身透视")
        compose.onNodeWithText("全部肌肉").assertDoesNotExist()
        compose.onNodeWithContentDescription("图谱目录").performClick()
        compose.onNodeWithText("经络与穴位").assertIsDisplayed()
        compose.onNodeWithText("经脉").assertDoesNotExist()
        compose.onNodeWithText("穴位").assertDoesNotExist()
        compose.onNodeWithText("全部").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索经络与穴位").assertExists()
        val route = model.state.value.data!!.meridians.first()
        val list = compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).onLast()
        val routeHeader = hasContentDescription("展开${route.nameZh}") or hasContentDescription("收起${route.nameZh}")
        list.performScrollToNode(routeHeader)
        // Expanding a directory group must not silently enable its route checkbox.
        if (route.id in model.state.value.enabledMeridians) {
            compose.onNodeWithContentDescription("显示${route.nameZh}路线").performClick()
        }
        val routesBeforeExpand = model.state.value.enabledMeridians
        if (compose.onAllNodesWithContentDescription("收起${route.nameZh}").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("收起${route.nameZh}").performClick()
        }
        compose.onNodeWithContentDescription("展开${route.nameZh}").performClick()
        compose.onNodeWithContentDescription("收起${route.nameZh}").assertIsDisplayed()
        assertState { assertEquals(routesBeforeExpand, model.state.value.enabledMeridians) }
        val routePoint = model.state.value.data!!.points.first { it.id in route.pointIds || it.meridianId == route.id }
        list.performScrollToNode(hasContentDescription("定位${routePoint.nameZh}"))
        compose.onNodeWithContentDescription("定位${routePoint.nameZh}").assertIsDisplayed()
        compose.onNode(hasText("经外奇穴") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        val oral = model.state.value.data!!.points.single { it.nameZh == "金津玉液" }
        list.performScrollToNode(hasContentDescription("展开${oral.region}奇穴"))
        compose.onNodeWithContentDescription("展开${oral.region}奇穴").performClick()
        list.performScrollToNode(hasContentDescription("定位金津玉液"))
        compose.onNodeWithContentDescription("定位金津玉液").assertIsDisplayed()
        // Category is a browsing choice, never a search restriction.
        compose.onNode(hasSetTextAction()).performTextReplacement("LU1")
        compose.onAllNodesWithContentDescription("定位中府").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位中府").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("中府")
        compose.onAllNodesWithContentDescription("定位中府").assertCountEquals(1)
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNode(hasText("经络路线") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("EXHN12")
        compose.onAllNodesWithContentDescription("定位金津玉液").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位金津玉液").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("fuai")
        compose.onAllNodesWithContentDescription("定位腹哀").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位腹哀").assertIsDisplayed()
    }

    @Test fun compactModesMuscleMasterRegionLayersAndChineseControls() {
        content(compact = true)
        compose.onNodeWithContentDescription("图谱目录").assertExists()
        mode(AtlasMode.MUSCLES)
        layers()
        val firstDepth = model.state.value.data!!.structures.filter {
            it.system == "muscle" && it.region == "head_neck"
        }.minOf { it.layer }
        val depthLabel = when (firstDepth) { 1 -> "浅层"; 2 -> "中层"; 3 -> "深层"; else -> "第 $firstDepth 层" }
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasText("头颈"))
        compose.onAllNodes(hasText(depthLabel) and hasClickAction() and hasAnyAncestor(isDialog())).onFirst().performClick()
        val key = "head_neck:$firstDepth"
        assertState { assertTrue(key in model.state.value.layer.disabledRegionLayers) }
        layerText("肌肉总开关").performClick()
        assertState { assertFalse(model.state.value.layer.muscle) }
        layerText("肌肉总开关").performClick()
        assertState { assertTrue(model.state.value.layer.muscle); assertTrue(key in model.state.value.layer.disabledRegionLayers) }
        layerText("中文名称").performClick()
        assertState { assertFalse(model.state.value.layer.labels) }
        layerText("中文名称").performClick()
        assertState { assertTrue(model.state.value.layer.labels) }
        layerText("说明面板").performClick()
        assertState { assertFalse(model.state.value.layer.details) }
        layerText("说明面板").performClick()
        assertState { assertTrue(model.state.value.layer.details) }
        back()
        mode(AtlasMode.OVERVIEW)
        assertState { assertTrue(model.state.value.layer.disabledRegionLayers.isEmpty()) }
        mode(AtlasMode.MUSCLES)
        assertState { assertTrue(key in model.state.value.layer.disabledRegionLayers) }
        mode(AtlasMode.MERIDIANS)
        assertState { assertFalse(model.state.value.layer.muscle); assertTrue(model.state.value.layer.points) }
    }

    @Test fun compactSearchOralSidesMultiplePointsAndRestore() {
        content(compact = true)
        mode(AtlasMode.MERIDIANS)
        val before = model.state.value.layer
        val routes = model.state.value.enabledMeridians
        search("fuai")
        compose.onNodeWithText("经络与穴位").assertIsDisplayed()
        compose.onNodeWithText("经脉").assertDoesNotExist()
        compose.onNodeWithText("穴位").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("定位腹哀").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位腹哀").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("EXHN12")
        compose.onAllNodesWithContentDescription("定位金津玉液").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位金津玉液").assertIsDisplayed().performClick()
        assertState {
            assertEquals("金津玉液", model.state.value.data!!.points.single { it.id == model.state.value.selectedPoint }.nameZh)
            assertTrue(model.state.value.layer.focusIds.isNotEmpty())
            assertFalse(model.state.value.layer.skin)
            assertTrue(model.state.value.canRestore)
        }
        // The oral focus is temporary; persistence must keep the pre-focus mode layer.
        SystemClock.sleep(700)
        val saved = runBlocking { AtlasPreferences(instrumentation.targetContext).read() }
        assertEquals(before, saved?.states?.get(AtlasMode.MERIDIANS))
        assertState { assertTrue(model.state.value.layer.focusIds.isNotEmpty()) }
        compose.onNodeWithContentDescription("查看完整说明").performClick()
        compose.onNodeWithText("右侧", useUnmergedTree = false).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("左侧", useUnmergedTree = false).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("教学示意 · 三维定位待校对").performScrollTo().assertIsDisplayed()
        back()
        compose.onNodeWithContentDescription("恢复定位前显示").performClick()
        assertState { assertEquals(before, model.state.value.layer); assertEquals(routes, model.state.value.enabledMeridians); assertNull(model.state.value.selectedPoint) }
        search("夹脊")
        compose.onAllNodesWithContentDescription("定位夹脊").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位夹脊").performClick()
        compose.onNodeWithContentDescription("查看完整说明").performClick()
        val spine = model.state.value.data!!.points.single { it.nameZh == "夹脊" }
        val rightLast = spine.instances.single { it.side == "right" && it.ordinal == 17 }.labelZh ?: "右侧 17"
        val leftLast = spine.instances.single { it.side == "left" && it.ordinal == 17 }.labelZh ?: "左侧 17"
        compose.onNodeWithText(rightLast).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText(leftLast).performScrollTo().performClick().assertIsSelected()
        back()
        compose.onNodeWithContentDescription("恢复定位前显示").performClick()
        assertState { assertEquals(before, model.state.value.layer); assertFalse(model.state.value.canRestore) }
    }

    @Test fun nativeTabletKeepsCatalogueAndSearchAvailable() {
        assumeTrue("This case requires the device's native tablet width", compose.activity.resources.configuration.screenWidthDp >= 840)
        content(compact = false)
        compose.onNodeWithContentDescription("图谱目录").assertDoesNotExist()
        compose.onNodeWithText("解剖目录").assertIsDisplayed()
        quickTools("体表", "内脏透视", "骨骼")
        mode(AtlasMode.MUSCLES)
        compose.onNodeWithText("肌肉目录").assertIsDisplayed()
        compose.onNodeWithText("解剖目录").assertDoesNotExist()
        quickTools("全部肌肉", "浅层", "中层", "深层", "肌肉", "骨骼", "内脏")
        assertState { assertEquals(AtlasMode.MUSCLES, model.state.value.mode); assertFalse(model.state.value.layer.bone) }
        // Each mode now owns its catalogue; acupoints are searched in MERIDIANS.
        mode(AtlasMode.MERIDIANS)
        compose.onNodeWithText("经络与穴位").assertIsDisplayed()
        compose.onNodeWithText("肌肉目录").assertDoesNotExist()
        compose.onNodeWithText("经脉").assertDoesNotExist()
        compose.onNodeWithText("穴位").assertDoesNotExist()
        quickTools("经络线", "穴位标记", "中文名称", "皮肤", "肌肉", "骨骼", "内脏", "脏腑关系线", "内脏透视", "左半身透视", "右半身透视")
        search("fuai")
        compose.onAllNodesWithContentDescription("定位腹哀").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位腹哀").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("EXHN12")
        compose.onAllNodesWithContentDescription("定位金津玉液").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位金津玉液").assertIsDisplayed().performClick()
        assertState { assertTrue(model.state.value.layer.focusIds.isNotEmpty()) }
        compose.onNodeWithContentDescription("恢复定位前显示").performClick()
        assertState { assertFalse(model.state.value.layer.focusIds.isNotEmpty()) }
    }

    @Test fun focusSnapshotsRestoreIndependentlyForOverviewAndMuscles() {
        content(compact = true)
        val muscles = model.state.value.data!!.structures.filter { it.system == "muscle" }.take(2)
        assertEquals(2, muscles.size)
        val overview = model.state.value.layer
        val routeIds = model.state.value.data!!.meridians.take(2).map { it.id }
        assertEquals(2, routeIds.size)
        compose.runOnUiThread {
            model.revealStructure(muscles[0].id)
            model.mode(AtlasMode.MERIDIANS)
            model.toggleMeridian(routeIds[0])
            model.mode(AtlasMode.MUSCLES)
        }
        val routesAfterFirstChoice = model.state.value.enabledMeridians
        val muscleLayer = model.state.value.layer
        compose.runOnUiThread { model.revealStructure(muscles[1].id); model.mode(AtlasMode.OVERVIEW) }
        assertState { assertTrue(model.state.value.canRestore); assertEquals(muscles[0].id, model.state.value.layer.isolateId) }
        compose.onNodeWithContentDescription("恢复定位前显示").performClick()
        assertState {
            assertEquals(overview, model.state.value.layer)
            assertFalse(model.state.value.canRestore)
            assertEquals("Structure restore must preserve later route choices", routesAfterFirstChoice, model.state.value.enabledMeridians)
        }
        compose.runOnUiThread { model.mode(AtlasMode.MERIDIANS); model.toggleMeridian(routeIds[1]) }
        val routesAfterSecondChoice = model.state.value.enabledMeridians
        mode(AtlasMode.MUSCLES)
        assertState { assertTrue(model.state.value.canRestore); assertEquals(muscles[1].id, model.state.value.layer.isolateId) }
        compose.onNodeWithContentDescription("恢复定位前显示").performClick()
        assertState {
            assertEquals(muscleLayer, model.state.value.layer)
            assertFalse(model.state.value.canRestore)
            assertEquals("Each mode's structure focus must leave later meridian toggles intact", routesAfterSecondChoice, model.state.value.enabledMeridians)
        }
    }

    @Test fun selectingPointEnablesItsRouteAndCheckingAnotherRouteKeepsPointSelection() {
        content(compact = true)
        mode(AtlasMode.MERIDIANS)
        compose.runOnUiThread {
            listOf("LU", "LI").filter { it in model.state.value.enabledMeridians }.forEach(model::toggleMeridian)
            model.clearSelection()
            model.layer { it.copy(lines = false, points = false) }
        }
        search("中府")
        compose.onAllNodesWithContentDescription("定位中府").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位中府").performClick()
        assertState {
            assertEquals("lu1", model.state.value.selectedPoint)
            assertEquals("LU", model.state.value.selectedMeridian)
            assertTrue("LU" in model.state.value.enabledMeridians)
            assertTrue(model.state.value.layer.lines)
            assertTrue(model.state.value.layer.points)
        }
        compose.onNodeWithContentDescription("图谱目录").performClick()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        val label = "显示${model.state.value.data!!.meridians.single { it.id == "LI" }.nameZh}路线"
        compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).onLast().performScrollToNode(hasContentDescription(label))
        compose.onNodeWithContentDescription(label).performClick()
        assertState {
            assertEquals("LU", model.state.value.selectedMeridian)
            assertEquals("lu1", model.state.value.selectedPoint)
            assertTrue(model.state.value.enabledMeridians.containsAll(setOf("LU", "LI")))
            assertTrue(model.state.value.layer.lines)
        }
    }

    @Test fun nativeTapFindsUnshownLungPointAndEnablesItsOwningRoute() {
        content(compact = true)
        mode(AtlasMode.MERIDIANS)
        val surface = modelSurface()
        val latest = AtomicReference<List<ProjectedAnchor>>(emptyList())
        val chosen = AtomicReference<ProjectedAnchor?>(null)
        val pointIndex = model.state.value.data!!.points.associateBy { it.id }
        var original: ((List<ProjectedAnchor>) -> Unit)? = null
        try {
            compose.runOnUiThread {
                val callback = surface.onProjection
                original = callback
                surface.onProjection = { anchors -> callback(anchors); latest.set(anchors.toList()) }
                model.state.value.enabledMeridians.toList().forEach(model::toggleMeridian)
                model.clearSelection()
                model.layer { it.copy(points = false, lines = false, skin = true, muscle = false,
                    organ = false, bone = false, isolateId = null, focusIds = emptySet(), cutawaySide = CutawaySide.NONE) }
                surface.applyCameraPreset(CameraPreset.FRONT, animated = false)
            }
            compose.waitUntil(30_000) {
                val anchors = latest.get()
                // Wait for the new hidden-dot snapshot, rather than a cached visible-dot frame.
                if (anchors.isEmpty() || anchors.any { it.id.startsWith("point:") }) false
                else {
                    val candidates = anchors.filter { anchor ->
                        anchor.id.startsWith("hitpoint:") && anchor.visible && anchor.frontFacing && !anchor.occluded &&
                            pointIndex[anchor.id.substringAfter(':').substringBefore('|')]?.instances
                                ?.getOrNull(anchor.id.substringAfterLast('|').toIntOrNull() ?: 0)?.viewId == "body"
                    }
                    val lung = candidates.filter { pointIndex[it.id.substringAfter(':').substringBefore('|')]?.meridianId == "LU" }
                    // Exact coincident points can belong to another meridian. Prefer an isolated
                    // LU1 projection; another isolated lung point is equally valid for this flow.
                    fun separation(anchor: ProjectedAnchor): Double = candidates.asSequence().filter {
                        pointIndex[it.id.substringAfter(':').substringBefore('|')]?.meridianId != "LU"
                    }.map { hypot((it.xPx - anchor.xPx).toDouble(), (it.yPx - anchor.yPx).toDouble()) }
                        .minOrNull() ?: Double.POSITIVE_INFINITY
                    val isolated = lung.filter { separation(it) > 4.0 }
                    val target = isolated.firstOrNull { it.id.startsWith("hitpoint:lu1|") }
                        ?: isolated.maxByOrNull(::separation)
                    chosen.set(target)
                    target != null
                }
            }
            assertState {
                assertTrue(model.state.value.enabledMeridians.isEmpty())
                assertFalse(model.state.value.layer.points)
                assertFalse(model.state.value.layer.lines)
                assertNull(model.state.value.selectedPoint)
            }
            val target = checkNotNull(chosen.get())
            val bounds = Rect()
            compose.runOnUiThread { check(surface.getGlobalVisibleRect(bounds)) }
            val x = bounds.left + target.xPx
            val y = bounds.top + target.yPx
            assertTrue("Projected target must be inside the actual SurfaceView", bounds.contains(x.toInt(), y.toInt()))
            requireForeground()
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, down, listOf(x to y))
            SystemClock.sleep(40)
            touch(MotionEvent.ACTION_UP, down, listOf(x to y))
            compose.waitUntil(10_000) { model.state.value.selectedPoint != null }
            assertState {
                val selected = pointIndex.getValue(model.state.value.selectedPoint!!)
                assertEquals("LU", selected.meridianId)
                assertEquals("LU", model.state.value.selectedMeridian)
                assertTrue("LU" in model.state.value.enabledMeridians)
                assertTrue(model.state.value.layer.lines)
                assertTrue(model.state.value.layer.points)
            }
        } finally {
            original?.let { callback -> compose.runOnUiThread { surface.onProjection = callback } }
        }
    }

    @Test fun locatingMuscleKeepsOtherPartsAndHideButtonRemovesOnlyTheSelectedId() {
        content(compact = true)
        mode(AtlasMode.MUSCLES)
        val structures = model.state.value.data!!.structures
        val target = structures.first { it.system == "muscle" }
        val before = VisibilityPolicy.visibleIds(structures, model.state.value.layer)
        assertTrue(target.id in before)
        assertTrue("Need other muscles to detect accidental solo mode", before.size > 1)
        search(target.id)
        compose.onNode(hasText(target.nameZh) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertState {
            assertEquals(target.id, model.state.value.selectedStructure)
            assertNull(model.state.value.layer.isolateId)
            assertTrue(model.state.value.layer.focusIds.isEmpty())
            assertEquals(before, VisibilityPolicy.visibleIds(structures, model.state.value.layer))
        }
        compose.onNodeWithText("隐藏这一块").assertIsDisplayed().performClick()
        assertState {
            assertNull(model.state.value.selectedStructure)
            assertEquals(setOf(target.id), model.state.value.layer.hiddenIds)
            assertEquals(before - target.id, VisibilityPolicy.visibleIds(structures, model.state.value.layer))
        }
        compose.onNodeWithText("隐藏这一块").assertDoesNotExist()
        compose.onNodeWithContentDescription("恢复定位前显示").performClick()
        assertState { assertEquals(before - target.id, VisibilityPolicy.visibleIds(structures, model.state.value.layer)) }
    }

    @Test fun meridianOrganAndAnatomicalHalfBodyToolsSetTheExpectedLayers() {
        content(compact = true)
        mode(AtlasMode.MERIDIANS)
        val routes = model.state.value.enabledMeridians
        val lines = model.state.value.layer.lines
        val points = model.state.value.layer.points
        clickQuickTool("内脏透视")
        assertState {
            assertEquals(CutawaySide.NONE, model.state.value.layer.cutawaySide)
            assertFalse(model.state.value.layer.skin)
            assertFalse(model.state.value.layer.muscle)
            assertTrue(model.state.value.layer.organ)
            assertTrue(model.state.value.layer.bone)
        }
        for (side in listOf(CutawaySide.LEFT, CutawaySide.RIGHT)) {
            clickQuickTool(side.title)
            compose.onAllNodes(hasText(side.title) and hasClickAction()).onFirst().assertIsSelected()
            assertState {
                assertEquals(side, model.state.value.layer.cutawaySide)
                assertTrue(model.state.value.layer.skin)
                assertTrue(model.state.value.layer.muscle)
                assertTrue(model.state.value.layer.organ)
                assertTrue(model.state.value.layer.bone)
            }
        }
        clickQuickTool("体表")
        assertState {
            assertEquals(CutawaySide.NONE, model.state.value.layer.cutawaySide)
            assertTrue(model.state.value.layer.skin)
            assertFalse(model.state.value.layer.organ)
            assertFalse(model.state.value.layer.bone)
            assertEquals(routes, model.state.value.enabledMeridians)
            assertEquals(lines, model.state.value.layer.lines)
            assertEquals(points, model.state.value.layer.points)
        }
    }

    /** Explicit opt-in only: host samples this actual SurfaceView while these native gestures run. */
    @Test fun soakModelGestures() {
        val args = InstrumentationRegistry.getArguments()
        val seconds = args.getString("soak_seconds")?.toLongOrNull() ?: 0L
        assumeTrue("Run through tools/qa/device_soak.py", seconds > 0)
        require(seconds in 10..43_200)
        content(compact = false)
        mode(when (args.getString("soak_mode")) { "overview" -> AtlasMode.OVERVIEW; "meridians" -> AtlasMode.MERIDIANS; else -> AtlasMode.MUSCLES })
        val cutaway = when (args.getString("soak_cutaway")?.lowercase()) {
            null -> null
            "left" -> CutawaySide.LEFT
            "right" -> CutawaySide.RIGHT
            else -> error("soak_cutaway must be left or right")
        }
        if (cutaway != null) {
            require(model.state.value.mode == AtlasMode.MERIDIANS) { "soak_cutaway requires soak_mode=meridians" }
            compose.runOnUiThread { model.layer { VisibilityPolicy.meridianView(it, true, cutaway) } }
            compose.waitForIdle()
        }
        val bounds = Rect()
        compose.runOnUiThread {
            fun find(view: View): SurfaceView? {
                if (view is SurfaceView && view.isShown) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            val surface = checkNotNull(find(compose.activity.window.decorView)) { "No visible model SurfaceView" }
            check(surface.getGlobalVisibleRect(bounds) && bounds.width() >= 120 && bounds.height() >= 120)
        }
        val stop = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { stop.set(true) } }
        val context = instrumentation.targetContext
        ContextCompat.registerReceiver(context, receiver, IntentFilter(STOP_ACTION), ContextCompat.RECEIVER_EXPORTED)
        var rotations = 0
        var pinches = 0
        val start = SystemClock.elapsedRealtime()
        fun status(key: String) = instrumentation.sendStatus(2, Bundle().apply {
            putString(key, JSONObject().put("package", context.packageName).put("pid", Process.myPid())
                .put("elapsedSeconds", (SystemClock.elapsedRealtime() - start) / 1000.0)
                .put("rotations", rotations).put("pinches", pinches)
                .put("bounds", listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
                .put("mode", model.state.value.mode.name).put("quality", model.state.value.quality.name)
                .put("cutawaySide", model.state.value.layer.cutawaySide.name).toString())
        })
        try {
            requireForeground()
            status("atlas_soak_ready")
            var lastStatus = start
            while (!stop.get() && SystemClock.elapsedRealtime() - start < seconds * 1000) {
                requireForeground()
                rotate(bounds, rotations % 2 == 0); rotations++
                if (stop.get()) break
                requireForeground()
                pinch(bounds, pinches % 2 == 0); pinches++
                if (SystemClock.elapsedRealtime() - lastStatus >= 10_000) { status("atlas_soak_heartbeat"); lastStatus = SystemClock.elapsedRealtime() }
                SystemClock.sleep(100)
            }
            status("atlas_soak_complete")
            assertTrue("No rotation injected", rotations > 0)
            assertTrue("No pinch injected", pinches > 0)
        } finally { context.unregisterReceiver(receiver) }
    }

    private fun requireForeground() {
        var ownWindow = false
        instrumentation.runOnMainSync {
            ownWindow = !compose.activity.isFinishing && !compose.activity.isDestroyed &&
                compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED && compose.activity.window.decorView.hasWindowFocus()
        }
        check(ownWindow) { "App lost foreground; no further input injected" }
        val activePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        check(activePackage == null || activePackage == instrumentation.targetContext.packageName) {
            "Foreground package changed to $activePackage; no further input injected"
        }
    }

    private fun touch(action: Int, down: Long, points: List<Pair<Float, Float>>) {
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
            x = points[index].first; y = points[index].second; pressure = 1f; size = .05f
        } }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Native touch injection rejected" } }
        finally { event.recycle() }
    }
    private fun rotate(box: Rect, forward: Boolean) {
        val y = box.top + box.height() * .46f
        val center = box.exactCenterX()
        val delta = box.width() * .16f * if (forward) 1 else -1
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(center - delta to y))
        repeat(16) { step -> SystemClock.sleep(26); touch(MotionEvent.ACTION_MOVE, down, listOf(center - delta + delta * 2 * (step + 1) / 16 to y)) }
        touch(MotionEvent.ACTION_UP, down, listOf(center + delta to y))
    }
    private fun pinch(box: Rect, outward: Boolean) {
        val x = box.exactCenterX(); val y = box.top + box.height() * .46f
        val from = box.width() * if (outward) .10f else .23f
        val to = box.width() * if (outward) .23f else .10f
        fun points(distance: Float) = listOf(x - distance to y, x + distance to y)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, points(from).take(1))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, points(from))
        repeat(16) { step -> SystemClock.sleep(26); touch(MotionEvent.ACTION_MOVE, down, points(from + (to - from) * (step + 1) / 16)) }
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, points(to))
        touch(MotionEvent.ACTION_UP, down, points(to).take(1))
    }
    companion object { const val STOP_ACTION = "com.zga.meridianatlas.qa.STOP_SOAK" }
}
