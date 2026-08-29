# FRDM-MCXN236 populated 3D model (partial)

This documents an addition on top of the board-substrate-only 3D pipeline
already described in `docs/FRDM_CAPTURE_INTAKE.md`. It attaches real,
disk-verified KiCad 3D models to real footprints, resolved from real BOM
data -- it does not implement board registration, a detector, or any AR
rendering, and it does not produce a fully populated board. Read the
"What this is not" section before using the outputs.

## Why this exists

The GLB/STEP exported directly from the Allegro import (`docs/FRDM_CAPTURE_INTAKE.md`)
is board-substrate only: outline, copper, pads, silkscreen, with zero
component 3D bodies, because Allegro `.brd` files carry no 3D model
references at all. That is still correct and useful as an AR-registration
reference asset, but it is not visually representative of the real,
populated board.

## Data source: the xlsx BOM, not the plain-text export

`SCH-90828_C.BOM` (plain text) has only `Item, Quantity, AgilePN, ASSY_OPT,
FIND#, PRE_APN, Reference` -- no package/footprint information at all.
`SCH-90828_C.BOM.xlsx` has four more columns the plain-text export drops:
`Description`, `MFG`, `MFG_PN`, `VALUE`. `Description` embeds a real,
human-authored package designator for most parts (for example `"CAP CER
4.7uF 16V 10% X5R 0603"`), and `MFG`/`MFG_PN` are real manufacturer part
numbers (for example Murata `GRM188R61C475KAAJD` for a 0603 capacitor).
`tools/xlsx_bom_parser.py` parses this generically (header-driven column
discovery, not hardcoded column letters) and expands it to one record per
reference designator: 97 BOM line items expand to exactly 391 reference
designators, matching the reference package's component count exactly.

This also independently confirmed an earlier finding: `BH1`-`BH4`'s real
BOM description is `"NON-PLATED MOUNTING HOLE ... NO PART TO ORDER"` --
they are mounting hardware, not real components, consistent with their
zero-size pads noted in `docs/FRDM_MCXN236_REFERENCE_PACKAGE.md`.

## Resolver: real package code, real installed file, or nothing

`tools/component_model_resolver.py` only claims a match when:

1. the BOM `Description` contains a recognized package designator
   (standard EIA/IPC chip-passive sizes for `R`/`C`/`L` references, or a
   recognized `SOT-23` family / `SOD-523` designator), **and**
2. the corresponding KiCad official 3D model file actually exists on disk
   at `C:\Program Files\KiCad\10.0\share\kicad\3dmodels\...` (checked with
   `Path.is_file()`, not assumed).

Real result against the actual board:

| Status | Count | Meaning |
|---|---|---|
| `matched_official_kicad_model` | 295 | Real package code recognized in the BOM description and the exact KiCad library file exists on disk |
| `no_recognized_package_code` | 86 | BOM description has no recognized standard package code (test points, connectors, headers, most ICs, switches, crystals, mounting hardware, jumpers) |
| `package_recognized_but_unresolved` | 10 | A package code was recognized but this resolver doesn't have a rule for it yet (5 diodes with a plain `0603` chip-diode designator; 5 solder jumpers with `0402`, which are correctly not real mountable parts anyway per their BOM description) |

75.4% of the 391 real components got a real, verified, non-fabricated
model. The remainder -- overwhelmingly test points (28), connectors (20),
ICs (13, including the MCU), jumper headers (8), diodes (8), mounting
holes (4), and switches/crystals -- are honestly reported as unresolved,
not guessed. This was a deliberate, time-boxed stopping point: the major
ICs and connectors would require per-part manual vendor CAD sourcing, not
something this generic resolver batch-solves.

## Attaching models: text-splice, not pcbnew scripting

