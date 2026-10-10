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
import dev.tokito.physical.dialogue.BoardReferenceContext
import dev.tokito.physical.dialogue.LocalBenchDialoguePolicy
import dev.tokito.physical.dialogue.LocalTextEmbeddingRuntime
import dev.tokito.physical.dialogue.SqliteDesignVectorCache
import dev.tokito.physical.dialogue.BoardKnowledgePackage
import dev.tokito.physical.dialogue.LocalBoardRag
import dev.tokito.physical.dialogue.TokitoDialogueRuntime
import dev.tokito.physical.dialogue.DialogueModelProvisioner
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

/** Camera-first host for the private, fully local voice conversation loop. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: TokitoVoiceRuntime
    private lateinit var ar: ArSessionController
    private lateinit var call: VoiceCallController
    private lateinit var measurement: MeasurementVoiceSession
    private var boardReference: BoardReferenceContext? = null
    private var embedding: LocalTextEmbeddingRuntime? = null
    private var knowledgePackage: BoardKnowledgePackage? = null
    private var localGenerator: TokitoDialogueRuntime? = null
    private var localRag: LocalBoardRag? = null
    @Volatile private var referenceScope: LocalBoardRag.Binding? = null
    private val knowledgePreparation = Mutex()
    private val embeddingOwner = Any()
    private val heavyWork = Mutex()
    @Volatile private var foregroundEpoch = 0L
    @Volatile private var foregroundActive = false
    private var preparationJob: Job? = null
    private var visionPreparationJob: Job? = null
    @Volatile private var destroyed = false
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

        val voiceMargins = binding.voiceControls.layoutParams as android.widget.FrameLayout.LayoutParams
        val voiceBaseStart = voiceMargins.marginStart
        val voiceBaseBottom = voiceMargins.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.voiceControls) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                marginStart = voiceBaseStart + maxOf(insets.left, insets.right)
                bottomMargin = voiceBaseBottom + insets.bottom
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
        foregroundActive = true
        foregroundEpoch++
        if (::ar.isInitialized && hasPermission(Manifest.permission.CAMERA)) ar.start()
        if (::call.isInitialized) preparationJob = lifecycleScope.launch { call.warmUp() }
        if (vision == null) visionPreparationJob = lifecycleScope.launch { prepareLocalVision() }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        renderCallState(call.currentState())
    }

    override fun onStop() {
        foregroundActive = false
        foregroundEpoch++
        preparationJob?.cancel()
        visionPreparationJob?.cancel()
        preparationJob = null
        visionPreparationJob = null
        val retiredEmbedding = synchronized(embeddingOwner) { embedding.also { embedding = null } }
        retireKnowledge()
        boardReference = null
        retiredEmbedding?.let { retired -> CoroutineScope(Dispatchers.IO).launch { retired.close() } }
        if (::call.isInitialized) {
            call.invalidatePreparation()
            val retired = call.revokeActiveTurn()
            lifecycleScope.launch { call.cleanupRevokedTurn(retired) }
        }
        if (::ar.isInitialized) ar.stop()
        super.onStop()
    }

    override fun onDestroy() {
        retireKnowledge()
        val retiredEmbedding = synchronized(embeddingOwner) {
            destroyed = true
            embedding.also { embedding = null }
        }
        retiredEmbedding?.let { retired -> CoroutineScope(Dispatchers.IO).launch { retired.close() } }
        debugUtteranceReceiver?.let(::unregisterReceiver)
        debugUtteranceReceiver = null
        if (::call.isInitialized) call.close()
        if (::ar.isInitialized) ar.destroy()
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
        val epoch = foregroundEpoch
        val caller = coroutineContext
        fun checkpoint() {
            caller.ensureActive()
            requireForeground(epoch)
        }
        val prepared = withContext(Dispatchers.IO) { knowledgePreparation.withLock { heavyWork.withLock knowledge@{
            checkpoint()
            val session = requireNotNull(sessionStore.session(sessionId)) { "Physical session is no longer available" }
            val reference = BoardReferenceContext.loadIfPresent(filesDir)?.takeIf { reference ->
                session.context.admitsBoardReference(reference.identity.contextRevision).also { admitted ->
                    if (!admitted) android.util.Log.w("TokitoDialogue", "Board reference does not match the bound session revision")
                }
            }
            // Explicit CPU encoder is independent of the unchanged Qwen NPU profile.
            val encoder = synchronized(embeddingOwner) { embedding } ?: run {
                // Reuse a previously admitted native handle; streaming bundle rehash
                // is needed only when opening a new handle, not for every call.
                val directory = ModelBundleRepository(this@MainActivity).activeDirectory(ModelCapability.EMBEDDING)
                    ?: return@knowledge reference
                requireHeavyWork("Knowledge search", 256L * 1024L * 1024L)
                LocalTextEmbeddingRuntime.open(directory)
            }.also { created ->
                val admitted = synchronized(embeddingOwner) {
                    if (destroyed || !foregroundActive || epoch != foregroundEpoch || !caller[Job]!!.isActive) false
                    else { embedding = created; true }
                }
                if (!admitted) { created.close(); throw CancellationException("Bench preparation retired") }
            }
            var preparing = true
            val index = if (reference != null) SqliteDesignVectorCache(File(filesDir, "knowledge/design-vectors.sqlite")).use { cache ->
                reference.index.withEmbeddings(encoder, cache) {
                    if (preparing) caller.ensureActive()
                    requireForeground(epoch)
                    requireHeavyWork("Knowledge search", 256L * 1024L * 1024L)
                }
            } else null
            preparing = false
            checkpoint()
            val directory = requireNotNull(ModelBundleRepository(this@MainActivity).activeDirectory(ModelCapability.EMBEDDING))
            val dataset = BoardKnowledgePackage.openIfPresent(directory)
            try { synchronized(embeddingOwner) { checkpoint(); knowledgePackage?.close(); knowledgePackage = dataset } }
            catch (failure: Throwable) { dataset?.close(); throw failure }
            reference?.copy(index = requireNotNull(index))
        } } }
        caller.ensureActive()
        requireForeground(epoch)
        boardReference = prepared
        activateProjectTwinIfPresent()
        if (voice.state.value !is VoiceRuntimeState.Ready &&
            voice.state.value != VoiceRuntimeState.Listening &&
            voice.state.value !is VoiceRuntimeState.Speaking &&
            voice.state.value !is VoiceRuntimeState.Synthesizing
        ) {
            withContext(Dispatchers.IO) { heavyWork.withLock {
                checkpoint()
                val admitted = requireNotNull(ModelBundleRepository(this@MainActivity).activeDirectory(ModelCapability.VOICE)) {
                    "Signed local voice bundle is unavailable"
                }
                // Sherpa's existing internal loader consumes this fixed slot. Its
                // inner inventory alone is NOT signed/license policy admission.
                check(admitted.canonicalFile == File(filesDir, "models/voice/active").canonicalFile) {
                    "Voice admission does not match its native runtime slot"
                }
                checkpoint()
                voice.initialize()
                checkpoint()
            } }
        }
        caller.ensureActive()
        requireForeground(epoch)
        if (knowledgePackage != null && localGenerator == null) {
            val generator = TokitoDialogueRuntime(this, this)
            localGenerator = generator
            try {
                val model = withContext(Dispatchers.IO) { heavyWork.withLock {
                    checkpoint()
                    requireHeavyWork("Local reasoning", 2L * 1024 * 1024 * 1024)
                    val directory = requireNotNull(ModelBundleRepository(this@MainActivity).activeDirectory(ModelCapability.DIALOGUE)) {
                        "Install the local conversation model to answer reference questions"
                    }
                    DialogueModelProvisioner.fromAdmittedBundle(directory).requireInstalled()
                } }
                generator.initialize(model)
                checkpoint()
                localRag = LocalBoardRag(requireNotNull(knowledgePackage).store, requireNotNull(embedding), generator,
                    { referenceScope }, android.os.SystemClock::elapsedRealtime)
            } catch (failure: Throwable) {
                generator.close()
                if (localGenerator === generator) localGenerator = null
                throw failure
            }
        }
    }

    private suspend fun prepareLocalVision() {
        val epoch = foregroundEpoch
        val caller = coroutineContext
        var created: LocalPoseVisionRuntime? = null
        val runtime = try {
            withContext(Dispatchers.IO) { heavyWork.withLock {
                caller.ensureActive()
                requireForeground(epoch)
                val modelRoot = requireNotNull(ModelBundleRepository(this@MainActivity).activeDirectory(ModelCapability.VISION)) {
                    "Signed local vision bundle is unavailable"
                }
                caller.ensureActive()
                requireForeground(epoch)
                val installedBytes = modelRoot.walkTopDown().filter(File::isFile).fold(0L) { total, file ->
                    Math.addExact(total, file.length())
                }
                requireHeavyWork("Local vision", Math.multiplyExact(installedBytes, 2))
                LocalPoseVisionRuntime.load(modelRoot).also { created = it }
            } }
        } catch (failure: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created?.close() }
            throw failure
        } catch (failure: Throwable) {
            android.util.Log.e("TokitoPoseVision", "Local vision bundle was rejected", failure)
            return
        }
        if (!caller[Job]!!.isActive || !foregroundActive || epoch != foregroundEpoch || destroyed) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runtime.close() }
            throw CancellationException("Vision preparation retired")
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

    /** In-process bench entry point shared by voice and installed-device conformance. */
    suspend fun respondLocally(utterance: String): String {
        val epoch = foregroundEpoch
        val caller = coroutineContext
        val reference = boardReference
        val dataset = knowledgePackage
        if (dataset?.mentionsUnsupportedVariant(utterance) == true) {
            referenceScope = null
            return "That board variant is not in the current reference library. I won't substitute a different model's specifications."
        }
        if (dataset != null && dataset.matchingScopes(utterance).size > 1) {
            return "Which board reference should I use? Please name one board at a time."
        }
        val namedScope = dataset?.resolve(utterance)
        if (namedScope != null) referenceScope = LocalBoardRag.Binding(namedScope.boardId,
            dataset.store.corpusRevision, "reference:$epoch:${namedScope.boardId}", Long.MAX_VALUE)
        if (referenceScope != null) {
            val rag = requireNotNull(localRag) { "Local reference reasoning is not ready. Install the knowledge and conversation bundles." }
            val result = heavyWork.withLock { rag.answer(utterance) }
            caller.ensureActive()
            requireForeground(epoch)
            return result.text
        }
        val grounded = withContext(Dispatchers.IO) { heavyWork.withLock {
            caller.ensureActive()
            requireForeground(epoch)
            LocalBenchDialoguePolicy().answer(utterance, reference?.index).also { caller.ensureActive(); requireForeground(epoch) }
        } }
        caller.ensureActive()
        requireForeground(epoch)
        grounded.boardTarget?.let { target ->
            val accepted = ar.highlightBoardTarget(target)
            android.util.Log.i(
                "TokitoDialogue",
                "grounded board target ${target.label} highlight accepted=$accepted",
            )
        }
        // Identity-sensitive answers are reviewed source text, not a model
        // rephrase that merely happens to retain some identifiers and numbers.
        return grounded.text
    }

    val localReasoningState: dev.tokito.physical.dialogue.DialogueRuntimeState?
        get() = localGenerator?.state?.value?.takeIf {
            localRag != null || it is dev.tokito.physical.dialogue.DialogueRuntimeState.Failed
        }

    private fun requireForeground(epoch: Long) {
        if (destroyed || !foregroundActive || epoch != foregroundEpoch) throw CancellationException("Bench foreground ownership retired")
    }

    private fun retireKnowledge() {
        referenceScope = null
        localRag = null
        localGenerator?.close()
        localGenerator = null
        val retired = synchronized(embeddingOwner) { knowledgePackage.also { knowledgePackage = null } }
        retired?.let { CoroutineScope(Dispatchers.IO).launch { heavyWork.withLock { it.close() } } }
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
        const val MEASUREMENT_SUCCESS_VISIBILITY_MS = 1_200L
    }
}
