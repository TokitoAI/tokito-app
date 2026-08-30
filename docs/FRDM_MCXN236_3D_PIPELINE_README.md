# FRDM-MCXN236 3D model pipeline: the whole process

This is a single-page walkthrough of how the NXP FRDM-MCXN236's 3D/AR asset
was actually produced, start to finish, for anyone picking this up cold. It
links out to the detailed docs for each stage rather than repeating them.
Every step below was actually run and its real output verified (rendered and
visually inspected, or checked against disk/BOM data) -- nothing here is a
plan that wasn't executed.

## The pipeline

```text
NXP FRDM-MCXN236 Rev C
  (official schematic PDF + design-files ZIP, account-gated,
   already present in datasets/local/frdm-mcxn236/intake/nxp/)
        |
        v
Allegro .brd  --[KiCad 10.0.5, kicad-cli pcb import]-->  .kicad_pcb
        |                                                    |
        |                                                    v
        |                                        board-substrate-only
        |                                        GLB/STEP/VRML export
        |                                        (2 nodes / 1 mesh)
        v
tools/kicad_pcb_placements.py
  (direct s-expression parse -- kicad-cli's own "pcb export pos"
   is unreliable for this Allegro-imported board, see below)
        |
        v
391 real reference designators, position/rotation/side
        |
        v
tools/build_frdm_reference_package.py
  + SCH-90828_C.BOM (plain text: no package data)
        |
        v
100% BOM cross-check + 190/190 non-component footprints classified
(187 graphic/mechanical, 3 real fiducials)
        |
        v
tools/xlsx_bom_parser.py + tools/component_model_resolver.py
  + SCH-90828_C.BOM.xlsx (richer columns: Description/MFG/MFG_PN/VALUE)
  + C:/Program Files/KiCad/10.0/share/kicad/3dmodels (real installed library)
        |
        v
316/391 (80.8%) components resolved to real, disk-verified KiCad 3D models
        |
        v
tools/attach_3d_models_text.py (direct text splice, not pcbnew scripting)
        |
        v
frdm_mcxn236_populated.glb: 634 nodes / 317 meshes
  (rendered top + isometric, visually confirmed: real resistor/capacitor/
   SOT-23/LED/pin-header bodies, correctly seated, nothing floating/mirrored)
        |
        v
Real iQOO photos + NXP Quick Start Guide -> semantic identity confirmation
for 4 Tier 1 components (U1, U8, J10, J11) -- no new 3D geometry from this,
identity confirmation only
```

## Why each non-obvious step exists

**Why parse the `.kicad_pcb` text directly instead of using `kicad-cli pcb
export pos`?** The Allegro importer doesn't populate the footprint's
canonical reference field that `pos` export reads -- every row came back
empty or the literal string `${REFERENCE}`. The real designators exist as
`property "Reference"` text on each footprint; `tools/kicad_pcb_placements.py`
reads that directly. Full detail: `docs/FRDM_CAPTURE_INTAKE.md`.

**Why the `.xlsx` BOM instead of the plain-text one?** The plain-text export
(`SCH-90828_C.BOM`) has only `Item, Quantity, AgilePN, ASSY_OPT, FIND#,
PRE_APN, Reference` -- no package data at all. The `.xlsx` version has four
more columns (`Description`, `MFG`, `MFG_PN`, `VALUE`) that embed real,
human-authored package designators (e.g. "CAP CER 4.7uF 16V 10% X5R 0603")
and real manufacturer part numbers. Full detail:
`docs/FRDM_MCXN236_POPULATED_MODEL.md`.

**Why text-splice instead of KiCad's own Python API?** `pcbnew.LoadBoard()`
surfaces load warnings as blocking GUI message boxes in this environment
(one per warning), which makes it unusable for automation here. The
text-splice approach finds each footprint's exact byte span via paren-depth
scanning and inserts a `(model ...)` node before its closing paren, leaving
every other byte untouched. Full detail:
`docs/FRDM_MCXN236_POPULATED_MODEL.md`.

**Why is it only 80.8%, not 100%?** The remaining 75 components are
overwhelmingly test points (28, genuinely no 3D body), mounting
holes/solder jumpers (9, not real mountable parts per their own BOM
description), and 29 Tier 1 parts (MCU, other major ICs, USB-C/audio/major
connectors, buttons, crystals) that are vendor-specific packages (BGA, QFN,
custom connector housings) with no KiCad library equivalent -- resolving
those would need real vendor CAD, sourced per-part, which this generic
resolver deliberately does not attempt. Full tiered breakdown:
`docs/FRDM_MCXN236_POPULATED_MODEL.md`.