KiCad's bundled `pcbnew` Python API can load the board and has a real
`FOOTPRINT.Add3DModel()` call, but `pcbnew.LoadBoard()` surfaces load
warnings (the same "Invalid zero-sized pad" warnings `kicad-cli` prints to
its console) as blocking GUI message boxes in this environment, one per
warning, which makes it impractical to script. `tools/attach_3d_models_text.py`
instead finds each top-level `(footprint ...)` block's exact character span
in the original file (paren-depth scanning with the same quote-escaping
rule as `tools/kicad_pcb_placements.py`'s tokenizer) and splices a
`(model "path" (offset ...) (scale ...) (rotate ...))` node in just before
that footprint's closing paren -- every other byte of the file is
untouched, and no placement/rotation data is read from or written by this
step; it only adds a model reference to footprints already correctly
positioned by the original Allegro import.

```bash
python tools/component_model_resolver.py \
  --reference-package build/ar-assets/frdm-mcxn236/generated/frdm_mcxn236_reference_package.json \
  --bom-xlsx build/ar-assets/frdm-mcxn236/source/SCH-90828_C.BOM.xlsx \
  --kicad-3d-library-root "C:/Program Files/KiCad/10.0/share/kicad/3dmodels" \
  --output-json build/ar-assets/frdm-mcxn236/generated/component_model_resolution.json

python tools/attach_3d_models_text.py \
  --kicad-pcb build/ar-assets/frdm-mcxn236/generated/LAY-90828_C.kicad_pcb \
  --resolution-json build/ar-assets/frdm-mcxn236/generated/component_model_resolution.json \
  --output-kicad-pcb build/ar-assets/frdm-mcxn236/generated/LAY-90828_C_populated.kicad_pcb

"C:/Program Files/KiCad/10.0/bin/kicad-cli.exe" pcb export glb --force \
  --output build/ar-assets/frdm-mcxn236/generated/frdm_mcxn236_populated.glb \
  build/ar-assets/frdm-mcxn236/generated/LAY-90828_C_populated.kicad_pcb
```

## Verified results

- `kicad-cli pcb export glb` accepted the spliced file cleanly (same
  pre-existing zero-sized-pad warnings as the original import, no new
  errors).
- GLB grew from 2 nodes / 1 mesh (board-only) to **592 nodes / 296 meshes**
  (295 attached component models + 1 board-substrate mesh) -- file size
  877KB to 1.84MB.
- Visually rendered (`kicad-cli pcb render`, both top orthographic and
  isometric views, saved as
  `build/ar-assets/frdm-mcxn236/generated/frdm_mcxn236_populated_render_top.png`
  and `..._render_iso.png`): correctly shaped, properly seated resistor
  and capacitor bodies are visible scattered across the board matching
  real component density, plus a visible SOT-23 body near the RGB header
  area. Nothing floating, mirrored, or obviously misplaced.

## What this is not

- **Not fully populated.** The MCU (`U1`, VFBGA184 package), other major
  ICs, and all connectors/headers/switches/crystals remain flat
  outline-only in the render -- exactly the components a hackathon judge
  would most want to see in 3D, and exactly the ones this pass does not
  attempt (no exact BGA/QFN/connector-shell model exists in KiCad's
  library for these vendor-specific parts, and sourcing one per part is
  manual, per-component work, not something this generic resolver batch-
  solves).
- **Not board registration, a detector, or an AR asset.** This is a
  visual-quality improvement to the reference 3D asset only. It does not
  change, and is not required by, `tools/component_locator.py`'s
  `locate_component()` pipeline, which already produces real board-plane
  coordinates and ROIs from the reference package alone.
- **Not a redistribution-rights determination.** KiCad's official 3D
  library ships under its own license (separate from this repository);
  this document does not re-state or verify that license here.

## Files

Generated files stay under `build/` (gitignored, not committed), same
policy as the rest of the AR asset pipeline:

- `LAY-90828_C_populated.kicad_pcb` -- the spliced board file
- `frdm_mcxn236_populated.glb` -- the populated render/AR asset
- `component_model_resolution.json` -- the component map: reference,
  real position/rotation/side, BOM part number/manufacturer/description,
  recognized package code, resolved model path (or null), and status with
  evidence for every one of the 391 real components
- `frdm_mcxn236_populated_render_top.png` / `..._render_iso.png` --
  visual verification renders
