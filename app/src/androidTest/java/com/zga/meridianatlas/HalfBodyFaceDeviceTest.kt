package com.zga.meridianatlas

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks real face meshes and installed Filament overrides, without claiming pixel acceptance. */
@RunWith(AndroidJUnit4::class)
class HalfBodyFaceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AtlasViewModel
    private val faceIds = setOf("FJ1289", "FJ1297", "FJ1317", "FJ1340", "FJ1348", "FJ1368",
        "FJ2814", "FJ1252", "FJ1253")
    private val facialMuscleIds = setOf("ZA_46005575", "ZA_667750948", "ZA_114442261",
        "ZA_200305276", "ZA_434972166", "ZA_796791312")
    private val deepIds = setOf("FJ2816", "FJ3200", "FJ1258", "FJ1272")

    @Before fun ready() {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.data)
        compose.runOnUiThread {
            AtlasMode.entries.forEach { mode -> model.mode(mode); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.MERIDIANS)
            model.quality(ModelQuality.LOW)
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

    private fun quick(title: String) = compose.onAllNodes(hasText(title) and hasClickAction()).onFirst()
        .performScrollTo().performClick()

    private fun surface(): AnatomySurfaceView {
        var result: AnatomySurfaceView? = null
        compose.runOnUiThread {
            fun find(view: View): AnatomySurfaceView? {
                if (view is AnatomySurfaceView && view.isShown) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            result = find(compose.activity.window.decorView)
        }
        return checkNotNull(result) { "No visible model SurfaceView" }
    }

    /** All renderer state and native material reads stay on its owning UI thread. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(viewer: AnatomySurfaceView, name: String): T {
        var result: T? = null
        compose.runOnUiThread {
            result = AnatomySurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(viewer) as T
        }
        return checkNotNull(result)
    }

    private fun cutMaterialIds(viewer: AnatomySurfaceView): Set<String> {
        val ids = field<Map<Int, String>>(viewer, "entityIds")
        var result = emptySet<String>()
        compose.runOnUiThread {
            val engine = AnatomySurfaceView::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(viewer) as Engine
            val manager = engine.renderableManager
            val bindings = AnatomySurfaceView::class.java.getDeclaredField("highlighted").apply { isAccessible = true }
                .get(viewer) as List<*>
            result = bindings.mapNotNull { binding ->
                val item = checkNotNull(binding)
                val entity = item.javaClass.getDeclaredField("entity").apply { isAccessible = true }.getInt(item)
                val primitive = item.javaClass.getDeclaredField("primitive").apply { isAccessible = true }.getInt(item)
                val material = item.javaClass.getDeclaredField("selected").apply { isAccessible = true }.get(item) as MaterialInstance
                val instance = manager.getInstance(entity)
                ids[entity].takeIf { material.name == "atlas-cutaway" && instance != 0 &&
                    manager.getMaterialInstanceAt(instance, primitive)?.name == "atlas-cutaway" }
            }.toSet()
        }
        return result
    }

    private fun expectHalf(viewer: AnatomySurfaceView, side: CutawaySide) {
        val structures = model.state.value.data!!.structures
        val outerIds = CutawayPolicy.outerIds(structures)
        assertTrue("Shipped source IDs for both sides must survive grouping", structures.map { it.id }.containsAll(faceIds + facialMuscleIds + deepIds))
        compose.waitUntil(30_000) {
            field<Int>(viewer, "cutawaySide") == side.sign &&
                field<Set<String>>(viewer, "cutawayStructureIds") == outerIds &&
                cutMaterialIds(viewer).containsAll(faceIds + facialMuscleIds)
        }
        compose.runOnIdle {
            val layer = model.state.value.layer
            assertEquals(side, layer.cutawaySide)
            assertTrue(layer.skin && layer.muscle && layer.organ && layer.bone)
            val visible = VisibilityPolicy.visibleIds(structures, layer)
            assertTrue("Half views must retain both halves and deep organs; the fragment mask decides the cut", visible.containsAll(faceIds + facialMuscleIds + deepIds))
        }
        assertEquals("Renderer must receive original mesh IDs for both halves, not only the cut-side nodes",
            outerIds, field<Set<String>>(viewer, "cutawayStructureIds"))
        assertTrue("Internal organs, skull and teeth must not receive the surface cut material",
            cutMaterialIds(viewer).intersect(deepIds).isEmpty())
        val engine = field<Engine>(viewer, "engine")
        val entities = field<Map<String, List<Int>>>(viewer, "entitiesById")
        compose.runOnUiThread {
            (faceIds + facialMuscleIds + deepIds).forEach { id ->
                val meshes = entities[id].orEmpty()
                assertTrue("Expected renderable mesh for $id", meshes.isNotEmpty())
                meshes.forEach { entity ->
                    val instance = engine.renderableManager.getInstance(entity)
                    assertNotEquals("Expected a renderable instance for $id", 0, instance)
                    assertEquals("A half view must keep $id submitted and clip its fragments, not hide a whole node",
                        1, engine.renderableManager.getLayerMask(instance) and 1)
                }
            }
        }
    }

    @Test fun leftAndRightButtonsClipBothFacialSurfacesWithoutLosingThePointOrOppositeMuscles() {
        compose.runOnUiThread { model.selectPoint("lu1", reveal = false) }
        val viewer = surface()
        for (side in listOf(CutawaySide.LEFT, CutawaySide.RIGHT, CutawaySide.LEFT)) {
            quick(side.title)
            expectHalf(viewer, side)
            compose.runOnIdle {
                assertEquals("lu1", model.state.value.selectedPoint)
                assertEquals("LU", model.state.value.selectedPointMeridian)
                assertTrue("LU" in model.state.value.effectiveMeridians)
            }
        }
    }

    @Test fun bothModelQualitiesReapplyRealFaceMaterialsInTheSameScene() {
        quick(CutawaySide.RIGHT.title)
        val viewer = surface()
        for (quality in listOf(ModelQuality.LOW, ModelQuality.STANDARD, ModelQuality.LOW)) {
            compose.runOnUiThread { model.quality(quality) }
            awaitModel()
            assertSame("Changing LOD must reuse the model view", viewer, surface())
            expectHalf(viewer, CutawaySide.RIGHT)
            val colors = field<Map<String, List<FloatArray>>>(viewer, "cutawayBaseColors")
            assertTrue("Cut surfaces need each real face mesh's source primitive colors", colors.keys.containsAll(faceIds))
            faceIds.forEach { id ->
                assertTrue("Missing source primitive colors for $id", colors.getValue(id).isNotEmpty())
                colors.getValue(id).forEach { color ->
                    assertEquals(4, color.size)
                    assertTrue(color.all { it.isFinite() && it in 0f..1f })
                }
            }
            val iris = colors.getValue("FJ1297").first()
            val muscle = colors.getValue("ZA_667750948").first()
            assertFalse("An iris must retain its source color rather than the generic muscle red", iris.contentEquals(muscle))
        }
    }

    @Test fun fullOrgansAndSurfaceRestoreUncutMaterialsAndTheirExpectedLayers() {
        val viewer = surface()
        quick(CutawaySide.LEFT.title)
        expectHalf(viewer, CutawaySide.LEFT)
        quick("内脏透视")
        compose.waitUntil(30_000) { field<Int>(viewer, "cutawaySide") == 0 && cutMaterialIds(viewer).isEmpty() }
        compose.runOnIdle {
            val layer = model.state.value.layer
            assertEquals(CutawaySide.NONE, layer.cutawaySide)
            assertFalse(layer.skin)
            assertFalse("Only the half-body preset keeps the opposite side's muscles", layer.muscle)
            assertTrue(layer.organ && layer.bone)
        }
        assertFalse(field<Boolean>(viewer, "opaqueSkinOcclusionEnabled"))
        quick("体表")
        compose.waitUntil(30_000) {
            field<Int>(viewer, "cutawaySide") == 0 && field<Boolean>(viewer, "opaqueSkinOcclusionEnabled") &&
                cutMaterialIds(viewer).isEmpty()
        }
        compose.runOnIdle {
            val layer = model.state.value.layer
            assertTrue(layer.skin)
            assertFalse(layer.organ || layer.bone)
            assertEquals(CutawaySide.NONE, layer.cutawaySide)
            val visible = VisibilityPolicy.visibleIds(model.state.value.data!!.structures, layer)
            assertTrue("Visible skin must retain the eyes, lips and gums even when deep organs are disabled", visible.containsAll(faceIds))
            assertTrue("The surface preset must hide deep organs, skull and teeth", visible.intersect(deepIds).isEmpty())
        }
        assertTrue("Surface occlusion must retain the outer eye/mouth IDs as exceptions",
            field<Set<String>>(viewer, "exteriorStructureIds").containsAll(faceIds))
    }
}
