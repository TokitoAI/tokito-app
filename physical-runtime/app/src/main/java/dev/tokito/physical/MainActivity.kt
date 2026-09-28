package dev.tokito.physical

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.tokito.physical.databinding.ActivityMainBinding
import dev.tokito.physical.dialogue.DialogueModelProvisioner
import dev.tokito.physical.dialogue.BoardReferenceContext
import dev.tokito.physical.dialogue.DialogueLimits
import dev.tokito.physical.dialogue.DialogueOutcome
import dev.tokito.physical.dialogue.DialogueRequest
import dev.tokito.physical.dialogue.DialogueRuntimeState
import dev.tokito.physical.dialogue.TokitoDialogueRuntime
import dev.tokito.physical.model.DeviceResourceGovernor
import dev.tokito.physical.voice.SpeechQueuePolicy
import dev.tokito.physical.voice.TokitoVoiceRuntime
import dev.tokito.physical.voice.VoiceEvent
import dev.tokito.physical.voice.VoiceRuntimeConfig
import dev.tokito.physical.voice.VoiceRuntimeState
import dev.tokito.physical.session.PhysicalSessionStore
import dev.tokito.physical.session.ObservationDraft
import dev.tokito.physical.measurement.MeasurementCandidate
import dev.tokito.physical.measurement.MeasurementTurn
import dev.tokito.physical.measurement.MeasurementValue
import dev.tokito.physical.measurement.RelationshipKind
import dev.tokito.physical.voice.MeasurementVoiceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

