package dev.tokito.physical

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.tokito.physical.databinding.ActivityMainBinding
import dev.tokito.physical.dialogue.Capability
import dev.tokito.physical.dialogue.DialogueModelProvisioner
import dev.tokito.physical.dialogue.DialogueOutcome
import dev.tokito.physical.dialogue.DialogueRequest
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: TokitoVoiceRuntime
    private lateinit var dialogue: TokitoDialogueRuntime
    private lateinit var measurementSession: MeasurementVoiceSession
    private val finalTranscript = StringBuilder()
    private var lastMeasurement: ObservationSummary? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchAction { voice.startListening() }
        else binding.statusText.text = "Microphone permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        voice = TokitoVoiceRuntime(this, VoiceRuntimeConfig())
        dialogue = TokitoDialogueRuntime(this)
        measurementSession = MeasurementVoiceSession(voice)
        bindActions()
        observeRuntime()
    }

    override fun onDestroy() {
        dialogue.close()
        voice.close()
        super.onDestroy()
    }

    private fun bindActions() {
        binding.initializeButton.setOnClickListener { launchAction { voice.initialize() } }
        binding.startButton.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                launchAction { voice.startListening() }
            } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.stopButton.setOnClickListener { launchAction { voice.stopListening() } }
        binding.speakButton.setOnClickListener {
            launchAction { voice.speak(binding.speechInput.text?.toString().orEmpty()) }
        }
        binding.interruptButton.setOnClickListener { launchAction { voice.interruptSpeech() } }
        binding.initializeDialogueButton.setOnClickListener { launchAction { initializeDialogue() } }
        binding.askDialogueButton.setOnClickListener { launchAction { askDialogue() } }
        binding.beginMeasurementButton.setOnClickListener { launchAction { beginMeasurement() } }
        binding.confirmMeasurementButton.setOnClickListener {
            launchAction { renderMeasurementTurn(measurementSession.confirmPrimary()) }
        }
        binding.rejectMeasurementButton.setOnClickListener {
            launchAction { renderMeasurementTurn(measurementSession.reject()) }
        }
    }

    private suspend fun initializeDialogue() {
        binding.initializeDialogueButton.isEnabled = false
        binding.statusText.text = "Loading verified local dialogue model"
        val provisioner = DialogueModelProvisioner.fromAdmittedBundle(java.io.File(filesDir, "models/dialogue"))
        val model = withContext(Dispatchers.IO) {
            provisioner.requireInstalled()
        }
        dialogue.initialize(model)
        binding.progressBar.visibility = android.view.View.GONE
        binding.askDialogueButton.isEnabled = true
        binding.statusText.text = "Local dialogue ready · ${model.id}"
    }

    private suspend fun askDialogue() {
        binding.askDialogueButton.isEnabled = false
        binding.statusText.text = "Thinking locally"
        try {
            val request = DialogueRequest(
                utterance = binding.speechInput.text?.toString().orEmpty(),
                observations = listOfNotNull(lastMeasurement),
                capabilities = listOf(REQUEST_MEASUREMENT),
            )
            when (val outcome = dialogue.respond(request)) {
                is DialogueOutcome.Response -> speakDialogue(outcome.text)
                is DialogueOutcome.Clarification -> speakDialogue(outcome.question)
                is DialogueOutcome.ToolProposal -> executeTool(outcome)
            }
        } finally {
            binding.askDialogueButton.isEnabled = true
        }
    }

    private suspend fun executeTool(proposal: DialogueOutcome.ToolProposal) {
        check(proposal.tool == REQUEST_MEASUREMENT.name && proposal.version == REQUEST_MEASUREMENT.version) {
            "The proposed tool is not authorized by this host"
        }
        beginMeasurement()
        binding.dialogueText.setText(R.string.measurement_request_prompt)
        voice.speak(getString(R.string.measurement_request_prompt), SpeechQueuePolicy.REPLACE)
    }

    private suspend fun beginMeasurement() {
        measurementSession.begin()
        binding.beginMeasurementButton.isEnabled = false
        binding.measurementText.setText(R.string.measurement_armed)
    }

    private suspend fun speakDialogue(text: String) {
        binding.dialogueText.text = text
        binding.statusText.text = "Local dialogue complete"
        voice.speak(text, SpeechQueuePolicy.REPLACE)
    }

    private fun observeRuntime() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { voice.state.collect(::renderState) }
                launch { voice.events.collect(::renderEvent) }
            }
        }
    }

    private fun renderState(state: VoiceRuntimeState) {
        binding.statusText.text = when (state) {
            VoiceRuntimeState.Uninitialized -> "Not initialized"
            is VoiceRuntimeState.Provisioning -> "Provisioning ${state.artifact}"
            is VoiceRuntimeState.Ready -> "Ready · ${state.capabilities.joinToString()}"
            VoiceRuntimeState.Listening -> "Listening locally"
            is VoiceRuntimeState.Speaking -> "Speaking · tap Interrupt or speak to barge in"
            is VoiceRuntimeState.Failed -> "${state.error.code}: ${state.error.message}"
            VoiceRuntimeState.Closed -> "Closed"
        }
        val ready = state is VoiceRuntimeState.Ready || state is VoiceRuntimeState.Listening
        binding.initializeButton.isEnabled = state == VoiceRuntimeState.Uninitialized || state is VoiceRuntimeState.Failed
        binding.startButton.isEnabled = state is VoiceRuntimeState.Ready
        binding.stopButton.isEnabled = state == VoiceRuntimeState.Listening
        binding.speakButton.isEnabled = ready
        binding.interruptButton.isEnabled = state is VoiceRuntimeState.Speaking
        binding.progressBar.visibility = if (state is VoiceRuntimeState.Provisioning) android.view.View.VISIBLE else android.view.View.GONE
        if (state is VoiceRuntimeState.Provisioning) {
            binding.progressBar.progress = (state.fraction * 100).toInt().coerceIn(0, 100)
        }
    }

    private fun renderEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Transcript -> {
                if (event.segment.isFinal) {
                    finalTranscript.appendLine(event.segment.text)
                    binding.transcriptText.text = finalTranscript.toString()
                    launchAction {
                        measurementSession.accept(event.segment)?.let { renderMeasurementTurn(it) }
                    }
                } else binding.transcriptText.text = finalTranscript.toString() + event.segment.text
            }
            is VoiceEvent.BargeInDetected -> binding.statusText.text = "Barge-in detected"
            is VoiceEvent.Error -> binding.statusText.text = "${event.failure.code}: ${event.failure.message}"
            else -> Unit
        }
    }

    private suspend fun renderMeasurementTurn(turn: MeasurementTurn) {
        binding.measurementText.text = when (turn) {
            is MeasurementTurn.ConfirmationRequired -> {
                binding.confirmMeasurementButton.isEnabled = true
                binding.rejectMeasurementButton.isEnabled = true
                "Awaiting confirmation: ${turn.draft.primary.display()}"
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
                voice.speak(confirmation, SpeechQueuePolicy.REPLACE)
                confirmation
            }
            is MeasurementTurn.CorrectionRequired -> {
                binding.confirmMeasurementButton.isEnabled = false
                binding.rejectMeasurementButton.isEnabled = false
                "Not recorded: ${turn.incomplete.recoverablePrompt}"
            }
            is MeasurementTurn.Rejected -> {
                resetMeasurementControls()
                "Not recorded: ${turn.reason}"
            }
        }
        if (turn is MeasurementTurn.ConfirmationRequired || turn is MeasurementTurn.CorrectionRequired) {
            binding.beginMeasurementButton.isEnabled = false
        }
    }

    private fun resetMeasurementControls() {
        binding.beginMeasurementButton.isEnabled = true
        binding.confirmMeasurementButton.isEnabled = false
        binding.rejectMeasurementButton.isEnabled = false
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

    private fun launchAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }
                .onFailure { binding.statusText.text = it.message ?: "Local operation failed" }
        }
    }

    companion object {
        private val REQUEST_MEASUREMENT = Capability(
            name = "request_measurement",
            version = 1,
            description = "Ask the engineer for one electrical reading and confirm it locally.",
            arguments = emptyList(),
            maxDeadlineMs = 15_000,
        )
    }
}
