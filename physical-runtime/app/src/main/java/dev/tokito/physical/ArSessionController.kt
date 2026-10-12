package dev.tokito.physical

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import ai.tokito.physical.ar.ArCameraSurface
import ai.tokito.physical.ar.ArPresenter
import ai.tokito.physical.ar.ArRuntime
import ai.tokito.physical.ar.CalibrationBoardAsset
import ai.tokito.physical.ar.CameraIntrinsics
import ai.tokito.physical.ar.CoordinateFrame
import ai.tokito.physical.ar.DigitalTwinRenderResult
import ai.tokito.physical.ar.DigitalTwinValidation
import ai.tokito.physical.ar.DigitalTwinValidator
import ai.tokito.physical.ar.DigitalTwinManifest
import ai.tokito.physical.ar.Guidance
import ai.tokito.physical.ar.ManualBoardRegistrar
import ai.tokito.physical.ar.OverlayPrimitive
import ai.tokito.physical.ar.OverlayScene
import ai.tokito.physical.ar.OverlayTone
import ai.tokito.physical.ar.Point2
import ai.tokito.physical.ar.PoseEstimation
import ai.tokito.physical.ar.RenderDecision
import ai.tokito.physical.ar.SceneSubmission
import dev.tokito.physical.dialogue.DesignBoardTarget
import java.io.File

