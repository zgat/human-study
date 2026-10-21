package com.zga.meridianatlas

import android.database.sqlite.SQLiteDatabase
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.zga.meridianatlas.data.AtlasRepository
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Matching debug APK: offline data binding plus the actual complete-description UI. */
@RunWith(AndroidJUnit4::class)
class St12TaraDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasViewModel

    @Before fun ready() {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.data)
        compose.runOnUiThread {
            AtlasMode.entries.forEach { model.mode(it); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.MERIDIANS)
            model.quality(ModelQuality.LOW)
        }
    }

    @Test fun offlineRepositoryKeepsChestAnchorsRoutesAndTaraSourceCollectionsDistinct() = runBlocking {
        val context = instrumentation.targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse("Offline atlas must not require network permission", "android.permission.INTERNET" in permissions)
        val loaded = AtlasRepository(context).load()
        val points = loaded.points.associateBy { it.id }
        assertEquals(413, points.size)
        assertEquals(395, points.values.count { it.taraReferences.isNotEmpty() })
        val uniqueRecords = points.values.flatMap { it.taraReferences }.distinctBy { it.sourceId }
        assertEquals(396, uniqueRecords.size) // One national-standard entry retains two Jinjin/Yuye records.
        assertEquals("Yintang", points.getValue("gv24-plus").taraReferences.single().label)
        assertEquals(listOf("Jinjing", "Yuye"), points.getValue("ex-hn12-hn13").taraReferences.map { it.label })
        assertTrue(uniqueRecords.all { record -> record.fields.keys.all { it in TaraReference.DISPLAY_FIELDS } })
        assertTrue(uniqueRecords.none { "Acupuncture Method" in it.fields || "Curator Comment" in it.fields })
        assertTrue(uniqueRecords.all { it.sourceFile == "acupoints-1.7.2.ttl" &&
            it.sourceId.startsWith("http://purl.org/tara/ontology/TARA_") && it.rowNumber > 0 })

        val raw = JSONObject(context.assets.open("content/acupoints.json").bufferedReader().use { it.readText() })
            .getJSONArray("points")
        val rawPoints = (0 until raw.length()).map { raw.getJSONObject(it) }.associateBy { it.getString("id") }
        val stomach = loaded.meridians.single { it.id == "ST" }
        for (side in listOf("left", "right")) {
            val positions = listOf("st12", "st13", "st14").map { id ->
                val point = points.getValue(id)
                val instance = point.instances.single { it.side == side }
                assertEquals("provisional", instance.status)
                assertEquals("FJ2810", instance.surfaceId)
                val position = checkNotNull(instance.position)
                assertTrue(stomach.paths.filter { !it.internal && it.side == side }.any { path ->
                    path.points.any { samePosition(it, position) }
                })
                position
            }
            val column = checkNotNull(points.getValue("st15").instances.single { it.side == side }.position).x
            positions.forEach { assertEquals(column, it.x, 1e-6f) }
            assertTrue(positions[0].y > positions[1].y)
            assertTrue("ST13 and ST14 must no longer pile up", positions[1].y - positions[2].y > .005f)
            fun evidence(id: String): JSONObject {
                val instances = rawPoints.getValue(id).getJSONArray("instances")
                val instance = (0 until instances.length()).map { instances.getJSONObject(it) }
                    .single { it.getString("side") == side }
                return instance.getJSONObject("anchor").getJSONObject("placementEvidence")
            }
            assertTrue(positions[0].y > evidence("st12").getJSONArray("clavicleSuperiorSectionPoint").getDouble(1))
            assertTrue(positions[1].y < evidence("st13").getJSONArray("clavicleInferiorSectionPoint").getDouble(1))
            assertTrue(positions[2].y > evidence("st14").getDouble("secondRibUpperRadialProjectionY"))
            assertTrue(positions[2].y < evidence("st14").getDouble("firstRibLowerRadialProjectionY"))
        }
        // Read the real Room seed, rather than merely checking an in-memory parser fixture.
        SQLiteDatabase.openDatabase(context.getDatabasePath("atlas.db").absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT body FROM atlas_entries WHERE kind = ?", arrayOf("point")).use { cursor ->
                assertEquals(413, cursor.count)
                while (cursor.moveToNext()) {
                    val body = JSONObject(cursor.getString(0))
                    assertFalse("TARA must not be merged into the curated Room record", body.has("taraReferences"))
                    assertFalse(body.has("Location"))
                }
            }
        }
    }

    @Test fun taraReaderAndLicenseKeepSelectedChestPointCameraAndActualViewportUnchanged() {
        compose.runOnUiThread {
            compose.activity.setContent {
                Box(Modifier.sizeIn(maxWidth = 420.dp, maxHeight = 760.dp).fillMaxSize()) { MeridianAtlasApp(model) }
            }
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
        val viewer = surface()
        compose.runOnUiThread {
            model.selectPoint("st12", reveal = false)
            model.layer { it.copy(details = true, skin = true, points = true, lines = true, cutawaySide = CutawaySide.NONE) }
            viewer.focusOn(Vec3(0f, 1.410f, .140f), .65f, Vec3(0f, 0f, 1f), animated = false)
        }
        compose.waitForIdle()
        val frame = field<Long>(viewer, "totalFrames")
        compose.waitUntil(30_000) { field<Long>(viewer, "totalFrames") >= frame + 4 }
        val chest = listOf("st12", "st13", "st14").map { id ->
            checkNotNull(model.state.value.data!!.points.single { it.id == id }.instances.single { it.side == "left" }.position)
        }
        val pixels = project(viewer, chest)
        assertTrue("Anatomical left must project to the same side of the front view",
            pixels.all { it.first > viewer.width / 2.0 })
        // Equal world X does not mean equal screen X under perspective: remove
        // the actual Z-dependent depth before comparing this chest column.
        val depthScaledX = pixels.zip(chest).map { (pixel, point) ->
            (pixel.first - viewer.width / 2.0) * (.140f + .65f - point.z)
        }
        assertTrue(depthScaledX.all { abs(it - depthScaledX.first()) < .05 })
        assertTrue(pixels[0].second < pixels[1].second && pixels[1].second < pixels[2].second)
        assertTrue(pixels.all { it.first in 0.0..viewer.width.toDouble() && it.second in 0.0..viewer.height.toDouble() })
        capture(viewer)
        val bounds = bounds(viewer)
        val pose = field<Any>(viewer, "pose")
        val st12 = model.state.value.data!!.points.single { it.id == "st12" }
        val reference = st12.taraReferences.single()
        val sourceLocation = reference.fields.getValue("Location").first()

        fun unchanged(stage: String) {
            compose.waitForIdle()
            assertEquals("$stage: selection", "st12", model.state.value.selectedPoint)
            assertSame("$stage: model surface", viewer, surface())
            assertEquals("$stage: actual viewport", bounds, bounds(viewer))
            assertEquals("$stage: camera pose", pose, field<Any>(viewer, "pose"))
        }
        compose.onNodeWithContentDescription("查看完整说明").performClick()
        compose.onNodeWithText("展开 TARA 原文").performScrollTo().performClick()
        compose.onNodeWithText(sourceLocation).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TARA · ${reference.label}").assertExists()
        compose.onNodeWithText("Acupuncture Method").assertDoesNotExist()
        compose.onNodeWithText("Avoid puncturing the artery.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Puncture perpendicularly 0.3-0.5 inch.", substring = true).assertDoesNotExist()
        unchanged("Expanded TARA source")
        compose.onNodeWithText("TARA 来源与许可").performScrollTo().performClick()
        compose.onNodeWithText("来源与许可").assertIsDisplayed()
        compose.onNode(hasText("TARA-NOTICE.md") and isSelected()).assertExists()
        compose.onNodeWithText("# 1 TARA 原文资料与许可", substring = true).assertIsDisplayed()
        unchanged("Opened TARA notice")
    }

    private fun samePosition(a: Vec3, b: Vec3) =
        abs(a.x - b.x) < 1e-6f && abs(a.y - b.y) < 1e-6f && abs(a.z - b.z) < 1e-6f

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
        return checkNotNull(result)
    }

    private fun bounds(viewer: AnatomySurfaceView): Rect {
        val result = Rect()
        compose.runOnUiThread {
            val location = IntArray(2)
            viewer.getLocationOnScreen(location)
            result.set(location[0], location[1], location[0] + viewer.width, location[1] + viewer.height)
        }
        return result
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(viewer: AnatomySurfaceView, name: String): T {
        var result: T? = null
        compose.runOnUiThread {
            result = AnatomySurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(viewer) as T
        }
        return checkNotNull(result)
    }

    private fun project(viewer: AnatomySurfaceView, points: List<Vec3>): List<Pair<Double, Double>> {
        var result = emptyList<Pair<Double, Double>>()
        compose.runOnUiThread {
            val camera = AnatomySurfaceView::class.java.getDeclaredField("camera").apply { isAccessible = true }.get(viewer) as Camera
            val view = DoubleArray(16); val projection = DoubleArray(16)
            camera.getViewMatrix(view); camera.getProjectionMatrix(projection)
            result = points.map { p ->
                val v = doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble(), 1.0)
                fun multiply(matrix: DoubleArray, vector: DoubleArray) = DoubleArray(4) { row ->
                    (0..3).sumOf { column -> matrix[column * 4 + row] * vector[column] }
                }
                val clip = multiply(projection, multiply(view, v))
                assertTrue(clip[3] > 0)
                (clip[0] / clip[3] + 1) * viewer.width / 2 to (1 - clip[1] / clip[3]) * viewer.height / 2
            }
        }
        return result
    }

    private fun capture(viewer: AnatomySurfaceView) {
        val bitmap = Bitmap.createBitmap(viewer.width, viewer.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = -1
        compose.runOnUiThread {
            PixelCopy.request(viewer, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(copied.await(15, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        val folder = File(instrumentation.targetContext.filesDir, "qa").apply { mkdirs() }
        File(folder, "st12-chest-surface.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        instrumentation.uiAutomation.takeScreenshot()?.let { screen ->
            File(folder, "st12-chest-ui.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screen.recycle()
        }
    }
}
