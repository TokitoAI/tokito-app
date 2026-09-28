# FRDM-MCXN236 photo-vs-model validation

This is a sanity check, not a geometry edit: does each Tier 1 landmark's
**real** placement (from `frdm_mcxn236_reference_package.json`, sourced only
from the imported `.kicad_pcb`) plausibly match where that feature is
visible in the real iQOO photos in
`datasets/local/frdm-mcxn236/intake/nxp/`? No coordinate is adjusted based
on this check. It either confirms the existing data is self-consistent, or
it flags a real problem to investigate -- it does not replace the CAD/BOM
as the coordinate source.

## Method

The real board outline spans `x: [-3.0, 115.0]` mm, `y: [-52.0, 3.0]` mm (a
~118mm x ~55mm elongated board, matching an Arduino/FRDM-shield form
factor). For each Tier 1 component, its real centroid was compared
qualitatively against its visible position in `IMG_20260829_165733.jpg` and
`IMG_20260829_165834.jpg` (both clear top-side views, opposite framing).

## Results

| Landmark | Reference | Real position (mm) | Visible in photo | Verdict |
|---|---|---|---|---|
| MCU | `U1` | x=60.7, y=-24.5 (board-length-center) | Chip marked "NXP MCXN236V CTAPA" sits away from either board end, roughly central | **consistent** |
| MCU-Link IC | `U8` | x=22.2, y=-24.9 (near the x=0 edge) | Chip marked "LPC55S69" sits close to the same short edge as the two USB-C ports | **consistent** |
| MCU USB / MCU-Link USB | `J10` (x=3.1, y=-36.6), `J11` (x=3.1, y=-21.5) | both at the x=0 edge | Both "GCT"-branded USB-C receptacles visible together at one short edge, next to the MCU-Link IC | **consistent** (connector identity confirmed; which of J10/J11 is specifically "MCU" vs "MCU-Link" not independently re-derived) |
| RGB LED | `D13` | x=94.7, y=-6.6 (near the x-max, y-max corner) | A small labelled "RGB" region visible near one corner of the board in the same photos | **consistent** |
| SW1/SW2/SW3 (buttons) | x=5.8/-47.9, x=104.6/-47.9, x=104.6/-1.1 (three different corners) | Silkscreen "ISP SW3" visible next to a button near one corner | **consistent for SW3**; SW1/SW2 not independently re-confirmed by silkscreen text in the inspected photos |
| Header pairs (J1/J2 vs J3/J4) | J1/J2 at y=-46 (near y-min edge), J3/J4 at y=-2.9 (near y-max edge) | Two rows of tall header connectors visible running along the two long edges of the board | **consistent** (opposite-edge header layout matches a shield/FRDM form factor) |
| Accelerometer, DMIC, CAN transceiver, external flash | `U10`, `U7`, `U11`, `U12` | clustered on the right half of the board (x=48-94) | Small ICs visible in that region, but package markings too small to read at this photo resolution/angle | **cannot verify identity from photo** (positions are plausible given their reference-designator prefix and BOM description, but not individually confirmed by legible marking) |
| PMOD / Aux Input / Headphone jacks | `J7` (x=95.4) and the two audio jacks | Labelled "PMOD", "Aux Input", "Headphone" regions visible in `IMG_20260829_165834.jpg` | **consistent** (labels visible and on the same side of the board as `J7`'s real x-coordinate) |
| SmartDMA/Camera header, mikroBUS, FlexIO/LCD header | `J9`, `J5`/`J6`, `J8` | J9 at x=99 (near J7), J8 at x=110 (far edge) | Corresponding silkscreen-labelled header blocks visible in the photos, general layout matches | **consistent** |

## Summary

Every Tier 1 landmark that could be checked from these two photos is
**spatially consistent** with its real CAD-derived coordinate -- nothing
suggests a mirrored, rotated, or mis-scaled placement. The items marked
"cannot verify" are honestly incomplete because the photo resolution/angle
doesn't resolve small IC package text, not because anything looks wrong;
this is the same limitation already documented in
`docs/FRDM_MCXN236_SEMANTIC_MAP.md`.

**No coordinate was changed as a result of this check.** Placement remains
sourced exclusively from `LAY-90828_C.brd` via the imported `.kicad_pcb`, as
documented in `docs/FRDM_CAPTURE_INTAKE.md` and
`docs/FRDM_MCXN236_REFERENCE_PACKAGE.md`.
