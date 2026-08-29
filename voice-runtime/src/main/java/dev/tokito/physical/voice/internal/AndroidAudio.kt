package dev.tokito.physical.voice.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidAudioInput(
    context: Context,
    override val sampleRate: Int = 16_000,
) : AudioInput {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var echoCancellationActive = false

    override val supportsEchoCancellation: Boolean
        get() = echoCancellationActive

    override fun start(consumer: (FloatArray) -> Unit, onFailure: (Throwable) -> Unit) {
        check(
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "RECORD_AUDIO permission is required" }
        if (!running.compareAndSet(false, true)) return

        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "device does not support 16 kHz mono audio capture" }
        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum * 2, FRAME_SAMPLES * 4))
            .build()
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
        record = audioRecord
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
            echoCancellationActive = echoCanceler?.enabled == true
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true }
        }
        worker = Thread({ captureLoop(audioRecord, consumer, onFailure) }, "tokito-voice-capture").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { record?.stop() }
        worker?.interrupt()
        runCatching { worker?.join(1_000) }
        worker = null
        releaseAudioRecord()
    }

    override fun close() = stop()

    private fun captureLoop(
        audioRecord: AudioRecord,
        consumer: (FloatArray) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val shorts = ShortArray(FRAME_SAMPLES)
        try {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "microphone did not enter recording state"
            }
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = audioRecord.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                if (read < 0) throw IllegalStateException("AudioRecord.read failed: $read")
                if (read == 0) continue
                consumer(FloatArray(read) { index -> shorts[index] / 32768f })
            }
        } catch (error: Throwable) {
            if (running.get()) onFailure(error)
        } finally {
            runCatching { audioRecord.stop() }
            running.set(false)
            releaseAudioRecord()
        }
    }

    @Synchronized
    private fun releaseAudioRecord() {
        echoCanceler?.release()
        noiseSuppressor?.release()
        echoCanceler = null
        noiseSuppressor = null
        echoCancellationActive = false
        record?.release()
        record = null
    }

    companion object {
        private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
    }
}

internal class AndroidAudioFocusGate(context: Context) : AudioFocusGate {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var activeRequest: AudioFocusRequest? = null

    @Synchronized
    override fun acquireCapture(): Boolean = request(
        gain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        onLoss = {},
    )

    @Synchronized
    override fun acquireSpeech(onLoss: () -> Unit): Boolean = request(
        gain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        onLoss = onLoss,
    )

    @Synchronized
    override fun abandon() {
        activeRequest?.let(manager::abandonAudioFocusRequest)
        activeRequest = null
    }

    private fun request(gain: Int, onLoss: () -> Unit): Boolean {
        abandon()
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                if (
                    change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    onLoss()
                }
            }
            .build()
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        activeRequest = request.takeIf { granted }
        return granted
    }
}
