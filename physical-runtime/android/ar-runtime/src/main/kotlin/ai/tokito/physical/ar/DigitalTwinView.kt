package ai.tokito.physical.ar

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.TextureView
import android.widget.FrameLayout
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Material
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

sealed interface DigitalTwinRenderResult {
    data object Loaded : DigitalTwinRenderResult
    data object Initializing : DigitalTwinRenderResult
    data class Rejected(val reason: String) : DigitalTwinRenderResult
}

/** Android-native transparent Filament layer. All methods are main-thread only. */
class DigitalTwinView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), Choreographer.FrameCallback {

    private val texture = TextureView(context).apply {
        isOpaque = false
        contentDescription = "Registered three-dimensional PCB digital twin"
    }
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
    private var viewer: ModelViewer? = null
    private val scheduled = AtomicBoolean(false)
    private var destroyed = false
    private var pendingLoad: Pair<DigitalTwinValidation.Accepted, (DigitalTwinRenderResult) -> Unit>? = null
    private var packageData: DigitalTwinPackage? = null
    private var pose: MetricBoardPose? = null
    private var intrinsics: CameraIntrinsics? = null
    private var screenCorners: List<Point2>? = null
    private var inspectionPreview = false
    private var referenceComparison = false
    private var componentIndex: ComponentEntityIndex? = null
    private data class HighlightMaterial(val entity: Int, val primitive: Int, val original: MaterialInstance, val selected: MaterialInstance)
    private val highlightMaterials = mutableListOf<HighlightMaterial>()
    private var highlightRevision = 0L

    init {
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = INVISIBLE
        // Filament requires its Engine thread to be adopted by Android. Defer
        // initialization until after the first traversal, but keep it on the
        // main looper instead of an unadopted executor thread.
        post {
            if (destroyed) return@post
            val engine = runCatching {
                Utils.init()
                Engine.Builder().config(
                    Engine.Config().apply {
                        // The admitted FRDM asset peaks above Filament's 1 MiB default while
                        // creating its 681-node scene. Preserve three buffers as Filament requires.
                        minCommandBufferSizeMB = 12
                        commandBufferSizeMB = 36
                        driverHandleArenaSizeMB = 32
                    },
                ).build()
            }.getOrNull()
            if (engine == null) {
                pendingLoad?.second?.invoke(DigitalTwinRenderResult.Rejected("Filament engine initialization failed"))
                pendingLoad = null
            } else {
                initializeViewer(engine)
            }
        }
    }

    fun load(
        validation: DigitalTwinValidation,
        onComplete: (DigitalTwinRenderResult) -> Unit = {},
    ): DigitalTwinRenderResult {
        checkMainThread()
        val accepted = validation as? DigitalTwinValidation.Accepted
            ?: return DigitalTwinRenderResult.Rejected((validation as DigitalTwinValidation.Rejected).detail).also(onComplete)
        if (viewer == null) {
            pendingLoad?.second?.invoke(DigitalTwinRenderResult.Rejected("3D asset load was superseded"))
            pendingLoad = accepted to onComplete
            return DigitalTwinRenderResult.Initializing
        }
        return loadAccepted(accepted).also(onComplete)
    }

