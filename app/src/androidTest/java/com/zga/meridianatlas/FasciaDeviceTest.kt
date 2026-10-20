package com.zga.meridianatlas

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.data.AtlasDatabase
import com.zga.meridianatlas.data.AtlasPreferences
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Exercises shipped Room content, independent connective layers and the actual fascia UI. */
@RunWith(AndroidJUnit4::class)
class FasciaDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
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
        }
    }

    private fun awaitModel() {
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
    }

    private fun quick(title: String) = compose.onAllNodes(hasText(title) and hasClickAction()).onFirst()
        .performScrollTo().performClick()

    private fun openFascia() {
        quick("筋膜力线")
        compose.onNodeWithText("筋膜与连接链").assertIsDisplayed()
    }

    private fun panelFind(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(matcher)
        return compose.onAllNodes(matcher and hasAnyAncestor(isDialog())).onFirst()
    }

    private fun firstConnection(): FascialConnection = model.state.value.data!!.fascialConnections.first().also {
        assertTrue("A connection needs actual structures, paths, reference regions and sources",
            it.structureIds.isNotEmpty() && it.paths.isNotEmpty() && it.targets.isNotEmpty() && it.sourceRefs.isNotEmpty())
    }

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
    private fun targetPositions(viewer: AnatomySurfaceView): Map<String, Vec3> {
        var result = emptyMap<String, Vec3>()
        compose.runOnUiThread {
            val field = AnatomySurfaceView::class.java.getDeclaredField("anchors").apply { isAccessible = true }
            result = (field.get(viewer) as List<ProjectionAnchor>).filter { it.id.startsWith("fascial-target:") }
                .associate { it.id to it.position }
        }
        return result
    }

    @Test fun roomClassificationAndIndependentConnectiveTogglePreserveOrgans() {
        val stored = runBlocking {
            withContext(Dispatchers.IO) {
                val database = Room.databaseBuilder(context, AtlasDatabase::class.java, "atlas.db").build()
                try { database.atlas().entries("structure").associate { it.id to JSONObject(it.body) } }
                finally { database.close() }
            }
        }
        val decoded = model.state.value.data!!.structures.associateBy { it.id }
        for (id in listOf("FJ1520", "FJ1520M", "FJ1521", "FJ1521M")) {
            assertEquals("Corrected source muscles must survive the Room readback", "muscle", stored.getValue(id).getString("system"))
            assertEquals("muscle", decoded.getValue(id).system)
        }
        assertEquals("connective", stored.getValue("FJ1448").getString("system"))
        assertEquals("bone", stored.getValue("FJ3178").getString("system"))
        assertTrue(decoded.getValue("FJ3178").nameZh.contains("胸骨体"))
        assertEquals("bone", stored.getValue("FJ3153").getString("system"))
        assertTrue(decoded.getValue("FJ3153").nameZh.contains("剑突"))
        val genuineFascia = stored.values.firstOrNull { it.optString("system") == "connective" &&
            it.optString("tissueType") == "fascia" && it.optString("sourceDataset").contains("Z-Anatomy") }
        assertNotNull("A real source fascia mesh must be present, not just a renamed muscle", genuineFascia)
        val fascia = decoded.getValue(genuineFascia!!.getString("id"))
        assertEquals("fascia", fascia.tissueType)
        assertTrue(fascia.tissueTypeZh.isNotBlank())
        assertTrue(fascia.sourceConceptNoteZh.isNotBlank())

        compose.runOnUiThread { model.layer { it.copy(skin = false, muscle = false, bone = false,
            organ = true, connective = false, spaces = false, focusIds = emptySet(), isolateId = null) } }
        val structures = model.state.value.data!!.structures
        val organIds = structures.filter { it.system == "organ" }.map { it.id }.toSet()
        assertTrue(organIds.isNotEmpty())
        assertEquals(organIds, VisibilityPolicy.visibleIds(structures, model.state.value.layer))
        quick("筋膜·腱韧带")
        compose.runOnIdle {
            val visible = VisibilityPolicy.visibleIds(structures, model.state.value.layer)
            assertTrue(fascia.id in visible)
            assertEquals(organIds, visible.intersect(organIds))
            assertTrue(model.state.value.layer.organ)
        }
        quick("筋膜·腱韧带")
        compose.runOnIdle { assertEquals(organIds, VisibilityPolicy.visibleIds(structures, model.state.value.layer)) }
    }

    @Test fun panelObservationTargetSelectionAndQualityChangesKeepRealTargetCoordinates() {
        awaitModel()
        val connection = firstConnection()
        val target = connection.targets.first()
        assertTrue("The reference region must retain source triangle/barycentric provenance", target.sourceGeometry.isNotBlank())
        assertTrue(target.structureIds.isNotEmpty())
        openFascia()
        panelFind(hasContentDescription("显示${connection.nameZh}连接链")).performClick()
        compose.runOnIdle { assertTrue(connection.id in model.state.value.layer.enabledFascialConnections) }
        panelFind(hasText("观察这组结构") and hasClickAction()).performClick()
        compose.onNodeWithText("筋膜与连接链").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(model.state.value.layer.fasciaChains)
            assertTrue(model.state.value.layer.connective)
            assertEquals(connection.structureIds.toSet(), model.state.value.layer.focusIds)
        }
        val viewer = surface()
        val expected = connection.targets.associate { "fascial-target:${it.id}" to it.position }
        compose.waitUntil(30_000) { targetPositions(viewer).keys.containsAll(expected.keys) }
        val before = targetPositions(viewer)
        assertEquals(expected, before)
        for (quality in listOf(ModelQuality.STANDARD, ModelQuality.LOW)) {
            compose.runOnUiThread { model.quality(quality) }
            awaitModel()
            assertSame(viewer, surface())
            assertEquals("Reference regions must stay in the same model coordinates across LOD", before, targetPositions(viewer))
        }

        openFascia()
        panelFind(hasContentDescription("定位连接参考区${target.id}")).performClick()
        compose.onNodeWithText("连接说明与出处").assertIsDisplayed()
        compose.onNodeWithText(target.nameZh).assertExists()

        val latest = AtomicReference<List<ProjectedAnchor>>(emptyList())
        var original: ((List<ProjectedAnchor>) -> Unit)? = null
        try {
            compose.runOnUiThread {
                original = viewer.onProjection
                val callback = viewer.onProjection
                viewer.onProjection = { anchors -> callback(anchors); latest.set(anchors.toList()) }
                // Request the same anatomical target again to publish a fresh camera projection.
                viewer.focusOn(target.position, .41f, target.normal)
            }
            compose.onNode(hasText("关闭") and hasClickAction()).performClick()
            compose.onNodeWithText("连接说明与出处").assertDoesNotExist()
            compose.waitUntil(30_000) { latest.get().any { it.id == "fascial-target:${target.id}" && it.visible } }
            val projected = latest.get().single { it.id == "fascial-target:${target.id}" }
            val location = IntArray(2)
            compose.runOnUiThread { viewer.getLocationOnScreen(location) }
            val x = location[0] + projected.xPx
            val y = location[1] + projected.yPx
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("连接说明与出处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(target.nameZh).assertExists()
        } finally {
            original?.let { callback -> compose.runOnUiThread { viewer.onProjection = callback } }
        }
    }

    @Test fun evidenceSourcesAndIndependentModePreferencesRemainAccessible() {
        val connection = firstConnection()
        openFascia()
        panelFind(hasContentDescription("显示${connection.nameZh}连接链")).performClick()
        panelFind(hasText("显示连接参考区") and hasClickAction()).performClick()
        panelFind(hasText(connection.evidenceZh)).assertIsDisplayed()
        if (connection.geometryCoverageZh.isNotBlank()) panelFind(hasText(connection.geometryCoverageZh)).assertIsDisplayed()
        val source = connection.sourceRefs.first()
        assertTrue(source.url.startsWith("https://"))
        panelFind(hasText(source.title, substring = true)).assertIsDisplayed()
        panelFind(hasText("采用的命名与学习范围")).assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        val overview = model.state.value.layer
        assertTrue(overview.fasciaChains)
        assertFalse(overview.fasciaTargets)
        assertEquals(setOf(connection.id), overview.enabledFascialConnections)
        for (mode in listOf(AtlasMode.MERIDIANS, AtlasMode.MUSCLES, AtlasMode.OVERVIEW)) {
            compose.onAllNodes(hasText(mode.title) and hasClickAction()).onLast().performClick()
        }
        compose.runOnIdle { assertEquals(overview, model.state.value.layer) }
        compose.waitUntil(10_000) {
            runBlocking { AtlasPreferences(context).read() }?.states?.get(AtlasMode.OVERVIEW)?.let {
                it.fasciaChains && !it.fasciaTargets && it.enabledFascialConnections == setOf(connection.id)
            } == true
        }
    }
}
