package com.zga.meridianatlas

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.zga.meridianatlas.model.*
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.viewer.AnatomyAppearance
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import com.zga.meridianatlas.viewer.OrbitLighting
import com.zga.meridianatlas.viewer.OrbitPose
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Reuses MainActivity's existing composition. No second setContent or UiAutomation screenshot. */
@RunWith(AndroidJUnit4::class)
class AppearanceDeviceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val failureEvidence = QaFailureRule()
    @get:Rule val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasViewModel
    private lateinit var viewer: AnatomySurfaceView
    private val captures = JSONArray()
    private val observations = JSONArray()
    private val chest = Vec3(0f, 1.27f, 0f)
    private val oblique = Vec3(.65f, 0f, 1f)

    @Before fun ready() {
        ui {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
        }
        await("The existing activity must load its real catalogue", 180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        ui {
            AtlasMode.entries.forEach { model.mode(it); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.OVERVIEW)
            model.setMeridians(model.state.value.enabledMeridians, false)
            model.quality(ModelQuality.LOW)
            model.layer { isolatedSystem("skin") }
        }
        compose.waitForIdle()
        await("The original composition must contain a visible model surface", 180_000) {
            ui { findSurface(compose.activity.window.decorView) != null }
        }
        viewer = ui { checkNotNull(findSurface(compose.activity.window.decorView)) }
        awaitQuality(ModelQuality.LOW)
    }

    @After fun retainEvidenceAndReleaseTestWindowFlag() {
        val report = JSONObject().put("case", testName.methodName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("capture", "PixelCopy of the actual Filament SurfaceView")
            .put("visualReviewPending", true)
            .put("materialValueBoundary", "Filament 1.78 Java has no uniform-value getter. JNI-returned MaterialInstance wrappers do not retain a Java Material back-reference. Real instance names, depth, shadow and AO settings are read. Only opaque-skin declarations/shading/blend are read from the app-owned replacement matched by native instance handle. Source bone/connective and duplicated reference-skin declarations/shading/blend are unavailable through this API; numerical color/roughness values are policy tests, not GPU readback.")
            .put("observations", observations).put("captures", captures)
        if (::viewer.isInitialized) try {
            report.put("finalViewer", ui { viewerState() })
        } catch (failure: Exception) { report.put("finalViewerReadError", failure.toString()) }
        File(folder(), "${testName.methodName}.json").writeText(report.toString(2))
        ui { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun lowThenStandardUseRealPbrBindingsAndBoundedQualitySettings() {
        val originalSurface = viewer
        for (quality in listOf(ModelQuality.LOW, ModelQuality.STANDARD)) {
            awaitQuality(quality)
            assertSame("Changing quality must retain the activity's original viewer", originalSurface,
                ui { findSurface(compose.activity.window.decorView) })
            ui {
                val engine = field<Engine>("engine")
                val view = field<com.google.android.filament.View>("filamentView")
                assertTrue(engine.config.enableMultipleDirectionalLights)
                assertTrue(view.isShadowingEnabled)
                assertEquals(com.google.android.filament.View.ShadowType.PCF, view.shadowType)
                assertTrue(view.ambientOcclusionOptions.enabled)
                assertEquals(.5f, view.ambientOcclusionOptions.resolution, 0f)
                assertEquals(.05f, view.ambientOcclusionOptions.radius, .000001f)
                val expectedAo = if (quality == ModelQuality.LOW) com.google.android.filament.View.QualityLevel.LOW
                    else com.google.android.filament.View.QualityLevel.MEDIUM
                assertEquals(expectedAo, view.ambientOcclusionOptions.quality)
                val lights = field<List<Int>>("lights")
                assertEquals(3, lights.size)
                assertEquals(1, lights.count { engine.lightManager.isShadowCaster(engine.lightManager.getInstance(it)) })
                assertEquals(if (quality == ModelQuality.LOW) 1024 else 2048,
                    engine.lightManager.getShadowOptions(engine.lightManager.getInstance(lights.first())).mapSize)
                observations.put(lightingObservation("quality-${quality.name}"))
                for (system in listOf("skin", "bone", "connective")) {
                    val material = materialFor(system)
                    assertEquals(if (system == "skin") "atlas-cutaway" else system, material.name)
                    val declarations = if (system == "skin") opaqueSkinMaterialFor(material) else null
                    if (declarations != null) {
                        assertEquals(Material.Shading.LIT, declarations.shading)
                        assertTrue(declarations.hasParameter("baseColorFactor"))
                        assertTrue(declarations.hasParameter("roughnessFactor"))
                        assertTrue(declarations.hasParameter("metallicFactor"))
                        assertEquals(Material.BlendingMode.MASKED, declarations.blendingMode)
                        assertTrue(material.isDepthWriteEnabled)
                    }
                    val policy = checkNotNull(AnatomyAppearance.forMaterialName(system))
                    observations.put(JSONObject().put("quality", quality.name).put("system", system)
                        .put("actualMaterialInstance", material.name)
                        .put("actualShading", declarations?.shading?.name ?: JSONObject.NULL)
                        .put("actualBlend", declarations?.blendingMode?.name ?: JSONObject.NULL)
                        .put("declarationReadScope", if (declarations != null) "app-owned replacement matched by native instance handle"
                            else "unavailable: source JNI wrapper has no Java Material back-reference")
                        .put("actualDepthWrite", material.isDepthWriteEnabled)
                        .put("policySrgb", "#${policy.srgb.toString(16)}").put("policyRoughness", policy.roughness)
                        .put("policyMetallic", policy.metallic).put("policyLinearRgba", JSONArray(policy.color().map { it })))
                }
            }
        }
    }

    @Test fun realSkinAndIsolatedTissuesHaveComparableFrontObliqueAndBackPixels() {
        showSystem("skin")
        aim(Vec3(0f, 0f, 1f)); val front = capture("skin-low-front", sampleTorso = true)
        aim(oblique); capture("skin-low-oblique")
        aim(Vec3(0f, 0f, -1f)); val back = capture("skin-low-back", sampleTorso = true)
        assertFrontBackTorsoBalance(front, back)
        aim(oblique)
        val comparisonPose = ui { field<Any>("pose") }
        for (system in listOf("bone", "connective")) {
            showSystem(system)
            assertEquals("Tissue comparison must preserve the exact camera", comparisonPose, ui { field<Any>("pose") })
            capture("$system-low-oblique")
        }
        aim(Vec3(0f, 0f, -1f)); capture("connective-low-back")
    }

    @Test fun neutralLightRigFollowsActualOrbitIncludingSidesTiltAndBothPoles() {
        for (normal in listOf(Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f),
            Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f), Vec3(.4f, .6f, 1f),
            Vec3(-.4f, -.6f, -1f), Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f))) {
            aim(normal)
        }
    }

    @Test fun halfBodyKeepsLitOpaqueSkinAndReferenceShellDoesNotCastSolidShadows() {
        for (side in listOf(CutawaySide.LEFT, CutawaySide.RIGHT)) {
            ui { model.layer { isolatedSystem("skin").copy(muscle = true, organ = true, bone = true, cutawaySide = side) } }
            // Advance Compose/LaunchedEffect before polling the independently clocked native viewer.
            compose.waitForIdle()
            await("Half-body state must reach the renderer") { ui { field<Int>("cutawaySide") == side.sign } }
            aim(Vec3(0f, 0f, 1f))
            ui {
                val material = materialFor("skin")
                assertEquals("atlas-cutaway", material.name)
                val declarations = opaqueSkinMaterialFor(material)
                assertEquals(Material.BlendingMode.MASKED, declarations.blendingMode)
                assertTrue(material.isDepthWriteEnabled)
                assertTrue(declarations.hasParameter("cutSign"))
                assertTrue(declarations.hasParameter("roughnessFactor"))
            }
            capture("half-${side.name.lowercase()}-low-front")
        }
        // Points/lines request the source BLEND reference skin without drawing it as a solid shell.
        ui { model.layer { isolatedSystem("bone").copy(points = true, lines = true) } }
        // Native-frame polling alone does not advance the Compose test frame clock.
        compose.waitForIdle()
        await("Reference shell must replace the opaque skin binding") {
            ui { field<Boolean>("referenceSkinEnabled") && materialFor("skin").name == "atlas-override" }
        }
        ui {
            val material = materialFor("skin")
            assertEquals("atlas-override", material.name)
            assertFalse(material.isDepthWriteEnabled)
            val engine = field<Engine>("engine")
            entitiesFor("skin").forEach { entity ->
                val instance = engine.renderableManager.getInstance(entity)
                assertFalse(engine.renderableManager.isShadowCaster(instance))
                assertFalse(engine.renderableManager.isShadowReceiver(instance))
            }
            observations.put(JSONObject().put("referenceSkinActualMaterialInstance", material.name)
                .put("referenceSkinActualBlend", JSONObject.NULL)
                .put("blendReadBoundary", "unavailable: duplicated source JNI wrapper retains no Java Material back-reference")
                .put("depthWrite", material.isDepthWriteEnabled).put("shadowCaster", false))
        }
    }

    private fun isolatedSystem(system: String) = LayerState(skin = system == "skin", muscle = false,
        organ = false, bone = system == "bone", connective = system == "connective", spaces = false,
        points = false, lines = false, labels = false, details = false, fasciaChains = false)

    private fun showSystem(system: String) {
        ui { model.clearSelection(); model.layer { isolatedSystem(system) } }
        compose.waitForIdle()
        val expected = VisibilityPolicy.visibleIds(model.state.value.data!!.structures, model.state.value.layer)
        await("System visibility must reach the real renderables") { ui { field<Set<String>>("visibleIds") == expected } }
        awaitFrames()
    }

    private fun aim(normal: Vec3) {
        ui { viewer.focusOn(chest, 1.1f, normal, animated = false) }
        awaitFrames()
        observations.put(ui { lightingObservation("normal=$normal") })
    }

    private fun awaitQuality(quality: ModelQuality) {
        ui { model.quality(quality) }
        compose.waitForIdle()
        val path = "models/body-${if (quality == ModelQuality.LOW) "low" else "standard"}.glb"
        await("$path must finish its actual asset/resource upload", 180_000) {
            ui { field<String?>("currentPath") == path && field<ModelQuality>("quality") == quality &&
                field<Any?>("asset") != null && !field<Boolean>("loadingResources") &&
                field<Long>("installedGeneration") == field<Long>("generation") &&
                field<Boolean>("resumed") && field<Boolean>("attached") && !field<Boolean>("closed") }
        }
        awaitFrames()
    }

    private fun awaitFrames() {
        val before = ui { field<Long>("totalFrames") }
        await("The actual surface must render new frames after the requested change", 30_000) {
            ui { field<Long>("totalFrames") >= before + 3 }
        }
    }

    private fun capture(name: String, sampleTorso: Boolean = false): JSONObject {
        awaitFrames()
        val (width, height) = ui { viewer.width to viewer.height }
        assertTrue(width > 0 && height > 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            var status = -1
            ui { PixelCopy.request(viewer, bitmap, { result -> status = result; copied.countDown() }, Handler(Looper.getMainLooper())) }
            assertTrue("PixelCopy did not return for $name", copied.await(15, TimeUnit.SECONDS))
            assertEquals("Read the real renderer pixels for $name", PixelCopy.SUCCESS, status)
            val background = bitmap.getPixel(0, 0)
            var samples = 0; var foreground = 0; var sum = 0.0; var squared = 0.0
            for (y in 0 until height step 4) for (x in 0 until width step 4) {
                val color = bitmap.getPixel(x, y)
                val delta = abs(Color.red(color) - Color.red(background)) + abs(Color.green(color) - Color.green(background)) +
                    abs(Color.blue(color) - Color.blue(background))
                samples++
                if (delta > 35) {
                    foreground++
                    val luma = .2126 * Color.red(color) + .7152 * Color.green(color) + .0722 * Color.blue(color)
                    sum += luma; squared += luma * luma
                }
            }
            val path = File(folder(), "$name.png")
            path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val fraction = foreground.toDouble() / samples.coerceAtLeast(1)
            val mean = sum / foreground.coerceAtLeast(1)
            val record = JSONObject().put("name", name).put("file", path.name).put("width", width).put("height", height)
                .put("foregroundFraction", fraction).put("foregroundMeanLuma", mean)
                .put("foregroundLumaVariance", (squared / foreground.coerceAtLeast(1) - mean * mean).coerceAtLeast(0.0))
                .put("camera", ui { field<Any>("pose").toString() }).put("viewer", ui { viewerState() })
            if (sampleTorso) record.put("torsoLuma", torsoLuma(bitmap, background))
            captures.put(record)
            assertTrue("$name must contain actual model pixels beyond the flat background (fraction=$fraction)", fraction > .005)
            return record
        } finally { bitmap.recycle() }
    }

    /** Native light state is readable; this is distinct from unavailable material uniforms. */
    private fun lightingObservation(stage: String): JSONObject {
        val pose = field<OrbitPose>("pose")
        val manager = field<Engine>("engine").lightManager
        val actual = JSONArray()
        val expected = OrbitLighting.directions(pose)
        field<List<Int>>("lights").forEachIndexed { index, entity ->
            val instance = manager.getInstance(entity)
            val direction = manager.getDirection(instance, FloatArray(3))
            val color = manager.getColor(instance, FloatArray(3))
            assertTrue(direction.all { it.isFinite() })
            assertEquals(expected[index].x, direction[0], .00001f)
            assertEquals(expected[index].y, direction[1], .00001f)
            assertEquals(expected[index].z, direction[2], .00001f)
            assertEquals(OrbitLighting.color.x, color[0], .00001f)
            assertEquals(OrbitLighting.color.y, color[1], .00001f)
            assertEquals(OrbitLighting.color.z, color[2], .00001f)
            assertEquals(OrbitLighting.lights[index].intensity, manager.getIntensity(instance), .1f)
            assertEquals(OrbitLighting.lights[index].shadows, manager.isShadowCaster(instance))
            actual.put(JSONObject().put("direction", JSONArray(direction.map { it }))
                .put("color", JSONArray(color.map { it })).put("intensity", manager.getIntensity(instance))
                .put("shadowCaster", manager.isShadowCaster(instance)))
        }
        val ambient = field<com.google.android.filament.IndirectLight>("indirectLight").intensity
        assertEquals(OrbitLighting.ambientIntensity, ambient, .1f)
        return JSONObject().put("lightingStage", stage).put("camera", pose.toString())
            .put("readback", "actual Filament LightManager direction/color/intensity, not material uniforms")
            .put("lights", actual).put("indirectIntensity", ambient)
    }

    private fun torsoLuma(bitmap: Bitmap, background: Int): JSONObject {
        // Same framing, two symmetric screen regions of the upper torso. Exclude background;
        // these are image samples, not individually registered front/back mesh vertices.
        val x0 = (bitmap.width * .43f).toInt(); val x1 = (bitmap.width * .57f).toInt()
        val y0 = (bitmap.height * .42f).toInt(); val y1 = (bitmap.height * .58f).toInt()
        val middle = bitmap.width / 2
        fun sample(startX: Int, endX: Int): JSONObject {
            val values = mutableListOf<Double>()
            var total = 0
            for (y in y0 until y1 step 4) for (x in startX until endX step 4) {
                ++total
                val color = bitmap.getPixel(x, y)
                val delta = abs(Color.red(color) - Color.red(background)) + abs(Color.green(color) - Color.green(background)) +
                    abs(Color.blue(color) - Color.blue(background))
                if (delta > 35) values += .2126 * Color.red(color) + .7152 * Color.green(color) + .0722 * Color.blue(color)
            }
            assertTrue("The upper-torso ROI must contain enough actual skin samples", values.size >= 50)
            val fraction = values.size.toDouble() / total.coerceAtLeast(1)
            assertTrue("The torso ROI must fall on the body, not mostly background ($fraction)", fraction > .70)
            values.sort()
            return JSONObject().put("samples", values.size).put("foregroundFraction", fraction)
                .put("medianLuma", values[values.size / 2]).put("meanLuma", values.average())
        }
        return JSONObject().put("left", sample(x0, middle)).put("right", sample(middle, x1))
            .put("normalizedBounds", JSONArray(listOf(.43, .42, .57, .58)))
            .put("boundary", "8-bit captured display luma in symmetric upper-torso screen regions; front/back anatomy differs and is not vertex registered")
    }

    private fun assertFrontBackTorsoBalance(front: JSONObject, back: JSONObject) {
        for (side in listOf("left", "right")) {
            val a = front.getJSONObject("torsoLuma").getJSONObject(side).getDouble("medianLuma")
            val b = back.getJSONObject("torsoLuma").getJSONObject(side).getDouble("medianLuma")
            val ratio = maxOf(a, b) / minOf(a, b).coerceAtLeast(1.0)
            val delta = abs(a - b)
            observations.put(JSONObject().put("pixelComparison", "front/back upper-torso $side")
                .put("frontMedianLuma", a).put("backMedianLuma", b).put("ratio", ratio).put("absoluteDifference", delta)
                .put("maximumRatio", 1.35).put("maximumAbsoluteDifference", 30)
                .put("boundary", "bounded image regression for large brightness imbalance; not identical shading or clinical color accuracy"))
            assertTrue("Front/back $side torso brightness must not diverge excessively: ratio=$ratio delta=$delta",
                ratio <= 1.35 && delta <= 30.0)
        }
    }

    private fun entitiesFor(system: String): List<Int> {
        val id = model.state.value.data!!.structures.first { it.system == system }.id
        return field<Map<String, List<Int>>>("entitiesById")[id].orEmpty().also { assertTrue(it.isNotEmpty()) }
    }

    private fun materialFor(system: String): MaterialInstance {
        val engine = field<Engine>("engine")
        return checkNotNull(engine.renderableManager.getMaterialInstanceAt(
            engine.renderableManager.getInstance(entitiesFor(system).first()), 0))
    }

    private fun opaqueSkinMaterialFor(bound: MaterialInstance): Material {
        // RenderableManager.getMaterialInstanceAt constructs a new JNI wrapper without a
        // Java Material reference even for app-created instances. Match its actual native
        // handle to the retained createInstance object before reading that object's owner.
        val owned = field<List<Any>>("highlighted").map { binding ->
            binding.javaClass.getDeclaredField("selected").apply { isAccessible = true }
                .get(binding) as MaterialInstance
        }.firstOrNull { it.nativeObject == bound.nativeObject }
        assertNotNull("The bound opaque skin must match a retained app-owned replacement", owned)
        val material = checkNotNull(checkNotNull(owned).material) {
            "Material.createInstance must retain the app-owned Material back-reference"
        }
        assertEquals(field<Material>("cutawayMaterial").nativeObject, material.nativeObject)
        return material
    }

    private fun viewerState() = JSONObject().put("closed", field<Boolean>("closed")).put("resumed", field<Boolean>("resumed"))
        .put("attached", field<Boolean>("attached")).put("frames", field<Long>("totalFrames"))
        .put("quality", field<ModelQuality>("quality").name).put("path", field<String?>("currentPath"))
        .put("loadingResources", field<Boolean>("loadingResources"))
        .put("installedGeneration", field<Long>("installedGeneration")).put("generation", field<Long>("generation"))

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = AnatomySurfaceView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(viewer) as T

    private fun findSurface(view: View): AnatomySurfaceView? {
        if (view is AnatomySurfaceView && view.isShown) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun await(message: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue(message, SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(30)
        }
    }

    private fun <T> ui(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun folder() = File(instrumentation.targetContext.filesDir, "qa/appearance").apply { mkdirs() }
}
