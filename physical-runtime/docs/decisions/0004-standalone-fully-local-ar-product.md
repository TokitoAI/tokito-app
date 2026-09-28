# ADR 0004: Standalone fully local AR product

- Status: Amended by root ADR 0001, one Tokito mobile application
- Date: 2026-08-29
- Amended: 2026-09-28

"Standalone" in this historical record now means local physical workflows are
independent of authentication and networking. The shipping surface is the
embedded physical workspace in the single Tokito application.

## Context

The bootstrap architecture described a reusable perception and speech library
whose primary reasoning client was Tokito. That boundary did not express the
intended product: an engineer should place a PCB under the iQOO phone and use a
complete camera-first assistant without Tokito, Companion, Cloud, or a network.

The phone therefore needs more than sensor inference. It needs its own AR
presentation, local conversational reasoning, visual reasoning, speech, tool
orchestration, evidence, and lifecycle management. This does not remove the
benefit of reusable Android libraries or optional Tokito design context.

## Decision

This repository owns a standalone offline-first Android AR application and its
reusable runtime libraries. Baseline operation includes:

- automatic PCB/work-area discovery, tracking, and board-plane registration;
- registered AR overlays and camera-first touch interaction;
- a quantized local text LLM for dialogue and constrained tool planning;
- specialized local visual models plus bounded local VLM inference;
- local wake word, VAD, English STT, deterministic measurement parsing, and
  English TTS;
- deterministic tool authorization, typed observations, calibrated
  abstention, and bounded evidence.

All baseline inference and orchestration run on the phone after explicit model
installation or provisioning. There is no remote fallback disguised as local
operation. Tokito design context is optional. Python remains an off-device
development tool under ADR 0003.

## Consequences

- The standalone application, not Companion, is the primary product surface.
- The Android AAR remains useful for tests and future integrations, but is no
  longer the only shipping artifact.
- Local LLM and VLM performance, memory, thermal behavior, cancellation, and
  schema reliability become release-gated evidence.
- Generic board discovery and inspection must work without design identity;
  exact references require optional design context and valid registration.
- Model candidates are selected through target-device comparison, not brand
  recognition or a preference for Whisper, YOLO, or any single runtime.
- ADR 0001 remains valid for repository and Companion separation; this ADR
  replaces its assumption that the repository is only a reusable layer.
