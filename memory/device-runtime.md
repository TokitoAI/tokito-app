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
