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
- **11 real board-outline segments** extracted from the `Edge.Cuts` layer.
- **190 footprints with no usable reference designator**, classified from
  real pad geometry only (no identity is asserted, only a geometric
  category and the measurement that produced it):
  - 187 have zero pads (`graphic_or_mechanical_no_pads`) — most likely
    silkscreen text, logos, or keepout graphics that Allegro represented as
    footprints.
  - 3 have exactly one ~1.52mm circular pad (`single_pad_unclassified`) —
    left unclassified rather than guessed, since 1.52mm sits at the
    boundary between this tool's mounting-hole and fiducial heuristics.
  - 0 met the `mounting_hole_candidate` or `fiducial_or_test_point_candidate`
    thresholds on this board, so **no landmarks were produced**. That is a
    real result, not a bug: this board's Allegro export does not appear to
    carry distinct fiducial/mounting-hole footprints with pads.
- The `footprint` field on each component is a geometry-derived descriptor
  (pad count and measured size), not an authoritative KiCad/JEDEC footprint
  name — the Allegro importer does not preserve one.

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
- `board.handedness` is left as `"not_yet_verified_against_physical_board"`
  rather than an asserted value, since this has not been independently
  confirmed against the real board.

## Held-out capture set: current gap

Only one physical capture session exists
(`iqoo-2026-08-29-session01`, 22 images, unlabeled). `docs/FRDM_MCXN236_VALIDATION.md`'s
capture discipline requires multiple sessions across distance, perspective,
rotation, lighting, glare, occlusion, clutter, and motion, with development
and held-out sessions kept disjoint. That has **not** been satisfied yet —
this is real outstanding work for #45, not something this change claims to
have completed.
