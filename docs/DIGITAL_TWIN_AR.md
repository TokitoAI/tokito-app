# Metric 3D digital-twin AR

## Contract boundary

The Android runtime consumes a validated GLB package. It does not know whether
that package originated from KiCad, Gerber plus assembly data, Allegro, or a
future Tokito exporter. Asset generation and runtime rendering communicate only
through `schemas/digital-twin.asset-manifest.schema.json` and the immutable
assembly-context revision.

The manifest fixes metre units, right-handed Y-up GLB axes, board origin,
bounds, board identity/revision, assembly-context revision, byte length and
SHA-256 digest. Component nodes use the declared prefix followed by the
reference designator, for example `ref:D1`. Unknown additive manifest fields
are accepted; an unknown major version is rejected.

The registration quad defaults to the corners of the signed board-plane
bounds. A manifest may instead declare `boardPlaneCornerPointsMetres`: four
explicit metric landmarks in the vision model's keypoint order, for authored
physical anchors such as corner mounting-hole centres taken from the PCB
source. The corner semantics belong to the detector, so this field is only
emitted together with a model trained on the same landmarks; a list that is
not exactly four finite in-bounds points is rejected as invalid geometry
rather than silently repaired.

Loading is bounded to 64 MiB. The complete GLB header/chunk structure, byte
length, digest, finite bounds and context revision are verified before the
package is atomically activated. Filament-decoded bounds are compared with the
signed manifest. A rejected package never replaces the active package.

## Runtime geometry

The assembly board plane uses authored metric X/Y coordinates. A Y-up GLB maps
asset X to board X, asset Z to board Y and asset Y to physical height. Camera
intrinsics are read from the selected Camera2 device and transformed into the
CameraX `FILL_CENTER` preview. Four or more matched metric landmarks produce a
board-to-camera pose and reprojection error. A pose is accepted only while its
absolute and frame-normalized reprojection errors satisfy the configured gate.

The transparent Filament view consumes the same registration revision as 2D
guidance. Missing calibration, stale pose, low confidence, lifecycle loss,
context mismatch, invalid GLB or renderer failure makes the 3D layer invisible;
the last pose is never silently reused.

## Conformance object

`CalibrationBoardAsset` creates a deterministic real GLB 2 object in memory: a
120 mm by 80 mm neutral metrology plane with raised dimensioned blocks and an
origin marker. It exists only to exercise loading, metric pose, projection,
transparency and component-node selection before a project asset is available.
It is labelled as calibration data and is never substituted for a missing PCB
asset.

The conformance app asks for four board corners, reads calibrated camera
intrinsics, estimates a metric pose, reports reprojection RMS and renders the
calibration object for two minutes. Backgrounding, camera loss, reset or missing
calibration suppresses it immediately. Automatic discovery/tracking may update
the same pose API continuously without changing the renderer or asset format.

The synthetic-pose renderer preview exists only in `android/ar-demo`. The
shipping `app` has no synthetic-pose action and never presents calibration
geometry as a project PCB. Its explicit calibration mode requires the matching
printable 120 x 80 mm physical target and a measured pose before drawing the
conformance object. Product PCB rendering still requires an active
assembly-context-bound package from the asset pipeline. In both modes, signed
X/Z bounds supply the metric corner landmarks used by pose estimation.

## Required device evidence

- rendered dimensions against a measured calibration target;
- median and p95 reprojection/alignment error across pose and distance;
- loss, false-lock and reacquisition rates;
- p50/p95 motion-to-overlay latency and frame pacing;
- peak memory and a 20-minute camera/3D/voice/local-LLM thermal run;
- lifecycle, permission, lens, surface and corrupt-package fault injection;
- complete network-blocked operation.

Host tests and an APK prove the implementation is buildable. They are not a
substitute for the target-iQOO evidence above.
