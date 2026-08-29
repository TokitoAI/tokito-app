# Offline voice runtime

## Implemented boundary

The `voice-runtime` Android library owns local English streaming recognition,
local English synthesis, microphone capture, Android audio focus, transcript
events, synthesis queueing, cancellation, and barge-in. The standalone `app`
module is a hardware test surface; Companion is not involved.

`MeasurementVoiceSession` is the typed bridge from final STT segments to the
separate deterministic `measurement-runtime`. It uses local TTS for correction
and confirmation prompts. It does not use the local LLM to normalize numbers,
units, decimal separators, or relationships.

The current measured candidate is Moonshine Voice 0.1.5 with:

- the English tiny-streaming STT architecture plus spelling fusion;
- the lightweight `piper_en_US-amy-low` TTS voice;
- 16 kHz mono `VOICE_COMMUNICATION` capture;
- platform acoustic echo cancellation and noise suppression when available.

This pins an implementation candidate, not a release benchmark claim. The exact
iQOO device must still pass issue #8 and the voice gates before the capability
is accepted for release.

## Runtime flow

```mermaid
flowchart LR
    Mic[Android AudioRecord]
    AEC[Platform AEC and noise suppression]
    Mode{Voice mode}
    STT[Moonshine streaming STT]
    Events[Typed partial and final transcript events]
    Parser[Deterministic measurement parser]
    Confirm[Bounded confirmation state]
    TTS[Moonshine Piper TTS]
    Speaker[Android AudioTrack]
    VAD[Adaptive local barge-in detector]

    Mic --> AEC --> Mode
    Mode -->|listening| STT --> Events
    Events -->|final only| Parser --> Confirm
    Confirm -->|prompt| TTS
    Mode -->|speaking| VAD
    TTS --> Speaker
    VAD -->|sustained speech| TTS
```

The recognizer and synthesizer never run through a network API. Runtime loading
accepts only a pre-provisioned model bundle whose complete SHA-256 inventory
matches the active manifest. Model files remain in app-private storage and are
not committed to Git.

Release admission must establish a publisher-signed model manifest and immutable
release artifact before the runtime can initialize. A missing or invalid
inventory does not expose STT/TTS capability.

## State and failure behavior

- `initialize` loads verified local models.
- `startListening` obtains exclusive capture focus and begins one bounded audio
  stream.
- partial hypotheses and final segments are distinct typed events.
- `speak` supports queue, replacement, and busy rejection policies.
- synthesis pauses recognition; capture remains local and becomes the barge-in
  signal path only when echo cancellation is active.
- speech loss, touch interruption, or detected barge-in stops synthesis and
  restores recognition deterministically.
- permission, focus, model, integrity, capture, recognition, synthesis,
  cancellation, and state failures have stable error codes.

Raw audio and synthesized samples are not persisted by this implementation.
Unconfirmed measurement drafts are bounded to one in-memory value and are
cleared on confirmation, rejection, invalid input, or cancellation.

## Model provenance and licensing

- Moonshine Voice runtime and English streaming STT are sourced from
  [moonshine-ai/moonshine](https://github.com/moonshine-ai/moonshine). The
  upstream project identifies its code and English STT models as MIT licensed.
- `piper_en_US-amy-low` originates in the
  [Piper voice collection](https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/amy/low).
  Its model card traces the training source to Mycroft Mimic 3 voices; that
  source repository is CC BY-SA 4.0. Release packaging must retain the required
  attribution and license notices.

Release tooling provisions these artifacts before runtime initialization; they
are not stored in this Git repository. A release manifest must pin the exact
upstream revision, file digests, attribution text, and license for every
distributed model artifact.

## Development verification

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The test APK is generated under `app/build/outputs/apk/debug/`. The app fails
closed until a verified local bundle has been admitted. A real-device acceptance
run additionally records model inventory digest, device profile,
WER/entity accuracy, first-partial/final latency, TTS first-audio latency,
barge-in latency, memory, and sustained thermal behavior.
