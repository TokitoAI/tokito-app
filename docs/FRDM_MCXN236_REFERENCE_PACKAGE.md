# FRDM-MCXN236 reference package (issue #45)

This document records the source provenance for the NXP FRDM-MCXN236
reference package and the current state of the held-out capture set. It does
not encode board-specific behavior into the runtime; all board content lives
in data files and the tools that build them from real inputs.

## Source provenance

| Artifact | Source | Revision | Digest |
|---|---|---|---|
| Schematic PDF (`SPF-90828.pdf`) | [NXP FRDM-MCXN236 product page](https://www.nxp.com/design/design-center/development-boards-and-designs/FRDM-MCXN236), account-gated NXP download | C | see `build/ar-assets/frdm-mcxn236/manifest.json` |
| Design files archive (`FRDM-MCXN236-DESIGN-FILES.zip`) | Same NXP product page, account-gated | C | see manifest |
| Allegro board (`LAY-90828_C.brd`) | Extracted from the design files archive above | C | see manifest |
| Schematic design files (`SCH-90828_C.DSN`, `.opj`) | Extracted from the design files archive above | C | see manifest |
| Bill of materials (`SCH-90828_C.BOM`) | Extracted from the design files archive above | C, dated 2024-04-10 per the file's own header | see manifest |

Retrieval date is not independently re-verified by this tooling session; the
files were already present in local intake before this work began. Redistribution
terms for NXP's account-gated design files have not been independently
confirmed — the reference-package bundle records this explicitly rather than
asserting a license this project has not checked (see `sources[].license` in
the generated bundle).

This satisfies part of #45's acceptance criteria (exact revision recorded and
matched to the official schematic/design archive; digests recorded). It does
not yet include the UM12041 user manual, which has not been retrieved in this
session.

## Reference package bundle

`tools/build_frdm_reference_package.py` builds a candidate assembly-context
bundle from the real imported board, matching the field shape used by the
native importer's golden fixtures (issue #44 / PR #68, not yet merged):

```bash
python tools/build_frdm_reference_package.py \
  --kicad-pcb build/ar-assets/frdm-mcxn236/generated/LAY-90828_C.kicad_pcb \
  --bom build/ar-assets/frdm-mcxn236/source/SCH-90828_C.BOM \
  --brd build/ar-assets/frdm-mcxn236/source/LAY-90828_C.brd \
  --board-id nxp-frdm-mcxn236 \
  --board-revision C \
  --output build/ar-assets/frdm-mcxn236/generated/frdm_mcxn236_reference_package.json
```

Real results from the actual board:

- **391 components** with a reference designator, centroid, rotation, and
  side, all recovered from `tools/kicad_pcb_placements.py`'s direct parse of
  the imported `.kicad_pcb` (see `docs/FRDM_CAPTURE_INTAKE.md` for why
  `kicad-cli pcb export pos` cannot be used instead).
- **100% BOM cross-check rate**: every one of the 391 recovered reference
  designators is present in the real `SCH-90828_C.BOM`, with its vendor part
  number recorded alongside. This is independent confirmation that the
  recovered designators are real, not fabricated.
- **Board outline as an ordered closed polygon of 43 `{x,y}` points**,
  chained from the 11 real `Edge.Cuts` segments (arc segments sampled
  along their true circle, computed from each arc's own real
  start/mid/end points). This is the exact shape PR #68's canonical
  importer requires (>=3 finite points, non-zero signed area); the
  enclosed area, 6486.6mm^2, independently sanity-checks against the
  board's ~118 x 55mm bounding box. The raw segments are preserved in
  `board.outline_segments` for provenance.
