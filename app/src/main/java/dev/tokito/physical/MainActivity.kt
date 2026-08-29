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
import dev.tokito.physical.dialogue.DialogueModelProvisioner
import dev.tokito.physical.dialogue.DialogueOutcome
import dev.tokito.physical.dialogue.DialogueRequest
import dev.tokito.physical.dialogue.TokitoDialogueRuntime
import dev.tokito.physical.voice.SpeechQueuePolicy
import dev.tokito.physical.voice.TokitoVoiceRuntime
import dev.tokito.physical.voice.VoiceEvent
import dev.tokito.physical.voice.VoiceRuntimeConfig
import dev.tokito.physical.voice.VoiceRuntimeState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: TokitoVoiceRuntime
    private lateinit var dialogue: TokitoDialogueRuntime
    private val finalTranscript = StringBuilder()

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
        voice = TokitoVoiceRuntime(
            this,
            VoiceRuntimeConfig(allowModelDownload = true),
        )
        dialogue = TokitoDialogueRuntime(this)
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
        binding.initializeDialogueButton.setOnClickListener { launchAction { initializeDialogue() } }
        binding.askDialogueButton.setOnClickListener { launchAction { askDialogue() } }
    }

    private suspend fun initializeDialogue() {
        binding.initializeDialogueButton.isEnabled = false
        binding.statusText.text = "Provisioning the pinned local Qwen3 model"
        val provisioner = DialogueModelProvisioner(java.io.File(filesDir, "models/dialogue"))
        val model = withContext(Dispatchers.IO) {
            provisioner.provision { progress ->
                val percent = (progress.downloadedBytes * 100 / progress.totalBytes).toInt()
                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.progressBar.progress = percent
                }
            }
        }
        dialogue.initialize(model)
        binding.progressBar.visibility = android.view.View.GONE
        binding.askDialogueButton.isEnabled = true
        binding.initializeDialogueButton.isEnabled = false
        binding.statusText.text = "Local dialogue ready · ${model.id}"
    }

    private suspend fun askDialogue() {
        val utterance = binding.speechInput.text?.toString().orEmpty()
        binding.askDialogueButton.isEnabled = false
        binding.statusText.text = "Thinking locally"
        try {
            when (val outcome = dialogue.respond(DialogueRequest(utterance = utterance))) {
                is DialogueOutcome.Response -> {
                    binding.dialogueText.text = outcome.text
                    voice.speak(outcome.text, SpeechQueuePolicy.REPLACE)
                }
                is DialogueOutcome.Clarification -> {
                    binding.dialogueText.text = outcome.question
                    voice.speak(outcome.question, SpeechQueuePolicy.REPLACE)
                }
                is DialogueOutcome.ToolProposal -> {
                    binding.dialogueText.text = "Proposed ${outcome.tool}; no host capability is authorized in this validation app."
                }
            }
            binding.statusText.text = "Local dialogue complete"
        } finally {
            binding.askDialogueButton.isEnabled = true
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
                } else {
                    binding.transcriptText.text = finalTranscript.toString() + event.segment.text
                }
            }
            is VoiceEvent.BargeInDetected -> binding.statusText.text = "Barge-in detected"
            is VoiceEvent.Error -> binding.statusText.text = "${event.failure.code}: ${event.failure.message}"
            else -> Unit
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }
                .onFailure { binding.statusText.text = it.message ?: "Voice operation failed" }
        }
    }
}