**Why weren't the real iQOO photos used to help generate the 3D mesh
itself?** 22-25 phone photos are far too few for photogrammetric
reconstruction (which needs 50-150+ controlled overlapping captures) and
would produce worse geometry than the real CAD-derived model already built.
The photos were used only to visually confirm component identity (reading
silkscreen text) for 4 Tier 1 components, cross-checked against the BOM.
No image pixels were used to generate or approximate any 3D geometry. Full
detail: `docs/FRDM_MCXN236_POPULATED_MODEL.md` and
`docs/FRDM_MCXN236_SEMANTIC_MAP.md`.

## The tools, in the order they run

| Tool | Input | Output |
|---|---|---|
| `tools/kicad_pcb_placements.py` | imported `.kicad_pcb` | real reference designator, position, rotation, side, pad geometry, board outline per footprint |
| `tools/build_frdm_reference_package.py` | `.kicad_pcb` + plain-text BOM | `frdm_mcxn236_reference_package.json`: 391 components + 190 classified non-component footprints (fiducials/mechanical) |
| `tools/xlsx_bom_parser.py` | `.xlsx` BOM | per-reference `Description`/`MFG`/`MFG_PN`/`VALUE` records (generic, header-driven parsing) |
| `tools/component_model_resolver.py` | reference package + xlsx BOM + KiCad 3D library path + `--kicad-pcb` (footprint geometry for exact model registration) + `--overrides-json` (manually-evidenced models) | `component_model_resolution.json`: resolved model path (or null) + status + evidence + registered model transform (rotation/offset + registration method) per component |
| `tools/canonical_footprint.py` | our footprint's pads/fab outline + the KiCad-authored `.kicad_mod` for the same model | exact per-footprint model rotation and offset (numbered-pad or pad-geometry registration, body-side disambiguation) |
| `tools/attach_3d_models_text.py` | `.kicad_pcb` + resolution JSON | populated `.kicad_pcb` (models spliced in, placement untouched) |
| `kicad-cli pcb export glb/step/vrml` | populated `.kicad_pcb` | the actual 3D/AR assets |
| `tools/verify_populated_glb.py` (+ `tools/glb_inspect.py`) | resolution JSON + exported GLB | `3d_model_validation.json`: numeric per-component PASS/WARN/FAIL for position, body extent (catches rotation + scale together), board side, board contact, height sanity, and node-name uniqueness |
| `tools/model_coverage_report.py` | resolution JSON | `3d_model_completeness_report.json`: every reference classified from the vendor BOM's own text (physical vs "NO PART TO ORDER" bare-copper features), model quality per physical component (exact / verified approximation / dimension-accurate placeholder / missing), coverage % of physical parts only |
| `tools/package_3d_generator.py` | JSON records of datasheet-verified package dimensions (source + evidence required) | one parametric VRML body per PACKAGE (never per reference) + override rows for the resolver; placement still comes only from the imported board |
| `tools/glb_embed_metadata.py` | GLB + component index + resolution JSON | AR-ready GLB with per-node glTF `extras` (reference, class, package, BOM identity, board position/rotation/side, model quality, provenance) -- the AR runtime answers "what is this node" from the GLB alone |
| `tools/verify_component_highlightability.py` | GLB + resolution JSON | `3d_component_highlightability.json`: proves each component can be highlighted *independently* (unique selector, non-empty subtree, no node or mesh shared with another component, finite bounds) |
| `tools/build_digital_twin_package.py` | AR GLB + reference package + index + coverage | `frdm_mcxn236_digital_twin_manifest.json` (conforms to `schemas/digital-twin.asset-manifest.schema.json`) + `frdm_mcxn236_component_map.json` (canonical `component_id` for the vision/LLM/AR lanes) |
| `tools/render_component_previews.py` | unpopulated `.kicad_pcb` + resolution JSON | per-component isolation PNGs (that component's model attached and no other) for human visual QA |
| `kicad-cli pcb render` | populated `.kicad_pcb` | top/isometric PNG for visual verification |

## AR runtime compatibility (what the Android app actually requires)

The Android AR runtime on `main` (`android/ar-runtime/.../DigitalTwinAsset.kt`,
`docs/DIGITAL_TWIN_AR.md`) does not accept a bare GLB. It consumes a
**package**: a GLB plus a manifest conforming to
`schemas/digital-twin.asset-manifest.schema.json`. Two requirements are easy
to miss and both are now met by the pipeline rather than by hand:

- **Component nodes must carry the manifest's `componentNodePrefix`** (the
  runtime's default is `ref:`), so selection uses `ref:U1`, not `U1`.
  `glb_embed_metadata.py --node-prefix ref:` applies it at packaging time,
  leaving the plain KiCad export untouched.
- **Bounds must be strictly non-degenerate on every axis**
  (`Bounds3Contract.isValid()` requires `maximum > minimum` for x, y and z).
  The export contains coincident zero-thickness soldermask/silkscreen layer
  nodes with the same footprint as the substrate, so the manifest builder
  selects the widest node *that has real thickness*; picking the flat one
  would produce a manifest the device rejects.

Loading is bounded to 64 MiB. The full-fidelity GLB (copper tracks, zones,
silkscreen) is 46.7 MB -- inside the cap but heavy for a phone -- so the AR
asset is exported without copper/zone/silkscreen geometry at **16.4 MB**,
with identical component nodes and placement. Both remain available; the AR
package uses the light one.

### Canonical component identity

`frdm_mcxn236_component_map.json` gives every reference one stable
`component_id` of the form `<board_id>:<board_revision>:<reference>` (e.g.
`nxp-frdm-mcxn236:C:U1`), alongside its GLB selector, board placement, BOM
identity, package and model quality. This is the bridge the vision (YOLO),
dialogue (LLM) and AR lanes share, so none of them has to parse another
lane's private string format: the detector proposes a component, the
dialogue layer resolves a request to a `component_id`, and the AR layer
highlights `glb_selector`.
| `tools/component_info.py` | reference package + resolution JSON + xlsx BOM + `.kicad_pcb` | `board_component_index.json`: per-component join of placement, BOM facts, GLB node name, per-pad nets and full net connectivity |

## The component information index (product query surface)

`board_component_index.json` is the deterministic layer the user-facing
features stand on. For every component it joins only facts that exist in
real artifacts:

- **locate + highlight** ("where is the main chip"): board position,
  side, rotation, and the GLB node name (equal to the reference
  designator; null when no model is attached, never a fake claim) for
  isolating the mesh in the 3D viewer / AR overlay. Querying `U1`
  returns the NXP `MCXN236VDFT` MCU with its BOM description for the
  LLM lane to ground its explanation in.
- **connection guidance** ("where/how do I connect this diode"):
  `get_component_info()` lists, per pad, the copper net and every other
  `(reference, pad)` endpoint on that net -- answered from the imported
  board's own net assignments, not inference. Direction: diode polarity
  is populated only from the vendor footprint's own pad names (the
  Allegro import names diode pads literally `A`/`C`; 10 diodes on this
  board), and stays null otherwise -- semantics are never invented.
