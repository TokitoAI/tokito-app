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

Real result against the actual board, after two resolution passes (see
below for what the second pass added):

| Status | Count | Meaning |
|---|---|---|
| `matched_official_kicad_model` | 316 | Real package code recognized in the BOM description and the exact KiCad library file exists on disk |
| `no_recognized_package_code` | 70 | BOM description has no recognized standard package code |
| `package_recognized_but_unresolved` | 5 | A package code was recognized (5 solder jumpers, `0402`) but they are correctly not real mountable parts per their own BOM description ("SOLDER BLOB JUMPER ... NO PART TO ORDER") |

80.8% of the 391 real components got a real, verified, non-fabricated
model.

### Second pass: LED/SOT-89/SOT-363/bare-header families

The first pass (295/391) only mapped chip-passive package codes for
`R`/`C`/`L` prefixes plus `SOT-23`/`SOD-523`. A second pass added, each
checked against a real installed file before being accepted:

- **LED chip packages** (`LED_SMD.3dshapes`): gated on the BOM description
  containing the word "LED" (not just a package-shaped number, since `D`
  prefixes cover both LEDs and diodes) plus a recognized chip package code.
  Resolved 5 LEDs (`D3`, `D6`, `D7`, `D8`, `D12`, all `0603`).
- **`SOT-363`/`SC-70-6`**: resolved 1 diode (`D11`).
- **Bare THT pin headers** (`Connector_PinHeader_*mm.3dshapes`): only
  matches a BOM description starting `"HDR NxM TH"` -- this pattern
  deliberately does **not** match `"CON ... SKT SHRD"` (shrouded box
  connectors), which are a different physical shape. Pin count and pitch
  (50mil/1.27mm or 100mil/2.54mm) come directly from the BOM text; a
  single-pin header (`1x1`) defaults to the 2.54mm family since pitch is
  meaningless for one pin. Resolved 7 headers (`J12`, `J13`, `J15`,
  `J16`, `J17`, `J19`, `J20`). These are recorded with
  `model_kind: "generic_family_approximate"` (real pin-count/pitch match,
  not an exact vendor part) rather than `"exact_family"` -- the component
  map preserves this distinction.
- **A real near-miss, correctly left unresolved**: `U2`'s BOM description
  says `"SOT-89-5"`, but KiCad's library only has `SOT-89-3.step` (a
  different pin count = a different real physical part). This is reported
  as unresolved rather than silently substituting the wrong-pin-count
  model.

The remainder (75 components) are honestly reported as unresolved, not
guessed -- see the tiered report below.

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
  errors), across both resolution passes.
- GLB grew from 2 nodes / 1 mesh (board-only) -> 592 nodes / 296 meshes
  (first pass, 295 models) -> **634 nodes / 317 meshes** (second pass,
  316 models) -- file size 877KB -> 1.84MB -> 2.29MB.
- Visually rendered (`kicad-cli pcb render`, both top orthographic and
  isometric views) after each pass and actually inspected: correctly
  shaped, properly seated resistor/capacitor/SOT-23 bodies, plus (second
  pass) real black/gold THT pin-header bodies visible at multiple
  locations with pins correctly protruding. Nothing floating, mirrored,
  or obviously misplaced in either render.

## Tiered report of the 75 still-unresolved components

Generated from `component_model_resolution.json`; saved as
`build/ar-assets/frdm-mcxn236/generated/unresolved_component_report.json`
with full reference/position/rotation/side/BOM fields per component.

| Tier | Count | Meaning |
|---|---|---|
| 1 | 29 | MCU/major ICs (`U1`-`U12`, `U41`), USB-C/audio/major shrouded connectors (`J1`-`J4`, `J8`-`J11`, `J14`, `J21`), buttons (`SW1`-`SW3`), crystals (`Y1`-`Y3`), tri-color LED (`D13`) |
| 2 | 8 | Smaller connectors/jumper headers not covered by the bare-header pattern, plain diodes, transistors |
| 3 | 38 | Test points (28, no real 3D body -- bare pads), mounting holes (4, `"NO PART TO ORDER"`), solder jumpers (5, `"NO PART TO ORDER"`), remaining passive/misc |

Tier 1 is exactly the set a hackathon judge would most want to see in
3D, and exactly the set that needs real vendor CAD (Digi-Key/Mouser/
Ultra Librarian STEP files) rather than a KiCad-library lookup: BGA/QFN/
WLCSP/DFN/HVSON/LGA packages for the ICs, and USB-C receptacle/shrouded-
box/audio-jack housings for the connectors, are vendor- and part-
specific shapes with no generic KiCad equivalent that could be trusted
without a real dimensional cross-check.

