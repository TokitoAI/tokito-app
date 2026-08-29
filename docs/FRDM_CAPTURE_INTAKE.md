# FRDM-MCXN236 Capture Intake

This document describes the local intake path for Navaneeth's iQOO
FRDM-MCXN236 captures and vendor design files. The files are useful for Wave B,
but they are local evidence and must remain outside Git.

## Local Layout

Use the ignored `datasets/local/` tree:

```text
datasets/local/frdm-mcxn236/intake/nxp/
datasets/local/frdm-mcxn236/sessions/<session-id>/
```

The intake tool copies and renames image files into a stable session folder,
creates empty YOLO `.txt` annotation tasks, records SHA-256 provenance, and
copies CAD/source artifacts for local review.

```bash
python tools/capture_intake.py \
  --source datasets/local/frdm-mcxn236/intake/nxp \
  --output-root datasets/local \
  --board-name FRDM-MCXN236 \
  --session-id iqoo-2026-08-29-session01 \
  --device-profile iqoo-local-capture \
  --captured-at 2026-08-29 \
  --class-name FRDM-MCXN236_full_board \
  --class-name FRDM-MCXN236_component
```

The generated label files are intentionally empty until reviewed in Roboflow,
CVAT, Label Studio, or another annotation tool. Empty label files from this
stage are not negative examples.

## Labeling Rules

- Use the full board name in class names, for example
  `FRDM-MCXN236_full_board`.
- Draw bounding boxes only around visible and identifiable objects.
- Do not infer hidden components or reference designators from schematic
  knowledge alone.
- Keep train/validation/test splits disjoint by physical board and capture
  session.
- Record unclear cases as needing review instead of guessing.

## Current Data Sufficiency

The first local drop contains 22 iQOO images. That is useful for annotation
workflow testing and board-acquisition experiments, but it is too small for a
robust YOLO training set by itself. More captures are needed across distance,
perspective, rotation, lighting, glare, occlusion, clutter, motion, and both
board sides.

## 3D/AR Asset Boundary

The supplied package currently exposes OrCAD/Allegro design files, BOM files,
and schematic PDF material. The visible files do not include Gerber/drill
exports or STEP/WRL board/component models.

AR-ready 3D generation needs one of:

- Gerber copper/soldermask/silkscreen layers plus drill files for a board mesh;
- a KiCad `.kicad_pcb` file that can be exported with open-source
  `kicad-cli pcb export step` and `kicad-cli pcb export vrml`;
- STEP/WRL board and component models exported by the CAD tool;
- a reviewed assembly-context bundle with board outline, placements,
  rotations, heights, and model references.

Use `tools/ar_asset_plan.py` to inspect the local source package and produce an
AR asset plan:

```bash
python tools/ar_asset_plan.py \
  --source datasets/local/frdm-mcxn236/intake/nxp \
  --output-dir build/ar-assets/frdm-mcxn236
```

Until supported exports exist, the runtime should use planar board
registration and 2D overlays. A fake 3D model would violate the no-placeholder
capability rule.
