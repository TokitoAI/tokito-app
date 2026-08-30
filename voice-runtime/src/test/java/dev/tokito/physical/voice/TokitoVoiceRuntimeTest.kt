package dev.tokito.physical.voice

import dev.tokito.physical.voice.internal.AudioFocusGate
import dev.tokito.physical.voice.internal.AudioInput
import dev.tokito.physical.voice.internal.PreparedBackend
import dev.tokito.physical.voice.internal.SpeechSynthesizer
import dev.tokito.physical.voice.internal.StreamingRecognizer
import dev.tokito.physical.voice.internal.VoiceBackendFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TokitoVoiceRuntimeTest {
    @Test
    fun listeningEmitsPartialAndFinalTranscripts() = runBlocking {
        val fixture = Fixture()
        fixture.runtime.initialize()
        fixture.runtime.startListening()

        val partialEvent = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.runtime.events.filterIsInstance<VoiceEvent.Transcript>().first()
        }
        fixture.recognizer.emit("D three", false)
        val partial = partialEvent.await()
        assertEquals("D three", partial.segment.text)
        assertFalse(partial.segment.isFinal)

        val finalEvent = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.runtime.events.filterIsInstance<VoiceEvent.Transcript>().first {
                it.segment.isFinal
            }
        }
        fixture.recognizer.emit("D3 cathode", true)
        val final = finalEvent.await()
        assertEquals("D3 cathode", final.segment.text)
        assertTrue(fixture.recognizer.started)
        fixture.runtime.close()
    }

    @Test
    fun speakingPausesRecognitionAndResumesIt() = runBlocking {
        val fixture = Fixture()
        fixture.runtime.initialize()
        fixture.runtime.startListening()
        fixture.runtime.speak("Place the diode with the stripe facing USB.")

        assertEquals(2, fixture.recognizer.startCount)
        assertEquals(1, fixture.recognizer.stopCount)
        assertEquals(VoiceRuntimeState.Listening, fixture.runtime.state.value)
        fixture.runtime.close()
    }

    @Test
    fun sustainedInputInterruptsSpeech() = runBlocking {
        val fixture = Fixture(blockSpeech = true)
        fixture.runtime.initialize()
        fixture.runtime.startListening()
        val speech = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.runtime.speak("Long guidance")
        }
        awaitLatch(fixture.synthesizer.entered)

        repeat(2) { fixture.audio.emit(FloatArray(320) { 0.002f }) }
        repeat(4) {
            fixture.audio.emit(FloatArray(320) { if (it % 2 == 0) 0.15f else -0.15f })
        }
        awaitLatch(fixture.synthesizer.stopped)
        speech.await()
        assertEquals(VoiceRuntimeState.Listening, fixture.runtime.state.value)
        fixture.runtime.close()
    }

    private suspend fun awaitLatch(latch: CountDownLatch) = withTimeout(5_000) {
        while (latch.count != 0L) delay(10)
    }

    @Test
    fun recognitionFailureStopsCaptureAndCanRecoverWithoutReloadingModels() = runBlocking {
        val fixture = Fixture()
        fixture.runtime.initialize()
        fixture.runtime.startListening()

        fixture.recognizer.fail(IllegalStateException("decoder failed"))
        val failed = withTimeout(2_000) {
            fixture.runtime.state.filterIsInstance<VoiceRuntimeState.Failed>().first()
        }
        assertEquals(VoiceErrorCode.RECOGNITION_FAILURE, failed.error.code)
        assertEquals(1, fixture.audio.stopCount)
        assertFalse(fixture.recognizer.started)

        fixture.runtime.initialize()
        assertTrue(fixture.runtime.state.value is VoiceRuntimeState.Ready)
        fixture.runtime.startListening()
        assertTrue(fixture.recognizer.started)
        fixture.runtime.close()
    }

    private class Fixture(blockSpeech: Boolean = false) {
        val recognizer = FakeRecognizer()
        val synthesizer = FakeSynthesizer(blockSpeech)
        val audio = FakeAudioInput()
        val runtime = TokitoVoiceRuntime(
            config = VoiceRuntimeConfig(
                bargeIn = BargeInConfig(
                    calibrationMillis = 40,
                    minimumRms = 0.02f,
                    noiseMultiplier = 2f,
                    requiredActiveFrames = 2,
                    rollingWindowFrames = 3,
                ),
            ),
            backendFactory = object : VoiceBackendFactory {
                override suspend fun prepare() = PreparedBackend(recognizer, synthesizer, "fixture")
            },
            audioInput = audio,
            focusGate = FakeFocus(),
        )
    }

    private class FakeRecognizer : StreamingRecognizer {
        var listenerRef: StreamingRecognizer.Listener? = null
        var started = false
        var startCount = 0
        var stopCount = 0

        override fun start() {
            started = true
            startCount++
        }

        override fun accept(samples: FloatArray, sampleRate: Int) = Unit
        override fun stop() {
            started = false
            stopCount++
        }

        override fun setListener(listener: StreamingRecognizer.Listener?) {
            this.listenerRef = listener
        }

        fun emit(text: String, final: Boolean) {
            listenerRef?.onTranscript(TranscriptSegment(text, final, 1))
        }

        fun fail(error: Throwable) = listenerRef?.onFailure(error)

        override fun close() = Unit
    }

    private class FakeSynthesizer(private val block: Boolean) : SpeechSynthesizer {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        @Volatile private var speaking = false
        override val isSpeaking: Boolean get() = speaking

        override fun speak(text: String) {
            speaking = true
            entered.countDown()
            if (block) stopped.await(3, TimeUnit.SECONDS)
            speaking = false
        }

        override fun stop() {
            speaking = false
            stopped.countDown()
        }

        override fun close() = stop()
    }

    private class FakeAudioInput : AudioInput {
        override val sampleRate = 16_000
        override val supportsEchoCancellation = true
        private var consumer: ((FloatArray) -> Unit)? = null
        var stopCount = 0
        override fun start(consumer: (FloatArray) -> Unit, onFailure: (Throwable) -> Unit) {
            this.consumer = consumer
        }
        fun emit(samples: FloatArray) = consumer?.invoke(samples)
        override fun stop() {
            stopCount++
            consumer = null
        }
        override fun close() = Unit
    }

    private class FakeFocus : AudioFocusGate {
        override fun acquireCapture() = true
        override fun acquireSpeech(onLoss: () -> Unit) = true
        override fun abandon() = Unit
    }
}
