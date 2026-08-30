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
The shipping app must not expose a synthetic-pose preview or substitute
`CalibrationBoardAsset` for a missing PCB. It may load that object only inside
an explicit measured calibration session using the matching printable 120 x
80 mm physical target. Product pose estimation takes metric board landmarks
only from the active validated project digital-twin package.
The shipping app may offer a clearly labelled unregistered inspection view of
that admitted project package, provided it creates no physical pose and clears
the view before registered guidance resumes.
Filament `ModelViewer` owns native teardown through its `TextureView` detach
listener. Hosts mark `DigitalTwinView` destroyed and stop scheduling frames,
but must not invoke `ModelViewer.destroy()` a second time during Activity
teardown; doing so double-frees the resource loader during configuration
recreation.
The admitted FRDM GLB's 681-node scene measured an 8.06 MiB peak low-level
command allocation on the declared iQOO. The renderer therefore provisions a
12 MiB minimum command buffer, a 36 MiB three-buffer arena, and a 32 MiB handle
arena. The manual inspection camera fits the signed package bounds and does not
create or retain a physical registration.

Baseline runtime inference is entirely local: text LLM, bounded VLM, compact
vision models, English STT, and English TTS. The executable speech candidate is
sherpa-onnx; Qwen3 1.7B QAIRT is the active text-dialogue profile and SmolVLM
256M/500M remain bounded visual-reasoning candidates. No candidate is admitted
as a release backend until license, export, conformance, and physical-device
gates pass.

The executable voice candidate is sherpa-onnx 1.13.6 using the English Kroko
streaming Zipformer2 transducer and LJSpeech medium INT8 VITS voice. Moonshine
Voice 0.1.5 was rejected after an uncatchable native `SIGILL` during physical
iQOO model load. Android capture, focus, AEC/noise suppression, typed transcript
events, synthesis replacement/queueing, and barge-in live in `voice-runtime`.
The AAR and models are hash-pinned and provisioned to app-private storage; no
network permission or cloud fallback exists. This is not an accepted release
backend until the connected iQOO passes issue #8 and Gate 4.

The executable text-dialogue candidate is Qualcomm's Qwen3 1.7B QAIRT w4a16
bundle through GenieX Android 0.3.1. Its full eleven-file inventory is verified
before loading and it runs on the declared iQOO's Snapdragon NPU. Device
instrumentation verifies bounded grounded answers. The previously recorded
6927-8114 prompt tokens/s and 125-139 decode tokens/s belong to the retired
0.6B profile, not this one. QAIRT grammar generation failed, so this profile is
conversational only and declares no tool capabilities. It is not an accepted
general release backend until issue #38 and Gate 5 pass.

The active FRDM detector is a manifest-bound YOLO11n pose export running through
LiteRT 2.2.0 on CPU. It supplies a four-corner board quad to the presentation
layer; the GLB follows that quad and clears after tracking expiry. Its manifest
explicitly marks the smoke evidence as not release-eligible, so it is an
experimental FRDM product path rather than proof of the general vision gate.

Q8_0 and Q4_K_M llama.cpp profiles were rejected for target-device packing
memory, startup, and ANR behavior. Q4_0 loaded but grammar generation failed and
unconstrained output could abort JNI through invalid UTF-8. These local
artifacts are quarantined evidence, not runtime fallbacks.

Local design retrieval is deterministic and bounded: exact reference keys are
authoritative, lexical evidence search is secondary, and each result carries
the verified context revision and source IDs. No FRDM facts are built into
runtime control flow or the APK.

Deterministic electrical measurement parsing lives in `measurement-runtime`.
`MeasurementVoiceSession` accepts only final STT segments, retains at most one
unconfirmed draft, speaks prompts through local TTS, and requires confirmation
before emitting `engineer_voice` evidence. Numeric, locale, unit, and
relationship normalization must never be delegated to the local LLM.
