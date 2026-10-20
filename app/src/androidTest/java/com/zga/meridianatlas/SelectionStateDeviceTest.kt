package com.zga.meridianatlas

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Selection is a learning focus, independent of observation presets and route checkboxes. */
@RunWith(AndroidJUnit4::class)
class SelectionStateDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AtlasViewModel

    @Before fun ready() {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        compose.runOnUiThread {
            AtlasMode.entries.forEach { mode -> model.mode(mode); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.MERIDIANS)
            model.state.value.enabledMeridians.toList().forEach(model::toggleMeridian)
            model.quality(ModelQuality.LOW)
        }
    }

    private fun expectLungFocus() {
        val state = model.state.value
        assertEquals("lu1", state.selectedPoint)
        assertEquals("LU", state.selectedPointMeridian)
        assertEquals("LU", state.selectedMeridian)
        assertTrue("The selected point must keep its own route visible", "LU" in state.effectiveMeridians)
        assertTrue(state.layer.points)
        assertTrue(state.layer.lines)
    }

    @Test fun organAndHalfBodyButtonsKeepTheSelectedPointAndOwningRoute() {
        compose.runOnUiThread { model.selectPoint("lu1", reveal = false) }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasText("内脏透视") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        for (title in listOf("内脏透视", "左半身透视", "右半身透视", "体表")) {
            compose.onAllNodes(hasText(title) and hasClickAction()).onFirst().performScrollTo().performClick()
            compose.runOnIdle {
                expectLungFocus()
                when (title) {
                    "内脏透视" -> { assertTrue(model.state.value.layer.organ); assertFalse(model.state.value.layer.skin) }
                    "左半身透视" -> assertEquals(CutawaySide.LEFT, model.state.value.layer.cutawaySide)
                    "右半身透视" -> assertEquals(CutawaySide.RIGHT, model.state.value.layer.cutawaySide)
                    "体表" -> { assertTrue(model.state.value.layer.skin); assertFalse(model.state.value.layer.organ) }
                }
            }
        }
    }

    @Test fun selectingAPosteriorPointThenOpeningOrgansKeepsItsActualCameraView() {
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("搜索图谱").performClick()
        compose.onNodeWithContentDescription("搜索经络与穴位").performTextReplacement("BL13")
        compose.onNodeWithContentDescription("定位肺俞").performClick()
        compose.onNodeWithContentDescription("查看完整说明").assertIsDisplayed()
        lateinit var surface: AnatomySurfaceView
        var before: Any? = null
        val pose = AnatomySurfaceView::class.java.getDeclaredField("pose").apply { isAccessible = true }
        compose.runOnUiThread {
            fun find(view: View): AnatomySurfaceView? {
                if (view is AnatomySurfaceView && view.isShown) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            surface = checkNotNull(find(compose.activity.window.decorView))
            before = pose.get(surface)
        }
        compose.onAllNodes(hasText("内脏透视") and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.runOnUiThread {
            assertEquals("Observing organs must keep the selected posterior point in view", before, pose.get(surface))
            assertEquals("bl13", model.state.value.selectedPoint)
            assertEquals("BL", model.state.value.selectedPointMeridian)
            assertTrue("BL" in model.state.value.effectiveMeridians)
        }
    }

    @Test fun addingRemovingAndExpandingOtherRoutesDoNotReplacePointFocus() {
        compose.runOnUiThread {
            model.selectPoint("lu1", reveal = false)
            model.toggleMeridian("LI")
            expectLungFocus()
            assertTrue("LI" in model.state.value.enabledMeridians)
            model.selectMeridianForCatalogue("ST")
            expectLungFocus()
            model.toggleMeridian("LI")
            expectLungFocus()
            assertFalse("LI" in model.state.value.enabledMeridians)
        }
    }

    @Test fun uncheckingOwningRouteKeepsPointContextUntilExplicitlyCleared() {
        compose.runOnUiThread {
            model.selectPoint("lu1", reveal = false)
            assertTrue("LU" in model.state.value.enabledMeridians)
            model.toggleMeridian("LU")
            assertFalse("LU" in model.state.value.enabledMeridians)
            expectLungFocus()
            model.clearSelection()
            assertNull(model.state.value.selectedPoint)
            assertFalse("Unselected and unchecked route should disappear", "LU" in model.state.value.effectiveMeridians)
        }
    }

    @Test fun observationPreparationPreservesControlsButExplicitRestoreStillClearsSelection() {
        compose.runOnUiThread {
            model.layer { it.copy(lines = false, points = false, details = false) }
            val original = model.state.value.layer
            model.selectPoint("lu1", reveal = false)
            model.layer { it.copy(labels = false, internalRelations = true, focusIds = setOf("local-view"), skin = false) }
            assertTrue(model.prepareObservationPreset())
            expectLungFocus()
            assertFalse(model.state.value.layer.labels)
            assertTrue(model.state.value.layer.internalRelations)
            assertTrue(model.state.value.layer.details)
            assertEquals(original.skin, model.state.value.layer.skin)
            assertTrue(model.state.value.layer.focusIds.isEmpty())
            assertFalse(model.state.value.canRestore)
            assertFalse("The camera snapshot is consumed only once", model.prepareObservationPreset())

            model.selectPoint("li1", reveal = false)
            assertEquals("li1", model.state.value.selectedPoint)
            assertEquals("LI", model.state.value.selectedPointMeridian)
            model.restore()
            assertNull(model.state.value.selectedPoint)
            assertNull(model.state.value.selectedStructure)
            assertFalse(model.state.value.canRestore)
        }
    }
}
