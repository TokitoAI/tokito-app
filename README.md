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

Documentation and architecture baseline only. There is intentionally no model,
runtime, Android app/library, Python package, or placeholder implementation yet.
Implementation begins from tracked issues on the
[Tokito project board](https://github.com/orgs/TokitoAI/projects/1).

## Documentation

- [Technical Bible](docs/TECHNICAL_BIBLE.md) - normative engineering contract
- [Architecture](docs/ARCHITECTURE.md) - components, deployment, and data flow
- [Product scope](docs/PRODUCT_SCOPE.md) - user outcomes and non-goals
- [Runtime contract](docs/RUNTIME_CONTRACT.md) - tools and observation envelope
- [Model strategy](docs/MODEL_STRATEGY.md) - composed pipeline and deployment policy
- [Evaluation](docs/EVALUATION.md) - datasets, metrics, and release gates
- [Security and privacy](docs/SECURITY_AND_PRIVACY.md) - camera/audio trust boundary
- [Delivery gates](docs/DELIVERY_GATES.md) - evidence required for each milestone
- [Evidence manifests](docs/EVIDENCE_MANIFESTS.md) - machine-readable model, corpus, evaluation, security, and release records
- [CI/CD](docs/CI_CD.md) - pull request flow, current CI checks, and branch-policy status
- [Architecture decisions](docs/decisions/) - durable decisions and rationale

## Planned implementation shape

Python is reserved for dataset preparation, training, export, evaluation, and a
reference harness. The shipping phone hot path will be an Android-native
application plus reusable libraries with Kotlin orchestration and
benchmark-selected native inference backends. Model execution, dialogue,
camera perception, speech, and AR guidance remain local after model
installation. This avoids embedding a general Python interpreter in the camera
and audio lifecycle without measured justification.

## License

MIT - see [LICENSE](LICENSE).