### Why the real iQOO photos weren't used to crop reference thumbnails

This was considered (project board position -> approximate image ROI ->
crop, for visual identification only, not mesh generation). It was not
done: computing an image-pixel region requires a real, calibrated
board-to-camera transform, and the only transform available anywhere in
this codebase is `tools/component_locator.py`'s
`identity_test_transform` -- an explicit test fixture (`transform_revision:
"test-fixture-identity-scale"`), not derived from any actual photo's real
camera parameters. Cropping "reference thumbnails" with it would show an
essentially arbitrary region of the photo, not the component's real
location -- that would be misleading rather than helpful, so it was
skipped rather than faked. The BOM descriptions already extracted here
(e.g. `"CON 2X8 USB2.0_TYPE_C RCPT"` for `J10`/`J11`, `"IC MCU ... VFBGA184"`
for `U1`) are specific enough for a human to identify these parts directly
in the real photos without a crop.

## What this is not

- **Not fully populated.** 29 Tier 1 components -- the MCU, other major
  ICs, and the USB-C/audio/major connectors -- remain flat outline-only
  in the render. Real vendor 3D models for these would need manual
  per-part sourcing, not something this generic resolver batch-solves.
- **Not board registration, a detector, or an AR asset.** This is a
  visual-quality improvement to the reference 3D asset only. It does not
  change, and is not required by, `tools/component_locator.py`'s
  `locate_component()` pipeline, which already produces real board-plane
  coordinates and ROIs from the reference package alone.
- **Not a redistribution-rights determination.** KiCad's official 3D
  library ships under its own license (separate from this repository);
  this document does not re-state or verify that license here.
- **Does not touch the YOLO labeling track.** No file under
  `datasets/local` was read or modified by this work; the 22 iQOO images
  remain exactly as your teammate's labeling session will find them.

## Photo-based identity confirmation for Tier 1 (no new attachments)

The 22 real iQOO photos in `datasets/local/frdm-mcxn236/intake/nxp/`
(the same single capture session used throughout this work -- no new
photos have been added there) were inspected directly for legible
silkscreen/package markings on Tier 1 unresolved components, cross-
checked against `SCH-90828_C.BOM.xlsx`.

Confirmed by directly reading the photo and matching against the BOM:

| Reference | Visible marking in photo | BOM `MFG` / `MFG_PN` | Match |
|---|---|---|---|
| `U1` | "NXP MCXN236V CTAPA 2506 0P21K" | NXP SEMICONDUCTORS / `MCXN236VDFT` | Confirmed (MCU) |
| `U8` | "LPC55S69 0.JEV98 2 W737117 sS022031B" | NXP SEMICONDUCTOR / `LPC55S69JEV98` | Confirmed (MCU-Link debug probe IC) |
| `J10`, `J11` | "GCT" printed on both USB-C receptacle shells | GCT / `USB4085-GF-A` | Confirmed |
| `SW3` | Silkscreen label "ISP SW3" next to a visible tactile button | WURTH ELEKTRONIK / `434123025826` | Location/function confirmed |

**None of these got a new 3D model attached.** Confirming identity is not
the same as having a real geometry source:

- `U1` (VFBGA184) and `U8` (VFBGA98) are NXP-specific ball-grid-array
  packages with no KiCad library equivalent -- a real model would need
  NXP's own STEP file (if published) or a manufacturer/distributor CAD
  download, not something safe to approximate generically.
- `J10`/`J11` (`USB4085-GF-A`, manufacturer GCT) is a specific USB-C
  receptacle mechanical part; KiCad's library has no USB-C receptacle
  family at all (checked: no match under any `Connector_USB*` folder).
  GCT's own site or a distributor (Digi-Key/Mouser) would carry the real
  STEP file for this exact part number.
- `SW3` (and `SW1`/`SW2`, same part): KiCad's `Button_Switch_SMD.3dshapes`
  only has Panasonic and Nidec Copal tactile switches. The real part is a
  **Würth Elektronik** `434123025826` -- none of the available generic
  models is from that manufacturer, and without a confirmed dimensional
  match this resolver does not substitute a different manufacturer's
  part, the same discipline that correctly left `U2`'s `SOT-89-5`
  unresolved earlier.

The remaining 25 Tier 1 components (other ICs, most connectors, crystals)
were not legibly identifiable at these photos' resolution/angle for
their small package markings -- reported honestly as still unconfirmed,
not guessed. Photos were used exclusively for reading existing text/
identity; no image pixels were used to generate or approximate any 3D
geometry.

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
- `unresolved_component_report.json` -- the 75 still-unresolved
  components with tier, full BOM fields, and real position/rotation/side
