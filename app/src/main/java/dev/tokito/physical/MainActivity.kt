package dev.tokito.physical

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import dev.tokito.physical.voice.SpeechQueuePolicy
import dev.tokito.physical.voice.TokitoVoiceRuntime
import dev.tokito.physical.voice.VoiceEvent
import dev.tokito.physical.voice.VoiceRuntimeConfig
import dev.tokito.physical.voice.VoiceRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Camera-first host for the private, fully local voice conversation loop. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: TokitoVoiceRuntime
    private lateinit var dialogue: TokitoDialogueRuntime
    private lateinit var ar: ArSessionController
    private lateinit var call: VoiceCallController
    private var boardReference: BoardReferenceContext? = null
    private var digitalTwinAttempted = false
    private var vision: LocalPoseVisionRuntime? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) ar.start()
        else renderArState(ArSessionController.State("Camera blocked", "Camera permission is required for AR guidance"))
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) lifecycleScope.launch { call.start() }
        else renderCallState(CallState.Unavailable("Microphone permission is required"))
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
            speak = { voice.speak(it, SpeechQueuePolicy.REPLACE) },
            onState = ::renderCallState,
        )

        bindActions()
        observeVoiceEvents()
        ensureCameraPermission()
        lifecycleScope.launch { prepareLocalVision() }
        lifecycleScope.launch { call.warmUp() }
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
        if (::call.isInitialized) call.close()
        if (::ar.isInitialized) ar.destroy()
        if (::dialogue.isInitialized) dialogue.close()
        if (::voice.isInitialized) voice.close()
        super.onDestroy()
    }

    private fun bindActions() {
        binding.callButton.setOnClickListener {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                lifecycleScope.launch { call.toggle() }
            }
        }
        binding.registerArButton.setOnClickListener { ar.beginRegistration() }
        binding.previewDigitalTwinButton.setOnClickListener { ar.previewDigitalTwin() }
        binding.resetRegistrationButton.setOnClickListener { ar.reset() }
    }

    private fun ensureCameraPermission() {
        if (hasPermission(Manifest.permission.CAMERA)) ar.start()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun observeVoiceEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                voice.events.collect { event ->
                    when (event) {
                        is VoiceEvent.Transcript -> call.accept(event.segment)
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
            voice.state.value !is VoiceRuntimeState.Speaking
        ) {
            voice.initialize()
        }
        if (dialogue.state.value !is DialogueRuntimeState.Ready) {
            val model = withContext(Dispatchers.IO) {
                DialogueModelProvisioner.fromAdmittedBundle(java.io.File(filesDir, "models/dialogue"))
                    .requireInstalled()
            }
            dialogue.initialize(model)
        }
    }

    private suspend fun prepareLocalVision() {
        val runtime = withContext(Dispatchers.IO) {
            runCatching { LocalPoseVisionRuntime.load(File(filesDir, "models/vision")) }
        }.getOrElse { failure ->
            android.util.Log.e("TokitoPoseVision", "Local vision bundle was rejected", failure)
            return
        }
        android.util.Log.i("TokitoPoseVision", "Local pose runtime admitted")
        vision = runtime
        ar.enableAutomaticRegistration(runtime)
    }

    private suspend fun respondLocally(utterance: String): String {
        val reference = boardReference
            ?: return "No verified board reference is loaded."
        val grounded = reference.index.groundedAnswer(utterance)
            ?: return "I couldn't match that to verified ${reference.identity.model} data. Please repeat the component name."
        grounded.boardTarget?.let(ar::highlightBoardTarget)
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

    private fun renderArState(state: ArSessionController.State) {
        binding.trackingChip.text = state.label
        binding.trackingChip.contentDescription = state.detail
        binding.trackingChip.setTextColor(
            ContextCompat.getColor(this, if (state.registered) R.color.tokito_primary else R.color.tokito_text),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val QUALITY_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{1,63}")
        const val MAX_QUESTION_ECHO_RATIO = 0.80
    }
}
