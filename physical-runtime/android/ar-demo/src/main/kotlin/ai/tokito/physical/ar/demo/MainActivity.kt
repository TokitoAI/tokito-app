package ai.tokito.physical.ar.demo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ai.tokito.physical.ar.CoordinateFrame
import ai.tokito.physical.ar.Guidance
import ai.tokito.physical.ar.ManualBoardRegistrar
import ai.tokito.physical.ar.OverlayPrimitive
import ai.tokito.physical.ar.OverlayScene
import ai.tokito.physical.ar.OverlayTone
import ai.tokito.physical.ar.Point2
import ai.tokito.physical.ar.RenderDecision
import ai.tokito.physical.ar.SceneSubmission
import ai.tokito.physical.ar.ArCameraSurface
import ai.tokito.physical.ar.ArRuntime
import ai.tokito.physical.ar.ArPresenter
import ai.tokito.physical.ar.ArCameraSurface.StartResult
import ai.tokito.physical.ar.ArCameraSurface.Status
import ai.tokito.physical.ar.CalibrationBoardAsset
import ai.tokito.physical.ar.CameraIntrinsics
import ai.tokito.physical.ar.DigitalTwinRenderResult
import ai.tokito.physical.ar.DigitalTwinValidation
import ai.tokito.physical.ar.DigitalTwinValidator
import ai.tokito.physical.ar.MetricPoseEstimator
import ai.tokito.physical.ar.MetricBoardPose
import ai.tokito.physical.ar.PoseEstimation
import ai.tokito.physical.ar.Vector3

/** Device conformance host. It accepts manual registration only; it never claims vision output. */
class MainActivity : ComponentActivity() {
    private lateinit var surface: ArCameraSurface
    private lateinit var status: TextView
    private val runtime = ArRuntime()
    private lateinit var presenter: ArPresenter
    private val registrar = ManualBoardRegistrar()
    private val corners = mutableListOf<Point2>()
    private var cameraStarted = false
    private var sequence = 0L
    private val poseEstimator = MetricPoseEstimator(validityNanos = 120_000_000_000L)
    private var twinReady = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showStatus("Camera permission is required. No camera data leaves this device.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = ArCameraSurface(this)
        setContentView(buildContent())
        loadCalibrationTwin()
        presenter = ArPresenter(runtime, surface.overlay) { reason ->
            if (corners.size == 4) showStatus("AR guidance paused: $reason. Reset and register again.")
        }.also { it.bind(this) }
        surface.overlay.setOnBoardTapListener(::acceptCorner)
        ensureCameraPermission()
        resetRegistration()
    }