    private fun loadAccepted(accepted: DigitalTwinValidation.Accepted): DigitalTwinRenderResult {
        val currentViewer = viewer ?: return DigitalTwinRenderResult.Initializing
        return try {
            if (packageData?.manifest == accepted.packageData.manifest &&
                currentViewer.asset != null
            ) {
                applyRenderState()
                return DigitalTwinRenderResult.Loaded
            }
            clearComponentHighlight()
            componentIndex = null
            packageData = null
            if (currentViewer.asset != null) currentViewer.destroyModel()
            currentViewer.loadModelGlb(ByteBuffer.wrap(accepted.packageData.glb))
            val asset = currentViewer.asset ?: return DigitalTwinRenderResult.Rejected("Filament rejected the GLB")
            if (!boundsMatch(asset.boundingBox.center, asset.boundingBox.halfExtent, accepted.packageData.manifest.boundsMetres)) {
                android.util.Log.e("TokitoDigitalTwin", "Decoded bounds mismatch: center=${asset.boundingBox.center.contentToString()} half=${asset.boundingBox.halfExtent.contentToString()} declared=${accepted.packageData.manifest.boundsMetres}")
                currentViewer.destroyModel()
                return DigitalTwinRenderResult.Rejected("decoded GLB bounds differ from the signed manifest")
            }
            packageData = accepted.packageData
            val transforms = currentViewer.engine.transformManager
            // ModelViewer may parent the imported scene under its own transform.
            // Include that renderer-owned ancestry, without inventing selectors
            // for entities that were not authored in the GLB.
            val authoredEntities = (asset.entities.toList() + asset.root).toSet()
            val hierarchy = linkedMapOf<Int, ComponentEntityIndex.Node>()
            val pending = java.util.ArrayDeque<Int>().apply { addAll(authoredEntities) }
            while (pending.isNotEmpty()) {
                val entity = pending.removeFirst()
                if (entity in hierarchy) continue
                require(hierarchy.size < 100_000) { "Renderer hierarchy exceeds entity budget" }
                val instance = transforms.getInstance(entity)
                val parent = if (instance == 0) 0 else transforms.getParent(instance)
                hierarchy[entity] = ComponentEntityIndex.Node(entity, parent,
                    if (entity in authoredEntities) asset.getName(entity) else null)
                if (parent != 0 && parent !in hierarchy) pending.addLast(parent)
            }
            componentIndex = ComponentEntityIndex.build(
                hierarchy.values.toList(), asset.renderableEntities.toSet(), accepted.packageData.manifest.componentNodePrefix,
            )
            applyRenderState()
            DigitalTwinRenderResult.Loaded
        } catch (failure: RuntimeException) {
            android.util.Log.e("TokitoDigitalTwin", "Validated source asset failed renderer admission", failure)
            componentIndex = null
            currentViewer.destroyModel()
            packageData = null
            visibility = INVISIBLE
            DigitalTwinRenderResult.Rejected("3D asset could not be created by the renderer")
        }
    }

    fun updateRegistration(nextPose: MetricBoardPose?, nextIntrinsics: CameraIntrinsics?, compareReference: Boolean = false) {
        checkMainThread()
        // A fresh pose for the same loaded asset moves the selected geometry;
        // it must not erase selection on every tracking frame. Loss still revokes it.
        if (nextPose == null || nextIntrinsics == null || referenceComparison != compareReference) clearComponentHighlight()
        inspectionPreview = false
        referenceComparison = compareReference && nextPose != null && nextIntrinsics != null
        texture.contentDescription = if (referenceComparison) "Registered source reference overlay; physical identity and fit unverified"
            else "Registered three-dimensional PCB digital twin"
        pose = nextPose
        intrinsics = nextIntrinsics
        screenCorners = null
        applyRenderState()
    }

    /** Maps the admitted board asset directly to a tracked screen-space quadrilateral. */
    fun updateScreenRegistration(nextCorners: List<Point2>?) {
        checkMainThread()
        clearComponentHighlight()
        require(nextCorners == null || nextCorners.size == 4)
        inspectionPreview = false
        referenceComparison = false
        pose = null
        intrinsics = null
        screenCorners = nextCorners?.toList()
        applyRenderState()
    }

    /** Displays the admitted asset for inspection without creating an AR pose. */
    fun showUnregisteredPreview(): Boolean {
        checkMainThread()
        val currentViewer = viewer ?: return false
        if (currentViewer.asset == null || packageData == null || width <= 0 || height <= 0) return false
        pose = null
        intrinsics = null
        screenCorners = null
        inspectionPreview = true
        referenceComparison = false
        val bounds = packageData!!.manifest.boundsMetres
        val centerX = (bounds.minimum.x + bounds.maximum.x) / 2.0
        val centerY = (bounds.minimum.y + bounds.maximum.y) / 2.0
        val centerZ = (bounds.minimum.z + bounds.maximum.z) / 2.0
        val boardWidth = bounds.maximum.x - bounds.minimum.x
        val boardHeight = bounds.maximum.z - bounds.minimum.z
        if (!boardWidth.isFinite() || !boardHeight.isFinite() || boardWidth <= 0.0 || boardHeight <= 0.0) return false
        val previewDistance = 2.4
        val verticalFovDegrees = 42.0
        val verticalSpan = 2.0 * previewDistance * tan(Math.toRadians(verticalFovDegrees / 2.0))
        val horizontalSpan = verticalSpan * width.toDouble() / height.toDouble()
        val scale = (0.82 * minOf(horizontalSpan / boardWidth, verticalSpan / boardHeight)).toFloat()
        val asset = currentViewer.asset ?: return false
        val transformManager = currentViewer.engine.transformManager
        val root = transformManager.getInstance(asset.root)
        if (root == 0) return false
        transformManager.setTransform(root, floatArrayOf(
            scale, 0f, 0f, 0f,
            0f, scale, 0f, 0f,
            0f, 0f, scale, 0f,
            (-centerX * scale).toFloat(),
            (-centerY * scale).toFloat(),
            (-centerZ * scale).toFloat(),
            1f,
        ))
        currentViewer.camera.setProjection(
            verticalFovDegrees,
            width.toDouble() / height.toDouble(),
            0.1,
            100.0,
            Camera.Fov.VERTICAL,
        )
        currentViewer.camera.lookAt(
            0.0, previewDistance, 0.0,
            0.0, 0.0, 0.0,
            0.0, 0.0, -1.0,
        )
        visibility = VISIBLE
        schedule()
        return true
    }

