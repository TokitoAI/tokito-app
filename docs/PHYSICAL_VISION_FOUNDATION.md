# Physical Vision Foundation

This document describes the first board-discovery implementation slice for
Wave B. It supports issue #40 by proving the structured contracts needed for
automatic PCB discovery, board-plane registration, and ROI output without
claiming Android CameraX or physical FRDM-MCXN236 validation.

## Implemented

- `tools/vision_foundation/` contains an offline reference pipeline.
- Input frames use explicit `frame_id`, image dimensions, monotonic timestamp,
  coordinate frame, pixels, board profile, and detector configuration.
- `ThresholdBoardDetector` finds a bright board-like region in a synthetic
  grayscale fixture and returns a typed detection result with confidence,
  source frame, and polygon frame.
- `register_board` converts the detected image-space rectangle into a declared
  board coordinate frame using an explicit 2D transform with a stable revision
  digest.
- ROI helpers emit a whole-board visible region in board coordinates and its
  projected image-space equivalent.
- The CLI provides an end-to-end local command that reads a fixture and emits
  a structured observation or typed abstention/error.

## Not Implemented

- No live CameraX, Android lifecycle, AR renderer, or iQOO hardware path.
- No model, VLM, or learned component detector.
- No real NXP FRDM-MCXN236 source package, component coordinates, or captures.
- No component identity resolution such as `D1`, `R14`, or `U1`.

Those remain separate implementation and validation work. Real FRDM-MCXN236
validation belongs to #45 and requires official NXP artifacts, the physical
board revision, and held-out iQOO capture sessions.

## Coordinate Frames

The reference pipeline uses two explicit frames:

- `upright-image-pixels` for normalized image coordinates;
- a caller-supplied board frame such as `synthetic-board-mm`.

The board profile supplies the board dimensions, units, and coordinate-system
label as data. The pipeline currently supports millimetre, right-handed board
profiles. Unsupported units or coordinate systems fail closed.

## Confidence And Abstention

Detector thresholds are required input fields, not hidden tuning constants. If
the detected area or confidence is too low, the pipeline returns
`abstained` with no positive ROI claim. Invalid frame geometry returns a typed
error/abstention path instead of fabricating board state.

## NXP/FRDM Role

NXP and FRDM-MCXN236 appear only in documentation as the future validation
target. Runtime logic and tests use synthetic data and arbitrary board profile
IDs so the code path remains board-agnostic.

## Local Commands

Run the unit tests:

```bash
python -m unittest discover -s tools -p "test_*.py"
```

Run the pipeline manually:

```bash
python -m tools.vision_foundation.cli \
  --input tools/vision_foundation/fixtures/synthetic_board_frame.valid.json
```
