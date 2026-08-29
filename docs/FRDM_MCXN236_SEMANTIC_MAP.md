# FRDM-MCXN236 semantic board map

This maps NXP's official Quick Start Guide (`MCXN236QSG.pdf`, in
`datasets/local/frdm-mcxn236/intake/nxp/`, not committed) region labels to
real reference designators, cross-checked against `SCH-90828_C.BOM.xlsx` and,
where noted, directly confirmed by reading the physical board in the real
iQOO photos. The documentation supplies *identity/naming*; the PCB/BOM data
supplies *coordinates and geometry*. This file never substitutes for either.

## Source documents actually used

| File | What it is | Useful for |
|---|---|---|
| `MCXN236QSG.pdf` (NXP Quick Start Guide, Document Number MCXN236QSG REV 1) | Official labeled board diagram (page 2) and full header pinout tables (page 3) | Semantic region names, J1-J9 header pin mapping |
| `FRDMECOPPR.pdf` | A general NXP marketing deck about the entire FRDM board *family* (Q1 2026) | Background only -- **no board-specific mechanical/dimensional data**, not used for 3D or identity mapping |
| 7 WhatsApp images | Phone photos of the printed QSG pages 2-3 | Redundant with the PDF (same content, lower quality); not new information |
| 22 `IMG_*.jpg` | Real iQOO photos of the physical board | Direct visual confirmation (see below) |

An attempt to fetch NXP's product page (`nxp.com/design/design-center/...`
and the short `nxp.com/FRDM-MCXN236` URL printed in the QSG itself) both
returned HTTP 404 via automated fetch -- likely bot-blocking or a
JS-rendered page a basic fetch can't execute. No web-scraped content is
included here; everything below comes from the two local documents and the
real photos.

## Region label -> reference designator

Confidence levels: **confirmed** (BOM manufacturer part number match, and/or
directly read off the physical board in a real photo), **high** (BOM
description + QSG label agree, not independently photo-verified),
**uncertain** (functionally clear, exact refdes not cross-checked).

| QSG label | Reference(s) | BOM part number | Confidence | Evidence |
|---|---|---|---|---|
| MCXN236VDFT (the MCU) | `U1` | `MCXN236VDFT` (NXP) | **confirmed** | Chip silkscreen reads "NXP MCXN236V CTAPA 2506 0P21K" in `IMG_20260829_165733.jpg`/`165834.jpg`, matching the BOM part number exactly |
| MCU-Link Debugger | `U8` | `LPC55S69JEV98` (NXP) | **confirmed** | Chip silkscreen reads "LPC55S69 0.JEV98 2 W737117 sS022031B" in the same photos, matching exactly |
| External Flash W25Q64JVSSIQ | `U12` | `W25Q64JVSSIQ` (Winbond) | high | BOM description "IC MEM SERIAL FLASH DUAL/QUAD SPI 64MBIT" matches the part number exactly; not independently photo-read (small package) |
| CAN Transceiver | `U11` | `TJA1057GTK/3Z` (NXP) | high | BOM description "IC XCVR CAN 5MBIT/S" |
| Accelerometer | `U10` | `FXLS8974CFR3` (NXP) | high | BOM description "IC ACCELEROMETER 3-AXIS 12BIT" |
| DMIC Sensor | `U7` | `SPK0641HT4H-1` | high | BOM description "IC MIC MEMS PDM DIGITAL SISONIC" |
| RGB LED | `D13` | `MHPA2525RGBDT-S` | high | BOM description "LED RED/GRN/BL TRI 20/20/20MA SMT" |
| MCU USB / MCU-Link USB | `J10`, `J11` (both) | `USB4085-GF-A` (GCT) | **confirmed** (connector identity), uncertain (which J-number is which function) | "GCT" printed on both USB-C shells in `IMG_20260829_165733.jpg`/`165834.jpg`, matching BOM manufacturer field exactly. Which physical port is "MCU USB" vs "MCU-Link USB" was not independently re-derived from board orientation -- do not assume a specific assignment without checking |
| SW1 Reset, SW2 Wakeup, SW3 ISP | `SW1`, `SW2`, `SW3` | `434123025826` (Wurth Elektronik) | high (SW3 confirmed by photo silkscreen "ISP SW3"; SW1/SW2 share the identical BOM line item, function names only from QSG) | |
| J1, J2, J3, J4 Headers | `J1`-`J4` | various (see reference package) | **confirmed** | QSG page 3 explicitly labels these J1-J4, matching the real board's own J1-J4 silkscreen/BOM designators directly (not just a functional guess) |
| Pmod | `J7` | -- | **confirmed** | QSG page 3: "J7 Pmod" |
| SmartDMA/Camera Header | `J9` | -- | **confirmed** | QSG page 3: "J9 SmartDMA" |
| FlexIO/LCD Header | `J8` | -- | **confirmed** | QSG page 3: "J8 FlexIO" |
| mikroBUS Headers | `J5`, `J6` | -- | **confirmed** | QSG page 3: "mikroBUS J6 J5" |
| CAN Header | not independently identified | -- | uncertain | QSG shows a distinct "CAN Header" region separate from J1-J4; not cross-checked against a specific reference designator in this pass |
| MCU SWD | not independently identified | -- | uncertain | Likely a small header near the MCU; not cross-checked |

## What this map is not

- Not a source of coordinates. Every reference designator's real position,
  rotation, and side still comes only from
  `build/ar-assets/frdm-mcxn236/generated/frdm_mcxn236_reference_package.json`
  (built from the actual imported `.kicad_pcb` + BOM), never from this
  document or the QSG image.
- Not a claim that every QSG-labeled region has been cross-checked. Rows
  marked "uncertain" are honestly incomplete, not guessed.
- Not new 3D geometry. Confirming that `U1` is the real MCXN236VDFT chip
  does not by itself provide a 3D model for it (see
  `docs/FRDM_MCXN236_POPULATED_MODEL.md` for why it remains unresolved).