    fun showOnlyComponents(referenceDesignators: Set<String>?): Boolean {
        checkMainThread()
        val currentViewer = viewer ?: return false
        val asset = currentViewer.asset ?: return false
        val index = componentIndex ?: return false
        val selected = referenceDesignators?.let(index::resolve)
        val manager = currentViewer.engine.renderableManager
        asset.renderableEntities.forEach { entity ->
            val visible = selected == null || entity !in index.referencesByRenderable || entity in selected
            manager.getInstance(entity).takeIf { it != 0 }?.let { manager.setLayerMask(it, 0xff, if (visible) 0x01 else 0x00) }
        }
        return referenceDesignators == null || selected != null
    }

    /** Picks rendered source geometry, never a detector-class approximation. */
    fun pickComponent(point: Point2, onSelected: (String?) -> Unit): Boolean {
        checkMainThread()
        val currentViewer = viewer ?: return false
        val index = componentIndex ?: return false
        if (destroyed || visibility != VISIBLE || !scheduled.get() ||
            !point.x.isFinite() || !point.y.isFinite() ||
            point.x < 0 || point.y < 0 || point.x >= width || point.y >= height) return false
        clearComponentHighlight()
        val revision = highlightRevision
        val currentPackage = packageData
        currentViewer.view.pick(point.x.toInt(), height - 1 - point.y.toInt(),
            android.os.Handler(android.os.Looper.getMainLooper())) { result ->
            if (destroyed || revision != highlightRevision || packageData !== currentPackage ||
                visibility != VISIBLE || !scheduled.get() ||
                (!inspectionPreview && pose?.let { System.nanoTime() > it.expiresAtNanos } == true)) return@pick
            val reference = index.referencesByRenderable[result.renderable]
            if (reference != null && highlightComponents(setOf(reference), 15_000)) onSelected(reference)
            else onSelected(null)
        }
        return true
    }

    /** Highlights every authored mesh in a component without hiding the rest of the board.
     * Cloned material instances keep shared source materials and textures untouched.
     */
    fun highlightComponents(references: Set<String>, validityMillis: Long): Boolean {
        checkMainThread()
        require(validityMillis in 1..60_000)
        val currentViewer = viewer ?: return false
        val selected = componentIndex?.resolve(references) ?: return false
        clearComponentHighlight()
        val manager = currentViewer.engine.renderableManager
        // glTF emissive radiance is pre-exposed by Filament. Unit emission is
        // invisible at the viewer's daylight exposure; compensate using the
        // actual camera settings rather than changing the whole scene exposure.
        val camera = currentViewer.camera
        val exposureDenominator = 1.2 * camera.aperture.toDouble() * camera.aperture /
            camera.shutterSpeed * (100.0 / camera.sensitivity)
        if (!exposureDenominator.isFinite() || exposureDenominator <= 0.0) return false
        val emission = (0.8 * exposureDenominator).coerceIn(1.0, 100_000.0).toFloat()
        try {
            // Admit the entire selection before installing any overrides.
            for (entity in selected) {
                val instance = manager.getInstance(entity)
                require(instance != 0)
                for (primitive in 0 until manager.getPrimitiveCount(instance)) {
                    require(highlightMaterials.size < 4096) { "Component highlight exceeds primitive budget" }
                    val original = manager.getMaterialInstanceAt(instance, primitive)
                    require(original.material.parameters.any {
                        it.name == "emissiveFactor" && it.type == Material.Parameter.Type.FLOAT3 && it.count == 1
                    }) { "Material does not support component highlighting" }
                    val duplicate = MaterialInstance.duplicate(original, "tokito-component-highlight")
                    highlightMaterials.add(HighlightMaterial(entity, primitive, original, duplicate))
                    duplicate.setParameter("emissiveFactor", 0.0f, emission, emission * 0.65f)
                }
            }
            require(highlightMaterials.isNotEmpty())
            highlightMaterials.forEach { slot ->
                manager.setMaterialInstanceAt(manager.getInstance(slot.entity), slot.primitive, slot.selected)
            }
        } catch (_: RuntimeException) {
            clearComponentHighlight()
            return false
        }
        val revision = highlightRevision
        postDelayed({ if (revision == highlightRevision) clearComponentHighlight() }, validityMillis)
        return true
    }

