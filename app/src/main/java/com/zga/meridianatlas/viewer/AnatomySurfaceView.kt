package com.zga.meridianatlas.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.ViewConfiguration
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Viewport
import com.google.android.filament.View.QualityLevel
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.zga.meridianatlas.model.CameraPreset
import com.zga.meridianatlas.model.CutawayPolicy
import com.zga.meridianatlas.model.FrameStats
import com.zga.meridianatlas.model.ModelQuality
import com.zga.meridianatlas.model.ProjectedAnchor
import com.zga.meridianatlas.model.ProjectionAnchor
import com.zga.meridianatlas.model.Vec3
import com.zga.meridianatlas.model.StructureGroup
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Owns one Filament scene for every atlas mode. Call resume/pause with the host lifecycle and
 * close from AndroidView.onRelease. All public methods and callbacks run on the main thread.
 *
 * GLBs and annotation anchors must use metres, Y-up, front +Z. Geometry is never normalized.
 */
class AnatomySurfaceView(context: Context) : SurfaceView(context), AutoCloseable {
    var onPick: (String?) -> Unit = {}
    /** Return true after selecting an overlay point to suppress the anatomical mesh pick. */
    var onTap: ((Float, Float) -> Boolean)? = null
    var onProjection: (List<ProjectedAnchor>) -> Unit = {}
    /** Counts submitted Filament frames; external GPU traces are needed for device acceptance. */
    var onFrameStats: (FrameStats) -> Unit = {}
    /** true = loading; false + non-null message = failure; false + null = complete. */
    var onLoadState: (Boolean, String?) -> Unit = { _, _ -> }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "atlas-model-io").apply { isDaemon = true }
    }
    private val projectionWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "atlas-skin-projection").apply { isDaemon = true }
    }
    private val choreographer = Choreographer.getInstance()
    private val engine: Engine
    private val renderer: com.google.android.filament.Renderer
    private val scene: com.google.android.filament.Scene
    private val camera: Camera
    private val cameraEntity: Int
    private val filamentView: com.google.android.filament.View
    private val materialProvider: UbershaderProvider
    private val assetLoader: AssetLoader
    private val resourceLoader: ResourceLoader
    private val cutawayMaterial: Material
    private val skybox: Skybox
    private val indirectLight: IndirectLight
    private val lights = ArrayList<Int>()
    private var appearanceQuality: ModelQuality? = null
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    private var loadTask: Future<*>? = null
    private var generation = 0L
    private var installedGeneration = 0L
    private var closed = false
    private var resumed = true
    private var attached = false
    private var frameScheduled = false
    private var loadingResources = false
    private var hasFitModel = false
    private var currentPath: String? = null
    private var quality = ModelQuality.STANDARD
    private var nodeBindings: Map<String, String> = emptyMap()
    private val entityIds = HashMap<Int, String>()
    private val entitiesById = HashMap<String, MutableList<Int>>()
    private var visibleIds: Set<String>? = null
    private var selectedIds: Set<String> = emptySet()
    private var referenceSkinEnabled = false
    private var referenceSkinIds: Set<String> = emptySet()
    private var opaqueSkinOcclusionEnabled = false
    private var exteriorStructureIds: Set<String> = emptySet()
    private var cutawaySide = 0
    private var cutawayStructureIds: Set<String> = emptySet()
    private var sourceBaseColors: Map<String, List<FloatArray>> = emptyMap()
    private var cutawayBaseColors: Map<String, List<FloatArray>> = emptyMap()
    private val highlighted = ArrayList<HighlightBinding>()
    private var anchors: List<ProjectionAnchor> = emptyList()
    private var pose = OrbitPose()
    private var homePose = pose
    private var savedPose: OrbitPose? = null
    private var cameraMotion: CameraMotion? = null
    private var motionElapsedNs = 0L
    private var motionDurationNs = 0L
    private var motionLastFrameNs = 0L
    /** Camera motion is driven by the same frames that render the model. */
    val isCameraMoving: Boolean get() = cameraMotion != null
    private val cameraBookmarks = HashMap<String, OrbitPose>()
    private var maxDistance = 12f
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var cameraPoseApplied: OrbitPose? = null
    private var cameraWidthApplied = 0
    private var cameraHeightApplied = 0
    private var projectionDirty = true
    private var projectionInFlight = false
    private var projectionContentRevision = 0L
    private var projectionIntervalNs = 33_000_000L
    private data class ProjectionCache(val revision: Long, val anchors: List<ProjectionAnchor>,
        val occluded: List<Boolean>)
    private var projectionCache: ProjectionCache? = null
    private var projectedPose: OrbitPose? = null
    private var priorityAnchorIds: Set<String> = emptySet()
    private var currentProjections: List<ProjectedAnchor> = emptyList()
    private var pickRevision = 0L
    private var skinOccluder: SkinOccluder? = null
    private var surfaceRouteProjector: SurfaceRouteProjector? = null
    private var lastRenderNs = 0L
    private var lastProjectionNs = 0L
    private var statsStartNs = 0L
    private var statsFrames = 0L
    private var totalFrames = 0L
    private val readyEntities = IntArray(128)
    private val projection = DoubleArray(16)
    private val viewMatrix = DoubleArray(16)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointerCount = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastSpan = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    init {
        Gltfio.init()
        // Filament evaluates only one directional light unless this explicit engine option is set.
        engine = Engine.Builder().backend(Engine.Backend.OPENGL)
            .config(Engine.Config().apply { enableMultipleDirectionalLights = true }).build()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        camera.setExposure(16f, 1f / 125f, 100f)
        filamentView = engine.createView().apply {
            scene = this@AnatomySurfaceView.scene
            camera = this@AnatomySurfaceView.camera
            setVisibleLayers(0xff, 1)
            setShadowingEnabled(true)
            setShadowType(com.google.android.filament.View.ShadowType.PCF)
        }
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine, true)
        cutawayMaterial = context.assets.open("materials/cutaway.filamat").use { stream ->
            val bytes = stream.readBytes()
            Material.Builder().payload(ByteBuffer.wrap(bytes), bytes.size).build(engine)
        }
        skybox = Skybox.Builder().color(0.025f, 0.038f, 0.055f, 1f).build(engine)
        scene.skybox = skybox
        val ambient = OrbitLighting.ambientIrradiance
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(ambient.x, ambient.y, ambient.z))
            .intensity(OrbitLighting.ambientIntensity).build(engine)
        scene.indirectLight = indirectLight
        val lightColor = OrbitLighting.color
        OrbitLighting.lights.zip(OrbitLighting.directions(pose)).forEach { (light, direction) ->
            addLight(direction.x, direction.y, direction.z, light.intensity,
                floatArrayOf(lightColor.x, lightColor.y, lightColor.z), shadows = light.shadows)
        }
        configureAppearance(quality)
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                if (closed) return
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                scheduleFrame()
            }

            override fun onDetachedFromSurface() {
                motionLastFrameNs = 0L
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                if (closed || width <= 0 || height <= 0) return
                // Finish work referencing the old surface size before Android changes the buffer.
                engine.flushAndWait()
                viewportWidth = width
                viewportHeight = height
                ++projectionContentRevision
                filamentView.viewport = Viewport(0, 0, width, height)
                updateCamera()
                projectedPose = null
                publishCachedProjection()
                publishProjection()
            }
        }
        uiHelper.attachTo(this)
        loadSkinOccluder()
        isClickable = true
        isFocusable = true
        contentDescription = "可旋转缩放的人体三维图谱；单指旋转，双指缩放和平移"
    }

    /** Asset path is relative to APK assets. Node names are mapped to stable catalog IDs. */
    fun loadModel(
        assetPath: String,
        nodeNameToStructureId: Map<String, String>,
        quality: ModelQuality,
    ) {
        requireMainThread()
        if (closed) return
        configureAppearance(quality)
        if (currentPath == assetPath && asset != null) {
            this.quality = quality
            if (nodeBindings != nodeNameToStructureId) {
                nodeBindings = nodeNameToStructureId.toMap()
                indexEntities()
                applyVisibility()
                applyHighlight()
            }
            return
        }
        currentPath = assetPath
        this.quality = quality
        nodeBindings = nodeNameToStructureId.toMap()
        val requestGeneration = ++generation
        loadTask?.cancel(true)
        onLoadState(true, null)
        loadTask = io.submit {
            try {
                val buffer = readAsset(assetPath)
                val colors = GlbBaseColors.read(buffer)
                main.post {
                    if (!closed && generation == requestGeneration) installModel(buffer, colors)
                }
            } catch (failure: Exception) {
                main.post {
                    if (!closed && generation == requestGeneration) {
                        currentPath = null
                        onLoadState(false, "模型读取失败：${failure.message ?: failure.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    fun setVisibleStructureIds(ids: Set<String>) {
        requireMainThread()
        if (closed || visibleIds == ids) return
        visibleIds = ids.toSet()
        applyVisibility()
        applyHighlight()
    }

    fun selectStructure(id: String?) = selectStructures(setOfNotNull(id))

    /** One anatomical selection can span several independent source meshes. */
    fun selectStructures(ids: Set<String>) {
        requireMainThread()
        if (closed || selectedIds == ids) return
        selectedIds = ids.toSet()
        applyHighlight()
    }

    /**
     * A faint, real skin shell gives surface annotations their anatomical context. Skin GLB
     * materials must use alphaMode=BLEND. Normally visible skin keeps its original color.
     */
    fun setReferenceSkin(enabled: Boolean, skinIds: Set<String>) {
        requireMainThread()
        if (closed || (referenceSkinEnabled == enabled && referenceSkinIds == skinIds)) return
        referenceSkinEnabled = enabled
        referenceSkinIds = skinIds.toSet()
        applyVisibility()
        applyHighlight()
    }

    /**
     * An opaque skin shell covers internal geometry without changing the catalog's enabled
     * layers. In a half view the clipped muscles' remaining half is still skin-covered, so
     * suppress its draws to prevent registered muscle meshes protruding through the face.
     * The opening retains whole deep organs and bones. Hiding skin restores the muscle draws.
     */
    fun setOpaqueSkinOcclusion(enabled: Boolean, exteriorIds: Set<String>) {
        requireMainThread()
        if (closed || (opaqueSkinOcclusionEnabled == enabled && exteriorStructureIds == exteriorIds)) return
        opaqueSkinOcclusionEnabled = enabled
        exteriorStructureIds = exteriorIds.toSet()
        applyVisibility()
    }

    /**
     * Remove fragments on exactly one anatomical side of the x=0 sagittal plane. +1 exposes
     * anatomical left (+X), -1 right (-X), 0 restores the uncut source materials. A structure
     * crossing the midline is genuinely clipped, rather than hidden by its bounding-box centre.
     * Eye/lip exterior surfaces follow the skin; deep organs, teeth and bones stay complete.
     */
    fun setCutawaySide(side: Int, outerIds: Set<String>) {
        requireMainThread()
        require(side in -1..1) { "cutaway side must be -1, 0 or +1" }
        if (closed || (cutawaySide == side && cutawayStructureIds == outerIds)) return
        cutawaySide = side
        cutawayStructureIds = outerIds.toSet()
        applyVisibility()
        applyHighlight()
    }

    fun setAnchors(anchors: List<ProjectionAnchor>) {
        requireMainThread()
        if (closed || this.anchors == anchors) return
        this.anchors = anchors.toList()
        ++projectionContentRevision
        projectionIntervalNs = if (anchors.count { it.occlusionTest } > 1_500) 66_000_000L else 33_000_000L
        projectionDirty = true
        projectedPose = null
        publishCachedProjection()
        publishProjection()
    }

    /** The selected name must not wait for every route sample to finish conforming. */
    fun setPriorityAnchorIds(ids: Set<String>) {
        requireMainThread()
        if (closed || priorityAnchorIds == ids) return
        priorityAnchorIds = ids.toSet()
        projectedPose = null
        publishCachedProjection()
    }

    fun applyCameraPreset(preset: CameraPreset, animated: Boolean = true) {
        requireMainThread()
        if (closed) return
        savedPose = null
        val destination = if (preset == CameraPreset.RESET) homePose else pose.copy(yaw = when (preset) {
            CameraPreset.BACK -> PI.toFloat()
            CameraPreset.LEFT -> PI.toFloat() / 2f
            CameraPreset.RIGHT -> -PI.toFloat() / 2f
            CameraPreset.FRONT, CameraPreset.RESET -> 0f
        }, pitch = 0f)
        moveCamera(destination, animated)
    }

    fun focusOnStructure(group: StructureGroup) {
        val aspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1)
        focusOn(group.representative.center, group.focusDistance(aspect))
    }

    fun focusOnRegion(min: Vec3, max: Vec3, normal: Vec3? = null) {
        val center = Vec3((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)
        val aspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1)
        focusOn(center, regionFocusDistance(min, max, aspect), normal)
    }

    /** Saves the current orbit once so a sequence of selected points has a stable return view. */
    fun focusOn(position: Vec3, distance: Float = 0.35f, normal: Vec3? = null, animated: Boolean = true) {
        requireMainThread()
        if (closed) return
        if (savedPose == null) savedPose = pose
        // View local anatomy from its nearest exterior side instead of looking through the body.
        val dx = position.x - homePose.target.x
        val dz = position.z - homePose.target.z
        val length = normal?.let { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) } ?: 0f
        val yaw = if (normal != null && length > 0.00001f) atan2(normal.x, normal.z)
            else if (dx * dx + dz * dz > 0.0001f) atan2(dx, dz) else pose.yaw
        val pitch = if (normal != null && length > 0.00001f)
            asin((normal.y / length).coerceIn(-1f, 1f)) else 0f
        moveCamera(pose.copy(target = position, yaw = yaw, pitch = pitch,
            distance = distance.coerceIn(0.08f, maxDistance)), animated)
    }

    fun restoreCamera() {
        requireMainThread()
        if (closed) return
        val original = savedPose
        savedPose = null
        original?.let { moveCamera(it) }
    }

    /** The first temporary action in a mode owns its return view until that mode restores it. */
    fun captureCamera(key: String) {
        requireMainThread()
        if (!closed) cameraBookmarks.putIfAbsent(key, pose)
    }

    fun restoreCamera(key: String) {
        requireMainThread()
        if (closed) return
        val original = cameraBookmarks.remove(key) ?: return
        moveCamera(original)
    }

    /** Finish a temporary observation without moving the selected point's current view. */
    fun discardCameraBookmark(key: String) {
        requireMainThread()
        cameraBookmarks.remove(key)
        savedPose = null
    }

    fun invalidatePendingPicks() {
        requireMainThread()
        ++pickRevision
    }

    /** Uses the viewer's current camera projection, independently of Compose recomposition timing. */
    fun projectionsForHitTest(): List<ProjectedAnchor> {
        requireMainThread()
        if (closed) return emptyList()
        publishCachedProjection()
        if (viewportWidth < 2 || viewportHeight < 2) return emptyList()
        // Pick targets are raw anatomical anchors; they never need route conforming.
        // Refresh their screen positions even while a new route batch is still preparing.
        camera.getProjectionMatrix(projection)
        camera.getViewMatrix(viewMatrix)
        return AnchorProjector.project(anchors.filter { it.id.startsWith("point:") ||
            it.id.startsWith("hitpoint:") || it.id.startsWith("fascial-target:") },
            projection, viewMatrix, pose.eye(), viewportWidth, viewportHeight)
    }

    /** Only nearby tap candidates need a fresh ray; display occlusion remains batched off-thread. */
    fun isAnchorVisibleForHitTest(id: String): Boolean {
        requireMainThread()
        if (closed) return false
        val anchor = anchors.firstOrNull { it.id == id } ?: return false
        return !anchor.occlusionTest || skinOccluder?.isOccluded(pose.eye(), anchor.position) != true
    }

    private fun moveCamera(destination: OrbitPose, animated: Boolean = true) {
        invalidatePendingPicks()
        cancelCameraMotion()
        val scale = animationDurationScale()
        if (!animated || scale == 0f || pose == destination) {
            pose = destination
            updateCamera()
            publishCachedProjection()
            publishProjection()
            return
        }
        cameraMotion = CameraMotion(pose, destination)
        motionDurationNs = (450_000_000.0 * scale).toLong().coerceAtLeast(1L)
        scheduleFrame()
    }

    private fun animationDurationScale(): Float {
        if (!ValueAnimator.areAnimatorsEnabled()) return 0f
        val scale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ValueAnimator.getDurationScale()
            else Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return scale.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 1f
    }

    private fun advanceCameraMotion(frameTimeNanos: Long) {
        val motion = cameraMotion ?: return
        if (motionLastFrameNs != 0L) motionElapsedNs += (frameTimeNanos - motionLastFrameNs).coerceAtLeast(0L)
        motionLastFrameNs = frameTimeNanos
        val fraction = (motionElapsedNs.toDouble() / motionDurationNs).coerceIn(0.0, 1.0).toFloat()
        pose = motion.poseAt(fraction)
        if (fraction >= 1f) cancelCameraMotion()
    }

    private fun cancelCameraMotion() {
        cameraMotion = null
        motionElapsedNs = 0L
        motionLastFrameNs = 0L
    }

    fun resume() {
        requireMainThread()
        if (closed) return
        if (resumed) {
            scheduleFrame()
            return
        }
        resumed = true
        resetFrameClock()
        scheduleFrame()
    }

    fun pause() {
        requireMainThread()
        if (closed || !resumed) return
        resumed = false
        motionLastFrameNs = 0L
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        resetFrameClock()
        onFrameStats(FrameStats(0f, 0f, totalFrames))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        attached = false
        motionLastFrameNs = 0L
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        pause()
        closed = true
        ++generation
        loadTask?.cancel(true)
        io.shutdownNow()
        projectionWorker.shutdownNow()
        // UiHelper must release its Surface / SwapChain before the engine is destroyed.
        uiHelper.detach()
        destroyModel()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        engine.destroyMaterial(cutawayMaterial)
        resourceLoader.destroy()
        lights.forEach {
            scene.remove(it)
            engine.destroyEntity(it)
            EntityManager.get().destroy(it)
        }
        scene.skybox = null
        scene.indirectLight = null
        engine.destroySkybox(skybox)
        engine.destroyIndirectLight(indirectLight)
        filamentView.camera = null
        filamentView.scene = null
        engine.destroyView(filamentView)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
        anchors = emptyList()
        priorityAnchorIds = emptySet()
        skinOccluder = null
        surfaceRouteProjector = null
        cameraBookmarks.clear()
        savedPose = null
        cancelCameraMotion()
        projectionCache = null
        currentProjections = emptyList()
        onPick = {}
        onTap = null
        onProjection = {}
        onFrameStats = {}
        onLoadState = { _, _ -> }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                invalidatePendingPicks()
                cancelCameraMotion()
                parent?.requestDisallowInterceptTouchEvent(true)
                pointerCount = 1
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                downTime = event.eventTime
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelCameraMotion()
                moved = true
                resetTouch(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != pointerCount) {
                    resetTouch(event)
                    return true
                }
                if (event.pointerCount >= 2) {
                    val x = (event.getX(0) + event.getX(1)) / 2f
                    val y = (event.getY(0) + event.getY(1)) / 2f
                    val span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    pose = pose.pan(x - lastTouchX, y - lastTouchY, viewportHeight)
                    if (lastSpan > 1f && span > 1f) {
                        pose = pose.copy(distance = (pose.distance * lastSpan / span).coerceIn(0.08f, maxDistance))
                    }
                    lastTouchX = x
                    lastTouchY = y
                    lastSpan = span
                } else if (event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (abs(event.x - downX) + abs(event.y - downY) > touchSlop) moved = true
                    if (moved) pose = pose.copy(yaw = pose.yaw - dx * 0.007f,
                        pitch = (pose.pitch + dy * 0.007f).coerceIn(-1.45f, 1.45f))
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                updateCamera()
                publishCachedProjection()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                moved = true
                pointerCount = 0 // Rebase on the next move; lifted pointer indices are transient.
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved && event.eventTime - downTime < 500) {
                    performClick()
                    if (onTap?.invoke(event.x, event.y) != true) pick(event.x, event.y)
                }
                pointerCount = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                pointerCount = 0
                moved = true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun resetTouch(event: MotionEvent) {
        pointerCount = event.pointerCount
        if (pointerCount >= 2) {
            lastTouchX = (event.getX(0) + event.getX(1)) / 2f
            lastTouchY = (event.getY(0) + event.getY(1)) / 2f
            lastSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
        } else {
            lastTouchX = event.x
            lastTouchY = event.y
        }
    }

    private fun pick(x: Float, y: Float) {
        if (asset == null || !uiHelper.isReadyToRender || loadingResources) return
        val requestGeneration = generation
        val requestPick = pickRevision
        filamentView.pick(x.toInt().coerceIn(0, viewportWidth - 1),
            (viewportHeight - 1 - y.toInt()).coerceIn(0, viewportHeight - 1), main) { result ->
            if (!closed && requestGeneration == generation && requestPick == pickRevision) {
                val id = entityIds[result.renderable]
                onPick(id?.takeIf { isNormallyVisible(it) && isRendered(it) })
            }
        }
    }

    private fun installModel(buffer: ByteBuffer, colors: Map<String, List<FloatArray>>) {
        try {
            destroyModel()
            sourceBaseColors = colors
            val next = assetLoader.createAsset(buffer) ?: error("GLB 格式无效")
            asset = next
            installedGeneration = generation
            indexEntities()
            applyVisibility()
            if (!hasFitModel) {
                fitModel(next)
                hasFitModel = true
            }
            if (!resourceLoader.asyncBeginLoad(next)) error("无法上传 GLB 资源")
            next.releaseSourceData()
            loadingResources = true
            applyHighlight()
            updateCamera()
            scheduleFrame()
        } catch (failure: Exception) {
            destroyModel()
            currentPath = null
            loadingResources = false
            onLoadState(false, "模型加载失败：${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private fun indexEntities() {
        entityIds.clear()
        entitiesById.clear()
        cutawayBaseColors = sourceBaseColors.mapKeys { (nodeName, _) -> nodeBindings[nodeName] ?: nodeName }
        val current = asset ?: return
        current.renderableEntities.forEach { entity ->
            val name = current.getName(entity)
            if (name == null) {
                val manager = engine.renderableManager
                manager.setLayerMask(manager.getInstance(entity), 0xff, 0)
                return@forEach
            }
            val id = nodeBindings[name] ?: name
            entityIds[entity] = id
            entitiesById.getOrPut(id) { ArrayList() }.add(entity)
        }
    }

    private fun applyVisibility() {
        if (closed) return
        val manager = engine.renderableManager
        entityIds.forEach { (entity, id) ->
            val instance = manager.getInstance(entity)
            if (instance != 0) {
                val rendered = isRendered(id)
                manager.setLayerMask(instance, 0xff, if (rendered) 1 else 0)
                // A translucent reference shell must not cast a solid silhouette on the organs.
                manager.setCastShadows(instance, rendered && !isReferenceSkin(id))
                manager.setReceiveShadows(instance, !isReferenceSkin(id))
            }
        }
        // The source skin supports BLEND for the reference shell. Keep that shell out of
        // transparent picking so it cannot block the structures visible through it.
        filamentView.setTransparentPickingEnabled(referenceSkinIds.any(::isNormallyVisible))
    }

    private fun applyHighlight() {
        clearHighlight()
        // Texture upload updates the original glTF material instances. Clone only after those
        // bindings are final, otherwise a copied instance can keep its temporary dummy textures.
        if (loadingResources) return
        val manager = engine.renderableManager
        val updatedMaterials = hashSetOf<Long>()
        entitiesById.forEach { (id, entities) ->
            val catalogSkin = id in referenceSkinIds
            val reference = isReferenceSkin(id)
            val selected = id in selectedIds && isNormallyVisible(id)
            val cutaway = cutawaySide != 0 && id in cutawayStructureIds && isNormallyVisible(id)
            entities.forEach entityLoop@ { entity ->
                val instance = manager.getInstance(entity)
                if (instance == 0) return@entityLoop
                for (primitive in 0 until manager.getPrimitiveCount(instance)) {
                    val original = manager.getMaterialInstanceAt(instance, primitive) ?: continue
                    val appearance = AnatomyAppearance.forMaterialName(original.name)
                    if (appearance != null && updatedMaterials.add(original.nativeObject)) appearance.applyTo(original)
                    val skin = catalogSkin || original.name == "skin"
                    if (!skin && !reference && !selected && !cutaway) continue
                    // The source skin is BLEND for the optional reference shell. Normal skin
                    // instead uses the lit masked material, giving it the opaque depth/AO pass.
                    val opaqueSkin = skin && !reference
                    // Our GLBs use standard glTF PBR, whose ubershader declares this factor.
                    val replacement = if (cutaway || opaqueSkin) cutawayMaterial.createInstance("atlas-cutaway")
                        else MaterialInstance.duplicate(original, "atlas-override")
                    if (cutaway || opaqueSkin) {
                        replacement.setParameter("cutSign", if (cutaway) cutawaySide.toFloat() else 0f)
                        val color = appearance?.color() ?: cutawayBaseColors[id]?.getOrNull(primitive)
                            ?: floatArrayOf(1f, 1f, 1f, 1f)
                        replacement.setParameter("baseColorFactor", color[0], color[1], color[2], color[3])
                        replacement.setParameter("roughnessFactor", appearance?.roughness ?: .73f)
                        replacement.setParameter("metallicFactor", appearance?.metallic ?: 0f)
                    }
                    if (skin) {
                        // gltfio's double-sided BLEND mode draws back/front passes and ignores
                        // face culling. Draw only the outer surface; opaque skin also writes
                        // depth so triangle order cannot reveal surfaces behind it.
                        replacement.setDoubleSided(false)
                        replacement.setCullingMode(Material.CullingMode.BACK)
                        replacement.setTransparencyMode(Material.TransparencyMode.DEFAULT)
                        replacement.setDepthWrite(!reference)
                    }
                    val alpha = if (reference) 0.055f else 1f
                    if (selected) replacement.setParameter("baseColorFactor", 1f, 0.62f, 0.08f, alpha)
                    else if (reference) AnatomyAppearance.forMaterialName("skin")!!.applyTo(replacement, alpha)
                    manager.setMaterialInstanceAt(instance, primitive, replacement)
                    highlighted += HighlightBinding(entity, primitive, original, replacement)
                }
            }
        }
    }

    private fun isNormallyVisible(id: String): Boolean = visibleIds?.contains(id) ?: true

    private fun isReferenceSkin(id: String): Boolean =
        referenceSkinEnabled && id in referenceSkinIds && !isNormallyVisible(id)

    private fun isRendered(id: String): Boolean {
        val opaqueSkin = opaqueSkinOcclusionEnabled && referenceSkinIds.any(::isNormallyVisible)
        val covered = CutawayPolicy.isOccludedBySkin(id, opaqueSkin, cutawaySide != 0,
            referenceSkinIds, exteriorStructureIds, cutawayStructureIds)
        return (isNormallyVisible(id) && !covered) || isReferenceSkin(id)
    }

    private fun clearHighlight() {
        val manager = engine.renderableManager
        highlighted.forEach {
            val instance = manager.getInstance(it.entity)
            if (instance != 0) manager.setMaterialInstanceAt(instance, it.primitive, it.original)
            engine.destroyMaterialInstance(it.selected)
        }
        highlighted.clear()
    }

    private fun fitModel(model: FilamentAsset) {
        val box = model.boundingBox
        val center = box.center
        val half = box.halfExtent
        val aspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1)
        val halfFovTan = tan(Math.toRadians(22.5)).toFloat()
        val fitDistance = (max(half[1] / halfFovTan,
            max(half[0], half[2]) / (halfFovTan * aspect.coerceAtLeast(0.2f))) + half[2]) * 1.12f
        homePose = OrbitPose(Vec3(center[0], center[1], center[2]), distance = fitDistance.coerceAtLeast(0.5f))
        pose = homePose
        cancelCameraMotion()
        maxDistance = max(6f, homePose.distance * 4f)
    }

    private fun updateCamera() {
        if (cameraPoseApplied == pose && cameraWidthApplied == viewportWidth &&
            cameraHeightApplied == viewportHeight) return
        val eye = pose.eye()
        // A downward / upward point normal can be parallel to world Y (sole, perineum, palate).
        // Use the orbit's screen-up vector at a pole to keep lookAt invertible.
        val pole = abs(cos(pose.pitch)) < 0.02f
        val upX = if (pole) -sin(pose.yaw) * sin(pose.pitch) else 0f
        val upY = if (pole) 0f else 1f
        val upZ = if (pole) -cos(pose.yaw) * sin(pose.pitch) else 0f
        camera.lookAt(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(),
            pose.target.x.toDouble(), pose.target.y.toDouble(), pose.target.z.toDouble(),
            upX.toDouble(), upY.toDouble(), upZ.toDouble())
        camera.setProjection(45.0, viewportWidth.toDouble() / viewportHeight,
            0.01, maxDistance.toDouble() + 5.0, Camera.Fov.VERTICAL)
        // Apply the same actual orbit pose to the light rig before this camera frame is drawn.
        updateLighting()
        cameraPoseApplied = pose
        cameraWidthApplied = viewportWidth
        cameraHeightApplied = viewportHeight
        projectionDirty = true
    }

    private fun publishProjection() {
        if (closed || !projectionDirty || projectionInFlight || viewportWidth < 2 || viewportHeight < 2) return
        val now = System.nanoTime()
        if (lastProjectionNs != 0L && now - lastProjectionNs < projectionIntervalNs) return
        camera.getProjectionMatrix(projection)
        camera.getViewMatrix(viewMatrix)
        val projectionSnapshot = projection.copyOf()
        val viewSnapshot = viewMatrix.copyOf()
        val anchorSnapshot = anchors
        val eye = pose.eye()
        val width = viewportWidth
        val height = viewportHeight
        val contentRevision = projectionContentRevision
        val occluder = skinOccluder
        val routeProjector = surfaceRouteProjector
        lastProjectionNs = now
        projectionInFlight = true
        projectionDirty = false
        projectionWorker.execute {
            val prepared = routeProjector?.conform(anchorSnapshot) ?: anchorSnapshot
            val projected = AnchorProjector.project(prepared, projectionSnapshot, viewSnapshot,
                eye, width, height, occluder)
            main.post {
                if (!closed) {
                    projectionInFlight = false
                    if (contentRevision == projectionContentRevision) {
                        // Reproject with the current camera, not the worker's older camera frame.
                        projectionCache = ProjectionCache(contentRevision, prepared, projected.map { it.occluded })
                        projectedPose = null
                        publishCachedProjection()
                    } else projectionDirty = true
                }
            }
        }
    }

    /** Batch routes off-thread; only the selected instance needs a current-frame skin ray. */
    private fun publishCachedProjection() {
        if (closed || projectedPose == pose || viewportWidth < 2 || viewportHeight < 2) return
        val cached = projectionCache
        camera.getProjectionMatrix(projection)
        camera.getViewMatrix(viewMatrix)
        val currentIds = if (cached?.revision != projectionContentRevision) anchors.mapTo(hashSetOf()) { it.id } else null
        val projected = cached?.let {
            AnchorProjector.project(it.anchors, projection, viewMatrix, pose.eye(), viewportWidth, viewportHeight)
                .mapIndexed { index, anchor -> anchor.copy(occluded = it.occluded[index]) }
                .filter { anchor -> anchor.id !in priorityAnchorIds && (currentIds == null || anchor.id in currentIds) }
        }.orEmpty()
        val selected = AnchorProjector.project(anchors.filter { it.id in priorityAnchorIds },
            projection, viewMatrix, pose.eye(), viewportWidth, viewportHeight, skinOccluder)
        currentProjections = projected + selected
        onProjection(currentProjections)
        projectedPose = pose
    }

    private fun loadSkinOccluder() {
        projectionWorker.execute {
            try {
                val mesh = context.assets.open("models/skin-occlusion.bin").use { SkinOccluder.readFrom(it) }
                main.post {
                    if (!closed) {
                        skinOccluder = mesh
                        surfaceRouteProjector = SurfaceRouteProjector(mesh)
                        ++projectionContentRevision
                        projectedPose = null
                        projectionDirty = true
                        publishCachedProjection()
                        publishProjection()
                    }
                }
            } catch (failure: Exception) {
                if (!closed) Log.e("MeridianAtlas", "Skin occlusion mesh failed to load", failure)
            }
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (closed || !resumed || !attached) return
            scheduleFrame()
            val interval = if (quality == ModelQuality.LOW) 1_000_000_000L / 30 else 1_000_000_000L / 60
            if (lastRenderNs != 0L && frameTimeNanos - lastRenderNs < interval * 0.9) return
            val chain = swapChain ?: return
            if (!uiHelper.isReadyToRender) return
            lastRenderNs = frameTimeNanos
            resourceLoader.asyncUpdateLoad()
            asset?.let { model ->
                var count = model.popRenderables(readyEntities)
                while (count > 0) {
                    scene.addEntities(readyEntities.copyOf(count))
                    count = model.popRenderables(readyEntities)
                }
                if (loadingResources && resourceLoader.asyncGetLoadProgress() >= 1f) {
                    loadingResources = false
                    applyHighlight()
                    if (installedGeneration == generation) onLoadState(false, null)
                }
            }
            advanceCameraMotion(frameTimeNanos)
            updateCamera()
            if (renderer.beginFrame(chain, frameTimeNanos)) {
                renderer.render(filamentView)
                renderer.endFrame()
                ++statsFrames
                ++totalFrames
            }
            publishCachedProjection()
            if (frameTimeNanos - lastProjectionNs >= projectionIntervalNs) {
                publishProjection()
            }
            if (statsStartNs == 0L) statsStartNs = frameTimeNanos
            val elapsed = frameTimeNanos - statsStartNs
            if (elapsed >= 1_000_000_000L) {
                val fps = statsFrames * 1_000_000_000f / elapsed
                onFrameStats(FrameStats(fps, if (fps > 0f) 1_000f / fps else 0f, totalFrames))
                statsFrames = 0
                statsStartNs = frameTimeNanos
            }
        }
    }

    private fun scheduleFrame() {
        if (!closed && resumed && attached && !frameScheduled) {
            frameScheduled = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun resetFrameClock() {
        statsStartNs = 0L
        statsFrames = 0L
        lastRenderNs = 0L
    }

    private fun destroyModel() {
        resourceLoader.asyncCancelLoad()
        resourceLoader.evictResourceData()
        clearHighlight()
        asset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        asset = null
        installedGeneration = 0L
        assetLoader.gc()
        entityIds.clear()
        entitiesById.clear()
        sourceBaseColors = emptyMap()
        cutawayBaseColors = emptyMap()
        loadingResources = false
    }

    private fun configureAppearance(quality: ModelQuality) {
        if (appearanceQuality == quality) return
        filamentView.setAmbientOcclusionOptions(com.google.android.filament.View.AmbientOcclusionOptions().apply {
            enabled = true
            radius = .05f
            bias = .001f
            power = 1f
            intensity = .65f
            resolution = .5f
            this.quality = if (quality == ModelQuality.LOW) QualityLevel.LOW else QualityLevel.MEDIUM
            lowPassFilter = QualityLevel.MEDIUM
            upsampling = QualityLevel.MEDIUM
            ssctEnabled = false
        })
        // One shadow caster and one cascade keep the mobile cost bounded in both model modes.
        lights.firstOrNull()?.let { entity ->
            val manager = engine.lightManager
            manager.setShadowOptions(manager.getInstance(entity), LightManager.ShadowOptions().apply {
                mapSize = if (quality == ModelQuality.LOW) 1024 else 2048
                shadowCascades = 1
                constantBias = .001f
                normalBias = 1f
                shadowNearHint = .1f
                shadowFarHint = 8f
            })
        }
        appearanceQuality = quality
    }

    private fun addLight(x: Float, y: Float, z: Float, intensity: Float, color: FloatArray,
        shadows: Boolean = false) {
        val entity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(color[0], color[1], color[2]).intensity(intensity).direction(x, y, z)
            .castShadows(shadows).build(engine, entity)
        scene.addEntity(entity)
        lights += entity
    }

    private fun updateLighting() {
        val manager = engine.lightManager
        OrbitLighting.directions(pose).forEachIndexed { index, direction ->
            val instance = manager.getInstance(lights[index])
            manager.setDirection(instance, direction.x, direction.y, direction.z)
        }
    }

    private fun readAsset(path: String): ByteBuffer {
        // GLBs are packaged uncompressed, allowing direct mapping without two full CPU copies.
        try {
            return context.assets.openFd(path).use { descriptor ->
                descriptor.createInputStream().use { input ->
                    input.channel.map(FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset, descriptor.declaredLength)
                        .order(ByteOrder.nativeOrder())
                }
            }
        } catch (_: IOException) {
            // Support a compressed asset in development builds as well.
            return context.assets.open(path).use { stream ->
                val bytes = stream.readBytes()
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    flip()
                }
            }
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "AnatomySurfaceView requires the main thread" }
    }

    private data class HighlightBinding(
        val entity: Int, val primitive: Int,
        val original: MaterialInstance, val selected: MaterialInstance,
    )
}
