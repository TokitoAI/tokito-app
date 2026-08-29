package ai.tokito.physical.ar

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.TextureView
import android.widget.FrameLayout
import com.google.android.filament.Engine
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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

    init {
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = INVISIBLE
        // Filament requires its Engine thread to be adopted by Android. Defer
        // initialization until after the first traversal, but keep it on the
        // main looper instead of an unadopted executor thread.
        post {
            if (destroyed) return@post
            val engine = runCatching { Utils.init(); Engine.create() }.getOrNull()
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
            currentViewer.loadModelGlb(ByteBuffer.wrap(accepted.packageData.glb))
            val asset = currentViewer.asset ?: return DigitalTwinRenderResult.Rejected("Filament rejected the GLB")
            if (!boundsMatch(asset.boundingBox.center, asset.boundingBox.halfExtent, accepted.packageData.manifest.boundsMetres)) {
                currentViewer.destroyModel()
                return DigitalTwinRenderResult.Rejected("decoded GLB bounds differ from the signed manifest")
            }
            packageData = accepted.packageData
            applyRenderState()
            DigitalTwinRenderResult.Loaded
        } catch (_: RuntimeException) {
            packageData = null
            visibility = INVISIBLE
            DigitalTwinRenderResult.Rejected("3D asset could not be created by the renderer")
        }
    }

    fun updateRegistration(nextPose: MetricBoardPose?, nextIntrinsics: CameraIntrinsics?) {
        checkMainThread()
        pose = nextPose
        intrinsics = nextIntrinsics
        applyRenderState()
    }

    fun showOnlyComponents(referenceDesignators: Set<String>?) {
        checkMainThread()
        val currentViewer = viewer ?: return
        val asset = currentViewer.asset ?: return
        val selected = referenceDesignators?.map { packageData!!.manifest.componentNodePrefix + it }?.toSet()
        val manager = currentViewer.engine.renderableManager
        asset.renderableEntities.forEach { entity ->
            val name = asset.getName(entity).orEmpty()
            val visible = selected == null || !name.startsWith(packageData!!.manifest.componentNodePrefix) || name in selected
            manager.getInstance(entity).takeIf { it != 0 }?.let { manager.setLayerMask(it, 0xff, if (visible) 0x01 else 0x00) }
        }
    }

    fun suspendRendering() {
        pose = null
        visibility = INVISIBLE
        unschedule()
    }

    fun clearAsset() {
        checkMainThread()
        suspendRendering()
        viewer?.destroyModel()
        packageData = null
    }

    fun destroy() {
        checkMainThread()
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
        if (visibility == VISIBLE && isAttachedToWindow && packageData != null && pose != null) {
            viewer?.render(frameTimeNanos)
            schedule()
        }
    }

    private fun applyRenderState() {
        val currentViewer = viewer
        val asset = currentViewer?.asset
        val currentPose = pose
        val camera = intrinsics
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
}
