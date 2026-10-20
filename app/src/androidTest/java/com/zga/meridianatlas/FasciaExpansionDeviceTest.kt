package com.zga.meridianatlas

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
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.data.AtlasDatabase
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/** Reads shipped Room content and exercises real controls; no claim of professional point review. */
@RunWith(AndroidJUnit4::class)
class FasciaExpansionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
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

    private fun openFascia() {
        compose.onAllNodes(hasText("筋膜解剖") and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("筋膜解剖与连接").assertIsDisplayed()
        compose.onNodeWithText("文献连接").performClick()
    }

    private fun panelClick(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(matcher)
        compose.onAllNodes(matcher and hasClickAction() and hasAnyAncestor(isDialog())).onFirst().performClick()
    }

    private fun expectLungPoint() = compose.runOnIdle {
        assertEquals("lu1", model.state.value.selectedPoint)
        assertEquals("LU", model.state.value.selectedPointMeridian)
        assertTrue("LU" in model.state.value.effectiveMeridians)
    }

    private fun vector(array: JSONArray) = Vec3(array.getDouble(0).toFloat(),
        array.getDouble(1).toFloat(), array.getDouble(2).toFloat())

    @Test fun roomPreservesSevenDistinctConnectionsTheirSourcesAndRealSurfaceSampleProvenance() {
        val loaded = model.state.value.data!!.fascialConnections
        val stored = runBlocking {
            withContext(Dispatchers.IO) {
                val database = Room.databaseBuilder(context, AtlasDatabase::class.java, "atlas.db").build()
                try { database.atlas().entries("fascial").associate { it.id to JSONObject(it.body) } }
                finally { database.close() }
            }
        }
        assertEquals(7, loaded.size)
        assertEquals(7, loaded.map { it.id }.distinct().size)
        assertEquals(loaded.map { it.id }.toSet(), stored.keys)
        assertEquals("Numeric JSON colors must survive Room and decoding", 7, loaded.map { it.color }.distinct().size)
        assertEquals(0xff79d1bdL, loaded.single { it.id == "latissimus-thoracolumbar-contralateral-gluteus" }.color)
        val requiredSources = mapOf(
            "gluteus-maximus-iliotibial-tract" to setOf("24139012"),
            "tensor-fasciae-latae-iliotibial-tract" to setOf("15378277"),
            "palmaris-longus-flexor-retinaculum" to setOf("17574470"),
            "transversus-abdominis-thoracolumbar-fascia" to setOf("14722403", "17924150"),
        )
        requiredSources.forEach { (id, pmids) ->
            val urls = loaded.single { it.id == id }.sourceRefs.map { it.url }.toSet()
            pmids.forEach { pmid -> assertTrue("$id must retain its primary literature source $pmid",
                "https://pubmed.ncbi.nlm.nih.gov/$pmid/" in urls) }
        }
        loaded.forEach { connection ->
            val raw = stored.getValue(connection.id)
            assertEquals(raw.getLong("color"), connection.color)
            assertTrue(connection.bodyRegionsZh.isNotEmpty())
            assertNotNull(connection.preferredViewNormal)
            val targets = connection.targets.associateBy { it.id }
            assertTrue(targets.isNotEmpty())
            assertTrue(connection.targets.all { it.sourceGeometry.isNotBlank() })
            val rawPaths = raw.getJSONArray("paths")
            val contextPaths = connection.paths.filter { it.kind == "anatomical_context_schematic" }
            assertTrue("Each group must have real-tissue extent context", contextPaths.isNotEmpty())
            connection.paths.forEach { path ->
                val rawPath = (0 until rawPaths.length()).map { rawPaths.getJSONObject(it) }
                    .single { it.getString("id") == path.id }
                assertTrue(rawPath.getBoolean("notAForceVector"))
                assertTrue(rawPath.isNull("direction") && rawPath.isNull("forceMagnitude"))
                val points = rawPath.getJSONArray("points")
                val normals = rawPath.getJSONArray("normals")
                assertEquals(points.length(), path.points.size)
                assertEquals(normals.length(), path.normals.size)
                assertEquals(path.points.size, path.normals.size)
                path.points.indices.forEach { index ->
                    assertEquals(vector(points.getJSONArray(index)), path.points[index])
                    assertEquals(vector(normals.getJSONArray(index)), path.normals[index])
                    val normal = path.normals[index]
                    assertEquals(1f, sqrt(normal.x*normal.x+normal.y*normal.y+normal.z*normal.z), .001f)
                }
                if (path.kind == "anatomical_context_schematic") {
                    assertTrue(path.targetIds.isEmpty())
                    val samples = rawPath.getJSONArray("sourceGeometrySamples")
                    assertEquals(path.points.size, samples.length())
                    for (index in 0 until samples.length()) {
                        val geometry = samples.getJSONObject(index)
                        assertTrue(geometry.getString("nodeName") in connection.structureIds)
                        assertTrue(geometry.getInt("triangleIndex") >= 0)
                        assertEquals(3, geometry.getJSONArray("vertexIndices").length())
                        assertEquals(64, geometry.getString("modelSha256").length)
                        val barycentric = geometry.getJSONArray("barycentric")
                        assertEquals(3, barycentric.length())
                        assertEquals(1.0, (0 until 3).sumOf { barycentric.getDouble(it) }, .000001)
                    }
                } else {
                    assertEquals("unoriented_connection_schematic", path.kind)
                    assertTrue(path.targetIds.isNotEmpty())
                    assertEquals(path.points.size, path.targetIds.size)
                    path.targetIds.forEachIndexed { index, id ->
                        assertTrue("A local bridge must refer to this group's canonical targets", id in targets)
                        assertEquals(targets.getValue(id).position, path.points[index])
                    }
                }
            }
        }
    }

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
        return checkNotNull(result)
    }

    private fun cameraTarget(viewer: AnatomySurfaceView, field: String): Vec3 {
        var result: Vec3? = null
        compose.runOnUiThread {
            val pose = AnatomySurfaceView::class.java.getDeclaredField(field).apply { isAccessible = true }.get(viewer)
            result = pose.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(pose) as Vec3
        }
        return checkNotNull(result)
    }

    @Test fun realBulkControlsAndRegionObservationKeepThePointAndIndependentAnnotationSettings() {
        val data = model.state.value.data!!
        val allIds = data.fascialConnections.map { it.id }.toSet()
        val union = data.fascialConnections.flatMap { it.structureIds }.toSet()
        compose.runOnUiThread { model.selectPoint("lu1", reveal = false) }
        openFascia()
        panelClick(hasContentDescription("全选筋膜文献连接"))
        compose.runOnIdle {
            assertEquals(allIds, model.state.value.layer.enabledFascialConnections)
            assertTrue(model.state.value.layer.fasciaChains)
            assertTrue(model.state.value.layer.fasciaTargets)
        }
        expectLungPoint()
        panelClick(hasText("显示连接参考区"))
        panelClick(hasText("显示连接图层"))
        compose.runOnIdle {
            assertFalse(model.state.value.layer.fasciaTargets)
            assertFalse(model.state.value.layer.fasciaChains)
            assertEquals(allIds, model.state.value.layer.enabledFascialConnections)
        }
        panelClick(hasText("显示连接图层"))
        panelClick(hasText("观察已选结构"))
        compose.onNodeWithText("筋膜解剖与连接").assertDoesNotExist()
        compose.runOnIdle {
            val layer = model.state.value.layer
            assertEquals(union, layer.focusIds)
            assertFalse(layer.skin)
            assertTrue(layer.connective)
            assertTrue(layer.fasciaChains)
            assertFalse(layer.fasciaTargets)
        }
        expectLungPoint()

        // A wrist-only group must move the actual camera to its union bounds, rather than
        // resetting to the whole-body home view used by the old observation action.
        val connection = data.fascialConnections.single { it.id == "palmaris-longus-flexor-retinaculum" }
        val bounds = data.structures.filter { it.id in connection.structureIds }.flatMap { part ->
            listOf(part.boundsMin ?: part.center, part.boundsMax ?: part.center)
        } + connection.paths.flatMap { it.points }
        val expectedCenter = Vec3((bounds.minOf { it.x }+bounds.maxOf { it.x })/2f,
            (bounds.minOf { it.y }+bounds.maxOf { it.y })/2f,
            (bounds.minOf { it.z }+bounds.maxOf { it.z })/2f)
        val viewer = surface()
        openFascia()
        panelClick(hasContentDescription("观察筋膜连接组${connection.id}"))
        compose.onNodeWithText("筋膜解剖与连接").assertDoesNotExist()
        val target = cameraTarget(viewer, "pose")
        assertEquals(expectedCenter.x, target.x, .000001f)
        assertEquals(expectedCenter.y, target.y, .000001f)
        assertEquals(expectedCenter.z, target.z, .000001f)
        assertNotEquals("Local observation must not return to the whole-body camera center", cameraTarget(viewer, "homePose"), target)
        compose.runOnIdle { assertEquals(connection.structureIds.toSet(), model.state.value.layer.focusIds) }
        expectLungPoint()

        openFascia()
        panelClick(hasContentDescription("全不选筋膜文献连接"))
        compose.runOnIdle {
            val layer = model.state.value.layer
            assertTrue(layer.enabledFascialConnections.isEmpty())
            assertTrue("Unselecting groups must not rewrite the master switch", layer.fasciaChains)
            assertFalse("Unselecting groups must not rewrite the marker switch", layer.fasciaTargets)
            assertEquals(connection.structureIds.toSet(), layer.focusIds)
        }
        expectLungPoint()
    }
}