- **search**: substring match over designators/BOM text
  (`--search diode`). Semantic queries stay in the LLM lane, grounded
  on this index.

The index deliberately contains **structured facts only** -- prose
explanations (LLM lane) and on-screen highlighting (AR lane) are the
consumers, not part of this artifact.

### Connection guidance (`get_connection_guidance()`)

`component_info.py --connection-guidance <REF>` answers "where and how
does this component connect" deterministically, per terminal:

- the copper **net** and every other `(reference, pad)` endpoint on it,
  each with its board position, GLB node name, and the geometric
  **direction vector** from the queried component to that endpoint
  (board mm, straight from the two authored placements) -- the data an
  AR arrow or an LLM sentence is built from;
- the terminal **role** (`anode`/`cathode`) *only* when the vendor
  footprint itself names the pads (`polarity_status:
  "source_verified"`); otherwise `polarity_status:
  "not_available_from_source"` and roles stay null -- direction
  instructions must not be given for such parts;
- honest endpoint truncation (`endpoint_count_total`,
  `endpoints_truncated`) for large nets (GND has 236 endpoints), and an
  explicit `unknown_reference` status instead of a guess.

Real-board example: `D1` (Vishay `MSS1P3L` Schottky) reports anode on
`P5V_USB_HS` (toward `L3`/`C40`/`TP14`) and cathode on `P5V0`;
`D14` (a dual CAN protector with pads 1/2/3) correctly refuses polarity
roles because its footprint provides none.

## Where the outputs live

Everything generated is under `build/ar-assets/frdm-mcxn236/generated/`
(gitignored, never committed):

- `frdm_mcxn236_populated.glb` -- the AR/render handoff asset
- `LAY-90828_C_populated.kicad_pcb` -- the spliced board file
- `frdm_mcxn236_reference_package.json` -- the 391-component + fiducial data
- `component_model_resolution.json` -- the per-component model map
- `unresolved_component_report.json` -- the 75 still-unresolved components, tiered
- `frdm_mcxn236_populated_render_top.png` / `..._render_iso.png` -- visual verification renders

## What this pipeline is explicitly not

- Not a fully populated board (see the 80.8% breakdown above).
- Not board registration, a detector, or AR rendering. `tools/component_locator.py`'s
  `locate_component()` consumes the reference package directly and does not
  depend on the populated model's completeness.
- Not touching the YOLO labeling track (`datasets/local` label files) at any
  point in this pipeline.

## Further reading, in the order a newcomer should read them

1. `docs/FRDM_CAPTURE_INTAKE.md` -- capture intake, board-only 3D, why `pos` export fails
2. `docs/FRDM_MCXN236_REFERENCE_PACKAGE.md` -- the 391-component reference package and footprint classification
3. `docs/FRDM_MCXN236_POPULATED_MODEL.md` -- the model resolver, text-splice attacher, coverage numbers, photo-identity confirmation
4. `docs/FRDM_MCXN236_SEMANTIC_MAP.md` -- QSG-derived region names cross-checked against real refdes
