package com.zga.meridianatlas

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.model.AtlasMode
import com.zga.meridianatlas.model.ModelQuality
import com.zga.meridianatlas.ui.AtlasViewModel
import com.zga.meridianatlas.ui.MeridianAtlasApp
import com.zga.meridianatlas.viewer.AnatomySurfaceView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Uses the installed soft keyboard and real window insets; never changes IME/system settings.
 * Text replacement only tests query content AFTER an actual click has shown the IME.
 * The device runner owns the user's preference backup, as for the other full-app fixtures.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SearchImeDeviceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val failureEvidence = QaFailureRule()
    @get:Rule(order = 2) val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasViewModel
    private lateinit var viewer: AnatomySurfaceView
    private var requestedSize by mutableStateOf<Pair<Int, Int>?>(null)
    private var originalOrientation: Int? = null
    private val observations = JSONArray()
    private val runToken = System.currentTimeMillis()

    private data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height get() = bottom - top
        fun json() = JSONArray(listOf(left, top, right, bottom))
    }
    private data class Ime(val visible: Boolean, val bottom: Int)
    private data class Nodes(val catalogue: SemanticsNode, val header: SemanticsNode,
        val title: SemanticsNode, val search: SemanticsNode, val results: SemanticsNode,
        val root: View, val windowBottomBeforeIme: Int)
    private data class Renderer(val engine: Any, val asset: Any, val viewport: Rect, val pose: Any,
        val lockViewport: Boolean)
    private data class Frame(val nanos: Long, val ime: Ime, val catalogue: ScreenRect,
        val header: ScreenRect, val title: ScreenRect, val search: ScreenRect, val results: ScreenRect,
        val viewport: Rect, val rendererFrame: Long, val rootAttached: Boolean) {
        fun json() = JSONObject().put("frameTimeNanos", nanos).put("imeVisible", ime.visible)
            .put("imeBottomPx", ime.bottom).put("catalogueScreen", catalogue.json())
            .put("headerScreen", header.json()).put("titleScreen", title.json())
            .put("searchScreen", search.json()).put("resultsScreen", results.json())
            .put("viewportScreen", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom)))
            .put("rendererFrame", rendererFrame).put("catalogueWindowAttached", rootAttached)
    }
    private data class Target(val id: String, val chinese: String, val code: String, val point: Boolean)

    @Before fun ready() {
        ui {
            originalOrientation = compose.activity.requestedOrientation
            model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
        }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.data)
        val imm = compose.activity.getSystemService(InputMethodManager::class.java)
        assumeTrue("A real enabled software input method is required; no settings are changed", imm.enabledInputMethodList.isNotEmpty())
        ui {
            AtlasMode.entries.forEach { model.mode(it); model.restore(); model.resetLayers() }
            model.clearSelection(); model.mode(AtlasMode.OVERVIEW); model.quality(ModelQuality.LOW)
            compose.activity.setContent {
                val size = requestedSize
                val bounds = if (size == null) Modifier.fillMaxSize() else
                    Modifier.sizeIn(maxWidth = size.first.dp, maxHeight = size.second.dp).fillMaxSize()
                Box(bounds.testTag(FIXTURE)) { MeridianAtlasApp(model) }
            }
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        viewer = ui { singleSurface() }
        compose.waitUntil(180_000) { ui { field<Any?>("asset") != null && field<Long>("totalFrames") > 0 } }
        observations.put(JSONObject().put("stage", "native-device")
            .put("sdk", Build.VERSION.SDK_INT).put("nativeWidthDp", compose.activity.resources.configuration.screenWidthDp)
            .put("nativeHeightDp", compose.activity.resources.configuration.screenHeightDp)
            .put("smallestWidthDp", compose.activity.resources.configuration.smallestScreenWidthDp)
            .put("hardwareKeyboard", compose.activity.resources.configuration.keyboard)
            .put("initialRequestedOrientation", originalOrientation))
    }

    @After fun saveEvidenceAndRestoreOrientation() {
        try {
            val folder = File(instrumentation.targetContext.filesDir, "qa/search-ime").apply { mkdirs() }
            File(folder, "${testName.methodName}-$runToken.json").writeText(JSONObject()
                .put("case", testName.methodName).put("observations", observations)
                .put("coordinateSpace", "Physical screen pixels: SemanticsNode.positionOnScreen and SurfaceView.getLocationOnScreen; independent of activity/dialog window origins.")
                .put("imeEvidence", "Real search clicks followed by WindowInsetsCompat.Type.ime visibility; Choreographer captures transition frames and stable show/hide endpoints. Text replacement is not IME evidence.")
                .put("boundary", "Constrained fixtures are recorded separately from native tablet orientation. No build, device connection or system keyboard setting is performed by this test source.")
                .toString(2))
        } finally {
            originalOrientation?.let { saved -> ui { compose.activity.requestedOrientation = saved } }
        }
    }

    @Test fun compactAllModesUseOneInternalSearchAndStableRealKeyboard() {
        setFixture(420, 760, "compact-requested-420x760")
        exerciseModes(sidebar = false)
    }

    @Test fun portraitTabletOrRecordedLimitedFixtureKeepsSearchFixedWithRealKeyboard() {
        // Android 16 may decline orientation requests on large displays. Do not
        // fake a native portrait pass or force display/IME configuration.
        try {
            ui { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val portrait = awaitConfigurationOrientation(Configuration.ORIENTATION_PORTRAIT, 6_000)
            val config = compose.activity.resources.configuration
            if (portrait && config.screenWidthDp >= 760 && config.screenHeightDp >= 1000) {
                setFixture(760, 1000, "portrait-native-requested-760x1000")
            } else {
                observations.put(JSONObject().put("stage", "portrait-limitation")
                    .put("orientationRequestHonored", portrait).put("availableWidthDp", config.screenWidthDp)
                    .put("availableHeightDp", config.screenHeightDp)
                    .put("reason", "Native window cannot contain 760x1000dp; use the explicitly limited 580x760dp compact fixture."))
                setFixture(580, 760, "portrait-limited-requested-580x760")
            }
            exerciseModes(sidebar = false)
        } finally {
            originalOrientation?.let { saved -> ui { compose.activity.requestedOrientation = saved } }
        }
    }

    @Test fun nativeWideSidebarKeepsItsOnlySearchFixedWithRealKeyboard() {
        if (compose.activity.resources.configuration.screenWidthDp < 840) {
            ui { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val landscape = awaitConfigurationOrientation(Configuration.ORIENTATION_LANDSCAPE, 6_000)
            observations.put(JSONObject().put("stage", "wide-orientation-request")
                .put("landscapeObserved", landscape)
                .put("availableWidthDp", compose.activity.resources.configuration.screenWidthDp))
        }
        assumeTrue("A native window at least 840dp wide is required for the real sidebar case",
            compose.activity.resources.configuration.screenWidthDp >= 840)
        ui { requestedSize = null }
        compose.waitForIdle()
        recordFixture("native-wide-sidebar")
        exerciseModes(sidebar = true)
    }

    private fun exerciseModes(sidebar: Boolean) {
        AtlasMode.entries.forEach { mode ->
            compose.onAllNodes(hasText(mode.title) and hasClickAction()).onLast().performClick()
            compose.waitForIdle()
            assertNoExternalSearch()
            if (sidebar) {
                // The wide workspace exposes the fixed sidebar directly.
                compose.onNodeWithContentDescription("图谱目录").assertDoesNotExist()
                compose.onNodeWithTag(CATALOGUE).assertIsDisplayed()
            } else {
                compose.onNodeWithTag(CATALOGUE).assertDoesNotExist()
                compose.onNodeWithContentDescription("图谱目录")
                    .assertIsDisplayed().assertHasClickAction().performClick()
            }
            compose.onNodeWithTag(CATALOGUE).assertIsDisplayed()
            compose.onNodeWithTag(TITLE).assertTextEquals(title(mode))
            assertEquals("Each mode must own exactly one search input", 1,
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().count { it.layoutInfo.isPlaced })
            val search = compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsNotFocused()
            search.assertContentDescriptionEquals(description(mode))
            val nodes = nodes()
            compose.waitUntil(30_000) { ui { !viewer.isCameraMoving } }
            // The existing wide-window inset policy is retained: record its
            // viewport, require the same renderer, but do not impose a new size policy.
            val baselineRenderer = ui { renderer(lockViewport = !sidebar) }
            val baseline = trace("${mode.name}-opened-without-keyboard", nodes, baselineRenderer, false) {}
            if (mode == AtlasMode.OVERVIEW) screenshot("catalogue-before-ime")
            val shown = trace("${mode.name}-show", nodes, baselineRenderer, true) { search.performClick() }
            search.assertIsFocused()
            if (mode == AtlasMode.OVERVIEW) screenshot("catalogue-with-ime")
            assertFixedHeader(baseline, shown, "${mode.name} show", lockViewport = !sidebar)
            if (!sidebar) {
                assertTrue("Only the result area may shrink", shown.results.height <= baseline.results.height + 2f)
                if (shown.ime.bottom > 0 && baseline.results.bottom > nodes.windowBottomBeforeIme-shown.ime.bottom) {
                    assertTrue("The compact result area must shrink above the docked IME",
                        shown.results.bottom <= nodes.windowBottomBeforeIme-shown.ime.bottom+2f)
                    assertTrue("The compact IME must reduce the result viewport", shown.results.height < baseline.results.height-1f)
                }
            }
            val hidden = trace("${mode.name}-hide", nodes, baselineRenderer, false, baseline.results.bottom) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            assertFixedHeader(baseline, hidden, "${mode.name} hide", lockViewport = !sidebar)
            compose.onNodeWithTag(CATALOGUE).assertIsDisplayed()
            trace("${mode.name}-reshow-for-query", nodes, baselineRenderer, true) { search.performClick() }

            val target = target(mode)
            // Genuine keyboard visibility was already asserted above, including
            // frame evidence. These actions separately exercise Chinese/ID search.
            search.performTextReplacement(target.chinese)
            compose.waitForIdle()
            scrollResultsTo(target)
            result(target).assertIsDisplayed()
            recordQueryFrame("${mode.name}-chinese-query", nodes, baseline, baselineRenderer)
            search.performTextReplacement(target.code)
            compose.waitForIdle()
            scrollResultsTo(target)
            recordQueryFrame("${mode.name}-number-query", nodes, baseline, baselineRenderer)
            val beforeSelect = ui { field<Long>("totalFrames") }
            result(target).assertIsDisplayed().performClick()
            awaitKeyboardHidden(nodes.root, "${mode.name}-selected-row")
            if (sidebar) {
                compose.onNodeWithTag(CATALOGUE).assertIsDisplayed()
                val current = ui { frame(nodes, System.nanoTime()) }
                assertFixedHeader(baseline, current, "${mode.name} selection hide", lockViewport = false)
            } else compose.onNodeWithTag(CATALOGUE).assertDoesNotExist()
            ui {
                if (target.point) assertEquals(target.id, model.state.value.selectedPoint)
                else assertEquals(target.id, model.state.value.selectedStructure)
                assertRenderer(baselineRenderer, "${mode.name} selected catalogue row", checkPose = false)
                assertTrue("The renderer's counter must not reset", field<Long>("totalFrames") >= beforeSelect)
            }
            observations.put(JSONObject().put("stage", "query-and-selection").put("mode", mode.name)
                .put("chineseQuery", target.chinese).put("numberQuery", target.code).put("selectedId", target.id)
                .put("sidebar", sidebar).put("imeHiddenAfterSelection", true))
        }
    }

    private fun screenshot(stage: String) {
        val folder = File(instrumentation.targetContext.filesDir, "qa/search-ime").apply { mkdirs() }
        val file = File(folder, "${testName.methodName}-$runToken-$stage.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        observations.put(JSONObject().put("stage", stage).put("screenshot", file.name))
    }

    private fun assertNoExternalSearch() {
        compose.onNodeWithTag("workspace-toolbar").assertDoesNotExist()
        compose.onNodeWithTag("workspace-actions").assertIsDisplayed()
        compose.onNodeWithTag("view-controls").assertIsDisplayed()
        listOf("搜索图谱", "搜索目录").forEach {
            compose.onNodeWithContentDescription(it, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
        listOf("workspace-actions", "view-controls").forEach { rail ->
            compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag(rail)),
                useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private fun nodes(): Nodes {
        fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val search = node(SEARCH)
        val root = (search.root as ViewRootForTest).view.rootView
        val windowBottom = ui { val location = IntArray(2); root.getLocationOnScreen(location); location[1]+root.height }
        return Nodes(node(CATALOGUE), node(HEADER), node(TITLE), search, node(RESULTS), root, windowBottom)
    }

    private fun trace(stage: String, nodes: Nodes, expected: Renderer, visible: Boolean,
        endpointResultsBottom: Float? = null, action: () -> Unit): Frame {
        val frames = mutableListOf<Frame>()
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        var running = true
        var stable = 0
        var priorBottom = Int.MIN_VALUE
        var priorResults: ScreenRect? = null
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                try {
                    assertRenderer(expected, stage)
                    val sample = frame(nodes, frameTimeNanos)
                    frames += sample
                    // RootWindowInsets reports the final IME size before the platform
                    // animation has applied all padding frames. Wait for the actual
                    // catalogue endpoint as well as stable insets and geometry.
                    val visibleFits = !visible || !expected.lockViewport || sample.ime.bottom == 0 ||
                        sample.results.bottom <= nodes.windowBottomBeforeIme - sample.ime.bottom + 2f
                    val hiddenRestored = endpointResultsBottom == null || abs(sample.results.bottom - endpointResultsBottom) <= 2f
                    val endpoint = sample.ime.visible == visible && (visible || sample.ime.bottom == 0) && visibleFits && hiddenRestored
                    stable = if (endpoint && sample.ime.bottom == priorBottom && sample.results == priorResults) stable + 1 else 0
                    priorBottom = sample.ime.bottom
                    priorResults = sample.results
                    if (stable >= 5) { running = false; done.countDown() }
                    else Choreographer.getInstance().postFrameCallback(this)
                } catch (failure: Throwable) { error.set(failure); running = false; done.countDown() }
            }
        }
        ui { frames += frame(nodes, System.nanoTime()); Choreographer.getInstance().postFrameCallback(callback) }
        try {
            action()
            assertTrue("$stage: actual IME did not reach a stable visible=$visible state", done.await(12, TimeUnit.SECONDS))
        } finally {
            ui { running = false; Choreographer.getInstance().removeFrameCallback(callback) }
            observations.put(JSONObject().put("stage", stage).put("expectedImeVisible", visible)
                .put("windowBottomBeforeIme", nodes.windowBottomBeforeIme)
                .put("frames", JSONArray(frames.map { it.json() })))
        }
        error.get()?.let { throw AssertionError("$stage: frame sampling failed", it) }
        assertTrue("$stage must include multiple real UI frames", frames.size >= 6)
        val baseline = frames.first()
        frames.forEach { assertFixedHeader(baseline, it, stage, expected.lockViewport) }
        assertEquals("$stage real WindowInsets endpoint", visible, frames.last().ime.visible)
        return frames.last()
    }

    private fun frame(nodes: Nodes, nanos: Long) = Frame(nanos, ime(nodes.root),
        screen(nodes.catalogue), screen(nodes.header), screen(nodes.title), screen(nodes.search), screen(nodes.results),
        viewport(), field("totalFrames"), nodes.root.isAttachedToWindow)

    private fun screen(node: SemanticsNode): ScreenRect {
        check(node.layoutInfo.isAttached && node.layoutInfo.isPlaced) { "Fixed catalogue node detached during IME transition" }
        val position = node.positionOnScreen
        check(position.x.isFinite() && position.y.isFinite())
        return ScreenRect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
    }

    private fun assertFixedHeader(expected: Frame, actual: Frame, stage: String, lockViewport: Boolean = true) {
        listOf("header" to (expected.header to actual.header), "title" to (expected.title to actual.title),
            "search" to (expected.search to actual.search)).forEach { (name, pair) ->
            val a = pair.first; val b = pair.second
            assertTrue("$stage $name must retain physical screen bounds: $a -> $b",
                abs(a.left-b.left) <= 2f && abs(a.top-b.top) <= 2f &&
                    abs(a.right-b.right) <= 2f && abs(a.bottom-b.bottom) <= 2f)
        }
        if (lockViewport) assertEquals("$stage model viewport must not resize or pan", expected.viewport, actual.viewport)
    }

    private fun recordQueryFrame(stage: String, nodes: Nodes, baseline: Frame, expected: Renderer) {
        val current = ui { assertRenderer(expected, stage); frame(nodes, System.nanoTime()) }
        assertTrue("$stage must keep the actually shown IME", current.ime.visible)
        assertFixedHeader(baseline, current, stage, expected.lockViewport)
        observations.put(JSONObject().put("stage", stage).put("geometry", current.json()))
    }

    private fun ime(root: View): Ime {
        val roots = listOf(root, compose.activity.window.decorView).filter { it.isAttachedToWindow }.distinct()
        val insets = roots.mapNotNull { ViewCompat.getRootWindowInsets(it) }
        return Ime(insets.any { it.isVisible(WindowInsetsCompat.Type.ime()) },
            insets.maxOfOrNull { it.getInsets(WindowInsetsCompat.Type.ime()).bottom } ?: 0)
    }

    private fun awaitKeyboardHidden(root: View, stage: String) {
        val done = CountDownLatch(1); val samples = JSONArray(); var running = true; var stable = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val value = ime(root)
                samples.put(JSONObject().put("frameTimeNanos", frameTimeNanos)
                    .put("imeVisible", value.visible).put("imeBottomPx", value.bottom))
                stable = if (!value.visible && value.bottom == 0) stable + 1 else 0
                if (stable >= 5) { running = false; done.countDown() }
                else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        ui { Choreographer.getInstance().postFrameCallback(callback) }
        try { assertTrue("$stage: selected row must hide the actual keyboard", done.await(12, TimeUnit.SECONDS)) }
        finally {
            ui { running = false; Choreographer.getInstance().removeFrameCallback(callback) }
            observations.put(JSONObject().put("stage", stage).put("imeFrames", samples))
        }
    }

    private fun renderer(lockViewport: Boolean) = Renderer(field("engine"), checkNotNull(field<Any?>("asset")),
        viewport(), field("pose"), lockViewport)
    private fun assertRenderer(expected: Renderer, stage: String, checkPose: Boolean = true) {
        assertSame("$stage must retain its SurfaceView", viewer, singleSurface())
        assertSame("$stage must retain its Filament engine", expected.engine, field<Any>("engine"))
        assertSame("$stage must retain the already loaded GLB asset", expected.asset, field<Any?>("asset"))
        if (expected.lockViewport) assertEquals("$stage must retain the viewport", expected.viewport, viewport())
        if (checkPose) assertEquals("$stage must retain the camera pose", expected.pose, field<Any>("pose"))
    }
    private fun viewport(): Rect {
        val location = IntArray(2); viewer.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0]+viewer.width, location[1]+viewer.height)
    }
    private fun singleSurface(): AnatomySurfaceView {
        fun find(view: View): AnatomySurfaceView? {
            if (view is AnatomySurfaceView && view.isShown) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.window.decorView)) { "No live model SurfaceView" }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = AnatomySurfaceView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(viewer) as T

    private fun setFixture(width: Int, height: Int, label: String) {
        ui { requestedSize = width to height }; compose.waitForIdle()
        assertSame(viewer, ui { singleSurface() })
        recordFixture(label)
    }
    private fun recordFixture(label: String) {
        val node = compose.onNodeWithTag(FIXTURE).fetchSemanticsNode()
        val bounds = ui { screen(node) }
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Compact fixture must stay below the sidebar breakpoint", requestedSize == null || (bounds.right-bounds.left)/density < 840f)
        observations.put(JSONObject().put("stage", "fixture").put("kind", label)
            .put("requestedDp", requestedSize?.let { JSONArray(listOf(it.first,it.second)) })
            .put("actualScreen", bounds.json()).put("actualWidthDp", (bounds.right-bounds.left)/density)
            .put("actualHeightDp", bounds.height/density)
            .put("nativeOrientation", compose.activity.resources.configuration.orientation))
    }
    private fun awaitConfigurationOrientation(orientation: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis()+timeoutMs
        val done = CountDownLatch(1); var found = false; var running = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                found = compose.activity.resources.configuration.orientation == orientation
                if (found || SystemClock.uptimeMillis() >= deadline) { running = false; done.countDown() }
                else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        ui { Choreographer.getInstance().postFrameCallback(callback) }
        try { done.await(timeoutMs+2_000, TimeUnit.MILLISECONDS) }
        finally { ui { running = false; Choreographer.getInstance().removeFrameCallback(callback) } }
        compose.waitForIdle()
        return found
    }
    private fun target(mode: AtlasMode): Target {
        val data = checkNotNull(model.state.value.data)
        if (mode == AtlasMode.MERIDIANS) {
            val point = data.points.first { it.id == "lu1" }
            return Target(point.id,point.nameZh,point.codes.first(),true)
        }
        val system = if (mode == AtlasMode.MUSCLES) "muscle" else "bone"
        val structure = data.structureGroups.entries.map { it.representative }
            .filter { it.system == system && it.nameZh.any { ch -> ch in '\u4e00'..'\u9fff' } }
            .minBy { it.nameZh.length }
        return Target(structure.id,structure.nameZh,structure.id,false)
    }
    private fun result(target: Target) = if (target.point)
        compose.onNodeWithContentDescription("定位${target.chinese}")
        else compose.onNode(hasText(target.chinese) and hasClickAction() and hasAnyAncestor(hasTestTag(RESULTS)))
    private fun scrollResultsTo(target: Target) {
        val matcher = if (target.point) hasContentDescription("定位${target.chinese}") else hasText(target.chinese)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasScrollAction() and hasAnyAncestor(hasTestTag(RESULTS)), useUnmergedTree = true).performScrollToNode(matcher)
    }
    private fun title(mode: AtlasMode) = when (mode) {
        AtlasMode.OVERVIEW -> "解剖目录"; AtlasMode.MERIDIANS -> "经络与穴位"; AtlasMode.MUSCLES -> "肌肉目录"
    }
    private fun description(mode: AtlasMode) = when (mode) {
        AtlasMode.OVERVIEW -> "搜索解剖结构"; AtlasMode.MERIDIANS -> "搜索经络与穴位"; AtlasMode.MUSCLES -> "搜索肌肉"
    }
    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<T>(); compose.runOnUiThread { result.set(action()) }; return result.get()
    }
    private companion object {
        const val FIXTURE = "search-ime-fixture"
        const val CATALOGUE = "atlas-catalogue"
        const val HEADER = "atlas-catalogue-header"
        const val TITLE = "atlas-catalogue-title"
        const val SEARCH = "atlas-catalogue-search"
        const val RESULTS = "atlas-catalogue-results"
    }
}
