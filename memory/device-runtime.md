# Device runtime facts

The exact target iQOO model, SoC, Android build, accelerator/operator support,
and camera profile have not yet been recorded from connected hardware.

**Why:** A brand name is insufficient for backend selection; iQOO devices span
different Qualcomm/MediaTek SoCs and Android builds.

**How to apply:** the first implementation task captures the physical device
profile and benchmarks candidate native backends. Do not hardcode vendor/NPU
assumptions before that evidence exists.

Python is approved for training, export, evaluation, and reference conformance,
not the shipping Android camera/audio hot path (ADR 0003).

Baseline runtime inference is entirely local: text LLM, bounded VLM, compact
vision models, English STT, and English TTS. Current benchmark candidates are
Moonshine or sherpa-onnx for STT, KittenTTS or sherpa-onnx TTS models for
synthesis, Qwen3-0.6B for text planning, and SmolVLM 256M/500M for bounded
visual reasoning. None is selected until license, export, conformance, and
physical-device gates pass.
