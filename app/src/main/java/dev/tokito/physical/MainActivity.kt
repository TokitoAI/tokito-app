package dev.tokito.physical

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.tokito.physical.databinding.ActivityMainBinding
import dev.tokito.physical.dialogue.Capability
import dev.tokito.physical.dialogue.DialogueModelProvisioner
import dev.tokito.physical.dialogue.DialogueOutcome
import dev.tokito.physical.dialogue.DialogueRequest
import dev.tokito.physical.dialogue.DialogueRole
import dev.tokito.physical.dialogue.DialogueRuntimeState
import dev.tokito.physical.dialogue.DialogueTurn
import dev.tokito.physical.dialogue.ObservationSummary
import dev.tokito.physical.dialogue.TokitoDialogueRuntime
import dev.tokito.physical.measurement.MeasurementCandidate
import dev.tokito.physical.measurement.MeasurementTurn
import dev.tokito.physical.measurement.MeasurementValue
import dev.tokito.physical.measurement.RelationshipKind
import dev.tokito.physical.voice.MeasurementVoiceSession
import dev.tokito.physical.voice.SpeechQueuePolicy
import dev.tokito.physical.voice.TokitoVoiceRuntime
import dev.tokito.physical.voice.VoiceEvent
import dev.tokito.physical.voice.VoiceRuntimeConfig
import dev.tokito.physical.voice.VoiceRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One product surface: camera, AR, local dialogue, STT, TTS, and typed tools. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: TokitoVoiceRuntime
    private lateinit var dialogue: TokitoDialogueRuntime
    private lateinit var measurementSession: MeasurementVoiceSession
    private lateinit var ar: ArSessionController

    private val conversation = ArrayDeque<DialogueTurn>()
    private var lastMeasurement: ObservationSummary? = null
    private var preparingModels = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) ar.start()
        else renderArState(ArSessionController.State("Camera blocked", "Camera permission is required for board guidance"))
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchAction { voice.startListening() }
        else showStatus("Microphone permission denied; text chat remains available")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voice = TokitoVoiceRuntime(this, VoiceRuntimeConfig())
        dialogue = TokitoDialogueRuntime(this)
        measurementSession = MeasurementVoiceSession(voice)
        ar = ArSessionController(this, binding.cameraSurface, ::renderArState)

        bindActions()
        observeRuntimes()
        ensureCameraPermission()
    }

    override fun onStart() {
        super.onStart()
        if (::ar.isInitialized && hasPermission(Manifest.permission.CAMERA)) ar.start()
    }

    override fun onStop() {
        if (::ar.isInitialized) ar.stop()
        if (::voice.isInitialized && voice.state.value == VoiceRuntimeState.Listening) {
            lifecycleScope.launch { runCatching { voice.stopListening() } }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::ar.isInitialized) ar.destroy()
        if (::dialogue.isInitialized) dialogue.close()
        if (::voice.isInitialized) voice.close()
        super.onDestroy()
    }

    private fun bindActions() {
        binding.modelButton.setOnClickListener { launchAction(::prepareLocalAi) }
        binding.resetRegistrationButton.setOnClickListener { ar.reset() }
        binding.beginMeasurementButton.setOnClickListener { launchAction(::beginMeasurement) }
        binding.confirmMeasurementButton.setOnClickListener {
            launchAction { renderMeasurementTurn(measurementSession.confirmPrimary()) }
        }
        binding.rejectMeasurementButton.setOnClickListener {
            launchAction { renderMeasurementTurn(measurementSession.reject()) }
        }
        binding.micButton.setOnClickListener { toggleVoice() }
        binding.interruptButton.setOnClickListener { launchAction { voice.interruptSpeech() } }
        binding.sendButton.setOnClickListener { launchAction(::askDialogue) }
        binding.speechInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                launchAction(::askDialogue)
                true
            } else false
        }
    }

    private fun ensureCameraPermission() {
        if (hasPermission(Manifest.permission.CAMERA)) ar.start()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun toggleVoice() {
        when (voice.state.value) {
            VoiceRuntimeState.Listening -> launchAction { voice.stopListening() }
            is VoiceRuntimeState.Speaking -> launchAction { voice.interruptSpeech() }
            is VoiceRuntimeState.Ready -> {
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) launchAction { voice.startListening() }
                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> showStatus("Prepare the verified local voice bundle first")
        }
    }

    private suspend fun prepareLocalAi() {
        if (preparingModels) return
        preparingModels = true
        binding.modelButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        showStatus("Verifying local voice and dialogue bundles")
        try {
            if (voice.state.value !is VoiceRuntimeState.Ready) voice.initialize()
            if (dialogue.state.value !is DialogueRuntimeState.Ready) {
                val model = withContext(Dispatchers.IO) {
                    DialogueModelProvisioner.fromAdmittedBundle(java.io.File(filesDir, "models/dialogue"))
                        .requireInstalled()
                }
                dialogue.initialize(model)
            }
            showStatus("Local AI ready · no cloud fallback")
        } finally {
            preparingModels = false
            binding.progressBar.visibility = View.GONE
            updateAiControls()
        }
    }

    private suspend fun askDialogue() {
        val utterance = binding.speechInput.text?.toString().orEmpty().trim()
        require(utterance.isNotEmpty()) { "Ask a question or use Talk first" }
        check(dialogue.state.value is DialogueRuntimeState.Ready) { "Verified local dialogue model is not ready" }

        hideKeyboard()
        binding.speechInput.text?.clear()
        binding.transcriptText.text = "You · $utterance"
        binding.assistantText.text = "Thinking locally…"
        val priorHistory = conversation.toList()
        appendTurn(DialogueTurn(DialogueRole.USER, utterance))
        val outcome = dialogue.respond(
            DialogueRequest(
                utterance = utterance,
                history = priorHistory,
                observations = listOfNotNull(lastMeasurement),
                capabilities = listOf(REQUEST_MEASUREMENT),
            ),
        )
        when (outcome) {
            is DialogueOutcome.Response -> presentAssistant(outcome.text)
            is DialogueOutcome.Clarification -> presentAssistant(outcome.question)
            is DialogueOutcome.ToolProposal -> executeTool(outcome)
        }
    }

    private suspend fun executeTool(proposal: DialogueOutcome.ToolProposal) {
        check(proposal.tool == REQUEST_MEASUREMENT.name && proposal.version == REQUEST_MEASUREMENT.version) {
            "The proposed tool is not authorized by this app"
        }
        beginMeasurement()
        presentAssistant(getString(R.string.measurement_request_prompt))
    }

    private suspend fun presentAssistant(text: String) {
        binding.assistantText.text = text
        appendTurn(DialogueTurn(DialogueRole.ASSISTANT, text))
        if (voice.state.value is VoiceRuntimeState.Ready || voice.state.value == VoiceRuntimeState.Listening) {
            runCatching { voice.speak(text, SpeechQueuePolicy.REPLACE) }
                .onFailure { showStatus("Response ready; local speech failed: ${it.message}") }
        }
    }

    private suspend fun beginMeasurement() {
        measurementSession.begin()
        binding.measurementCard.visibility = View.VISIBLE
        binding.beginMeasurementButton.isEnabled = false
        binding.measurementText.setText(R.string.measurement_armed)
        showStatus("Measurement capture armed")
    }

    private fun observeRuntimes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { voice.state.collect(::renderVoiceState) }
                launch { voice.events.collect(::renderVoiceEvent) }
                launch { dialogue.state.collect(::renderDialogueState) }
            }
        }
    }

    private fun renderVoiceState(state: VoiceRuntimeState) {
        binding.voiceChip.text = when (state) {
            VoiceRuntimeState.Uninitialized -> "Voice model not ready"
            is VoiceRuntimeState.Ready -> "Voice ready · on device"
            VoiceRuntimeState.Listening -> "Listening locally"
            is VoiceRuntimeState.Speaking -> "Speaking locally"
            is VoiceRuntimeState.Failed -> "Voice unavailable"
            VoiceRuntimeState.Closed -> "Voice closed"
        }
        binding.micButton.text = when (state) {
            VoiceRuntimeState.Listening -> getString(R.string.stop)
            is VoiceRuntimeState.Speaking -> getString(R.string.interrupt)
            else -> getString(R.string.talk)
        }
        binding.micButton.isEnabled = state is VoiceRuntimeState.Ready ||
            state == VoiceRuntimeState.Listening || state is VoiceRuntimeState.Speaking
        binding.interruptButton.visibility = if (state is VoiceRuntimeState.Speaking) View.VISIBLE else View.GONE
        binding.interruptButton.isEnabled = state is VoiceRuntimeState.Speaking
        if (state is VoiceRuntimeState.Failed) showStatus("${state.error.code}: ${state.error.message}")
        updateAiControls()
    }

    private fun renderDialogueState(state: DialogueRuntimeState) {
        when (state) {
            DialogueRuntimeState.Generating -> binding.assistantText.text = "Thinking locally…"
            is DialogueRuntimeState.Failed -> showStatus("${state.failure.code}: ${state.failure.message}")
            else -> Unit
        }
        updateAiControls()
    }

    private fun updateAiControls() {
        if (!::voice.isInitialized || !::dialogue.isInitialized) return
        val voiceReady = voice.state.value is VoiceRuntimeState.Ready ||
            voice.state.value == VoiceRuntimeState.Listening || voice.state.value is VoiceRuntimeState.Speaking
        val dialogueReady = dialogue.state.value is DialogueRuntimeState.Ready
        binding.sendButton.isEnabled = dialogueReady
        binding.modelButton.isEnabled = !preparingModels && !(voiceReady && dialogueReady)
        binding.modelButton.text = if (voiceReady && dialogueReady) "Local AI ready" else getString(R.string.prepare_models)
        binding.progressBar.visibility = if (preparingModels || dialogue.state.value == DialogueRuntimeState.Loading ||
            dialogue.state.value == DialogueRuntimeState.Generating
        ) View.VISIBLE else View.GONE
    }

    private fun renderVoiceEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Transcript -> {
                val prefix = if (event.segment.isFinal) "Heard" else "Listening"
                binding.transcriptText.text = "$prefix · ${event.segment.text}"
                if (event.segment.isFinal) {
                    binding.speechInput.setText(event.segment.text)
                    binding.speechInput.setSelection(event.segment.text.length)
                    launchAction {
                        measurementSession.accept(event.segment)?.let { renderMeasurementTurn(it) }
                    }
                }
            }
            is VoiceEvent.BargeInDetected -> showStatus("Barge-in detected · speech interrupted")
            is VoiceEvent.Error -> showStatus("${event.failure.code}: ${event.failure.message}")
            else -> Unit
        }
    }

    private suspend fun renderMeasurementTurn(turn: MeasurementTurn) {
        binding.measurementCard.visibility = View.VISIBLE
        binding.measurementText.text = when (turn) {
            is MeasurementTurn.ConfirmationRequired -> {
                binding.confirmMeasurementButton.isEnabled = true
                binding.rejectMeasurementButton.isEnabled = true
                "Confirm ${turn.draft.primary.display()}"
            }
            is MeasurementTurn.Confirmed -> {
                resetMeasurementControls()
                val candidate = turn.measurement.confirmedCandidate
                lastMeasurement = ObservationSummary(
                    kind = "engineer_voice_measurement",
                    summary = candidate.display(),
                    confidence = candidate.confidence,
                    observedAtElapsedMs = turn.measurement.confirmedAtMonotonicMillis,
                )
                val confirmation = "Confirmed ${candidate.display()}"
                if (voice.state.value is VoiceRuntimeState.Ready || voice.state.value == VoiceRuntimeState.Listening) {
                    voice.speak(confirmation, SpeechQueuePolicy.REPLACE)
                }
                confirmation
            }
            is MeasurementTurn.CorrectionRequired -> {
                binding.confirmMeasurementButton.isEnabled = false
                binding.rejectMeasurementButton.isEnabled = false
                turn.incomplete.recoverablePrompt
            }
            is MeasurementTurn.Rejected -> {
                resetMeasurementControls()
                "Not recorded: ${turn.reason}"
            }
        }
    }

    private fun resetMeasurementControls() {
        binding.beginMeasurementButton.isEnabled = true
        binding.confirmMeasurementButton.isEnabled = false
        binding.rejectMeasurementButton.isEnabled = false
    }

    private fun renderArState(state: ArSessionController.State) {
        binding.trackingChip.text = state.label
        binding.trackingChip.setTextColor(
            ContextCompat.getColor(this, if (state.registered) R.color.tokito_primary else R.color.tokito_text),
        )
        showStatus(state.detail)
    }

    private fun appendTurn(turn: DialogueTurn) {
        conversation.addLast(turn)
        while (conversation.size > MAX_CONVERSATION_TURNS) conversation.removeFirst()
    }

    private fun MeasurementCandidate.display(): String {
        val renderedValue = when (val parsed = value) {
            is MeasurementValue.Scalar -> parsed.value.toPlainString()
            is MeasurementValue.Range -> "${parsed.low.toPlainString()}–${parsed.high.toPlainString()}"
        }
        val location = relationship?.let { relation ->
            when (relation.kind) {
                RelationshipKind.BETWEEN -> " from ${relation.primary} to ${relation.reference}"
                RelationshipKind.ACROSS -> " across ${relation.primary}"
                RelationshipKind.AT -> " at ${relation.primary}"
            }
        }.orEmpty()
        return "$renderedValue ${unit.symbol}$location"
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.speechInput.windowToken, 0)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showStatus(message: String) {
        binding.statusText.text = message
    }

    private fun launchAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }
                .onFailure { showStatus(it.message ?: "Local operation failed") }
        }
    }

    companion object {
        private const val MAX_CONVERSATION_TURNS = 8
        private val REQUEST_MEASUREMENT = Capability(
            name = "request_measurement",
            version = 1,
            description = "Ask the engineer for one electrical reading and confirm it locally.",
            arguments = emptyList(),
            maxDeadlineMs = 15_000,
        )
    }
}
