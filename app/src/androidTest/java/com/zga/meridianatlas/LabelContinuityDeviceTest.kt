package com.zga.meridianatlas

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.model.*
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
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Diagnostic evidence for adjacent real catalogue selections. Canvas names have no semantics:
 * eligibility below is the input to label placement, not a claim that drawText ran for a name.
 * Native-frame collection never takes screenshots, serializes JSON, or waits for Compose idle.
 */
@RunWith(AndroidJUnit4::class)
class LabelContinuityDeviceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val failureEvidence = QaFailureRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasViewModel
    private lateinit var viewer: AnatomySurfaceView
    private val captures = mutableListOf<Pair<String, Bitmap>>()
    private var scale = 1f

    private data class Frame(
        val nanos: Long, val selected: String?, val moving: Boolean, val renderedFrames: Long,
        val width: Int, val height: Int, val showLabels: Boolean, val publication: Int,
        val anchors: List<ProjectedAnchor>, val eligibleNames: List<String>
    )
    private data class Publication(val nanos: Long, val selected: String?, val anchors: List<ProjectedAnchor>)
    private data class CardProbe(val nanos: Long, val completedNanos: Long, val selected: String?,
        val selectedAfter: String?, val texts: List<String>, val bounds: List<Float>?, val error: String? = null)
    private data class Trace(val name: String, val frames: List<Frame>, val cards: List<CardProbe>,
        val targetAtStart: ProjectedAnchor?)

    @Before fun ready() {
        scale = Settings.Global.getFloat(instrumentation.targetContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        compose.runOnUiThread {
            // Long semantic/frame probes generate no physical touches; keep this test window awake.
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
        }
        compose.waitUntil(180_000) { !model.state.value.loading }
        assertNull(model.state.value.error)
        compose.runOnUiThread {
            AtlasMode.entries.forEach { model.mode(it); model.restore(); model.resetLayers() }
            model.clearSelection()
            model.mode(AtlasMode.MERIDIANS)
            model.quality(ModelQuality.LOW)
            model.setMeridians(model.state.value.enabledMeridians, false)
            model.layer { it.copy(skin = true, muscle = false, organ = false, bone = false,
                points = true, lines = true, labels = true, details = true, cutawaySide = CutawaySide.NONE) }
        }
        compose.waitUntil(180_000) {
            compose.onAllNodesWithText("正在载入真实解剖模型").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("图层控制").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("请检查模型资源是否完整随包提供").assertDoesNotExist()
        ui {
            fun find(view: View): AnatomySurfaceView? {
                if (view is AnatomySurfaceView && view.isShown) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            viewer = checkNotNull(find(compose.activity.window.decorView))
            viewer.applyCameraPreset(CameraPreset.RESET, animated = false)
        }
    }

    @After fun saveScreenshotsOutsideFrameSampling() {
        if (captures.isEmpty()) return
        val folder = folder()
        captures.forEach { (name, bitmap) ->
            try {
                File(folder, "$name.png").outputStream().use {
                    assertTrue("PNG encoding failed for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        captures.clear()
    }

    @Test fun adjacentCataloguePointsKeepRawProjectionAndSelectionCardEvidence() {
        observePair("st12", "st13")
        observePair("lu1", "st12")
        assertEquals("Only the two stable endpoints of each pair are captured", 4, captures.size)
    }

    @Test fun sameMeridianSelectionKeepsRawInstanceAndCommittedCard() {
        val trace = observePair("st12", "st13", captureEndpoints = false, traceName = "acceptance-st12-st13")
        assertProjectionAndCardContinuity(trace, "st12", "st13")
    }

    @Test fun newMeridianSelectionKeepsRawInstanceAndCommittedCard() {
        val trace = observePair("lu1", "st12", captureEndpoints = false, traceName = "acceptance-lu1-st12")
        assertProjectionAndCardContinuity(trace, "lu1", "st12")
    }

    @Test fun lateralChestSelectionKeepsRawInstanceCardAndDistinctLocationDescriptions() {
        val points = model.state.value.data!!.points.associateBy { it.id }
        val from = points.getValue("gb22")
        val to = points.getValue("gb23")
        val raw = JSONObject(instrumentation.targetContext.assets.open("content/acupoints.json")
            .bufferedReader().use { it.readText() }).getJSONArray("points")
        val rawPoints = (0 until raw.length()).map { raw.getJSONObject(it) }.associateBy { it.getString("id") }
        for (point in listOf(from, to)) {
            val notice = rawPoints.getValue(point.id).optString("coordinateNoticeZh")
            assertTrue("${point.id} must retain its coordinate limitation in the actual bundled asset", notice.isNotBlank())
            assertEquals("Runtime notice must match the actual asset for ${point.id}", notice, point.coordinateNoticeZh)
        }
        assertTrue(from.locationZh.contains("第4肋间隙") && from.locationZh.contains("腋中线"))
        assertTrue(to.locationZh.contains("第4肋间隙") && to.locationZh.contains("前1寸"))
        assertNotEquals("Adjacent points must retain their separate location descriptions", from.locationZh, to.locationZh)
        val trace = observePair("gb22", "gb23", traceName = "acceptance-gb22-gb23", checkLocations = true)
        assertEquals("The lateral-chest case uses only two stable endpoint screenshots", 2, captures.size)
        assertProjectionAndCardContinuity(trace, "gb22", "gb23")
    }

    @Test fun rawHitTargetsRemainUsableWhileNewRouteProjectionWorkerIsBlocked() {
        val points = model.state.value.data!!.points.associateBy { it.id }
        val stomachAction = catalogueAction(points.getValue("st12"))
        ui { stomachAction() }
        awaitStopped()
        compose.waitForIdle()
        fun field(name: String): Any? = AnatomySurfaceView::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(viewer)
        fun cacheRevision(): Long? = field("projectionCache")?.let { cache ->
            cache.javaClass.getDeclaredField("revision").apply { isAccessible = true }.getLong(cache)
        }
        fun awaitNative(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + max(10_000L, (450 * scale).toLong() + 5_000L)
            while (true) {
                var ready = false
                ui { ready = condition() }
                if (ready) return
                assertTrue(message, SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(12)
            }
        }
        awaitNative("The selected ST12 cache and skin ray data must finish before queue blocking") {
            cacheRevision() == (field("projectionContentRevision") as Long) && field("skinOccluder") != null
        }
        lateinit var worker: ExecutorService
        ui { worker = field("projectionWorker") as ExecutorService }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val report = JSONObject().put("case", "raw-hit-targets-during-new-route-cache-invalidation")
            .put("selectedPointBeforeToggle", "st12").put("toggledRoute", "LU")
            .put("doesNotTapOrFabricateSelection", true)
        worker.execute {
            entered.countDown()
            try { release.await(30, TimeUnit.SECONDS) }
            finally { exited.countDown() }
        }
        try {
            assertTrue("Background projection blocker must enter without holding the UI thread", entered.await(10, TimeUnit.SECONDS))
            var beforeRevision = 0L
            var beforeCache: Long? = null
            ui {
                beforeRevision = field("projectionContentRevision") as Long
                beforeCache = cacheRevision()
            }
            assertEquals("The blocker starts from the completed ST12 route cache", beforeRevision, checkNotNull(beforeCache))
            // This is the same public controller action as toggling a catalogue route.
            // Keep the selected point while adding a different route; do not keep IME/search open.
            ui { model.toggleMeridian("LU") }
            // Advance Compose's test clock so its LaunchedEffect submits the new anchors.
            // Native polling below deliberately does not drive composition.
            compose.waitForIdle()
            awaitNative("Toggling another route must invalidate the selected point's route batch") {
                (field("projectionContentRevision") as Long) > beforeRevision &&
                    cacheRevision() != (field("projectionContentRevision") as Long)
            }
            // Camera movement itself remains live while the background route batch is held.
            // Inspect at its requested endpoint, so a legitimate turning occlusion is excluded.
            awaitNative("The camera must finish independently of the blocked route worker") { !viewer.isCameraMoving }
            var contentRevision = 0L
            var blockedCache: Long? = null
            var hit: ProjectedAnchor? = null
            var selectedRaw: ProjectedAnchor? = null
            var selectedDisplay: ProjectedAnchor? = null
            var freshVisible = false
            var missingVisible = true
            var skinRayLoaded = false
            ui {
                contentRevision = field("projectionContentRevision") as Long
                blockedCache = cacheRevision()
                val projected = viewer.projectionsForHitTest()
                hit = projected.firstOrNull { it.id == "hitpoint:st12|0" }
                selectedRaw = projected.firstOrNull { it.id == "point:st12|0" }
                @Suppress("UNCHECKED_CAST")
                val display = field("currentProjections") as List<ProjectedAnchor>
                selectedDisplay = display.firstOrNull { it.id == "point:st12|0" }
                freshVisible = viewer.isAnchorVisibleForHitTest("hitpoint:st12|0")
                missingVisible = viewer.isAnchorVisibleForHitTest("hitpoint:does-not-exist|0")
                skinRayLoaded = field("skinOccluder") != null
            }
            fun anchorJson(value: ProjectedAnchor?) = value?.let { JSONObject().put("id", it.id)
                .put("xPx", it.xPx).put("yPx", it.yPx).put("visible", it.visible)
                .put("frontFacing", it.frontFacing).put("occluded", it.occluded) }
            report.put("beforeContentRevision", beforeRevision).put("beforeCacheRevision", beforeCache)
                .put("blockedContentRevision", contentRevision).put("blockedCacheRevision", blockedCache)
                .put("workerStillHeld", exited.count > 0).put("hitTarget", anchorJson(hit))
                .put("selectedRawPoint", anchorJson(selectedRaw)).put("selectedDisplayedPoint", anchorJson(selectedDisplay))
                .put("skinRayDataLoaded", skinRayLoaded).put("freshNearbyRayVisible", freshVisible)
                .put("unknownAnchorVisible", missingVisible).put("selectedPoint", model.state.value.selectedPoint)
            assertTrue("Route revision must advance while the worker is held", contentRevision > beforeRevision)
            assertTrue("The route cache must still be stale at the actual hit-test observation", contentRevision != blockedCache)
            assertTrue("The blocker must remain held during hit testing", exited.count > 0)
            assertEquals("The actual ST12 selection must survive another route toggle", "st12", model.state.value.selectedPoint)
            assertNotNull("Raw ST12 hit targets must survive a stale route cache", hit)
            assertTrue("Inspect the nearby target after normal camera turning ends", hit!!.visible && hit!!.frontFacing)
            assertTrue("Fresh raw-anchor rays must work without the new batch cache", skinRayLoaded && freshVisible)
            assertFalse("Unknown anchors must not become visible through a permissive fallback", missingVisible)
            assertNotNull("The selected raw point must be projected without the prepared route cache", selectedRaw)
            assertNotNull("The selected displayed point must remain in the priority projection", selectedDisplay)
        } finally {
            release.countDown()
            val blockerExited = exited.await(10, TimeUnit.SECONDS)
            // A queue barrier proves all work enqueued behind the blocker so far was allowed
            // to run; future normal frame work remains owned by the viewer.
            worker.execute { drained.countDown() }
            val queueDrained = drained.await(10, TimeUnit.SECONDS)
            ui { }
            report.put("blockerReleased", true).put("blockerExited", blockerExited).put("queuedWorkDrained", queueDrained)
            File(folder(), "blocked-worker-hit-targets.json").writeText(report.toString(2))
            assertTrue("The blocker must exit after finally releases it", blockerExited)
            assertTrue("Queued projection work must drain after release", queueDrained)
        }
    }

    private fun observePair(fromId: String, toId: String, captureEndpoints: Boolean = true,
        traceName: String = "$fromId-$toId", checkLocations: Boolean = false): Trace {
        val data = checkNotNull(model.state.value.data)
        val points = data.points.associateBy { it.id }
        val from = points.getValue(fromId)
        val to = points.getValue(toId)
        val first = catalogueAction(from)
        ui { first() }
        awaitStopped()
        compose.onNodeWithContentDescription("查看完整说明").assertIsDisplayed()
        if (checkLocations) {
            compose.onAllNodesWithText(from.locationZh, useUnmergedTree = true).onFirst().assertExists()
            compose.onAllNodesWithText(from.coordinateNoticeZh, useUnmergedTree = true).onFirst().assertExists()
        }
        if (captureEndpoints) screenshot("$traceName-start")
        val action = catalogueAction(to)
        val frames = mutableListOf<Frame>()
        val publications = mutableListOf<Publication>()
        val cards = mutableListOf<CardProbe>()
        var original: ((List<ProjectedAnchor>) -> Unit)? = null
        var latest = emptyList<ProjectedAnchor>()
        var collecting = false
        var targetAtStart: ProjectedAnchor? = null
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!collecting) return
                val state = model.state.value
                val selected = points[state.selectedPoint]
                val anchors = latest.filter { it.id.startsWith("point:") ||
                    it.id.startsWith("hitpoint:$fromId|") || it.id.startsWith("hitpoint:$toId|") }
                val names = if (!state.layer.labels || selected == null) emptyList() else anchors.asSequence()
                    .filter { it.id.startsWith("point:") && it.visible && it.frontFacing && !it.occluded }
                    .mapNotNull { points[it.id.substringAfter(':').substringBefore('|')] }
                    .filter { if (selected.meridianId == null) it.id == selected.id else it.meridianId == selected.meridianId }
                    .map { it.nameZh }.distinct().toList()
                frames += Frame(frameTimeNanos, state.selectedPoint, viewer.isCameraMoving,
                    numberField("totalFrames"), viewer.width, viewer.height, state.layer.labels,
                    publications.size, anchors, names)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        val start = System.nanoTime()
        try {
            ui {
                @Suppress("UNCHECKED_CAST")
                latest = AnatomySurfaceView::class.java.getDeclaredField("currentProjections")
                    .apply { isAccessible = true }.get(viewer) as List<ProjectedAnchor>
                targetAtStart = latest.firstOrNull { it.id == "point:$toId|0" }
                    ?: latest.firstOrNull { it.id == "hitpoint:$toId|0" }
                original = viewer.onProjection
                val appCallback = viewer.onProjection
                viewer.onProjection = { anchors ->
                    appCallback(anchors)
                    latest = anchors
                    publications += Publication(System.nanoTime(), model.state.value.selectedPoint,
                        anchors.filter { it.id.startsWith("point:") })
                }
                collecting = true
                Choreographer.getInstance().postFrameCallback(callback)
                action() // The real displayed catalogue row's OnClick; no replacement focus API.
            }
            val deadline = SystemClock.uptimeMillis() + max(5_000L, (450 * scale).toLong() + 4_000L)
            var stoppedSince = 0L
            do {
                cards += cardProbe(setOf(from.nameZh, to.nameZh))
                var moving = false
                ui { moving = viewer.isCameraMoving }
                val now = SystemClock.uptimeMillis()
                stoppedSince = if (moving) 0L else stoppedSince.takeIf { it != 0L } ?: now
                assertTrue("Camera did not settle before evidence timeout", now < deadline)
                if (stoppedSince == 0L || now - stoppedSince < 250L) SystemClock.sleep(24)
            } while (stoppedSince == 0L || SystemClock.uptimeMillis() - stoppedSince < 250L)
        } finally {
            ui {
                collecting = false
                Choreographer.getInstance().removeFrameCallback(callback)
                original?.let { viewer.onProjection = it }
            }
            writeTrace(traceName, start, frames, publications, cards)
        }
        // Keep both baseline cases even if projections, selected labels or card text fail.
        // A completed diagnostic run is not an acceptance pass for text continuity.
        if (checkLocations) {
            compose.onAllNodesWithText(to.locationZh, useUnmergedTree = true).onFirst().assertExists()
            compose.onAllNodesWithText(to.coordinateNoticeZh, useUnmergedTree = true).onFirst().assertExists()
        }
        if (captureEndpoints) screenshot("$traceName-end")
        return Trace(traceName, frames.toList(), cards.toList(), targetAtStart)
    }

    /** These assertions cover the selected raw instance and real card text, not Canvas drawText. */
    private fun assertProjectionAndCardContinuity(trace: Trace, fromId: String, toId: String) {
        val points = model.state.value.data!!.points.associateBy { it.id }
        val targetName = points.getValue(toId).nameZh
        val sourceName = points.getValue(fromId).nameZh
        val anchorId = "point:$toId|0"
        val targetFrames = trace.frames.dropWhile { it.selected != toId }
        // Selection changes synchronously; Compose may commit the new point anchors on the next frame.
        val committedFrames = targetFrames.drop(1)
        fun eligible(anchor: ProjectedAnchor?) = anchor != null && anchor.visible && anchor.frontFacing && !anchor.occluded
        fun raw(frame: Frame) = frame.anchors.firstOrNull { it.id == anchorId }
        val moving = targetFrames.filter { it.moving }
        val halfTime = if (moving.isEmpty()) targetFrames.firstOrNull()?.nanos ?: 0L else
            moving.first().nanos + (moving.last().nanos - moving.first().nanos) / 2
        val lateWindow = committedFrames.filter { it.nanos >= halfTime }
        val visibility = lateWindow.map { eligible(raw(it)) }
        val changes = visibility.zipWithNext().count { (a, b) -> a != b }
        val settled = committedFrames.takeLastWhile { !it.moving }
        val endpointVisible = settled.lastOrNull()?.let { eligible(raw(it)) } == true
        val startVisible = eligible(trace.targetAtStart)
        val cardCommit = trace.cards.indexOfFirst { it.selectedAfter == toId && targetName in it.texts && it.error == null }
        val committedCards = if (cardCommit >= 0) trace.cards.drop(cardCommit) else emptyList()
        val missing = committedFrames.count { raw(it) == null }
        val badCards = committedCards.count { it.error != null || targetName !in it.texts || sourceName in it.texts || it.selectedAfter != toId }
        val checks = JSONObject().put("evidenceBoundary", "raw instance visibility and actual SelectionCard semantics; Canvas drawn label continuity is not asserted")
            .put("selectedAnchorId", anchorId).put("allowedInitialCommitDelayFrames", 1)
            .put("targetFrames", targetFrames.size).put("missingInstanceFramesAfterCommit", missing)
            .put("targetVisibleAtStart", startVisible).put("targetVisibleAtStableEndpoint", endpointVisible)
            .put("lateWindowFrames", visibility.size).put("lateWindowVisibilityChanges", changes)
            .put("lateWindowVisibility", JSONArray(visibility)).put("stableTailFrames", settled.size)
            .put("cardCommitProbe", cardCommit).put("committedCardProbes", committedCards.size).put("badCommittedCardProbes", badCards)
            .put("visibilityComparable", endpointVisible)
        File(folder(), "${trace.name}-checks.json").writeText(checks.toString(2))
        assertTrue("Record several frames after selecting $toId", targetFrames.size >= 3)
        assertTrue("The selected point must not revert after target selection", targetFrames.all { it.selected == toId })
        assertEquals("The selected raw instance may be absent only on the first Compose commit frame", 0, missing)
        assertTrue("The real SelectionCard must commit $targetName", cardCommit >= 0)
        assertTrue("Keep multiple probes after the card commits the target", committedCards.size >= 2)
        assertEquals("After target text commits the card must not become blank or return to $sourceName", 0, badCards)
        // A genuinely hidden/offscreen final location cannot establish a no-flicker visibility
        // premise. Mark it skipped, with the raw evidence retained, rather than calling it a pass.
        assumeTrue("The stable endpoint is hidden/offscreen; visibility continuity is not accepted for this case (see ${trace.name}-checks.json)", endpointVisible)
        assertTrue("Keep several actual stable-tail frames", settled.size >= 3)
        assertTrue("The target must stay observable once the camera has settled", settled.all { eligible(raw(it)) })
        if (startVisible) {
            assertTrue("When both endpoint views show the target it must not disappear in the latter half", visibility.all { it })
        } else {
            // Turning from a different side may bring the point into view once. Reject a
            // visible-hidden-visible bounce, without penalizing that legitimate first reveal.
            assertTrue("A normal reveal may change visibility once; repeated visibility flips indicate flicker", changes <= 1)
            assertTrue("Once the late-window target appears it must remain visible", visibility.dropWhile { !it }.all { it })
        }
    }

    private fun catalogueAction(point: Acupoint): () -> Unit {
        openCatalogueIfNeeded(compose)
        compose.onNodeWithContentDescription("搜索经络与穴位").performTextReplacement(point.codes.first())
        val node = compose.onNodeWithContentDescription("定位${point.nameZh}")
        node.assertIsDisplayed()
        val click = checkNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        return { check(click()) }
    }

    private fun cardProbe(names: Set<String>): CardProbe {
        val selected = model.state.value.selectedPoint
        val now = System.nanoTime()
        return try {
            val nodes = compose.onAllNodesWithContentDescription("查看完整说明", useUnmergedTree = true).fetchSemanticsNodes()
            fun texts(node: SemanticsNode): List<String> =
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
            var match: SemanticsNode? = null
            for (node in nodes) {
                var ancestor: SemanticsNode? = node
                while (ancestor != null) {
                    if (texts(ancestor).any { it in names }) { match = ancestor; break }
                    ancestor = ancestor.parent
                }
                if (match != null) break
            }
            val bounds = match?.boundsInRoot?.let { listOf(it.left, it.top, it.right, it.bottom) }
            CardProbe(now, System.nanoTime(), selected, model.state.value.selectedPoint,
                match?.let(::texts).orEmpty().distinct(), bounds)
        } catch (failure: Exception) {
            CardProbe(now, System.nanoTime(), selected, model.state.value.selectedPoint,
                emptyList(), null, failure.javaClass.simpleName + ": " + failure.message)
        }
    }

    private fun writeTrace(name: String, start: Long, frames: List<Frame>, publications: List<Publication>, cards: List<CardProbe>) {
        fun ms(nanos: Long) = (nanos - start) / 1_000_000.0
        fun anchor(value: ProjectedAnchor) = JSONObject().put("id", value.id).put("xPx", value.xPx)
            .put("yPx", value.yPx).put("depth", value.depth).put("visible", value.visible)
            .put("frontFacing", value.frontFacing).put("occluded", value.occluded)
        fun anchors(values: List<ProjectedAnchor>) = JSONArray(values.map(::anchor))
        val root = JSONObject().put("case", name).put("animationScale", scale)
            .put("animatorsEnabled", ValueAnimator.areAnimatorsEnabled())
            .put("diagnosticOnly", true).put("completedRunIsNotTextContinuityAcceptance", true)
            .put("labelEvidenceBoundary", "eligibleNamesBeforeLabelLayout are raw visible, front-facing, non-occluded same-meridian candidates; Canvas drawText and exclusion/collision layout are not directly observed")
            .put("sampling", "Choreographer UI frame callback; screenshot acquisition outside trace windows; PNG encoding after all trace collection")
            .put("summary", JSONObject().put("sampledFrames", frames.size).put("projectionPublications", publications.size)
                .put("framesWithoutAnyPointAnchors", frames.count { frame -> frame.anchors.none { it.id.startsWith("point:") } })
                .put("framesWithoutSelectedPointAnchor", frames.count { frame -> frame.selected != null &&
                    frame.anchors.none { it.id.startsWith("point:${frame.selected}|") } })
                .put("selectedPointSequence", JSONArray(frames.map { it.selected }.distinct()))
                .put("cardProbeErrors", cards.count { it.error != null }))
            .put("pointNames", JSONObject().apply { model.state.value.data!!.points.forEach { put(it.id, it.nameZh) } })
            .put("frames", JSONArray(frames.map { value -> JSONObject().put("ms", ms(value.nanos))
                .put("selectedPoint", value.selected).put("moving", value.moving).put("renderedFrames", value.renderedFrames)
                .put("viewportWidth", value.width).put("viewportHeight", value.height).put("showLabels", value.showLabels)
                .put("publication", value.publication).put("eligibleNamesBeforeLabelLayout", JSONArray(value.eligibleNames))
                .put("anchors", anchors(value.anchors)) }))
            .put("publications", JSONArray(publications.map { value -> JSONObject().put("ms", ms(value.nanos))
                .put("selectedPoint", value.selected).put("anchors", anchors(value.anchors)) }))
            .put("selectionCardProbes", JSONArray(cards.map { value -> JSONObject().put("startMs", ms(value.nanos))
                .put("endMs", ms(value.completedNanos)).put("selectedPointAtProbeStart", value.selected)
                .put("selectedPointAtProbeEnd", value.selectedAfter).put("texts", JSONArray(value.texts))
                .put("boundsInRoot", value.bounds?.let(::JSONArray)).put("error", value.error) }))
        File(folder(), "$name.json").writeText(root.toString(2))
    }

    private fun screenshot(name: String) {
        check(captures.size < 4) { "At most four auxiliary captures are allowed" }
        captures += name to checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    }
    private fun awaitStopped() {
        compose.waitUntil(max(5_000L, (450 * scale).toLong() + 4_000L)) {
            var moving = true
            ui { moving = viewer.isCameraMoving }
            !moving
        }
        compose.waitForIdle()
    }
    private fun numberField(name: String): Long = AnatomySurfaceView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.getLong(viewer)
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun folder() = File(instrumentation.targetContext.filesDir, "qa/label-continuity").apply { mkdirs() }
}
