# Device runtime facts

The declared target was captured from connected hardware on 2026-08-29. Its
observed brand/model is iQOO I2501 (product I2501i), with QTI SM8850, Android
16 / SDK 36, `arm64-v8a`, and 15,594,224 KiB total memory. The Android camera
service reports five service-visible devices, two normal devices, and two
API1-visible devices; audio policy reports six inputs and four outputs.

The sanitized profile and raw-probe digests are recorded in
[`docs/DEVICE_PROFILE_I2501.md`](../docs/DEVICE_PROFILE_I2501.md). Raw ADB
output and the device selector remain local.

The NNAPI shell probe is unavailable on this build. This does not prove that
NNAPI or a vendor accelerator is absent. Operator support, native backend
choice, thread policy, latency, memory, fallback, and thermal behavior remain
unmeasured until issue #9's conformance benchmark.

The runtime selection mechanism is deliberately fail-closed: it accepts only a
caller-ordered backend candidate whose recorded conformance, lifecycle, and
thermal evidence digests match the active device-profile and ABI and report no
silent fallback. This mechanism does not constitute measured evidence or select
a backend for the captured device.

**How to apply:** use the versioned profile capture procedure for each firmware
revision. Do not hardcode device, vendor/NPU, camera, ABI, or backend
assumptions into the runtime; unsupported paths must fail closed.

Python is approved for training, export, evaluation, and reference conformance,
not the shipping Android camera/audio hot path (ADR 0003).

The Android AR presentation boundary is `android/ar-runtime`; `app` is the
single user-facing host that composes AR, local dialogue, STT/TTS, and typed
measurements. `android/ar-demo` is manual-registration conformance tooling, not
a second product. Presentation consumes the same
versioned coordinate graph as camera/tracking and uses a latest-only scene
mailbox. Every overlay primitive is frame-, revision-, time-, expiry-, and
uncertainty-bound. Missing/stale transforms, lifecycle suspension, and a new
registration revision clear geometry rather than preserving the last visual.
The demo is conformance tooling and must never be cited as automatic vision.
Filament `ModelViewer` owns native teardown through its `TextureView` detach
listener. Hosts mark `DigitalTwinView` destroyed and stop scheduling frames,
but must not invoke `ModelViewer.destroy()` a second time during Activity
teardown; doing so double-frees the resource loader during configuration
recreation.

Baseline runtime inference is entirely local: text LLM, bounded VLM, compact
vision models, English STT, and English TTS. Current benchmark candidates are
Moonshine or sherpa-onnx for STT, KittenTTS or sherpa-onnx TTS models for
synthesis, Qwen3-0.6B for text planning, and SmolVLM 256M/500M for bounded
visual reasoning. None is selected until license, export, conformance, and
physical-device gates pass.

The first executable voice candidate is Moonshine Voice 0.1.5 using English
tiny-streaming STT with spelling fusion and `piper_en_US-amy-low` TTS. Android
capture, focus, AEC/noise suppression, typed transcript events, synthesis
replacement/queueing, and barge-in live in `voice-runtime`. This is not an
accepted release backend until the connected iQOO passes issue #8 and Gate 4.

The first executable text-dialogue candidate is the official Qwen3-0.6B Q8_0
GGUF through llama.cpp pinned at commit
`cc83d7b4824f73cfdda4dfbb47ee39804f71b328`. `dialogue-runtime` owns resumable
digest-verified provisioning, bounded serialized generation, exact typed JSON
outcomes, and deterministic capability validation. It is not an accepted
release backend until issue #38 and Gate 5 pass on the declared iQOO device.

Deterministic electrical measurement parsing lives in `measurement-runtime`.
`MeasurementVoiceSession` accepts only final STT segments, retains at most one
unconfirmed draft, speaks prompts through local TTS, and requires confirmation
before emitting `engineer_voice` evidence. Numeric, locale, unit, and
relationship normalization must never be delegated to the local LLM.
