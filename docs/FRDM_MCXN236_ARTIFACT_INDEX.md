# FRDM-MCXN236 artifact index: which file is which

Several 3D files exist for this board and only one is the AR production
asset. This page states which is which so nobody has to guess from a
filename. All are published on the GitHub release
`v0.1-frdm-mcxn236-3d-asset`; none are committed to git (they are build
outputs under `build/ar-assets/frdm-mcxn236/generated/`, which is
gitignored).

## Use these

| File | Role |
|---|---|
| `frdm_mcxn236_ar_annotated.glb` | **The AR production asset.** 15.8 MiB, component nodes named `ref:<designator>`, per-node glTF `extras` metadata, no copper/zone/silkscreen geometry. This is the file the Android runtime loads. |
| `frdm_mcxn236_digital_twin_manifest.json` | **Required alongside it.** Declares units/axes/origin/bounds, the node prefix, and the exact byte length and SHA-256 of the GLB above. The runtime rejects a package whose GLB does not match this digest. |
| `frdm_mcxn236_component_map.json` | **The canonical identity table.** One `component_id` per reference (`nxp-frdm-mcxn236:C:U1`) with GLB selector, board placement, BOM identity, package and model quality. Shared by the vision, dialogue and AR lanes. |
| `board_component_index.json` | Per-component placement + BOM + per-pad nets + full net connectivity (379 nets). Backs `get_component_info()` and `get_connection_guidance()`. |
| `frdm_mcxn236_populated.step` | **Engineering/CAD master.** Full-fidelity solid model for CAD interchange. Not for AR (73 MB, and STEP carries no reference-designator node names). |

## Reference / inspection only

| File | Role |
|---|---|
| `frdm_mcxn236_populated.glb` | Full-fidelity visual master (44.5 MB): copper tracks, zones, silkscreen. Good for desktop inspection and renders; too heavy for the phone. Same placement as the AR asset. |
| `frdm_mcxn236_populated_annotated.glb` | The visual master with metadata embedded but **without** the `ref:` prefix. Superseded by the AR asset for runtime use. |
| `frdm_mcxn236_ar.glb` | The AR asset **before** metadata/prefix are applied. Intermediate build output. |
| `frdm_mcxn236_populated_render_{top,iso,side}.png` | Human visual QA renders. |
| `component_previews/component_preview_<REF>.png` | Per-component isolation renders (one component's model attached and no other). |
| `3d_model_completeness_report.json` | Every reference classified; coverage of physical components. |
| `3d_model_validation.json` | Per-component position/extent/side/contact/height/node checks. |
| `3d_component_highlightability.json` | Proof each component is independently selectable. |
| `component_model_resolution.json` | Per-component model path, registration method and evidence. |
| `manual_model_overrides.json`, `frdm_mcxn236_package_records.json` | Datasheet-sourced dimension records and the overrides they generate. |

## Artifacts owned by other lanes

These are published by teammates and consumed by this lane; they are listed
here so nobody hunts for them in the wrong release.

| Release | Asset | Status |
|---|---|---|
| `v0.1.0` | `tokito-physical-v0.1.0-production.apk`, `tokito-physical-v0.1.0-iqoo-tested-debug.apk` | The working application build. **Do not overwrite** -- rebuild under a new tag. |
| `yolo-frdm-mcxn236-pose-smoke-v1` | `tokito-frdm-mcxn236-yolo11n-pose-smoke-v1.pt` + `training-manifest.json` | Fine-tuned `yolo11n-pose`, 4 board-corner keypoints, 640 px. Its own manifest records `release_eligible: false`, `evaluation_valid: false` and metrics that are *training-session smoke only*, so it proves the pipeline, not detection quality. |

The pose checkpoint is a PyTorch `.pt`. The Android runtime loads a **TFLite**
bundle (`models/vision/model.tflite` + `manifest.json`), so an export step
sits between the two and has not been performed. The checkpoint's structure
does match the runtime contract -- `kpt_shape` is 4 keypoints x 3 and the
training image size is 640, which yields the required `[1, 17, 8400]` output
(4 box + 1 class + 4 keypoints x 3, over 80^2+40^2+20^2 anchors). What is
still needed from the vision lane is the exported bundle itself plus the
decode parameters the export assumes (normalization and whether keypoint
coordinates are normalized), because an export whose preprocessing disagrees
with `LocalPoseVisionRuntime` produces plausible but wrong corners, and a
wrong corner set yields a confident, wrong board pose.

`tools/verify_vision_bundle.py` checks a candidate bundle against every rule
in `LocalPoseVisionRuntime.load()` before it is pushed to a device.

## Not part of the pipeline

| File | Why |
|---|---|
| `frdm_mcxn236_populated.3mf` | A Bambu Studio 3D-printing project created by importing our own `frdm_mcxn236_populated.step`. Its parts are named by generic package type (`R_0402_1005Metric`), never by reference designator, so it cannot be mapped back to specific components, and it is one build behind. Archived curiosity, not a geometry or identity source. |
| Gerber files | None exist for this board and none are needed: the Allegro `.brd` -> KiCad path already provides authored placement, rotation, side and net data that Gerbers do not carry. |

## Regenerating

The whole set is reproducible from the vendor design data; see
`docs/FRDM_MCXN236_3D_PIPELINE_README.md` for the stage-by-stage tool
sequence. Nothing here is hand-edited, so a rebuild replaces every file
above without manual steps.
