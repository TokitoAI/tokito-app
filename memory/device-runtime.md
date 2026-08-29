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