    fun clearComponentHighlight() {
        checkMainThread()
        highlightRevision++
        val currentViewer = viewer
        if (currentViewer != null) {
            val manager = currentViewer.engine.renderableManager
            highlightMaterials.forEach { slot ->
                val instance = manager.getInstance(slot.entity)
                if (instance != 0) manager.setMaterialInstanceAt(instance, slot.primitive, slot.original)
                currentViewer.engine.destroyMaterialInstance(slot.selected)
            }
        }
        highlightMaterials.clear()
    }

    fun suspendRendering() {
        clearComponentHighlight()
        inspectionPreview = false
        referenceComparison = false
        pose = null
        screenCorners = null
        visibility = INVISIBLE
        unschedule()
    }

    fun clearAsset() {
        checkMainThread()
        suspendRendering()
        viewer?.destroyModel()
        componentIndex = null
        packageData = null
        texture.alpha = 1f
    }

    fun destroy() {
        checkMainThread()
        clearComponentHighlight()
        destroyed = true
        unschedule()
        pendingLoad?.second?.invoke(DigitalTwinRenderResult.Rejected("renderer was destroyed before asset initialization"))
        // ModelViewer installs its own TextureView detach listener and destroys
        // the native resource loader there. Calling destroy() here as well
        // double-frees that loader during Activity recreation.
        viewer = null
        pendingLoad = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyRenderState()
    }

    override fun onDetachedFromWindow() {
        unschedule()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled.set(false)
        if (!inspectionPreview && pose?.let { System.nanoTime() > it.expiresAtNanos } == true) {
            suspendRendering()
            return
        }
        if (visibility == VISIBLE && isAttachedToWindow && packageData != null && (pose != null || screenCorners != null || inspectionPreview)) {
            viewer?.render(frameTimeNanos)
            schedule()
        }
    }

