# Evaluation facts

- Split visual/audio data by physical board and acquisition session, never by
  adjacent frame.
- Identity-sensitive automation is precision-first and must abstain below the
  accepted threshold.
- Reports bind model, preprocessing, runtime/backend, device, corpus revision,
  raw predictions, and timings by digest.
- Synthetic/training results are never represented as held-out physical-board
  evidence.

**How to apply:** no capability is production-supported until its held-out task
metrics and target-device performance/thermal gates pass.
