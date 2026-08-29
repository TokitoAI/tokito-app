# Evaluation facts

- Split visual/audio data by physical board and acquisition session, never by
  adjacent frame.
- Identity-sensitive automation is precision-first and must abstain below the
  accepted threshold.
- Reports bind model, preprocessing, runtime/backend, device, corpus revision,
  raw predictions, and timings by digest.
- Synthetic/training results are never represented as held-out physical-board
  evidence.
- Standalone release evidence blocks networking and verifies that text LLM,
  VLM, visual models, English STT/TTS, AR overlays, and tool execution remain
  local throughout the scenario.
- Local generative models are evaluated for schema validity, unsupported
  claims, memory/context growth, cancellation, latency, throughput, and thermal
  degradation in addition to task quality.
- Corpus revisions are frozen by `tools/corpus_governance.py`. Dataset,
  inventory, annotation, and split manifests cross-bind by canonical SHA-256;
  evaluation inputs must carry all four digests. The auditor rejects
  cross-split physical boards, sessions, declared leakage groups, lineage,
  exact media digests, and bounded-distance fingerprints. Removal propagates
  to descendants, annotations, and split membership.

**How to apply:** no capability is production-supported until its held-out task
metrics and target-device performance/thermal gates pass.