    private fun applyRenderState() {
        val currentViewer = viewer
        val asset = currentViewer?.asset
        val currentPose = pose
        val camera = intrinsics
        if (asset != null && inspectionPreview && width > 0 && height > 0) {
            texture.alpha = 1f
            visibility = VISIBLE
            schedule()
            return
        }
        val manifest = packageData?.manifest
        if (manifest != null && !manifest.registrationEligible && !referenceComparison && manifest.assetId != CalibrationBoardAsset.ASSET_ID) {
            visibility = INVISIBLE
            unschedule()
            return
        }
        val trackedCorners = screenCorners
        if (asset != null && trackedCorners != null && width > 0 && height > 0) {
            applyScreenRegistration(currentViewer, asset, trackedCorners)
            return
        }
        if (asset == null || currentPose == null || camera == null || width <= 0 || height <= 0) {
            visibility = INVISIBLE; unschedule(); return
        }
        if (camera.widthPx != width || camera.heightPx != height) {
            visibility = INVISIBLE; unschedule(); return
        }
        val tm = currentViewer.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) {
            visibility = INVISIBLE; unschedule(); return
        }
        tm.setTransform(instance, currentPose.filamentTransformForYUpAsset())
        currentViewer.camera.setCustomProjection(camera.filamentProjection(), 0.01, 10.0)
        currentViewer.camera.setModelMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        ))
        texture.alpha = (packageData!!.manifest.defaultOpacity * TRACKED_OPACITY_MULTIPLIER).coerceAtMost(1.0).toFloat()
        visibility = VISIBLE
        schedule()
    }

    private fun applyScreenRegistration(
        currentViewer: ModelViewer,
        asset: com.google.android.filament.gltfio.FilamentAsset,
        corners: List<Point2>,
    ) {
        val bounds = packageData!!.manifest.boundsMetres
        val boardWidth = bounds.maximum.x - bounds.minimum.x
        val boardHeight = bounds.maximum.z - bounds.minimum.z
        if (boardWidth <= 0.0 || boardHeight <= 0.0) {
            visibility = INVISIBLE
            unschedule()
            return
        }
        val screenWidth = (hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y) +
            hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y)) / 2.0
        val screenHeight = (hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y) +
            hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y)) / 2.0
        if (screenWidth <= 1.0 || screenHeight <= 1.0) {
            visibility = INVISIBLE
            unschedule()
            return
        }
        val centerX = corners.map(Point2::x).average()
        val centerY = corners.map(Point2::y).average()
        val requiresQuarterTurn = (boardWidth >= boardHeight) != (screenWidth >= screenHeight)
        val angle = atan2(corners[1].y - corners[0].y, corners[1].x - corners[0].x) +
            if (requiresQuarterTurn) Math.PI / 2.0 else 0.0
        val cosine = cos(angle)
        val sine = sin(angle)
        val projectedWidth = if (requiresQuarterTurn) boardHeight else boardWidth
        val projectedHeight = if (requiresQuarterTurn) boardWidth else boardHeight
        val scale = maxOf(screenWidth / projectedWidth, screenHeight / projectedHeight)
        val assetCenterX = (bounds.minimum.x + bounds.maximum.x) / 2.0
        val assetCenterY = (bounds.minimum.y + bounds.maximum.y) / 2.0
        val assetCenterZ = (bounds.minimum.z + bounds.maximum.z) / 2.0
        val translationX = centerX - cosine * scale * assetCenterX + sine * scale * assetCenterZ
        val translationY = centerY - sine * scale * assetCenterX - cosine * scale * assetCenterZ
        val transformManager = currentViewer.engine.transformManager
        val root = transformManager.getInstance(asset.root)
        if (root == 0) {
            visibility = INVISIBLE
            unschedule()
            return
        }
        transformManager.setTransform(root, floatArrayOf(
            (cosine * scale).toFloat(), (sine * scale).toFloat(), 0f, 0f,
            0f, 0f, scale.toFloat(), 0f,
            (-sine * scale).toFloat(), (cosine * scale).toFloat(), 0f, 0f,
            translationX.toFloat(), translationY.toFloat(), (-1.0 - scale * assetCenterY).toFloat(), 1f,
        ))
        currentViewer.camera.setProjection(Camera.Projection.ORTHO, 0.0, width.toDouble(), height.toDouble(), 0.0, 0.1, 100.0)
        currentViewer.camera.setModelMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        ))
        texture.alpha = (packageData!!.manifest.defaultOpacity * TRACKED_OPACITY_MULTIPLIER).coerceAtMost(1.0).toFloat()
        visibility = VISIBLE
        schedule()
    }

    private fun initializeViewer(engine: Engine) {
        checkMainThread()
        val created = ModelViewer(texture, engine, uiHelper, null)
        created.view.blendMode = View.BlendMode.TRANSLUCENT
        created.view.isPostProcessingEnabled = false
        created.view.setShadowingEnabled(false)
        created.renderer.clearOptions = created.renderer.clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
        viewer = created
        pendingLoad?.let { (accepted, callback) -> callback(loadAccepted(accepted)) }
        pendingLoad = null
        applyRenderState()
    }

    private fun boundsMatch(center: FloatArray, half: FloatArray, declared: Bounds3Contract): Boolean {
        val decodedMin = doubleArrayOf((center[0] - half[0]).toDouble(), (center[1] - half[1]).toDouble(), (center[2] - half[2]).toDouble())
        val decodedMax = doubleArrayOf((center[0] + half[0]).toDouble(), (center[1] + half[1]).toDouble(), (center[2] + half[2]).toDouble())
        val expectedMin = doubleArrayOf(declared.minimum.x, declared.minimum.y, declared.minimum.z)
        val expectedMax = doubleArrayOf(declared.maximum.x, declared.maximum.y, declared.maximum.z)
        return (0..2).all { abs(decodedMin[it] - expectedMin[it]) <= 0.003 && abs(decodedMax[it] - expectedMax[it]) <= 0.003 }
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) Choreographer.getInstance().postFrameCallback(this)
    }
    private fun unschedule() {
        if (scheduled.getAndSet(false)) Choreographer.getInstance().removeFrameCallback(this)
    }
    private fun checkMainThread() = check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        "Filament renderer must be accessed from the main thread"
    }

    private companion object {
        const val TRACKED_OPACITY_MULTIPLIER = 1.5
    }
}
