# ADR 0003: Python for development tooling, native Android for the hot path

- Status: Accepted
- Date: 2026-08-29

## Context

Python is productive for model training, export, and evaluation. Embedding a
general Python runtime inside an Android camera/audio lifecycle adds startup,
packaging, ABI, memory, security, and hardware-acceleration complexity without
evidence that it improves the shipping runtime.

## Decision

Use Python for corpus tooling, training, export, calibration, reference
conformance, and benchmarks. Ship an Android-native runtime with Kotlin
orchestration and benchmark-selected native inference backends. Expose a stable
Android library boundary to future consumers.

## Consequences

- Reference vectors must prove exported/native output conformance.
- Training code cannot leak framework-specific tensors into the public API.
- A future embedded-Python proposal requires a new ADR with target-device
  latency, memory, thermal, packaging, and security evidence.
