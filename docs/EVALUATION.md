# Evaluation and benchmark contract

## Purpose

Evaluation decides whether a capability is supported on a declared device. It
is not a marketing chart and cannot be replaced by hand-selected screenshots.

## Corpus units and leakage control

The atomic split unit is a physical board and acquisition session, not an image
frame. All adjacent video frames, augmentations, crops, and audio segments from
one session stay in one split. Near-duplicate boards and revisions are grouped
when their visual identity would leak.

Every corpus item records:

- stable item, board, session, and split IDs;
- source and license/consent basis;
- capture device/lens/resolution and relevant settings;
- lighting, distance, angle, occlusion, motion, and board revision;
- annotation revision and reviewer status;
- file digest and lineage;
- synthetic/real classification;
- exclusion reason, if excluded before the evaluation freeze.

## Dataset policy

- Training, validation, calibration, and test sets are disjoint by board/session.
- Public, contributed, synthetic, and private data are reported separately.
- Test labels are frozen before release evaluation.
- Unsupported classes remain visible in open-set/abstention analysis.
- License incompatibility blocks use regardless of technical quality.
- Raw proprietary captures never enter a public corpus automatically.

## Task metrics

### Registration and mapping

- registration success rate;
- normalized reprojection error distribution;
- registered reference precision/recall;
- false-identity rate;
- calibration error and abstention/coverage curve;
- recovery after motion, lens switch, lifecycle pause, and lighting change.

### Component inspection

- per-package precision, recall, macro F1, and confusion matrix;
- presence and orientation metrics;
- open-set rejection;
- performance by size, occlusion, angle, glare, and board density.

### LED state

- per-state precision/recall/F1;
- blink frequency relative error by range;
- false activation under reflections, PWM artifacts, and exposure changes.

### Voice and measurement parsing

- word error rate and entity error rate;
- exact typed measurement match (value, scale, unit, relationship);
- ambiguity/confirmation rate;
- false wake and missed wake rates;
- clean, recorded bench noise, live bench noise, accent, and distance slices;
- TTS first-audio latency and barge-in response.
- English-only model footprint, cold/warm start, real-time factor, and sustained
  performance relative to declared alternatives.

### Local text and visual reasoning

- constrained tool-selection exact match and invalid-tool rate;
- argument-schema validity before repair and after deterministic validation;
- first-token latency, tokens per second, context/RSS growth, and cancellation;
- hallucinated observation rate, unsupported-claim rate, and abstention;
- VLM task accuracy on bounded ROI slices versus specialized models;
- recovery when the LLM or VLM is thermally unavailable or memory-rejected.

### AR presentation

- board acquisition success and time to stable track;
- motion-to-overlay latency and registered overlay alignment error;
- track loss, reacquisition, and incorrect-board-lock rates;
- touch-target and spoken-guidance task completion on phone and tablet layouts;
- frame pacing and thermal behavior with overlays, speech, and local LLM active.
- physical board-plane alignment error in millimetres where ground-truth scale
  is available, plus preview-pixel error;
- reference-designator localization and orientation correctness independently
  of dialogue quality.

## Device benchmark matrix

Each report declares:

- manufacturer/model, SoC, RAM, Android build, ABI;
- camera/lens and capture mode;
- runtime and model bundle digests;
- inference backend, precision, operator fallback, threads;
- battery state, power mode, ambient/start/end temperature;
- cold/warm runs and warm-up policy;
- per-stage p50/p95/p99 latency;
- peak RSS, model storage, energy proxy, dropped frames;
- thermal state over the sustained scenario;
- crashes, ANRs, backend resets, and recovery.

The first target iQOO profile is not assumed from the brand name. Its exact
model/SoC/Android/accelerator capabilities must be captured from the physical
device before backend selection.

## Release scenarios

At minimum:

1. fresh standalone launch with all networking blocked and no Tokito account;
2. automatic board discovery, registration, and requested component location;
3. local LLM conversation that selects a valid physical tool;
4. bounded local VLM inspection with typed validation and evidence;
5. component presence/package/orientation inspection;
6. static and blinking LED observation;
7. spoken voltage/resistance measurement with unit parsing and confirmation;
8. TTS guidance interrupted by engineer speech;
9. camera permission loss and recovery;
10. app background/resume with registration revalidation;
11. thermal degradation during a 20-minute mixed AR/LLM/voice scenario;
12. corrupt model bundle rejection and verified rollback;
13. low-confidence identity abstention rather than a wrong reference.
14. FRDM-MCXN236 acquisition, motion/reacquisition, reference localization, and
    orientation guidance across held-out sessions, viewpoints, and lighting.

## Report format

Every automated run writes an immutable machine-readable manifest and a derived
human summary. The manifest contains command/config, revisions, digests, sample
IDs, raw predictions, timing traces, environment, and exclusions. Human reports
are generated from the manifest; hand-edited metric values are invalid.

## Baselines and regression policy

A candidate is compared with the last accepted release on the same frozen test
revision and device profile. A change fails if it violates a hard gate or causes
an unexplained statistically/materially significant regression in supported
quality, latency, memory, or thermal behavior. Improvements on one aggregate
metric cannot conceal a worse false-identity rate or unsupported slice.
