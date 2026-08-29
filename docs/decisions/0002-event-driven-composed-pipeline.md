# ADR 0002: Event-driven composed perception pipeline

- Status: Accepted
- Date: 2026-08-29

## Context

A continuous whole-frame VLM would couple preview rate to the most expensive
model, consume battery/thermal headroom, create unbounded data pressure, and
make task accuracy difficult to calibrate.

## Decision

Run only lightweight tracking, registration, and change detection continuously.
Heavy visual or language inference executes only for an explicit bounded event,
ROI, deadline, and task. Use specialized models and deterministic processing
where they are sufficient; an optional local VLM is a bounded fallback, not the
system architecture.

## Consequences

- The tool layer must schedule capture and inference explicitly.
- Each capability has its own accuracy and latency evidence.
- Backpressure and thermal behavior are controllable.
- The runtime may abstain when a specialized supported path is unavailable.