/** Camera-first host for the private, fully local voice conversation loop. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: TokitoVoiceRuntime
    private lateinit var dialogue: TokitoDialogueRuntime
    private lateinit var ar: ArSessionController
    private lateinit var call: VoiceCallController
    private lateinit var measurement: MeasurementVoiceSession
    private var boardReference: BoardReferenceContext? = null
    private var digitalTwinAttempted = false
    private var vision: LocalPoseVisionRuntime? = null
    private var cameraPermissionRequested = false
    private var microphonePermissionRequested = false
    private lateinit var sessionStore: PhysicalSessionStore
    private lateinit var sessionId: String
    private lateinit var resourceGovernor: DeviceResourceGovernor

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            binding.permissionRecoveryButton.visibility = View.GONE
            ar.start()
        } else {
            binding.permissionRecoveryButton.visibility = View.VISIBLE
            renderArState(ArSessionController.State("Camera blocked", "Allow camera access to inspect the board"))
        }
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) lifecycleScope.launch { call.start() }
        else {
            if (::measurement.isInitialized && measurement.isActive) {
                lifecycleScope.launch { measurement.cancel() }
                closeMeasurementCapture()
            }
            renderCallState(CallState.Unavailable("Allow microphone access to start a call"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The camera view intentionally draws edge to edge, so the AR
        // controls column must clear the system bar / display cutout itself:
        // in landscape the navigation bar sits at the screen's end edge and
        // otherwise covers the buttons, leaving them clipped and only
        // partially tappable.
        val controlsBaseEndMargin =
            (binding.arControls.layoutParams as android.widget.FrameLayout.LayoutParams).marginEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.arControls) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                marginEnd = controlsBaseEndMargin + maxOf(insets.right, insets.left)
            }
            windowInsets
        }

        sessionId = requireNotNull(intent.getStringExtra(EXTRA_SESSION_ID)) {
            "Physical sessions must be opened through the Tokito session boundary"
        }
        sessionStore = PhysicalSessionStore.open(this)
        requireNotNull(sessionStore.session(sessionId)) { "Physical session does not exist" }
        resourceGovernor = DeviceResourceGovernor.android(this)

        voice = TokitoVoiceRuntime(this, VoiceRuntimeConfig())
        dialogue = TokitoDialogueRuntime(
            this,
            DialogueLimits(maxPredictTokens = BuildConfig.DIALOGUE_MAX_PREDICT_TOKENS),
        )
        ar = ArSessionController(this, binding.cameraSurface, ::renderArState)
        call = VoiceCallController(
            scope = lifecycleScope,
            prepare = ::prepareLocalAi,
            startListening = { voice.startListening() },
            stopListening = { voice.stopListening() },
            interruptSpeech = { voice.interruptSpeech() },
            respond = ::respondLocally,
            speak = { text, onPlaybackStarted ->
                voice.speak(text, SpeechQueuePolicy.REPLACE, onPlaybackStarted)
            },
            onState = ::renderCallState,
        )
        measurement = MeasurementVoiceSession(voice)

        bindActions()
        observeVoiceEvents()
        ensureCameraPermission()
        lifecycleScope.launch { prepareLocalVision() }
        lifecycleScope.launch { call.warmUp() }
        registerDebugUtteranceReceiver()
    }

    // Debug builds accept an utterance over adb so the grounded-answer and
    // highlight path can be exercised and diagnosed independently of the
    // microphone and speech recognition:
    //   adb shell am broadcast -a dev.tokito.physical.debug.UTTERANCE \
    //     --es utterance "where is the main chip"
    // Release builds never register the receiver, so the production entry
    // point for dialogue remains the local voice loop only.
    private var debugUtteranceReceiver: android.content.BroadcastReceiver? = null

    private fun registerDebugUtteranceReceiver() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val utterance = intent?.getStringExtra("utterance")?.takeIf { it.isNotBlank() } ?: return
                lifecycleScope.launch {
                    val answer = respondLocally(utterance)
                    android.util.Log.i(
                        "TokitoDialogue",
                        "debug utterance='$utterance' answer='$answer'",
                    )
                    // Some devices (observed on vivo/iQOO) drop all app logcat
                    // output via a system log kill switch, so the debug hook
                    // also records its outcome to a run-as readable file.
                    runCatching {
                        File(filesDir, "debug-last-answer.txt")
                            .writeText("utterance=$utterance\nanswer=$answer\n")
                    }
                    voice.speak(answer, SpeechQueuePolicy.REPLACE) {}
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            android.content.IntentFilter("dev.tokito.physical.debug.UTTERANCE"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        debugUtteranceReceiver = receiver
    }

    override fun onStart() {
        super.onStart()
        if (::ar.isInitialized && hasPermission(Manifest.permission.CAMERA)) ar.start()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        renderCallState(call.currentState())
    }

    override fun onStop() {
        if (::call.isInitialized) lifecycleScope.launch { call.stop() }
        if (::ar.isInitialized) ar.stop()
        super.onStop()
    }

    override fun onDestroy() {
        debugUtteranceReceiver?.let(::unregisterReceiver)
        debugUtteranceReceiver = null
        if (::call.isInitialized) call.close()
        if (::ar.isInitialized) ar.destroy()
        if (::dialogue.isInitialized) dialogue.close()
        if (::voice.isInitialized) voice.close()
        if (::sessionStore.isInitialized) {
            if (isFinishing && !isChangingConfigurations) {
                sessionStore.session(sessionId)?.takeIf { it.status == "active" }?.let { session ->
                    sessionStore.finishSession(sessionId, session.revision)
                }
            }
            sessionStore.close()
        }
        super.onDestroy()
    }

    private fun bindActions() {
        binding.callButton.setOnClickListener {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                requestMicrophonePermission()
            } else {
                lifecycleScope.launch { call.toggle() }
            }
        }
        binding.permissionRecoveryButton.setOnClickListener { requestCameraPermission() }
        binding.registerArButton.setOnClickListener { ar.beginRegistration() }
        binding.previewDigitalTwinButton.setOnClickListener { ar.previewDigitalTwin() }
        binding.resetRegistrationButton.setOnClickListener { ar.reset() }
        binding.recordMeasurementButton.setOnClickListener {
            lifecycleScope.launch { beginMeasurementCapture() }
        }
        binding.confirmMeasurementButton.setOnClickListener {
            lifecycleScope.launch { renderMeasurementTurn(measurement.confirmPrimary()) }
        }
        binding.discardMeasurementButton.setOnClickListener {
            lifecycleScope.launch {
                measurement.cancel()
                closeMeasurementCapture()
            }
        }
    }

    private fun ensureCameraPermission() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            binding.permissionRecoveryButton.visibility = View.GONE
            ar.start()
        } else {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        if (cameraPermissionRequested &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        ) {
            openPermissionSettings()
            return
        }
        cameraPermissionRequested = true
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun requestMicrophonePermission() {
        if (microphonePermissionRequested &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            openPermissionSettings()
            return
        }
        microphonePermissionRequested = true
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun openPermissionSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun observeVoiceEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                voice.events.collect { event ->
                    when (event) {
                        is VoiceEvent.Transcript -> {
                            if (measurement.isActive) {
                                measurement.accept(event.segment)?.let { renderMeasurementTurn(it) }
                            } else {
                                call.accept(event.segment)
                            }
                        }
                        is VoiceEvent.Error -> call.fail(event.failure.message)
                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun prepareLocalAi() {
        boardReference = withContext(Dispatchers.IO) {
            BoardReferenceContext.loadIfPresent(filesDir)
        }
        activateProjectTwinIfPresent()
        if (voice.state.value !is VoiceRuntimeState.Ready &&
            voice.state.value != VoiceRuntimeState.Listening &&
            voice.state.value !is VoiceRuntimeState.Speaking &&
            voice.state.value !is VoiceRuntimeState.Synthesizing
        ) {
            voice.initialize()
        }
        if (dialogue.state.value !is DialogueRuntimeState.Ready) {
            // The local LLM refines grounded answers into conversational
            // phrasing; the answers themselves come from the verified board
            // reference (respondLocally already falls back to the grounded
            // text when dialogue is unavailable or unfaithful). A missing
            // dialogue bundle therefore degrades phrasing, not capability,
            // and must not take down the whole voice loop with it.
            runCatching {
                val model = withContext(Dispatchers.IO) {
                    DialogueModelProvisioner.fromAdmittedBundle(File(filesDir, "models/dialogue/active"))
                        .requireInstalled()
                }
                val modelBytes = model.artifacts.fold(0L) { total, artifact ->
                    Math.addExact(total, artifact.sizeBytes)
                }
                requireHeavyWork("dialogue", Math.multiplyExact(modelBytes, 2))
                dialogue.initialize(model)
            }.onFailure { failure ->
                android.util.Log.i(
                    "TokitoDialogue",
                    "local LLM bundle unavailable; responding with verified board data only: ${failure.message}",
                )
            }
        }
    }

    private suspend fun prepareLocalVision() {
        val modelRoot = File(filesDir, "models/vision/active")
        val installedBytes = withContext(Dispatchers.IO) {
            modelRoot.walkTopDown().filter(File::isFile).fold(0L) { total, file ->
                Math.addExact(total, file.length())
            }
        }
        if (!resourceGovernor.admit(Math.multiplyExact(installedBytes, 2)).allowed) {
            android.util.Log.w("TokitoPoseVision", "Local vision admission rejected by resource governor")
            return
        }
        val runtime = withContext(Dispatchers.IO) {
            runCatching { LocalPoseVisionRuntime.load(modelRoot) }
        }.getOrElse { failure ->
            android.util.Log.e("TokitoPoseVision", "Local vision bundle was rejected", failure)
            return
        }
        android.util.Log.i("TokitoPoseVision", "Local pose runtime admitted")
        vision = runtime
        ar.enableAutomaticRegistration(runtime)
    }

    private fun requireHeavyWork(capability: String, requiredBytes: Long) {
        val admission = resourceGovernor.admit(requiredBytes)
        check(admission.allowed) {
            "$capability unavailable: ${admission.reason ?: "resource_limited"}"
        }
        if (admission.maxConcurrency == 1) {
            android.util.Log.w("TokitoResources", "$capability admitted with constrained concurrency")
        }
    }

    private suspend fun respondLocally(utterance: String): String {
        val reference = boardReference
            ?: return "No verified board reference is loaded."
        val grounded = reference.index.groundedAnswer(utterance)
            ?: return "I couldn't match that to verified ${reference.identity.model} data. Please repeat the component name."
        grounded.boardTarget?.let { target ->
            val accepted = ar.highlightBoardTarget(target)
            android.util.Log.i(
                "TokitoDialogue",
                "grounded board target ${target.label} highlight accepted=$accepted",
            )
        }
        val generated = runCatching {
            dialogue.respond(
                DialogueRequest(
                    utterance = utterance,
                    history = emptyList(),
                    designContext = grounded.designContext,
                    deadlineMs = BuildConfig.DIALOGUE_TURN_DEADLINE_MS,
                ),
            )
        }.getOrNull()
        val text = (generated as? DialogueOutcome.Response)?.text
        return text?.takeIf {
            isFaithfulRephrase(
                it,
                utterance,
                grounded.requiredAnchors,
                grounded.requiredResponseAnchors,
                grounded.allowedNumbers,
            )
        }
            ?: grounded.fallbackText
    }

    private fun activateProjectTwinIfPresent() {
        if (digitalTwinAttempted) return
        digitalTwinAttempted = true
        val reference = boardReference ?: return
        val contextDir = File(filesDir, "contexts")
        ar.activateDigitalTwin(
            manifestFile = File(contextDir, "active.digital-twin.json"),
            glbFile = File(contextDir, "active.digital-twin.glb"),
            expectedAssemblyContextRevision = reference.identity.contextRevision,
        )
    }

    private fun isFaithfulRephrase(
        candidate: String,
        utterance: String,
        requiredAnchors: Set<String>,
        requiredResponseAnchors: Set<String>,
        allowedNumbers: Set<String>,
    ): Boolean {
        val hasAnchor = requiredAnchors.any { anchor ->
            candidate.contains(anchor, ignoreCase = true)
        }
        if (!hasAnchor) return false
        if (requiredResponseAnchors.any { !candidate.contains(it, ignoreCase = true) }) return false
        val hasNewEvidenceAnchor = requiredAnchors.any { anchor ->
            candidate.contains(anchor, ignoreCase = true) && !utterance.contains(anchor, ignoreCase = true)
        }
        val answerTokens = QUALITY_TOKEN.findAll(candidate).map { it.value.lowercase() }.toSet()
        val questionTokens = QUALITY_TOKEN.findAll(utterance).map { it.value.lowercase() }.toSet()
        val questionEcho = answerTokens.isNotEmpty() &&
            answerTokens.intersect(questionTokens).size.toDouble() / answerTokens.size >= MAX_QUESTION_ECHO_RATIO
        if (!hasNewEvidenceAnchor || questionEcho) return false
        val numbers = Regex("(?<![A-Za-z])(?:[0-9]+(?:\\.[0-9]+)?)(?![A-Za-z])")
            .findAll(candidate)
            .map(MatchResult::value)
            .toSet()
        if (!allowedNumbers.containsAll(numbers)) return false
        val references = Regex(
            "(?i)(?<![A-Z0-9])(?:R|C|U|D|Q|L|J|SW|TP|F|FB|Y|X|CN|RN|RV|BT|BH|SJ)[0-9]{1,5}(?![A-Z0-9])",
        ).findAll(candidate).map { it.value.uppercase() }.toSet()
        return requiredAnchors.map(String::uppercase).containsAll(references)
    }

    private fun renderCallState(state: CallState) {
        val label = when (state) {
            CallState.Idle -> getString(R.string.call_ready)
            CallState.Preparing -> getString(R.string.call_preparing)
            CallState.Listening -> getString(R.string.call_listening)
            CallState.Thinking -> getString(R.string.call_thinking)
            CallState.Speaking -> getString(R.string.call_speaking)
            is CallState.Unavailable -> state.reason
        }
        binding.callStateText.text = label
        binding.callButton.contentDescription = label
        binding.callButton.isEnabled = state != CallState.Preparing
        binding.callProgress.visibility =
            if (state == CallState.Preparing || state == CallState.Thinking) View.VISIBLE else View.GONE
        binding.callButton.setIconResource(
            if (state == CallState.Idle || state is CallState.Unavailable) {
                android.R.drawable.ic_btn_speak_now
            } else {
                android.R.drawable.ic_media_pause
            },
        )
        val color = when (state) {
            is CallState.Unavailable -> R.color.tokito_error
            CallState.Speaking -> R.color.tokito_warning
            else -> R.color.tokito_primary
        }
        binding.callButton.backgroundTintList = ContextCompat.getColorStateList(this, color)
    }

    private suspend fun beginMeasurementCapture() {
        measurement.begin()
        binding.measurementPanel.visibility = View.VISIBLE
        binding.measurementStateText.text = getString(R.string.measurement_listening)
        binding.confirmMeasurementButton.visibility = View.GONE
        binding.recordMeasurementButton.isEnabled = false
        if (call.currentState() == CallState.Idle || call.currentState() is CallState.Unavailable) {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                requestMicrophonePermission()
            } else {
                call.start()
            }
        }
    }

    private suspend fun renderMeasurementTurn(turn: MeasurementTurn) {
        when (turn) {
            is MeasurementTurn.ConfirmationRequired -> {
                binding.measurementStateText.text = formatMeasurement(turn.draft.primary)
                binding.confirmMeasurementButton.visibility = View.VISIBLE
            }
            is MeasurementTurn.CorrectionRequired -> {
                binding.measurementStateText.text = turn.incomplete.recoverablePrompt
                binding.confirmMeasurementButton.visibility = View.GONE
            }
            is MeasurementTurn.Confirmed -> {
                runCatching {
                    withContext(Dispatchers.IO) {
                        persistMeasurement(turn.measurement.confirmedCandidate)
                    }
                }.onSuccess {
                    binding.measurementStateText.text = getString(R.string.measurement_saved)
                    binding.confirmMeasurementButton.visibility = View.GONE
                    binding.measurementPanel.postDelayed(::closeMeasurementCapture, MEASUREMENT_SUCCESS_VISIBILITY_MS)
                }.onFailure {
                    binding.measurementStateText.text = getString(R.string.measurement_save_failed)
                    binding.confirmMeasurementButton.visibility = View.GONE
                    binding.recordMeasurementButton.isEnabled = true
                }
            }
            is MeasurementTurn.Rejected -> closeMeasurementCapture()
        }
    }

    private fun persistMeasurement(candidate: MeasurementCandidate) {
        val value = when (val measured = candidate.value) {
            is MeasurementValue.Scalar -> JSONObject().put("kind", "scalar").put("value", measured.value.toPlainString())
            is MeasurementValue.Range -> JSONObject()
                .put("kind", "range")
                .put("low", measured.low.toPlainString())
                .put("high", measured.high.toPlainString())
        }
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("value", value)
            .put("unit", candidate.unit.symbol)
            .put("quantity", candidate.unit.quantity.name.lowercase())
            .put("normalized_si_value", when (val normalized = candidate.normalizedSiValue) {
                is MeasurementValue.Scalar -> JSONObject().put("kind", "scalar").put("value", normalized.value.toPlainString())
                is MeasurementValue.Range -> JSONObject().put("kind", "range")
                    .put("low", normalized.low.toPlainString()).put("high", normalized.high.toPlainString())
            })
            .put("relationship", candidate.relationship?.let { relationship ->
                JSONObject()
                    .put("kind", relationship.kind.name.lowercase())
                    .put("primary", relationship.primary)
                    .put("reference", relationship.reference ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
        val session = checkNotNull(sessionStore.session(sessionId))
        sessionStore.appendObservation(
            sessionId,
            session.revision,
            ObservationDraft(
                kind = "electrical_measurement",
                source = "engineer_voice",
                payloadJson = payload.toString(),
                capturedAtUnixMs = System.currentTimeMillis(),
                evidenceAvailability = "unavailable",
            ),
        )
    }

    private fun formatMeasurement(candidate: MeasurementCandidate): String {
        val value = when (val measured = candidate.value) {
            is MeasurementValue.Scalar -> measured.value.toPlainString()
            is MeasurementValue.Range -> "${measured.low.toPlainString()}–${measured.high.toPlainString()}"
        }
        val relationship = candidate.relationship?.let {
            when (it.kind) {
                RelationshipKind.BETWEEN -> getString(R.string.measurement_between, it.primary, it.reference)
                RelationshipKind.ACROSS -> getString(R.string.measurement_across, it.primary)
                RelationshipKind.AT -> getString(R.string.measurement_at, it.primary)
            }
        }.orEmpty()
        return getString(R.string.measurement_confirm_value, value, candidate.unit.symbol, relationship)
    }

    private fun closeMeasurementCapture() {
        binding.measurementPanel.visibility = View.GONE
        binding.confirmMeasurementButton.visibility = View.GONE
        binding.recordMeasurementButton.isEnabled = true
    }

    private fun renderArState(state: ArSessionController.State) {
        binding.trackingChip.text = state.label
        binding.trackingChip.contentDescription = state.detail
        binding.trackingChip.setTextColor(
            ContextCompat.getColor(this, if (state.registered) R.color.tokito_primary else R.color.tokito_text),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_SESSION_ID = "dev.tokito.physical.extra.SESSION_ID"
        const val EXTRA_PROJECT_ID = "dev.tokito.physical.extra.PROJECT_ID"
        const val EXTRA_DESIGN_ID = "dev.tokito.physical.extra.DESIGN_ID"
        const val EXTRA_CONTEXT_REVISION = "dev.tokito.physical.extra.CONTEXT_REVISION"
        val QUALITY_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{1,63}")
        const val MAX_QUESTION_ECHO_RATIO = 0.80
        const val MEASUREMENT_SUCCESS_VISIBILITY_MS = 1_200L
    }
}
