# ADR 0001: Separate physical runtime from Companion

- Status: Accepted
- Date: 2026-08-29

## Context

Tokito Companion is a mobile-first realtime review surface with a deliberately
limited authorship boundary. The proposed camera, model, STT/TTS, evidence, and
bench-tool system has independent model artifacts, native backends, hardware
lifecycle, datasets, benchmarks, and release cadence.

## Decision

Create `TokitoAI/tokito-physical-runtime` as the owner of on-device physical
perception and speech. It will eventually expose a stable Android library that a
consumer such as Companion may use. No physical-runtime implementation is added
to Companion during the repository bootstrap.

## Consequences

- Model/runtime work cannot broaden Companion's CAD/review capabilities by
  accident.
- Runtime testing and releases can target hardware independently.
- Integration requires a versioned boundary and cross-repository change.
- Shared UI and Cloud concerns stay outside this repository.
