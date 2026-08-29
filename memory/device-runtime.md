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

**How to apply:** use the versioned profile capture procedure for each firmware
revision. Do not hardcode device, vendor/NPU, camera, ABI, or backend
assumptions into the runtime; unsupported paths must fail closed.

Python is approved for training, export, evaluation, and reference conformance,
not the shipping Android camera/audio hot path (ADR 0003).

Baseline runtime inference is entirely local: text LLM, bounded VLM, compact
vision models, English STT, and English TTS. Current benchmark candidates are
Moonshine or sherpa-onnx for STT, KittenTTS or sherpa-onnx TTS models for
synthesis, Qwen3-0.6B for text planning, and SmolVLM 256M/500M for bounded
visual reasoning. None is selected until license, export, conformance, and
physical-device gates pass.
