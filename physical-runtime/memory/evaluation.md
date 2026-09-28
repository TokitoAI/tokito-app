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

Local YOLO smoke runs use `tools/yolo_experiment.py`. A smoke run may consume
explicitly requested local data only when every media and annotation digest is
verified; it stays `release_eligible: false` and `evaluation_valid: false`.
Same-session holdouts demonstrate pipeline execution, not detector
generalization. Checkpoints and rendered reports remain ignored local output.
Deterministic rotation descendants record their source lineage and always stay
in the parent's split. Four-corner/landmark experiments use
`tools/yolo_pose_experiment.py`; the ordered corner contract is clockwise from
top-left, and cropped or unreviewed landmarks are visibility-zero rather than
inferred. Semantic component points should be reviewed in normalized board
coordinates and projected through an accepted four-corner homography. Pose
confidence never bypasses the convexity, image-bound, box-consistency, area,
and edge-length gate; unsafe geometry must abstain.
Known-board pose may refine the YOLO proposal against an explicitly reviewed
reference using ROI-bounded ORB matching and RANSAC. Reference inputs are a
complete provenance tuple, and failed match-count, consensus, reprojection, or
geometry checks abstain instead of reverting to unverified component guidance.
Multi-view refinement attempts every eligible reviewed capture, excludes exact
query bytes, removes references below 10% of the strongest inlier support, and
requires two qualified poses to agree within 2% of the detection diagonal.
One reference may stand alone only with exceptional evidence: at least 250
RANSAC inliers, an 80% inlier ratio, median reprojection error no greater than
1.5 px, and p95 error no greater than 3 px. Otherwise refinement abstains.
Partial captures remain detection/abstention evidence, never four-corner truth.
