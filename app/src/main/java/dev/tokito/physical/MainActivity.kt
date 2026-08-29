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
import dev.tokito.physical.measurement.MeasurementTurn
import dev.tokito.physical.measurement.MeasurementValue
import dev.tokito.physical.voice.MeasurementVoiceSession
import dev.tokito.physical.voice.SpeechQueuePolicy
import dev.tokito.physical.voice.TokitoVoiceRuntime
import dev.tokito.physical.voice.VoiceEvent
import dev.tokito.physical.voice.VoiceRuntimeConfig
import dev.tokito.physical.voice.VoiceRuntimeState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: TokitoVoiceRuntime
    private lateinit var measurementSession: MeasurementVoiceSession
    private val finalTranscript = StringBuilder()

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchAction { voice.startActivation() }
        else binding.statusText.text = "Microphone permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        voice = TokitoVoiceRuntime(
            this,
            VoiceRuntimeConfig(allowModelDownload = true),
        )
        measurementSession = MeasurementVoiceSession(voice)
        bindActions()
        observeRuntime()
    }

    override fun onDestroy() {
        voice.close()
        super.onDestroy()
    }

    override fun onStop() {
        if (voice.state.value == VoiceRuntimeState.Listening || voice.state.value == VoiceRuntimeState.AwaitingWake) {
            lifecycleScope.launch { runCatching { voice.stopListening() } }
        }
        super.onStop()
    }

    private fun bindActions() {
        binding.initializeButton.setOnClickListener { launchAction { voice.initialize() } }
        binding.startButton.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                launchAction { voice.startActivation() }
            } else {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.stopButton.setOnClickListener { launchAction { voice.stopListening() } }
        binding.speakButton.setOnClickListener {
            launchAction {
                voice.speak(binding.speechInput.text?.toString().orEmpty(), SpeechQueuePolicy.REPLACE)
            }
        }
        binding.interruptButton.setOnClickListener { launchAction { voice.interruptSpeech() } }
        binding.beginMeasurementButton.setOnClickListener {
            launchAction {
                measurementSession.begin()
                binding.beginMeasurementButton.isEnabled = false
                binding.measurementText.setText(R.string.measurement_armed)
            }
        }
        binding.confirmMeasurementButton.setOnClickListener {
            launchAction { renderMeasurementTurn(measurementSession.confirmPrimary()) }
        }
        binding.rejectMeasurementButton.setOnClickListener {
            launchAction { renderMeasurementTurn(measurementSession.reject()) }
        }
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
            VoiceRuntimeState.AwaitingWake -> "Listening locally for Hey Tokito"
            VoiceRuntimeState.Listening -> "Listening locally"
            is VoiceRuntimeState.Speaking -> "Speaking · tap Interrupt or speak to barge in"
            is VoiceRuntimeState.Failed -> "${state.error.code}: ${state.error.message}"
            VoiceRuntimeState.Closed -> "Closed"
        }
        val ready = state is VoiceRuntimeState.Ready || state is VoiceRuntimeState.Listening
        binding.initializeButton.isEnabled = state == VoiceRuntimeState.Uninitialized || state is VoiceRuntimeState.Failed
        binding.startButton.isEnabled = state is VoiceRuntimeState.Ready
        binding.stopButton.isEnabled = state == VoiceRuntimeState.Listening || state == VoiceRuntimeState.AwaitingWake
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
                        measurementSession.accept(event.segment)?.let(::renderMeasurementTurn)
                    }
                } else {
                    binding.transcriptText.text = finalTranscript.toString() + event.segment.text
                }
            }
            is VoiceEvent.BargeInDetected -> binding.statusText.text = "Barge-in detected"
            is VoiceEvent.WakeWordDetected -> binding.statusText.text = "${event.phrase} · ${(event.confidence * 100).toInt()}%"
            is VoiceEvent.VoiceActivity -> Unit
            is VoiceEvent.Error -> binding.statusText.text = "${event.failure.code}: ${event.failure.message}"
            else -> Unit
        }
    }

    private fun renderMeasurementTurn(turn: MeasurementTurn) {
        binding.measurementText.text = when (turn) {
            is MeasurementTurn.ConfirmationRequired -> {
                binding.confirmMeasurementButton.isEnabled = true
                binding.rejectMeasurementButton.isEnabled = true
                "Awaiting confirmation: ${turn.draft.primary.display()}"
            }
            is MeasurementTurn.Confirmed -> {
                binding.beginMeasurementButton.isEnabled = true
                binding.confirmMeasurementButton.isEnabled = false
                binding.rejectMeasurementButton.isEnabled = false
                "Confirmed: ${turn.measurement.confirmedCandidate.display()}"
            }
            is MeasurementTurn.CorrectionRequired -> {
                binding.confirmMeasurementButton.isEnabled = false
                binding.rejectMeasurementButton.isEnabled = false
                "Not recorded: ${turn.incomplete.recoverablePrompt}"
            }
            is MeasurementTurn.Rejected -> {
                binding.beginMeasurementButton.isEnabled = true
                binding.confirmMeasurementButton.isEnabled = false
                binding.rejectMeasurementButton.isEnabled = false
                "Not recorded: ${turn.reason}"
            }
        }
        if (turn is MeasurementTurn.ConfirmationRequired || turn is MeasurementTurn.CorrectionRequired) {
            binding.beginMeasurementButton.isEnabled = false
        }
    }

    private fun dev.tokito.physical.measurement.MeasurementCandidate.display(): String {
        val renderedValue = when (val parsed = value) {
            is MeasurementValue.Scalar -> parsed.value.toPlainString()
            is MeasurementValue.Range -> "${parsed.low.toPlainString()}–${parsed.high.toPlainString()}"
        }
        val location = relationship?.let { relation ->
            when (relation.kind) {
                dev.tokito.physical.measurement.RelationshipKind.BETWEEN ->
                    " from ${relation.primary} to ${relation.reference}"
                dev.tokito.physical.measurement.RelationshipKind.ACROSS -> " across ${relation.primary}"
                dev.tokito.physical.measurement.RelationshipKind.AT -> " at ${relation.primary}"
            }
        }.orEmpty()
        return "$renderedValue ${unit.symbol}$location"
    }

    private fun launchAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }
                .onFailure { binding.statusText.text = it.message ?: "Voice operation failed" }
        }
    }
}