    override fun onStop() {
        runtime.suspend()
        corners.clear()
        surface.overlay.render(null)
        if (cameraStarted) {
            surface.stop()
            cameraStarted = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        surface.destroy()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (::surface.isInitialized && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else if (::status.isInitialized) {
            showStatus("Camera permission is unavailable. Grant it in system settings to continue.")
        }
    }

    private fun buildContent(): FrameLayout = FrameLayout(this).apply {
        setBackgroundColor(Color.rgb(9, 12, 18))
        addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val topBar = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.argb(225, 9, 12, 18))
        }
        status = TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
            contentDescription = "Registration status"
        }
        topBar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(Button(this@MainActivity).apply {
            text = "Preview 3D"
            isAllCaps = false
            contentDescription = "Preview the validated three-dimensional calibration asset without camera registration"
            setOnClickListener { showUnregisteredTwinPreview() }
        })
        topBar.addView(Button(this@MainActivity).apply {
            text = getString(R.string.reset)
            isAllCaps = false
            contentDescription = "Clear board registration"
            setOnClickListener { resetRegistration() }
        })
        addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (!cameraStarted) {
            when (val result = surface.start(this, ::onCameraStatus)) {
                StartResult.Started, StartResult.AlreadyRunning -> cameraStarted = true
                is StartResult.Failed -> {
                    runtime.suspend()
                    showStatus("Camera unavailable: ${result.reason}")
                }
            }
        }
    }

    private fun onCameraStatus(cameraStatus: Status) {
        when (cameraStatus) {
            is Status.Unavailable -> {
                runtime.suspend()
                surface.overlay.render(null)
                showStatus("Camera unavailable: ${cameraStatus.reason}")
            }
            Status.Closed -> if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                runtime.suspend()
                surface.overlay.render(null)
                showStatus("Camera closed. CameraX will reconnect when the device permits it.")
            }
            Status.Open -> if (corners.isEmpty()) showStatus("Tap the board corners: top-left, top-right, bottom-right, bottom-left")
            Status.Opening, Status.Closing -> Unit
        }
    }

    private fun resetRegistration() {
        corners.clear()
        runtime.suspend()
        surface.overlay.render(null)
        surface.digitalTwin.updateRegistration(null, null)
        showStatus("Tap the board corners: top-left, top-right, bottom-right, bottom-left")
    }

    private fun acceptCorner(point: Point2) {
        if (corners.size >= 4) return
        corners += point
        if (corners.size < 4) {
            showStatus("Corner ${corners.size}/4 saved — tap ${cornerName(corners.size)}")
            return
        }
        val now = System.nanoTime()
        val registration = registrar.register(corners, now, validityNanos = 120_000_000_000L)
        if (registration == null || !runtime.installTransforms(listOf(registration.transform), registration.revision)) {
            showStatus("Registration rejected: corners must enclose a non-degenerate board. Reset and retry.")
            corners.clear()
            return
        }
        val scene = conformanceScene(registration.revision, now)
        if (runtime.submit(scene) !is SceneSubmission.Accepted) {
            showStatus("Registration changed before the overlay was accepted. Reset and retry.")
            return
        }
        val intrinsics = surface.calibratedPreviewIntrinsics()
        val pose = intrinsics?.let {
            poseEstimator.estimate(
                boardPointsMetres = listOf(
                    Point2(0.0, 0.0),
                    Point2(CalibrationBoardAsset.BOARD_WIDTH_METRES, 0.0),
                    Point2(CalibrationBoardAsset.BOARD_WIDTH_METRES, CalibrationBoardAsset.BOARD_HEIGHT_METRES),
                    Point2(0.0, CalibrationBoardAsset.BOARD_HEIGHT_METRES),
                ),
                imagePointsPx = corners,
                intrinsics = it,
                revision = registration.revision,
                nowNanos = now,
            )
        }
        when (val decision = runtime.render()) {
            is RenderDecision.Ready -> when {
                !twinReady -> showStatus("2D registered; the validated 3D calibration asset is unavailable")
                intrinsics == null -> showStatus("2D registered; this camera does not expose calibrated intrinsics, so 3D is suppressed")
                pose is PoseEstimation.Rejected -> showStatus("2D registered; 3D pose rejected: ${pose.reason}")
                pose is PoseEstimation.Accepted -> {
                    surface.digitalTwin.updateRegistration(pose.pose, intrinsics)
                    showStatus("Metric 3D calibration twin registered · ${"%.2f".format(pose.pose.reprojectionRmsPx)} px RMS · valid for 2 minutes")
                }
                else -> showStatus("2D registered; metric pose is unavailable")
            }
            is RenderDecision.Suppressed -> showStatus("Overlay suppressed: ${decision.reason}")
        }
    }

    private fun loadCalibrationTwin() {
        val packageData = CalibrationBoardAsset.create()
        val validation = DigitalTwinValidator().validate(
            packageData.manifest,
            packageData.glb,
            packageData.manifest.assemblyContextRevision,
        )
        if (validation !is DigitalTwinValidation.Accepted) {
            twinReady = false
            return
        }
        twinReady = when (surface.digitalTwin.load(validation) { result ->
            twinReady = result is DigitalTwinRenderResult.Loaded
        }) {
            DigitalTwinRenderResult.Loaded -> true
            DigitalTwinRenderResult.Initializing, is DigitalTwinRenderResult.Rejected -> false
        }
    }

    /**
     * Renderer conformance proof only. The synthetic camera is declared in the
     * UI and never enters the registration runtime or masquerades as tracking.
     */
    private fun showUnregisteredTwinPreview() {
        if (!twinReady) {
            showStatus("3D renderer is still initializing; try again in a moment")
            return
        }
        corners.clear()
        runtime.suspend()
        surface.overlay.render(null)
        surface.digitalTwin.post {
            val width = surface.digitalTwin.width
            val height = surface.digitalTwin.height
            if (width <= 0 || height <= 0) {
                showStatus("3D preview surface is not ready")
                return@post
            }
            val now = System.nanoTime()
            val intrinsics = CameraIntrinsics(
                fx = width * 1.15,
                fy = width * 1.15,
                cx = width / 2.0,
                cy = height / 2.0,
                widthPx = width,
                heightPx = height,
            )
            val pose = MetricBoardPose(
                r1 = Vector3(0.966, 0.045, 0.255),
                r2 = Vector3(-0.118, 0.951, 0.286),
                r3 = Vector3(-0.230, -0.306, 0.924),
                translationMetres = Vector3(-0.060, -0.040, 0.500),
                revision = Long.MAX_VALUE,
                observedAtNanos = now,
                expiresAtNanos = now + 120_000_000_000L,
                reprojectionRmsPx = 0.0,
                confidence = 1.0,
            )
            surface.digitalTwin.updateRegistration(pose, intrinsics)
            showStatus("3D proof · synthetic pose · NOT AR-registered")
        }
    }

    private fun conformanceScene(revision: Long, now: Long): OverlayScene {
        val expiry = now + 120_000_000_000L
        return OverlayScene(
            sequence = ++sequence,
            transformRevision = revision,
            generatedAtNanos = now,
            expiresAtNanos = expiry,
            guidance = Guidance(
                title = "AR registration test",
                detail = "Confirm the outline and orientation marker stay on the physical board",
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
                    label = "Orientation check",
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

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
