# FRDM-MCXN236 Capture Intake

This document describes the local intake path for Navaneeth's iQOO
FRDM-MCXN236 captures and vendor design files. The files are useful for Wave B,
but they are local evidence and must remain outside Git.

## Local Layout

Use the ignored `datasets/local/` tree:

```text
datasets/local/frdm-mcxn236/intake/nxp/
datasets/local/nxp-frdm-mcxn236/sessions/<session-id>/
```

The intake tool copies and renames image files into a stable session folder,
creates empty YOLO `.txt` annotation tasks, records SHA-256 provenance, and
copies CAD/source artifacts for local review. ZIP archives are inventoried so
hidden package contents are recorded without committing extracted source data.

```bash
python tools/capture_intake.py \
  --source datasets/local/frdm-mcxn236/intake/nxp \
  --output-root datasets/local \
  --board-name "NXP FRDM-MCXN236" \
  --session-id iqoo-2026-08-29-session01 \
  --device-profile iqoo-local-capture \
  --captured-at 2026-08-29 \
  --viewpoint mixed-angle \
  --lighting indoor-bench \
  --side top \
  --class-name NXP_FRDM-MCXN236_full_board \
  --class-name NXP_FRDM-MCXN236_ic_package \
  --class-name NXP_FRDM-MCXN236_connector \
  --class-name NXP_FRDM-MCXN236_led \
  --class-name NXP_FRDM-MCXN236_passive_component
```

The generated label files are intentionally empty until reviewed in CVAT, Label
Studio, Roboflow, or another annotation tool. Empty label files from this stage
are not negative examples.

Canonical image IDs include board, session, side, viewpoint, lighting, and
sequence:

```text
nxp-frdm-mcxn236_iqoo-2026-08-29-session01_top_mixed-angle_indoor-bench_img_0001.jpg
```

## Labeling Rules

- Use the full board name in class names, for example
  `NXP_FRDM-MCXN236_full_board`.
- Treat YOLO-format labels as an interchange format, not a final model
  decision. Tokito's vision architecture remains composed: deterministic board
  registration and coordinate transforms first, then candidate learned
  detectors for bounded component/object ROIs.
- Prefer package/family classes for early training, not one class per
  reference designator. A detector should learn visual classes such as board,
  IC package, connector, LED, switch, header, passive component, and test
  point; design identity such as `D1` or `R14` comes later from board
  coordinates plus the reviewed assembly context.
- Draw bounding boxes only around visible and identifiable objects.
- Do not infer hidden components or reference designators from schematic
  knowledge alone.
- Keep train/validation/test splits disjoint by physical board and capture
  session.
- Record unclear cases as needing review instead of guessing.

## Current Data Sufficiency

The first local drop contains 22 iQOO images. That is useful for annotation
workflow testing and board-acquisition experiments, but it is too small for a
robust learned-detector training set by itself. Plan for hundreds of reviewed
images across multiple sessions, with enough examples per visual class. More
captures are needed across distance, perspective, rotation, lighting, glare,
occlusion, clutter, motion, and both board sides.

Keep each physical capture session in one split. Do not randomly split adjacent
frames or near-identical burst photos between train, validation, and test.

## 3D/AR Asset Boundary

The supplied package currently exposes OrCAD/Allegro design files, BOM files,
and schematic PDF material. The intake manifest also inspects ZIP contents.
The visible and archived files do not include Gerber/drill exports or STEP/WRL
board/component models.

AR-ready 3D generation needs one of:

- Gerber copper/soldermask/silkscreen layers plus drill files for a board mesh;
- a KiCad `.kicad_pcb` file that can be exported with open-source `kicad-cli`
  to GLB, STEP, and VRML;
- STEP/WRL board and component models exported by the CAD tool;
- a reviewed assembly-context bundle with board outline, placements,
  rotations, heights, and model references.

KiCad 10 is the preferred open-source conversion route when an Allegro board
can be imported into KiCad. The schematic is not a 3D model source; it provides
electrical context, references, and nets. If Gerber and drill files exist,
`gerber2blend` is a useful open-source fallback for board substrate geometry,
but populated component models still require placement data and 3D package
models.

For the current `FRDM-MCXN236-DESIGN-FILES.zip`, the archive inventory contains
`LAY-90828_C.brd`, `SCH-90828_C.DSN`, `SCH-90828_C.opj`, BOM files, and Allegro
`.dat` support files. It does not expose Gerber or drill file extensions, so
Gerber-based mesh generation is not yet available from this drop.

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

## Future Runtime Path

The intended path is:

```text
NXP FRDM-MCXN236 reference data
  -> deterministic board registration
  -> candidate component/object detector
  -> component ROI
  -> board-plane coordinate
  -> assembly-context identity mapping
  -> AR highlight
```

For `locate_component("D1")`, the runtime should resolve `D1` through the
assembly context, project its board coordinate through the current registration
transform, and render an AR highlight. For `read_led_state("D1")`, the same
coordinate path should produce a bounded ROI for a specialized LED state
detector. Neither path should infer design identity from appearance alone.
