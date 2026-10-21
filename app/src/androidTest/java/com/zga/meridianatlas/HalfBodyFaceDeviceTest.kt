package com.zga.meridianatlas

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Checks actual model pixels as well as face materials and logical versus rendered layers. */
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

    private fun expectHalf(viewer: AnatomySurfaceView, side: CutawaySide, skinVisible: Boolean = true) {
        val structures = model.state.value.data!!.structures
        val outerIds = CutawayPolicy.outerIds(structures)
        assertTrue("Shipped source IDs for both sides must survive grouping", structures.map { it.id }.containsAll(faceIds + facialMuscleIds + deepIds))
        compose.waitUntil(30_000) {
            field<Int>(viewer, "cutawaySide") == side.sign &&
                field<Set<String>>(viewer, "cutawayStructureIds") == outerIds &&
                cutMaterialIds(viewer).containsAll(faceIds + if (skinVisible) emptySet() else facialMuscleIds)
        }
        compose.runOnIdle {
            val layer = model.state.value.layer
            assertEquals(side, layer.cutawaySide)
            assertEquals(skinVisible, layer.skin)
            assertTrue(layer.muscle && layer.organ && layer.bone)
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
                    val expected = if (skinVisible && id in facialMuscleIds) 0 else 1
                    assertEquals("Skin should cover complete-side muscles; hiding skin must restore their clipped renderables ($id)",
                        expected, engine.renderableManager.getLayerMask(instance) and 1)
                }
            }
        }
    }

    /** Wait for frames produced after the state/camera change, not just Compose idleness. */
    private fun awaitRenderedFrames(viewer: AnatomySurfaceView) {
        compose.waitForIdle()
        val frame = field<Long>(viewer, "totalFrames")
        compose.waitUntil(30_000) { field<Long>(viewer, "totalFrames") >= frame + 4 }
    }

    private fun copyModelPixels(viewer: AnatomySurfaceView): Bitmap {
        awaitRenderedFrames(viewer)
        val bitmap = Bitmap.createBitmap(viewer.width, viewer.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = -1
        compose.runOnUiThread {
            PixelCopy.request(viewer, bitmap, { code -> result = code; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("PixelCopy did not complete", copied.await(15, TimeUnit.SECONDS))
        assertEquals("PixelCopy must capture the actual Filament SurfaceView", PixelCopy.SUCCESS, result)
        return bitmap
    }

    /** Project authored metre-space probes with the actual Filament camera and viewport. */
    private fun projectPixel(viewer: AnatomySurfaceView, position: Vec3): Pair<Int, Int> {
        var result: Pair<Int, Int>? = null
        compose.runOnUiThread {
            val camera = AnatomySurfaceView::class.java.getDeclaredField("camera").apply { isAccessible = true }.get(viewer) as Camera
            val view = DoubleArray(16)
            val projection = DoubleArray(16)
            camera.getViewMatrix(view)
            camera.getProjectionMatrix(projection)
            val vx = view[0]*position.x + view[4]*position.y + view[8]*position.z + view[12]
            val vy = view[1]*position.x + view[5]*position.y + view[9]*position.z + view[13]
            val vz = view[2]*position.x + view[6]*position.y + view[10]*position.z + view[14]
            val vw = view[3]*position.x + view[7]*position.y + view[11]*position.z + view[15]
            val cx = projection[0]*vx + projection[4]*vy + projection[8]*vz + projection[12]*vw
            val cy = projection[1]*vx + projection[5]*vy + projection[9]*vz + projection[13]*vw
            val cw = projection[3]*vx + projection[7]*vy + projection[11]*vz + projection[15]*vw
            assertTrue("Forehead probe must be in front of the camera", cw > 0.0)
            result = ((cx/cw + 1.0)*viewer.width/2.0).roundToInt() to
                ((1.0 - cy/cw)*viewer.height/2.0).roundToInt()
        }
        return checkNotNull(result)
    }

    private data class ForeheadPixels(val x: Int, val y: Int, val radius: Int, val samples: Int,
        val meanRed: Double, val meanGreen: Double, val meanBlue: Double,
        val medianRed: Int, val medianGreen: Int, val medianBlue: Int, val redFraction: Double) {
        fun json() = JSONObject().put("xPx", x).put("yPx", y).put("radiusPx", radius).put("samples", samples)
            .put("meanRed", meanRed).put("meanGreen", meanGreen).put("meanBlue", meanBlue)
            .put("medianRed", medianRed).put("medianGreen", medianGreen).put("medianBlue", medianBlue).put("redFraction", redFraction)
    }

    private fun foreheadPixels(viewer: AnatomySurfaceView, bitmap: Bitmap, point: Vec3): ForeheadPixels {
        val (x, y) = projectPixel(viewer, point)
        val offset = projectPixel(viewer, point.copy(x = point.x + .004f))
        val radius = abs(offset.first-x).coerceAtLeast(3)
        assertTrue("Forehead sampling region must lie within the real model image",
            x-radius >= 0 && y-radius >= 0 && x+radius < bitmap.width && y+radius < bitmap.height)
        var red = 0L; var green = 0L; var blue = 0L; var count = 0; var muscleRed = 0
        val rs = mutableListOf<Int>(); val gs = mutableListOf<Int>(); val bs = mutableListOf<Int>()
        for (py in y-radius..y+radius) for (px in x-radius..x+radius) {
            val color = bitmap.getPixel(px, py)
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            red += r; green += g; blue += b; ++count
            rs += r; gs += g; bs += b
            // A saturated muscle red criterion, distinct from the model's warm beige skin.
            // Full RGB means and the same-pose surface baseline remain in the evidence JSON.
            if (r >= 80 && r-g >= 30 && r > g*1.45 && r > b*1.30) ++muscleRed
        }
        return ForeheadPixels(x, y, radius, count, red.toDouble()/count, green.toDouble()/count,
            blue.toDouble()/count, rs.sorted()[count/2], gs.sorted()[count/2], bs.sorted()[count/2], muscleRed.toDouble()/count)
    }

    @Test fun actualHeadPixelsKeepTheIntactForeheadFreeOfProtrudingRedMuscles() {
        InstrumentationRegistry.getArguments().getString("halfbody_quality")?.let { requested ->
            compose.runOnUiThread { model.quality(ModelQuality.valueOf(requested.uppercase(java.util.Locale.ROOT))) }
            awaitModel()
        }
        val viewer = surface()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val label = (arguments.getString("halfbody_label") ?: "regression")
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val captureOnly = arguments.getString("halfbody_capture_only") == "true"
        val directory = File(context.filesDir, "qa").apply { mkdirs() }
        val probes = linkedMapOf("anatomical-right" to Vec3(-.02f, 1.66f, .1748f),
            "anatomical-left" to Vec3(.02f, 1.66f, .17f))
        val report = JSONObject().put("label", label).put("capture", "PixelCopy(SurfaceView)")
            .put("captureOnly", captureOnly).put("regressionAssertionsEnabled", !captureOnly)
            .put("quality", model.state.value.quality.name)
            .put("camera", "front target=(0,1.62,0.135), distance=0.5m, normal=(0,0,1)")
            .put("redCriterion", "R>=80 && R-G>=30 && R>1.45*G && R>1.30*B")
            .put("probeRadiusM", .004).put("maxIntactRedFractionIncrease", .15)
        val records = JSONObject()
        val pixels = linkedMapOf<String, Map<String, ForeheadPixels>>()
        fun capture(name: String) {
            compose.runOnUiThread {
                model.layer { it.copy(points = false, lines = false, labels = false, details = false, internalRelations = false) }
                viewer.focusOn(Vec3(0f, 1.62f, .135f), .5f, Vec3(0f, 0f, 1f))
            }
            val bitmap = copyModelPixels(viewer)
            try {
                val file = File(directory, "halfbody-$label-$name.png")
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val samples = probes.mapValues { (_, point) -> foreheadPixels(viewer, bitmap, point) }
                pixels[name] = samples
                records.put(name, JSONObject().put("path", file.absolutePath).put("width", bitmap.width)
                    .put("height", bitmap.height).put("probes", JSONObject().apply { samples.forEach { (id, value) -> put(id, value.json()) } }))
            } finally { bitmap.recycle() }
        }
        quick("体表")
        capture("surface-baseline")
        for (side in listOf(CutawaySide.LEFT, CutawaySide.RIGHT)) {
            quick(side.title)
            capture(side.name.lowercase())
        }
        report.put("frames", records)
        val file = File(directory, "halfbody-$label-metrics.json")
        file.writeText(report.toString(2))
        println("HALFBODY_PIXEL_METRICS ${report}")
        // Explicit calibration runs collect real old-renderer images without claiming a
        // regression pass. Default runs below retain the image-based acceptance assertions.
        if (captureOnly) return
        val errors = mutableListOf<String>()
        listOf("left" to "anatomical-right", "right" to "anatomical-left").forEach { (side, intact) ->
            val baseline = pixels.getValue("surface-baseline").getValue(intact)
            val actual = pixels.getValue(side).getValue(intact)
            if (baseline.redFraction > .15) errors += "$intact surface baseline is already red (${baseline.redFraction}); inspect calibration"
            if (actual.redFraction > baseline.redFraction + .15)
                errors += "$side cut exposes red muscle over intact $intact forehead: ${actual.redFraction} versus baseline ${baseline.redFraction}"
        }
        assertTrue("${errors.joinToString("; ")} | Actual PixelCopy images and RGB metrics: ${file.absolutePath}", errors.isEmpty())
    }

    @Test fun hidingSkinRestoresTheMuscleRenderablesAndTheirHalfBodyClip() {
        val viewer = surface()
        quick(CutawaySide.LEFT.title)
        expectHalf(viewer, CutawaySide.LEFT)
        compose.runOnUiThread { model.layer { it.copy(skin = false) } }
        expectHalf(viewer, CutawaySide.LEFT, skinVisible = false)
        compose.runOnUiThread { model.layer { it.copy(skin = true) } }
        expectHalf(viewer, CutawaySide.LEFT)
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
