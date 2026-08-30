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
    private var sequence = 0L
    private var vision: LocalPoseVisionRuntime? = null
    private var manualRegistration = false
    private var lastAutomaticRegistrationNanos = 0L
    private var automaticRegistrationActive = false
    private var lastAutomaticObservationNanos = 0L

    init {
        ArPresenter(runtime, surface.overlay) { reason ->
            onState(
                if (hasRegistration) State("Registration lost", reason)
                else State("Register board", "Tap the four board corners clockwise"),
            )
        }.bind(owner)
        surface.overlay.setOnBoardTapListener(::acceptCorner)
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
        vision?.close()
        vision = null
        surface.destroy()
    }

    fun enableAutomaticRegistration(runtime: LocalPoseVisionRuntime) {
        vision?.close()
        vision = runtime
        manualRegistration = false
        surface.setFrameListener { frame ->
            val observation = runCatching { runtime.analyze(frame) }.getOrElse { failure ->
                android.util.Log.e("TokitoPoseVision", "Local pose inference failed", failure)
                null
            }
            surface.post {
                if (observation == null) handleAutomaticMiss() else acceptAutomaticRegistration(observation)
            }
        }
        onState(State("Finding board", "Local pose detection is active"))
    }

    fun reset() {
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
        corners.clear()
        hasRegistration = false
        registrationRevision = null
        runtime.suspend()
        surface.overlay.render(null)
        if (surface.digitalTwin.showUnregisteredPreview()) {
            onState(State("3D preview", "Validated model inspection; not AR registered"))
        } else {
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
        return runtime.submit(scene) is SceneSubmission.Accepted
    }

    private fun activateValidatedTwin(validation: DigitalTwinValidation): DigitalTwinValidation {
        if (validation !is DigitalTwinValidation.Accepted) {
            activeTwin = null
            surface.digitalTwin.clearAsset()
            return validation
        }
        activeTwin = validation.packageData.manifest
        when (surface.digitalTwin.load(validation) { result ->
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
            ArCameraSurface.Status.Open -> if (corners.isEmpty()) {
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
        if (manualRegistration || System.nanoTime() - observation.timestampNanos > AUTOMATIC_OBSERVATION_MAX_AGE_NANOS) return
        lastAutomaticObservationNanos = System.nanoTime()
        if (observation.timestampNanos - lastAutomaticRegistrationNanos < AUTOMATIC_REGISTRATION_INTERVAL_NANOS) return
        if (hasRegistration && corners.indices.all { index ->
                kotlin.math.hypot(
                    corners[index].x - observation.corners[index].x,
                    corners[index].y - observation.corners[index].y,
                ) < AUTOMATIC_MINIMUM_MOVEMENT_PX
            }) return
        lastAutomaticRegistrationNanos = observation.timestampNanos
        installRegistration(observation.corners, automatic = true, observedAtNanos = observation.timestampNanos)
    }

    private fun handleAutomaticMiss() {
        if (manualRegistration || !automaticRegistrationActive ||
            System.nanoTime() - lastAutomaticObservationNanos <= AUTOMATIC_TRACKING_LOSS_NANOS
        ) return
        automaticRegistrationActive = false
        corners.clear()
        hasRegistration = false
        registrationRevision = null
        runtime.suspend()
        surface.overlay.render(null)
        surface.digitalTwin.updateRegistration(null, null)
        onState(State("Finding board", "Automatic registration cleared after tracking loss"))
    }

    private fun installRegistration(points: List<Point2>, automatic: Boolean, observedAtNanos: Long) {
        val now = System.nanoTime()
        val registration = registrar.register(points, observedAtNanos, REGISTRATION_VALIDITY_NANOS)
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
        if (runtime.submit(conformanceScene(registration.revision, now)) !is SceneSubmission.Accepted) {
            onState(State("Registration changed", "Reset and register the board again"))
            return
        }
        val twin = activeTwin
        val registeredLabel = "Board registered"
        if (twin != null) surface.digitalTwin.updateScreenRegistration(points)
        when (val decision = runtime.render()) {
            is RenderDecision.Suppressed -> onState(State("Overlay suppressed", decision.reason))
            is RenderDecision.Ready -> when {
                twin == null -> onState(State(registeredLabel, "2D guidance active; no validated project digital twin", true))
                else -> onState(State(if (automatic) "3D auto registered" else "3D registered", "Digital twin follows the tracked board", true))
            }
        }
    }

    private fun conformanceScene(revision: Long, now: Long): OverlayScene {
        val expiry = now + REGISTRATION_VALIDITY_NANOS
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
        const val AUTOMATIC_REGISTRATION_INTERVAL_NANOS = 300_000_000L
        const val AUTOMATIC_MINIMUM_MOVEMENT_PX = 3.0
        const val AUTOMATIC_TRACKING_LOSS_NANOS = 900_000_000L
    }
}