- **190 footprints with no usable reference designator**, all fully
  classified from real pad geometry only (no identity is asserted, only a
  geometric category and the measurement that produced it):
  - 187 have zero pads (`graphic_or_mechanical_no_pads`) — most likely
    silkscreen text, logos, or keepout graphics that Allegro represented as
    footprints.
  - **3 are `fiducial_candidate`**: each is a single unnamed (empty pad
    number) SMD circular pad, 1.524mm diameter, with no net connection at
    all. That combination — no electrical connection and no pad number —
    is the standard signature of an optical alignment fiducial, not a
    mounting hole (which is mechanical/through-hole) or a real component
    (which has a net). This was refined from an earlier diameter-only
    heuristic that left these 3 ambiguously "unclassified" at 1.52mm; the
    no-net/no-pad-number signature is decisive regardless of the exact
    diameter. Three fiducials is also a plausible, unremarkable count for
    a rectangular PCB (non-collinear placement for pick-and-place
    registration).
  - **These 3 fiducials are emitted as `landmarks`** in the bundle — the
    only landmarks this board's Allegro export supports. No
    `mounting_hole_candidate` was found (no through-hole pad on this board
    met that geometry), which is a real result, not a bug.
  - Zero footprints remain unclassified.
- The `footprint` field on each component is a geometry-derived descriptor
  (pad count and measured size), not an authoritative KiCad/JEDEC footprint
  name — the Allegro importer does not preserve one.
- Each component also carries a `footprint_bbox_mm` (`width`/`height`): the
  real union of every pad's local axis-aligned extent, in the footprint's
  own unrotated frame. This is what `locate_component()` (next section)
  uses to size an ROI from real geometry instead of a fixed constant.
  **9 of 391 components — `BH1`–`BH4` (battery holder) and `SJ1`–`SJ5`
  (solder jumpers) — have a zero-size bbox**, verified directly against
  their own parsed footprint nodes (not a proximity/grep artifact): their
  pads genuinely report `size: 0, 0` in the imported `.kicad_pcb`. This
  looks like the Allegro-to-KiCad importer not carrying over a custom pad
  shape's real geometry into the simple `size` field for these specific
  pad types. Their centroid/rotation/side positions are unaffected and
  still real (e.g. `BH1`–`BH4` sit at the real corners of a rectangular
  mounting pattern, cross-checked against the board outline). Consumers
  must treat `footprint_bbox_mm == {0, 0}` as "extent unknown", not "this
  component occupies zero space".

## What this bundle is not

The emitted JSON is explicitly `"status": "candidate_pending_importer_and_registration_validation"`:

- It has not been run through the native `import_assembly_context_from_json`
  importer (issue #44 / PR #68 is open but not yet merged). Field names
  follow that PR's golden fixture shape, but schema/conflict/integrity
  validation has not actually been performed by that code.
- `coordinate_frame_id` is `"kicad-import-native-mm"` — KiCad's raw imported
  PCB coordinate space. It has not been calibrated or verified against a
  physical board registration transform (issue #13, owned separately, not
  implemented here).
- `board.handedness` is declared `"left"` (the importer accepts only
  `left`/`right`), with the verification recorded in the bundle's
  `status_notes`: `docs/FRDM_MCXN236_PHOTO_VALIDATION.md` established
  that real placements render un-mirrored against the top-view iQOO
  photos, fixing the frame as x-right/y-down viewed from the top side --
  a left-handed triad with z toward the top-side viewer. The declaration
  is a required CLI input (`--handedness` + `--handedness-evidence`),
  never a tool default.
- `integrity.signature_id` remains `"not_yet_signed"`: per
  `docs/ASSEMBLY_CONTEXT.md` the platform integration owns the signature
  scheme and trusted-key policy, so a real signature cannot originate
  from this tooling. **This candidate must not be packaged into a
  production APK or activated by the runtime until it is signed and
  verifier-accepted** -- fabricating a signature id here would be
  exactly the placeholder-as-capability violation the repo forbids.

## Held-out capture set: current gap

Only one physical capture session exists
(`iqoo-2026-08-29-session01`, 22 images, unlabeled). `docs/FRDM_MCXN236_VALIDATION.md`'s
capture discipline requires multiple sessions across distance, perspective,
rotation, lighting, glare, occlusion, clutter, and motion, with development
and held-out sessions kept disjoint. That has **not** been satisfied yet —
this is real outstanding work for #45, not something this change claims to
have completed.
