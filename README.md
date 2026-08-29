# Tokito Physical Runtime

An offline, on-device AR assistant for electronics engineering. It detects and
tracks a board through the phone camera, reasons locally, speaks and listens
locally, overlays guidance, and produces structured bench observations. Tokito
integration is optional; the application must remain useful without Tokito,
Cloud, or Companion.

This repository is deliberately separate from
[`tokito-companion`](https://github.com/TokitoAI/tokito-companion). Companion is
the mobile review application. This project owns its own standalone Android AR
application and reusable runtime libraries. A future Companion integration may
consume the stable Android interface, but Companion is not the host or a
runtime dependency. No Companion changes are part of this phase.

## Product boundary

The product owns:

- lightweight continuous camera tracking and board registration;
- event-driven region-of-interest capture and specialized visual inference;
- an entirely local text LLM and bounded local vision-language inference;
- wake word, English speech-to-text, measurement parsing, and English
  text-to-speech, all on-device;
- a standalone camera-first AR interface with registered overlays and local
  conversational state;
- a digest-verified metric GLB digital twin rendered over a calibrated board
  pose, with a real calibration object for conformance before project assets
  are available;
- typed physical observations with confidence, provenance, and evidence;
- bench tools such as component location, inspection, LED-state reading,
  measurement requests, assembly verification, and change verification;
- model packaging, hardware capability detection, thermal scheduling, and
  reproducible on-device evaluation.

It does not require Tokito's design reasoning or any cloud service. It does not
edit schematics or turn Companion into a CAD application.

```mermaid
flowchart LR
    Bench[Engineer and physical board] --> Sensors[Camera and microphone]
    Sensors --> App[Standalone Android AR app]
    App --> LocalAI[Local LLM, VLM, vision, STT and TTS]
    LocalAI --> Tools[Typed physical tools]
    Tools --> Overlay[AR overlay and spoken guidance]
    Overlay --> Bench
    Tokito[Optional Tokito design context] -.-> App
```

## Current status

Implementation is active and tracked on the
[Tokito project board](https://github.com/orgs/TokitoAI/projects/1). The
repository now contains the versioned runtime contract, a fail-closed native
runtime foundation, Android lifecycle/capability APIs, reference perception
work, and a reusable Android AR presentation module with a standalone device
conformance app. Model selection and production claims remain gated on the
documented physical-device evidence; the conformance app's manual registration
does not pretend to be automatic vision.

## Documentation

- [Technical Bible](docs/TECHNICAL_BIBLE.md) - normative engineering contract
- [Architecture](docs/ARCHITECTURE.md) - components, deployment, and data flow
- [Product scope](docs/PRODUCT_SCOPE.md) - user outcomes and non-goals
- [Runtime contract](docs/RUNTIME_CONTRACT.md) - tools and observation envelope
- [Model strategy](docs/MODEL_STRATEGY.md) - composed pipeline and deployment policy
- [Evaluation](docs/EVALUATION.md) - datasets, metrics, and release gates
- [Corpus governance](docs/CORPUS_GOVERNANCE.md) - immutable manifests, leakage audits, and revisions
- [Security and privacy](docs/SECURITY_AND_PRIVACY.md) - camera/audio trust boundary
- [Delivery gates](docs/DELIVERY_GATES.md) - evidence required for each milestone
- [AR runtime](docs/AR_RUNTIME.md) - coordinate, scene, lifecycle, and conformance contract
- [Evidence manifests](docs/EVIDENCE_MANIFESTS.md) - machine-readable model, corpus, evaluation, security, and release records
- [CI/CD](docs/CI_CD.md) - pull request flow, current CI checks, and branch-policy status
- [Architecture decisions](docs/decisions/) - durable decisions and rationale

## Implementation shape

Python is reserved for dataset preparation, training, export, evaluation, and a
reference harness. The shipping phone hot path will be an Android-native
application plus reusable libraries with Kotlin orchestration and
benchmark-selected native inference backends. Model execution, dialogue,
camera perception, speech, and AR guidance remain local after model
installation. This avoids embedding a general Python interpreter in the camera
and audio lifecycle without measured justification.

## License

MIT - see [LICENSE](LICENSE).
