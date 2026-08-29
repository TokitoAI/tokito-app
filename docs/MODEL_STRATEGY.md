# Model strategy

## Principle: compose the smallest sufficient models

Continuous camera work and event-driven semantic inference have different
latency, power, and accuracy requirements. The runtime uses a composed pipeline
instead of forcing one VLM to handle tracking, component identity, LED state,
speech, and guidance.

## Planned capability families

| Layer | Candidate approach | Activation |
|---|---|---|
| Board/feature tracking | classical CV or compact learned features | continuous |
| Change detection | image statistics / compact temporal model | continuous |
| Component localization | compact detector or open-vocabulary candidate generator | event |
| Package/orientation | ROI classifier and geometry/context rules | event |
| Marking/OCR | specialized OCR/recognizer | event |
| LED state/blink | temporal photometric detector/classifier | bounded window |
| General visual fallback | quantized local VLM with structured decoding | explicit event only |
| Wake word/VAD | compact streaming audio models | continuous audio session |
| STT | streaming on-device ASR | after wake/explicit request |
| Measurement parsing | deterministic parser, optional SLM candidate stage | event |
| TTS | local streaming synthesizer | explicit guidance |

No named model is selected by this document. Selection requires licensing,
export, backend support, correctness, and target-device benchmark evidence.

## Training and data plane

Python tooling will own:

- corpus import with license/source manifests;
- annotation validation and board/session grouping;
- training and calibration;
- export and numerical conformance checks;
- quantization and backend compatibility analysis;
- evaluation and report generation.

The device consumes immutable model bundles; it does not train in production.
Personal board captures are not uploaded or used for training without separate,
explicit consent and provenance.

## Export and conformance

Every candidate model must have:

- source/training revision and license;
- input/output contract and preprocessing digest;
- framework checkpoint digest;
- exported artifact digest;
- reference inference vectors;
- per-backend numerical tolerance results;
- quantization method and calibration corpus revision;
- supported operators and fallback behavior;
- measured device performance and accuracy deltas.

An export that loads is not sufficient. Device output must satisfy conformance
tolerances and end-task metrics.

## Backend selection

Backends are selected per device profile using a deterministic preference list
whose entries have passed conformance and hardware gates. A backend cannot be
chosen solely because it reports an accelerator. Selection records operator
fallback, thread count, precision, memory, warm-up, and thermal behavior.

Silent CPU fallback is prohibited when it violates the active latency/thermal
profile. The runtime reports degradation or omits the capability.

## Model lifecycle

Bundles are signed and content-addressed. Activation is transactional:

1. download or sideload into app-private staging;
2. verify signature, digest, manifest schema, license policy, ABI/backend, and
   minimum runtime version;
3. run compact conformance vectors;
4. atomically switch the active manifest;
5. retain one verified rollback bundle within storage policy;
6. emit activation or rollback evidence.

Model artifacts are never committed to Git.

## Calibration and abstention

Raw logits/scores are not user confidence. Calibration is evaluated on held-out
boards/sessions and versioned with the model. Identity-sensitive automation
uses a precision-first threshold. Coverage/abstention is a reported product
metric, not hidden missing data.
