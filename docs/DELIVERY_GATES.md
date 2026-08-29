# Delivery gates

The project board owns work sequencing. These gates define the evidence needed
to finish each implementation stage; they are not a separate roadmap.

## Gate 0 - Architecture baseline

- Technical Bible, system boundary, contracts, ADRs, evaluation policy, and
  security/privacy policy reviewed and versioned.
- Source proposal preserved as informative context.
- Board contains roadmap, epics, and implementation issues.
- No product code or fake capability is required.

## Gate 1 - Device and runtime foundation

- Exact iQOO device profile recorded from hardware.
- Camera/audio/accelerator capability probe and support matrix.
- Android API lifecycle, cancellation, deadline, error, and capability contract.
- Backend/operator conformance spike with measured choice.
- Empty capability means unavailable, not stubbed success.
- Standalone application shell starts with networking disabled and reports
  locally installed model/capability state honestly.

## Gate 2 - Continuous camera and registration

- Camera lifecycle, automatic PCB/work-area discovery, and 30 fps bounded
  tracking pipeline.
- Coordinate frame/transform contract implemented and golden-tested.
- Registration loss/recovery and lifecycle revalidation.
- AR overlays remain aligned through supported motion, rotation, and lifecycle
  changes.
- Device latency, dropped-frame, memory, and 20-minute thermal evidence.

## Gate 3 - Event-driven vision

- ROI scheduler and capture quality gate.
- Baseline supported component/LED tasks with calibrated abstention.
- Frozen held-out board/session report meeting the Technical Bible gates.
- Per-backend export/conformance and rollback evidence.

## Gate 4 - Voice

- Wake/VAD/STT/measurement parser/TTS lifecycle and barge-in.
- Deterministic numeric/unit parsing with ambiguity confirmation.
- Clean and bench-noise evaluation slices meeting quality/latency gates.
- Raw audio remains ephemeral by default.
- English STT and TTS complete the scenario with networking blocked at the OS
  and test-network layers.

## Gate 5 - Physical tool runtime

- Capability handshake and baseline tool contracts.
- Idempotency, ordering, cancellation, deadline, and late-result tests.
- Structured observations with evidence/provenance.
- End-to-end offline scenarios and failure injection.
- Local text LLM tool planning and bounded local VLM candidates cannot bypass
  deterministic validation, confidence, or evidence policy.

## Gate 6 - Consumer integration readiness

- Standalone camera-first Android AR application passes the offline product
  scenario without Tokito, Cloud, or Companion.
- Stable reusable Android artifact and generated/verified consumer bindings.
- Contract conformance suite usable without Companion source changes.
- Standalone product and conformance harness prove lifecycle and performance.
- Cross-repository Companion integration is a separately approved issue/PR.

## Gate 7 - Release readiness

- Reproducible signed artifacts and model bundle.
- Full release definition in the Technical Bible satisfied.
- Support matrix, limitations, licenses, privacy disclosure, and rollback plan.
- Latest post-merge CI and physical-device evidence green.
