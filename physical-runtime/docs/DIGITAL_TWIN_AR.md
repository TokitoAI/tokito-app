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

Loading is bounded to 64 MiB. The complete GLB header/chunk structure, byte
length, digest, finite bounds and context revision are verified before the
package is admitted. Filament-decoded bounds and the component hierarchy are
checked during renderer activation. Renderer rejection clears the display;
it must not retain a prior model under the new package identity.

Source models can be inspected without physical registration. Verified physical
guidance and automatic source-model placement require both
`physicalGeometryVerified` and `arRegistrationVerified` to be explicitly true.
Manual registration may display an unqualified source as a labelled **reference
AR comparison** after calibrated metric pose admission. This is actual camera
registration for visual comparison, not proof of physical identity or fit. It
does not grant physical RAG scope or source-component guidance. Clearing,
replacement, expiry or lifecycle suspension revokes this comparison mode.
Missing or false qualification flags cannot be promoted by GLB parsing,
camera corner detection, or a source component-map coverage count.

Component selection resolves the declared `ref:` root through all descendant
renderables, including multi-mesh packages. Duplicate, nested, cyclic, detached,
or over-budget hierarchies are rejected. Highlighting clones material instances,
keeps the rest of the board visible, and restores originals before destroying
the clones. Expiry, replacement, registration changes and lifecycle suspension
clear selection. An older expiry cannot clear a newer selection.

Native touch selection uses Filament's rendered-entity picking and the same
authored hierarchy index. It does not infer a component from a detector label.
Asynchronous picks are invalidated by newer selections, package replacement,
registration changes and lifecycle suspension. Reference inspection may label
and highlight source geometry, but that is not physical-board component advice.
The explicit inspection mode owns its status and disables automatic pose
replacement; tapping an inspection model must not accidentally start corner
registration.

Dataset RAG returns validated selected source facts alongside spoken text. The
host may resolve their reference designators only against a current qualified
twin with the exact same board identity. Multi-board answers, unregistered
sources, missing meshes and mismatched identities do not create a highlight.
Neither generated speech nor semantic retrieval establishes physical identity.
The manually registered, already-qualified project provides an exact board
scope to local RAG. That scope is checked again after retrieval and generation;
registration loss, pose revision changes or asset replacement revoke it. A
named documentation reference and a generic automatic corner detector do not
grant this scope. Physical-scope queries never search an unbound whole corpus.

## Runtime geometry

Offline reference models may ship as inventoried artifacts of the signed vision
bundle. `reference-twins.json` declares a bounded catalog of labels, manifest/GLB
paths and exact context revisions. Installation validates every package before
activation. The native picker loads a selected model off the UI thread and
clears prior registration; it never chooses the first model as detected identity.
Source comparison does not require a Cloud project or legacy context file.

The assembly board plane uses authored metric X/Y coordinates. A Y-up GLB maps
asset X to board X, asset Z to board Y and asset Y to physical height. Camera
intrinsics are read from the selected Camera2 device and transformed into the
CameraX `FILL_CENTER` preview. Four or more matched metric landmarks produce a
board-to-camera pose and reprojection error. A pose is accepted only while its
absolute and frame-normalized reprojection errors satisfy the configured gate.

The transparent Filament view consumes the same registration revision as 2D
guidance. The shipping host estimates a metric perspective pose from the signed
X/Z landmarks and calibrated preview intrinsics; an orthographic screen fit is
not a registration fallback. Pose expiry also suppresses the renderer directly,
even if no further tracking callback arrives. Missing calibration, stale pose, low confidence, lifecycle loss,
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
