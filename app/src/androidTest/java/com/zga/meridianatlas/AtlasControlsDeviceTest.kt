package com.zga.meridianatlas

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises shared layer controls and the actual lazy, grouped point catalogue on a phone layout. */
@RunWith(AndroidJUnit4::class)
class AtlasControlsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
            model.quality(ModelQuality.LOW)
            compose.activity.setContent {
                Box(Modifier.sizeIn(maxWidth = 420.dp, maxHeight = 760.dp).fillMaxSize()) {
                    MeridianAtlasApp(model)
                }
            }
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
    }

    private fun mode(mode: AtlasMode) {
        compose.onAllNodes(hasText(mode.title) and hasClickAction()).onLast().performClick()
        compose.runOnIdle { assertEquals(mode, model.state.value.mode) }
    }

    private fun openCatalogue() {
        compose.onNodeWithContentDescription("图谱目录").performClick()
        compose.onNodeWithText("经络与穴位").assertIsDisplayed()
        compose.waitForIdle()
    }

    private fun closeCatalogue() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun catalogueList(): SemanticsNodeInteraction =
        compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).onLast()

    private fun action(label: String) =
        compose.onNode(hasContentDescription(label) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun point(id: String) = model.state.value.data!!.points.single { it.id == id }

    private fun assertPointKept(id: String) {
        compose.runOnIdle {
            assertEquals(id, model.state.value.selectedPoint)
            point(id).meridianId?.let {
                assertEquals(it, model.state.value.selectedPointMeridian)
                assertTrue("The highlighted point must retain its route context", it in model.state.value.effectiveMeridians)
            }
        }
    }

    @Test fun allThreeModesExposeIndependentMuscleBoneAndOrganControlsAndRetainTheirSettings() {
        val savedLayers = mutableMapOf<AtlasMode, LayerState>()
        AtlasMode.entries.forEachIndexed { index, atlasMode ->
            mode(atlasMode)
            compose.runOnUiThread {
                model.layer { it.copy(muscle = true, bone = true, organ = true,
                    disabledRegionLayers = setOf("head_neck:1")) }
                model.selectPoint("lu1", reveal = false)
            }
            listOf("显示肌肉", "显示骨骼", "显示内脏").forEach { description ->
                val before = model.state.value.layer
                val chip = compose.onNodeWithContentDescription(description)
                chip.performScrollTo().assertIsSelected().performClick()
                val expected = when (description) {
                    "显示肌肉" -> before.copy(muscle = false)
                    "显示骨骼" -> before.copy(bone = false)
                    else -> before.copy(organ = false)
                }
                compose.runOnIdle {
                    assertEquals("Each master switch must keep every unrelated setting", expected, model.state.value.layer)
                }
                chip.assertIsNotSelected()
                assertPointKept("lu1")
                chip.performClick().assertIsSelected()
                compose.runOnIdle { assertEquals(before, model.state.value.layer) }
            }
            // Leave a different state in each mode, then return through the actual mode buttons.
            val off = listOf("显示肌肉", "显示骨骼", "显示内脏")[index]
            compose.onNodeWithContentDescription(off).performScrollTo().performClick()
            savedLayers[atlasMode] = model.state.value.layer
        }
        AtlasMode.entries.forEach { atlasMode ->
            mode(atlasMode)
            compose.runOnIdle { assertEquals(savedLayers.getValue(atlasMode), model.state.value.layer) }
            assertPointKept("lu1")
        }
    }

    @Test fun openingCatalogueRevealsTheHighlightedPrimaryOrExtraPointWithoutManualScrolling() {
        mode(AtlasMode.MERIDIANS)
        val oral = model.state.value.data!!.points.single { it.nameZh == "金津玉液" }
        // BL67 requires passing a long route and many preceding groups; LU11 verifies returning
        // from an extra-point category to a point's primary route rather than a stale category.
        for (selected in listOf(point("bl67"), oral, point("lu11"))) {
            compose.runOnUiThread { model.selectPoint(selected.id, reveal = false) }
            openCatalogue()
            compose.onNodeWithContentDescription("定位${selected.nameZh}").assertIsDisplayed()
            val category = if (selected.kind == "extra") "经外奇穴" else "经络路线"
            compose.onNode(hasText(category) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsSelected()
            assertPointKept(selected.id)
            closeCatalogue()
        }
    }

    @Test fun routeBulkSelectionAndExpansionAreSeparateAndKeepTheHighlightedPoint() {
        mode(AtlasMode.MERIDIANS)
        compose.runOnUiThread {
            model.state.value.enabledMeridians.toList().forEach(model::toggleMeridian)
            model.toggleMeridian("__extra__")
            model.selectPoint("bl67", reveal = false)
        }
        val data = model.state.value.data!!
        val allRoutes = data.meridians.map { it.id }.toSet()
        openCatalogue()
        action("经络全选").performClick()
        compose.runOnIdle { assertEquals(allRoutes + "__extra__", model.state.value.enabledMeridians) }
        assertPointKept("bl67")
        action("经络全不选").performClick()
        compose.runOnIdle {
            assertEquals("Route bulk actions must leave the extra-point choice independent",
                setOf("__extra__"), model.state.value.enabledMeridians)
        }
        assertPointKept("bl67")

        action("全部收起").performClick()
        compose.onAllNodes(hasContentDescription("定位", substring = true) and hasAnyAncestor(isDialog())).assertCountEquals(0)
        assertPointKept("bl67")
        val routesBeforeExpansion = model.state.value.enabledMeridians
        action("全部展开").performClick()
        for (route in listOf(data.meridians.first(), data.meridians.last())) {
            val expandedHeader = hasContentDescription("收起${route.nameZh}")
            catalogueList().performScrollToNode(expandedHeader)
            compose.onNode(expandedHeader).assertIsDisplayed()
        }
        catalogueList().performScrollToNode(hasContentDescription("定位${point("bl67").nameZh}"))
        compose.onNodeWithContentDescription("定位${point("bl67").nameZh}").assertIsDisplayed()
        compose.runOnIdle { assertEquals(routesBeforeExpansion, model.state.value.enabledMeridians) }
        assertPointKept("bl67")
        action("全部收起").performClick()
        compose.onAllNodes(hasContentDescription("定位", substring = true) and hasAnyAncestor(isDialog())).assertCountEquals(0)
        assertPointKept("bl67")
    }

    @Test fun extraExpansionAndGlobalSearchDoNotDiscardTheHighlightedPointOrRestrictResults() {
        mode(AtlasMode.MERIDIANS)
        val oral = model.state.value.data!!.points.single { it.nameZh == "金津玉液" }
        compose.runOnUiThread { model.selectPoint(oral.id, reveal = false) }
        openCatalogue()
        action("全部收起").performClick()
        compose.onNodeWithContentDescription("定位金津玉液").assertDoesNotExist()
        action("全部展开").performClick()
        catalogueList().performScrollToNode(hasContentDescription("定位金津玉液"))
        compose.onNodeWithContentDescription("定位金津玉液").assertIsDisplayed()
        assertPointKept(oral.id)

        val search = compose.onNodeWithContentDescription("搜索经络与穴位")
        search.performTextReplacement("LU1")
        compose.onAllNodesWithContentDescription("定位中府").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位中府").assertIsDisplayed()
        assertPointKept(oral.id)
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNode(hasText("经络路线") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        search.performTextReplacement("EXHN12")
        compose.onAllNodesWithContentDescription("定位金津玉液").assertCountEquals(1)
        compose.onNodeWithContentDescription("定位金津玉液").assertIsDisplayed()
        assertPointKept(oral.id)
    }
}