/** Product host for the shared registered-presentation runtime. */
class ArSessionController(
    private val owner: LifecycleOwner,
    private val surface: ArCameraSurface,
    private val onState: (State) -> Unit,
) {
    data class State(val label: String, val detail: String, val registered: Boolean = false)

    private val runtime = ArRuntime()
    private val registrar = ManualBoardRegistrar()
    private val corners = mutableListOf<Point2>()
    private var cameraStarted = false
    private var activeTwin: DigitalTwinManifest? = null
    private var hasRegistration = false
    private var registrationRevision: Long? = null
    private var metricRegistrationValid = false
    private var referenceScopeEpoch = 0L
    @Volatile private var inspectingSource = false
    private var sequence = 0L
    @Volatile private var vision: LocalPoseVisionRuntime? = null
    @Volatile private var referenceTracker: ReferenceBoardTracker? = null
    private var dynamicReferenceComparison = false
    private var acquiringReferenceAsset = false
    private val acquisition = TrackingAcquisition()
    @Volatile private var segmentation: dev.tokito.physical.dialogue.LocalSegmentationRuntime? = null
    @Volatile private var segmentationAdmission: ((() -> Unit) -> Boolean)? = null
    @Volatile private var poseAdmission: ((() -> Unit) -> Boolean)? = null
    private val masks = SegmentMaskView(surface.context)
    @Volatile private var masksEnabled = true
    @Volatile private var maskEpoch = 0L
    private var lastMaskFrame = 0L
    private var lastPoseFrame = 0L
    private var manualRegistration = false
    private var lastAutomaticRegistrationNanos = 0L
    private var automaticRegistrationActive = false
    private var lastAutomaticObservationNanos = 0L

    init {
        surface.addView(masks, android.widget.FrameLayout.LayoutParams(-1, -1))
        ArPresenter(runtime, surface.overlay) { reason ->
            if (!inspectingSource) onState(
                if (hasRegistration) State("Registration lost", reason)
                else State("Register board", "Tap the four board corners clockwise"),
            )
        }.bind(owner)
        surface.overlay.setOnBoardTapListener { point ->
            if (manualRegistration && corners.size < 4) acceptCorner(point)
            else surface.digitalTwin.pickComponent(point) { reference ->
                if (reference != null) onState(State("Source component $reference",
                    "Selected authored model geometry; physical board identity and fit are not established by this selection", hasRegistration))
            }
        }
        surface.onCameraGeometryChanged = ::reset
        reset()
    }

    fun start() {
        if (cameraStarted) return
        when (val result = surface.start(owner, ::onCameraStatus)) {
            ArCameraSurface.StartResult.Started,
            ArCameraSurface.StartResult.AlreadyRunning,
            -> cameraStarted = true
            is ArCameraSurface.StartResult.Failed -> {
                runtime.suspend()
                onState(State("Camera unavailable", result.reason))
            }
        }
    }

    fun stop() {
        referenceScopeEpoch++
        inspectingSource = false
        maskEpoch++
        masks.clear()
        automaticRegistrationActive = false
        runtime.suspend()
        corners.clear()
        hasRegistration = false
        registrationRevision = null
        surface.overlay.render(null)
        if (cameraStarted) surface.stop()
        cameraStarted = false
        onState(State("Camera paused", "Return to Tokito Physical to resume"))
    }

    fun destroy() {
        surface.setFrameListener(null)
        referenceTracker?.close()
        referenceTracker = null
        segmentation?.let { old -> Thread({ old.close() }, "tokito-segmentation-close").start() }
        segmentation = null
        vision?.close()
        vision = null
        surface.destroy()
    }

    fun toggleMasks(): Boolean {
        masksEnabled = !masksEnabled
        maskEpoch++
        masks.clear()
        return masksEnabled
    }

    fun enableSegmentation(
        model: dev.tokito.physical.dialogue.LocalSegmentationRuntime,
        admittedWork: (() -> Unit) -> Boolean,
    ) {
        val previous = segmentation
        segmentation = model
        segmentationAdmission = admittedWork
        if (previous != null && previous !== model) Thread({ previous.close() }, "tokito-segmentation-close").start()
        installFramePipeline()
        onState(State("Component detection", "Experimental masks, labels and boxes; not verified part identity"))
    }

    /** One CameraX analyzer owns each bitmap; both native consumers borrow it serially.
     * No queue or full-frame copy is introduced, and heavy inference can be declined.
     */
    private fun installFramePipeline() {
        surface.setFrameListener { frame ->
            val epoch = maskEpoch
            try {
                processPoseFrame(frame, epoch)
                processSegmentationFrame(frame, epoch)
            } finally { if (!frame.bitmap.isRecycled) frame.bitmap.recycle() }
        }
    }

    private fun processSegmentationFrame(frame: ArCameraSurface.CameraFrame, epoch: Long) {
        val model = segmentation ?: return
        if (!masksEnabled || frame.timestampNanos - lastMaskFrame < 2_000_000_000L || frame.analysisToPreview == null) return
        lastMaskFrame = frame.timestampNanos
        var prediction: dev.tokito.physical.dialogue.LocalSegmentationRuntime.Result? = null
        var failed = false
        try {
            segmentationAdmission?.invoke { prediction = model.analyze(frame.bitmap) }
        } catch (failure: Exception) {
            failed = true
            android.util.Log.e("TokitoSegmentation", "Inference failed", failure)
        }
        val result = prediction
        surface.post {
            if (epoch != maskEpoch || model !== segmentation || !masksEnabled || frame.geometryRevision != surface.geometryRevision ||
                !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                System.nanoTime() - frame.timestampNanos > 3_000_000_000L) {
                result?.bitmap?.recycle()
            } else if (result != null) {
                masks.show(result, requireNotNull(frame.analysisToPreview), frame.timestampNanos)
                if (!hasRegistration && !inspectingSource) onState(State("Component predictions · ${result.count}",
                    if (result.count == 0) "No new detections; previous overlay expires within three seconds. This is not proof of absence"
                    else "Experimental masks and model scores; not verified component identity"))
                android.util.Log.i("TokitoSegmentation", "predictionCount=${result.count} elapsedMs=${(System.nanoTime()-frame.timestampNanos)/1_000_000}")
            } else if (failed) {
                masks.clear()
                onState(State("Detection unavailable", "Retry after reopening the bench"))
            }
        }
    }

    private fun processPoseFrame(frame: ArCameraSurface.CameraFrame, epoch: Long) {
        if (inspectingSource) return
        referenceTracker?.let { tracker ->
            if (manualRegistration || frame.timestampNanos - lastPoseFrame < REFERENCE_TRACKING_INTERVAL_NANOS) return
            lastPoseFrame = frame.timestampNanos
            var observed: ReferenceBoardTracker.Observation? = null
            val admitted = runCatching { poseAdmission?.invoke { observed = tracker.analyze(frame) } == true }
                .getOrElse { android.util.Log.e("TokitoReferenceTracking", "Reference tracking failed", it); false }
            surface.post {
                if (epoch != maskEpoch || tracker !== referenceTracker || frame.geometryRevision != surface.geometryRevision ||
                    !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@post
                val result = observed
                if (!admitted || result == null) { acquisition.reset(); handleAutomaticMiss() }
                else if (!acquiringReferenceAsset && activeTwin?.assetId != result.validation.packageData.manifest.assetId) {
                    if (!acquisition.accept(result.validation.packageData.manifest.assetId, result.pose.timestampNanos)) return@post
                    acquiringReferenceAsset = true
                    val accepted = activateValidatedTwin(result.validation)
                    if (accepted !is DigitalTwinValidation.Accepted) acquiringReferenceAsset = false
                    dynamicReferenceComparison = accepted is DigitalTwinValidation.Accepted
                } else if (!acquiringReferenceAsset) {
                    dynamicReferenceComparison = true
                    acceptAutomaticRegistration(result.pose)
                }
            }
            return
        }
        val poseRuntime = vision ?: return
        // Compact inference is still CPU work, not a free camera-frame operation.
        // Latest-only acquisition plus a bounded cadence avoids 30 model runs/s.
        if (frame.timestampNanos - lastPoseFrame < POSE_INFERENCE_INTERVAL_NANOS) return
        lastPoseFrame = frame.timestampNanos
        var observation: PoseVisionObservation? = null
        val admitted = runCatching {
            poseAdmission?.invoke { observation = poseRuntime.analyze(frame, recycleInput = false) } == true
        }.getOrElse { failure ->
            android.util.Log.e("TokitoPoseVision", "Local pose inference failed", failure)
            false
        }
        surface.post {
            if (epoch != maskEpoch || poseRuntime !== vision || frame.geometryRevision != surface.geometryRevision ||
                !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@post
            val result = observation
            if (!admitted || result == null) handleAutomaticMiss() else acceptAutomaticRegistration(result)
        }
    }

    fun enableAutomaticRegistration(runtime: LocalPoseVisionRuntime, admittedWork: (() -> Unit) -> Boolean) {
        val previous = vision
        vision = runtime
        poseAdmission = admittedWork
        if (previous != null && previous !== runtime) Thread({ previous.close() }, "tokito-pose-close").start()
        manualRegistration = false
        installFramePipeline()
        onState(State("Finding board", "Local pose detection is active"))
    }

    fun enableReferenceTracking(tracker: ReferenceBoardTracker, admittedWork: (() -> Unit) -> Boolean) {
        val previous = referenceTracker
        referenceTracker = tracker
        poseAdmission = admittedWork
        previous?.takeIf { it !== tracker }?.close()
        reset()
        installFramePipeline()
        onState(State("Finding board", "Matching board features to installed source models"))
    }

    fun reset() {
        referenceScopeEpoch++
        inspectingSource = false
        acquisition.reset()
        dynamicReferenceComparison = false
        maskEpoch++
        lastPoseFrame = 0L
        metricRegistrationValid = false
        masks.clear()
        manualRegistration = false
        corners.clear()
        hasRegistration = false
        automaticRegistrationActive = false
        registrationRevision = null
        runtime.suspend()
        surface.overlay.render(null)
        surface.digitalTwin.updateRegistration(null, null)
        onState(
            if (vision == null) State("Register board", "Tap top-left, top-right, bottom-right, then bottom-left")
            else State("Finding board", "Hold the complete board in view"),
        )
    }

    /**
     * Activates only an assembly-context-bound project asset. Calibration and
     * renderer-conformance objects are deliberately not accepted by the product host.
     */
    fun activateDigitalTwin(
        manifestFile: File,
        glbFile: File,
        expectedAssemblyContextRevision: String,
    ): DigitalTwinValidation {
        val validation = DigitalTwinValidator().load(
            manifestFile,
            glbFile,
            expectedAssemblyContextRevision,
        )
        if (validation is DigitalTwinValidation.Accepted &&
            validation.packageData.manifest.assetId == CalibrationBoardAsset.ASSET_ID
        ) {
            return DigitalTwinValidation.Rejected(
                DigitalTwinValidation.Code.INVALID_MANIFEST,
                "calibration assets require an explicit calibration session",
            )
        }
        return activateValidatedTwin(validation)
    }

    /** Clears prior geometry and starts a fresh registration without replacing the project twin. */
    fun beginRegistration() {
        manualRegistration = true
        reset()
        manualRegistration = true
        onState(State("Register board", "Tap top-left, top-right, bottom-right, then bottom-left"))
    }

    fun previewDigitalTwin() {
        manualRegistration = false
        corners.clear()
        hasRegistration = false
        registrationRevision = null
        runtime.suspend()
        surface.overlay.render(null)
        if (surface.digitalTwin.showUnregisteredPreview()) {
            inspectingSource = true
            onState(State("3D preview", "Validated model inspection; not AR registered"))
        } else {
            inspectingSource = false
            onState(State("3D unavailable", "The validated model is still loading or unavailable"))
        }
    }

    fun highlightBoardTarget(target: DesignBoardTarget): Boolean {
        val revision = registrationRevision ?: return false
        val now = System.nanoTime()
        val expiry = now + HIGHLIGHT_VALIDITY_NANOS
        val scene = OverlayScene(
            sequence = ++sequence,
            transformRevision = revision,
            generatedAtNanos = now,
            expiresAtNanos = expiry,
            guidance = Guidance(
                title = target.label,
                detail = "Verified component target",
                confidence = 1.0,
            ),
            primitives = listOf(
                OverlayPrimitive.Target(
                    id = "component-target-${target.label}",
                    frame = CoordinateFrame.BOARD,
                    transformRevision = revision,
                    observedAtNanos = now,
                    expiresAtNanos = expiry,
                    uncertaintyPx = 8.0,
                    label = target.label,
                    tone = OverlayTone.Warning,
                    center = Point2(target.xNormalized, target.yNormalized),
                    radius = 0.055,
                    directionRadians = 0.0,
                ),
            ),
        )
        if (runtime.submit(scene) !is SceneSubmission.Accepted) return false
        // Selection uses reviewed reference identity, never a detector class or
        // model-generated label. A missing mesh still permits verified 2D guidance.
        surface.digitalTwin.highlightComponents(setOf(target.label), HIGHLIGHT_VALIDITY_NANOS / 1_000_000L)
        return true
    }

    /** Dataset retrieval may propose source refs, but cannot establish camera identity.
     * Explicit source inspection may select its exact authored mesh without claiming
     * physical registration. Live guidance still requires a current qualified pose.
     */
    fun highlightSourceComponents(boardIdentity: String, references: Set<String>): Boolean {
        val twin = activeTwin ?: return false
        if (twin.boardIdentity != boardIdentity ||
            !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        if (inspectingSource) {
            return surface.digitalTwin.highlightComponents(references, HIGHLIGHT_VALIDITY_NANOS / 1_000_000L)
        }
        if (!hasRegistration || !metricRegistrationValid || registrationRevision == null || !twin.registrationEligible ||
            runtime.render() !is RenderDecision.Ready) return false
        return surface.digitalTwin.highlightComponents(references, HIGHLIGHT_VALIDITY_NANOS / 1_000_000L)
    }

    data class RegisteredReference(val boardIdentity: String, val scopeRevision: String)

    /** Explicit source inspection narrows reference retrieval, not camera identity. */
    fun inspectedReference(): RegisteredReference? {
        val twin = activeTwin ?: return null
        if (!inspectingSource || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
        return RegisteredReference(twin.boardIdentity, "inspection:$referenceScopeEpoch:${twin.assetId}")
    }

    /** Manual or source-feature registration binds an already-qualified reference.
     * A generic pose detector alone cannot select or prove a board variant.
     */
    fun registeredReference(): RegisteredReference? {
        val twin = activeTwin ?: return null
        val revision = registrationRevision ?: return null
        if ((!manualRegistration && !dynamicReferenceComparison) || !hasRegistration || !metricRegistrationValid || !twin.registrationEligible ||
            !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || runtime.render() !is RenderDecision.Ready) return null
        // Pose updates change transforms, not immutable source evidence identity.
        // Loss/replacement revokes this scope; speech can use the current fresh pose.
        return RegisteredReference(twin.boardIdentity, "registered:$referenceScopeEpoch:${twin.assetId}")
    }

    fun activateReferenceTwin(validation: DigitalTwinValidation): DigitalTwinValidation {
        if (validation is DigitalTwinValidation.Accepted && validation.packageData.manifest.assetId == CalibrationBoardAsset.ASSET_ID)
            return DigitalTwinValidation.Rejected(DigitalTwinValidation.Code.INVALID_MANIFEST, "calibration data is not a source model")
        return activateValidatedTwin(validation, preview = true)
    }

    private fun activateValidatedTwin(validation: DigitalTwinValidation, preview: Boolean = false): DigitalTwinValidation {
        if (validation !is DigitalTwinValidation.Accepted) {
            reset()
            activeTwin = null
            surface.digitalTwin.clearAsset()
            return validation
        }
        reset()
        activeTwin = validation.packageData.manifest
        when (surface.digitalTwin.load(validation) { result ->
            acquiringReferenceAsset = false
            if (result is DigitalTwinRenderResult.Loaded && preview) previewDigitalTwin()
            if (result !is DigitalTwinRenderResult.Loaded) {
                activeTwin = null
                surface.digitalTwin.clearAsset()
                onState(State("3D unavailable", "The project digital twin was rejected by the renderer"))
            }
        }) {
            is DigitalTwinRenderResult.Rejected -> {
                activeTwin = null
                surface.digitalTwin.clearAsset()
            }
            DigitalTwinRenderResult.Initializing,
            DigitalTwinRenderResult.Loaded,
            -> Unit
        }
        return validation
    }

    private fun onCameraStatus(status: ArCameraSurface.Status) {
        when (status) {
            is ArCameraSurface.Status.Unavailable -> {
                runtime.suspend()
                surface.overlay.render(null)
                onState(State("Camera unavailable", status.reason))
            }
            ArCameraSurface.Status.Open -> if (corners.isEmpty() && !inspectingSource) {
                onState(State("Register board", "Tap the four board corners clockwise"))
            }
            ArCameraSurface.Status.Closed -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                runtime.suspend()
                surface.overlay.render(null)
                onState(State("Camera reconnecting", "Registration was cleared safely"))
            }
            ArCameraSurface.Status.Opening -> onState(State("Camera starting", "Preparing the local preview"))
            ArCameraSurface.Status.Closing -> Unit
        }
    }

    private fun acceptCorner(point: Point2) {
        if (!manualRegistration) return
        if (corners.size >= 4) return
        corners += point
        if (corners.size < 4) {
            onState(State("Corner ${corners.size}/4", "Tap ${cornerName(corners.size)}"))
            return
        }
        installRegistration(corners.toList(), automatic = false, observedAtNanos = System.nanoTime())
    }

    private fun acceptAutomaticRegistration(observation: PoseVisionObservation) {
        if (inspectingSource || manualRegistration || System.nanoTime() - observation.timestampNanos > AUTOMATIC_OBSERVATION_MAX_AGE_NANOS) return
        lastAutomaticObservationNanos = System.nanoTime()
        if (observation.timestampNanos - lastAutomaticRegistrationNanos < AUTOMATIC_REGISTRATION_INTERVAL_NANOS) return
        // Even a stationary board needs renewed source timestamps and pose expiry.
        // Reusing an old receipt on small movement eventually hides a valid model.
        lastAutomaticRegistrationNanos = observation.timestampNanos
        installRegistration(observation.corners, automatic = true, observedAtNanos = observation.timestampNanos)
    }

    private fun handleAutomaticMiss() {
        if (inspectingSource || manualRegistration || !automaticRegistrationActive ||
            System.nanoTime() - lastAutomaticObservationNanos <= AUTOMATIC_TRACKING_LOSS_NANOS
        ) return
        automaticRegistrationActive = false
        referenceScopeEpoch++
        corners.clear()
        hasRegistration = false
        registrationRevision = null
        runtime.suspend()
        surface.overlay.render(null)
        surface.digitalTwin.updateRegistration(null, null)
        onState(State("Finding board", "Automatic registration cleared after tracking loss"))
    }

    private fun installRegistration(points: List<Point2>, automatic: Boolean, observedAtNanos: Long) {
        metricRegistrationValid = false
        val now = System.nanoTime()
        val validity = if (automatic) AUTOMATIC_TRACKING_LOSS_NANOS else REGISTRATION_VALIDITY_NANOS
        val registration = registrar.register(points, observedAtNanos, validity)
        if (registration == null || !runtime.installTransforms(listOf(registration.transform), registration.revision)) {
            if (!automatic) {
                corners.clear()
                onState(State("Registration rejected", "Use four distinct corners around the complete board"))
            }
            return
        }
        corners.clear()
        corners.addAll(points)
        hasRegistration = true
        automaticRegistrationActive = automatic
        registrationRevision = registration.revision
        if (runtime.submit(conformanceScene(registration.revision, now, validity)) !is SceneSubmission.Accepted) {
            onState(State("Registration changed", "Reset and register the board again"))
            return
        }
        val twin = activeTwin
        val registeredLabel = "Board registered"
        val referenceComparison = twin != null && !twin.registrationEligible &&
            ((manualRegistration && !automatic) || (automatic && dynamicReferenceComparison))
        val metricFailure = if (twin != null && (twin.registrationEligible || referenceComparison)) {
            val intrinsics = surface.calibratedPreviewIntrinsics()
            if (intrinsics == null) {
                surface.digitalTwin.updateRegistration(null, null)
                "Camera calibration is unavailable; metric 3D AR cannot be registered"
            } else {
                val landmarks = twin.boardPlaneCorners()
                when (val estimated = NativeMetricPoseEstimator(validityNanos = validity)
                    .estimate(landmarks, points, intrinsics, registration.revision, observedAtNanos)) {
                    is PoseEstimation.Accepted -> {
                        surface.digitalTwin.updateRegistration(estimated.pose, intrinsics, compareReference = referenceComparison)
                        metricRegistrationValid = true
                        null
                    }
                    is PoseEstimation.Rejected -> {
                        surface.digitalTwin.updateRegistration(null, null)
                        estimated.reason
                    }
                }
            }
        } else null
        if (metricFailure != null) android.util.Log.i("TokitoMetricAR", metricFailure)
        when (val decision = runtime.render()) {
            is RenderDecision.Suppressed -> onState(State("Overlay suppressed", decision.reason))
            is RenderDecision.Ready -> when {
                twin == null -> onState(State(registeredLabel, "2D guidance active; no validated project digital twin", true))
                metricFailure != null -> onState(State("3D registration unavailable", metricFailure, true))
                referenceComparison -> onState(State(if (automatic) "Dynamic reference AR" else "Reference AR · unverified fit",
                    "Source model follows matched board features; physical revision and component identity remain unverified", true))
                !twin.registrationEligible -> onState(State(registeredLabel, "2D guidance active; source model requires manual reference comparison", true))
                else -> onState(State(if (automatic) "3D auto registered" else "3D registered", "Digital twin follows the tracked board", true))
            }
        }
    }

    private fun conformanceScene(revision: Long, now: Long, validity: Long = REGISTRATION_VALIDITY_NANOS): OverlayScene {
        val expiry = now + validity
        return OverlayScene(
            sequence = ++sequence,
            transformRevision = revision,
            generatedAtNanos = now,
            expiresAtNanos = expiry,
            guidance = Guidance(
                title = "Board registered",
                detail = "Local frame established",
                step = 1,
                stepCount = 1,
            ),
            primitives = listOf(
                OverlayPrimitive.Outline(
                    id = "registered-board",
                    frame = CoordinateFrame.BOARD,
                    transformRevision = revision,
                    observedAtNanos = now,
                    expiresAtNanos = expiry,
                    uncertaintyPx = 0.0,
                    label = "Registered board",
                    tone = OverlayTone.Primary,
                    points = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0)),
                ),
            ),
        )
    }

    private fun cornerName(saved: Int): String = when (saved) {
        1 -> "top-right"
        2 -> "bottom-right"
        3 -> "bottom-left"
        else -> "top-left"
    }

    private companion object {
        const val REGISTRATION_VALIDITY_NANOS = 120_000_000_000L
        const val HIGHLIGHT_VALIDITY_NANOS = 15_000_000_000L
        const val AUTOMATIC_OBSERVATION_MAX_AGE_NANOS = 1_000_000_000L
        const val POSE_INFERENCE_INTERVAL_NANOS = 250_000_000L
        const val REFERENCE_TRACKING_INTERVAL_NANOS = 250_000_000L
        const val AUTOMATIC_REGISTRATION_INTERVAL_NANOS = 100_000_000L
        const val AUTOMATIC_TRACKING_LOSS_NANOS = 900_000_000L
    }
}
