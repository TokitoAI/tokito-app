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

The supplied package exposes OrCAD/Allegro design files, BOM files, and
schematic PDF material. It does not include Gerber/drill exports, and no
Gerber-based mesh generation is available from this drop.

KiCad 10 (installed locally: 10.0.5, via `winget install --id KiCad.KiCad
--version 10.0.5`) successfully imports the Allegro board with
`kicad-cli pcb import --output <out.kicad_pcb> <LAY-90828_C.brd>` (the
`--format` flag defaults to `auto`; `kicad-cli pcb import --help` does not
list `allegro` as an explicit `--format` choice, but `auto` detection
correctly logs `Importing ... using Allegro format...`). The import produced
581 footprints, 5532 tracks, 593 vias, and 37 zones with zero errors (only
non-fatal antipad/thermal-relief/zero-sized-pad warnings). From the resulting
`.kicad_pcb`, `kicad-cli pcb export glb|step|vrml` produce real GLB/STEP/VRML
files.

**Completeness is board-substrate only.** The exported GLB has a single fused
mesh (2 nodes, 1 mesh) and the STEP file has 2 solid shells: outline, copper,
pads, and silkscreen, with no populated IC/connector/LED bodies. Allegro
`.brd` files carry no 3D component model references, so this is expected, not
a conversion failure. Do not present this GLB as a fully populated 3D model to
reviewers or judges — it is a board-registration/AR-anchor asset, not a
photorealistic render.

**`kicad-cli pcb export pos` is unreliable for this board's reference
designators.** Every one of its 581 rows returned an empty or literal
`${REFERENCE}` string in the Ref/Val/Package columns. The real designators do
exist in the board file (as `property "Reference"` text on each footprint),
but the Allegro importer does not populate the footprint's canonical
reference field that `pos` export reads. Use
`tools/kicad_pcb_placements.py` instead, which parses the `.kicad_pcb`
s-expression tree directly and recovers the real per-footprint designator,
value, side, position, and rotation:

```bash
python tools/kicad_pcb_placements.py \
  --input build/ar-assets/frdm-mcxn236/generated/LAY-90828_C.kicad_pcb \
  --output-csv build/ar-assets/frdm-mcxn236/generated/frdm_mcxn236_real_placements.csv \
  --output-json build/ar-assets/frdm-mcxn236/generated/frdm_mcxn236_real_placements.json
```

For this board it recovers 391 usable reference designators out of 581
footprints (the remaining 190 have no reference text at all in the source
file — plausibly test points, fiducials, or mounting holes, not a parser
defect). A spot check of recovered designators `R87`, `R216`, `R133`, `C41`
against `SCH-90828_C.BOM` confirms all four appear with matching part
numbers, so the recovered designators are read from vendor data, not
invented.

Use `tools/ar_asset_plan.py` to inspect the local source package and produce
an AR asset plan (it emits the full import/export/placement command sequence
and the board-only-geometry and pos-export caveats above):

```bash
python tools/ar_asset_plan.py \
  --source datasets/local/frdm-mcxn236/intake/nxp \
  --output-dir build/ar-assets/frdm-mcxn236
```

Generated GLB/STEP/VRML/`.kicad_pcb`/placement files are local build output
(`build/`, gitignored) and are never committed to Git. The AR-handoff package
for the teammate doing Android/AR rendering consists of:
`frdm_mcxn236.glb` (render asset), `frdm_mcxn236_real_placements.csv`/`.json`
(board-plane coordinates + reference designators), and this document's
board-only-geometry caveat. Runtime registration and overlays should not
assume populated component geometry until a reviewed 3D package library is
added; a fake populated model would violate the no-placeholder capability
rule.

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
