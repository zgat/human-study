package com.zga.meridianatlas

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.data.AtlasPreferences
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the shipped fragmented structures, real catalogue, renderer and persisted legacy IDs. */
@RunWith(AndroidJUnit4::class)
class StructureGroupDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AtlasViewModel
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

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
        }
    }

    private fun awaitModel() {
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
    }

    private fun group(name: String): StructureGroup =
        model.state.value.data!!.structureGroups.entries.single { it.representative.nameZh == name }

    private fun organs(): LayerState = LayerState(skin = false, muscle = false, organ = true,
        bone = false, lines = false, points = false, connective = false, spaces = false)

    private fun surface(): AnatomySurfaceView {
        var result: AnatomySurfaceView? = null
        compose.runOnUiThread {
            fun find(view: View): AnatomySurfaceView? {
                if (view is AnatomySurfaceView && view.isShown) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            result = find(compose.activity.window.decorView)
        }
        return checkNotNull(result) { "No visible model SurfaceView" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun highlighted(viewer: AnatomySurfaceView): Set<String> {
        var result = emptySet<String>()
        compose.runOnUiThread {
            val field = AnatomySurfaceView::class.java.getDeclaredField("selectedIds").apply { isAccessible = true }
            result = (field.get(viewer) as Set<String>).toSet()
        }
        return result
    }

    @Test fun selectingAnyIleumFragmentHidesAndRestoresItsWholeNamedStructureOnly() {
        val data = model.state.value.data!!
        assertEquals("Original structures plus separately appended connective tissue meshes", 1291, data.structures.size)
        assertEquals(1112, data.structureGroups.entries.size)
        val proximal = group("回肠近侧部")
        val middle = group("回肠中部")
        val distal = group("回肠远侧部")
        assertEquals(10, proximal.members.size)
        assertEquals(10, middle.members.size)
        assertEquals(11, distal.members.size)
        assertTrue(proximal.memberIds.intersect(middle.memberIds.toSet()).isEmpty())
        assertTrue(proximal.memberIds.intersect(distal.memberIds.toSet()).isEmpty())
        assertTrue(middle.memberIds.intersect(distal.memberIds.toSet()).isEmpty())
        assertTrue("Whole-organ focus must have a finite useful camera distance", proximal.focusDistance.isFinite() && proximal.focusDistance > 0f)
        proximal.members.forEach { assertEquals(proximal.id, data.structureGroups.find(it.id)?.id) }

        compose.runOnUiThread {
            model.layer { organs() }
            val before = VisibilityPolicy.visibleIds(data.structures, model.state.value.layer)
            assertTrue(before.containsAll(proximal.memberIds))
            model.selectStructure(proximal.members.last().id)
            assertEquals(proximal.id, model.state.value.selectedStructure)
            model.hideSelectedStructure()
            assertEquals(proximal.memberIds.toSet(), model.state.value.layer.hiddenIds)
            assertEquals("Hiding the selected name must leave every other organ unchanged",
                before - proximal.memberIds.toSet(), VisibilityPolicy.visibleIds(data.structures, model.state.value.layer))

            model.locateStructure(proximal.members[1].id)
            assertEquals(proximal.id, model.state.value.selectedStructure)
            assertNull(model.state.value.layer.isolateId)
            assertEquals("Locating a hidden group must reveal all its fragments and retain its neighbours",
                before, VisibilityPolicy.visibleIds(data.structures, model.state.value.layer))
            assertTrue(model.state.value.layer.hiddenIds.intersect(proximal.memberIds.toSet()).isEmpty())

            model.layer { it.copy(hiddenIds = proximal.memberIds.toSet()) }
            model.revealStructure(proximal.members.last().id)
            assertEquals(proximal.id, model.state.value.selectedStructure)
            assertEquals(proximal.memberIds.toSet(), VisibilityPolicy.visibleIds(data.structures, model.state.value.layer))
            assertTrue(model.state.value.layer.hiddenIds.intersect(proximal.memberIds.toSet()).isEmpty())
        }
        for (quality in listOf(ModelQuality.STANDARD, ModelQuality.LOW)) {
            compose.runOnUiThread { model.quality(quality) }
            compose.runOnIdle {
                assertEquals(proximal.id, model.state.value.selectedStructure)
                assertEquals(proximal.memberIds.toSet(), VisibilityPolicy.visibleIds(data.structures, model.state.value.layer))
            }
        }
    }

    @Test fun oneSearchResultSelectsAllTenFragmentsInTheRealRendererAcrossModelQuality() {
        awaitModel()
        val proximal = group("回肠近侧部")
        val viewer = surface()
        compose.onNodeWithContentDescription("搜索图谱").performClick()
        compose.onNodeWithContentDescription("搜索解剖结构").performTextReplacement(proximal.representative.nameZh)
        val row = hasText(proximal.representative.nameZh) and hasClickAction()
        compose.onAllNodes(row).assertCountEquals(1)
        compose.onAllNodes(row).onFirst().performClick()
        compose.onNodeWithContentDescription("取消选中").assertIsDisplayed()
        compose.onAllNodesWithText("10 个片段", substring = true).onLast().assertIsDisplayed()
        compose.waitUntil(30_000) { highlighted(viewer) == proximal.memberIds.toSet() }
        assertEquals(proximal.id, model.state.value.selectedStructure)

        for (quality in listOf(ModelQuality.STANDARD, ModelQuality.LOW)) {
            compose.runOnUiThread { model.quality(quality) }
            awaitModel()
            assertSame("Quality switching must reuse the scene view", viewer, surface())
            compose.waitUntil(30_000) { highlighted(viewer) == proximal.memberIds.toSet() }
            assertEquals(proximal.id, model.state.value.selectedStructure)
            compose.onNodeWithContentDescription("取消选中").assertIsDisplayed()
        }
    }

    @Test fun legacySingleFragmentPreferencesExpandOnReadWithoutJoiningLeftAndRightStructures() {
        val data = model.state.value.data!!
        val proximal = group("回肠近侧部")
        val left = group("左侧二腹肌")
        val right = group("右侧二腹肌")
        assertEquals(3, left.members.size)
        assertEquals(2, right.members.size)
        assertNotEquals(left.id, right.id)
        assertTrue(left.memberIds.intersect(right.memberIds.toSet()).isEmpty())
        val preferences = AtlasPreferences(context)
        // Let the ViewModel finish its debounced setup write before exercising old on-disk IDs.
        compose.waitUntil(10_000) {
            val current = model.state.value
            runBlocking { preferences.read() }?.let {
                it.mode == current.mode && it.quality == current.quality && it.states == current.layers
            } == true
        }
        val original = checkNotNull(runBlocking { preferences.read() })
        val base = organs().copy(muscle = true)
        try {
            val oldHidden = setOf(proximal.members.last().id, left.members.last().id)
            runBlocking { preferences.write(original.copy(states = original.states +
                (AtlasMode.OVERVIEW to base.copy(hiddenIds = oldHidden)))) }
            var restored = checkNotNull(runBlocking { preferences.read() }).states.getValue(AtlasMode.OVERVIEW)
            assertEquals("The persisted representation stays compatible with raw mesh IDs", oldHidden, restored.hiddenIds)
            var visible = VisibilityPolicy.visibleIds(data.structures, restored)
            assertTrue(visible.intersect(proximal.memberIds.toSet()).isEmpty())
            assertTrue(visible.intersect(left.memberIds.toSet()).isEmpty())
            assertTrue("Hiding the left muscle must retain every right muscle fragment", visible.containsAll(right.memberIds))
            assertTrue(visible.containsAll(group("回肠中部").memberIds))

            for (target in listOf(proximal, left)) {
                val oldIsolate = target.members.last().id
                runBlocking { preferences.write(original.copy(states = original.states +
                    (AtlasMode.OVERVIEW to base.copy(isolateId = oldIsolate)))) }
                restored = checkNotNull(runBlocking { preferences.read() }).states.getValue(AtlasMode.OVERVIEW)
                assertEquals(oldIsolate, restored.isolateId)
                visible = VisibilityPolicy.visibleIds(data.structures, restored)
                assertEquals("A legacy isolated fragment must now reveal its complete named structure", target.memberIds.toSet(), visible)
            }
        } finally {
            runBlocking { preferences.write(original) }
        }
    }
}
