package dev.tokito.physical

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import ai.tokito.physical.ar.ArCameraSurface
import ai.tokito.physical.ar.ArPresenter
import ai.tokito.physical.ar.ArRuntime
import ai.tokito.physical.ar.CameraIntrinsics
import ai.tokito.physical.ar.CoordinateFrame
import ai.tokito.physical.ar.DigitalTwinRenderResult
import ai.tokito.physical.ar.DigitalTwinValidation
import ai.tokito.physical.ar.DigitalTwinValidator
import ai.tokito.physical.ar.DigitalTwinManifest
import ai.tokito.physical.ar.Guidance
import ai.tokito.physical.ar.ManualBoardRegistrar
import ai.tokito.physical.ar.MetricPoseEstimator
import ai.tokito.physical.ar.OverlayPrimitive
import ai.tokito.physical.ar.OverlayScene
import ai.tokito.physical.ar.OverlayTone
import ai.tokito.physical.ar.Point2
import ai.tokito.physical.ar.PoseEstimation
import ai.tokito.physical.ar.RenderDecision
import ai.tokito.physical.ar.SceneSubmission
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
    private val poseEstimator = MetricPoseEstimator(validityNanos = REGISTRATION_VALIDITY_NANOS)
    private val corners = mutableListOf<Point2>()
    private var cameraStarted = false
    private var activeTwin: DigitalTwinManifest? = null
    private var hasRegistration = false
    private var sequence = 0L

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
        runtime.suspend()
        corners.clear()
        hasRegistration = false
        surface.overlay.render(null)
        if (cameraStarted) surface.stop()
        cameraStarted = false
        onState(State("Camera paused", "Return to Tokito Physical to resume"))
    }

    fun destroy() = surface.destroy()

    fun reset() {
        corners.clear()
        hasRegistration = false
        runtime.suspend()
        surface.overlay.render(null)
        surface.digitalTwin.updateRegistration(null, null)
        onState(State("Register board", "Tap top-left, top-right, bottom-right, then bottom-left"))
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
        if (corners.size >= 4) return
        corners += point
        if (corners.size < 4) {
            onState(State("Corner ${corners.size}/4", "Tap ${cornerName(corners.size)}"))
            return
        }
        val now = System.nanoTime()
        val registration = registrar.register(corners, now, REGISTRATION_VALIDITY_NANOS)
        if (registration == null || !runtime.installTransforms(listOf(registration.transform), registration.revision)) {
            corners.clear()
            onState(State("Registration rejected", "Use four distinct corners around the complete board"))
            return
        }
        hasRegistration = true
        if (runtime.submit(conformanceScene(registration.revision, now)) !is SceneSubmission.Accepted) {
            onState(State("Registration changed", "Reset and register the board again"))
            return
        }
        val twin = activeTwin
        val intrinsics = if (twin == null) null else surface.calibratedPreviewIntrinsics()
        val pose = if (twin == null || intrinsics == null) null else {
            poseEstimator.estimate(
                boardPointsMetres = twin.boardPlaneCorners(),
                imagePointsPx = corners,
                intrinsics = intrinsics,
                revision = registration.revision,
                nowNanos = now,
            )
        }
        when (val decision = runtime.render()) {
            is RenderDecision.Suppressed -> onState(State("Overlay suppressed", decision.reason))
            is RenderDecision.Ready -> when {
                twin == null -> onState(State("Board registered", "2D guidance active; no validated project digital twin", true))
                intrinsics == null -> onState(State("Board registered", "2D guidance active; calibrated intrinsics unavailable", true))
                pose is PoseEstimation.Rejected -> onState(State("Board registered", "3D pose rejected: ${pose.reason}", true))
                pose is PoseEstimation.Accepted -> {
                    surface.digitalTwin.updateRegistration(pose.pose, intrinsics)
                    onState(State("3D registered", "${"%.2f".format(pose.pose.reprojectionRmsPx)} px reprojection RMS", true))
                }
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
                    uncertaintyPx = 8.0,
                    label = "Registered board",
                    tone = OverlayTone.Primary,
                    points = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0)),
                ),
                OverlayPrimitive.Target(
                    id = "orientation-marker",
                    frame = CoordinateFrame.BOARD,
                    transformRevision = revision,
                    observedAtNanos = now,
                    expiresAtNanos = expiry,
                    uncertaintyPx = 8.0,
                    label = "Orientation",
                    tone = OverlayTone.Warning,
                    center = Point2(0.15, 0.15),
                    radius = 0.04,
                    directionRadians = 0.0,
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
    }
}
