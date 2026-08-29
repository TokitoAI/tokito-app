#!/usr/bin/env python3
"""Systematic, numeric verification of a populated-board GLB export.

For every component with an attached model, predicts where its 3D body's
centre must land on the board -- from the footprint's authored placement,
the written model rotation/offset, and the canonical footprint's own body
outline (fab layer) -- then compares that against the body centre actually
measured from the exported GLB's node bounding boxes. This replaces
eyeballing hundreds of parts in renders with a per-component check.

Conventions used here were established empirically against kicad-cli
10.0.5 GLB exports (see docs): model rotate z is counterclockwise in
footprint-local file coordinates; the model offset is applied after
rotation, with the file's offset y flipped relative to pad coordinates
(dialog up-positive); GLB is metres with x = board x and z = board y.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

from canonical_footprint import _graphics_bbox, canonical_footprint_path, parse_canonical_footprint
from glb_inspect import load_gltf_json, node_world_bounds
from kicad_pcb_placements import parse, tokenize


def _rotate_deg(point: tuple[float, float], degrees: float) -> tuple[float, float]:
    r = math.radians(degrees)
    return (
        point[0] * math.cos(r) - point[1] * math.sin(r),
        point[0] * math.sin(r) + point[1] * math.cos(r),
    )


def canonical_body_center(model_path: str) -> tuple[float, float] | None:
    """Centre of the canonical footprint's fab (or courtyard) outline: the
    authored position of the model's body in footprint-local coordinates.
    None for models with no authored KiCad footprint (e.g. generated
    placeholder boxes, which are centred at their own origin)."""
    footprint = canonical_footprint_path(model_path)
    if footprint is None:
        return None
    tree = parse(tokenize(footprint.read_text(encoding="utf-8")))
    boxes = _graphics_bbox(tree)
    for layer in ("F.Fab", "B.Fab", "F.CrtYd", "B.CrtYd"):
        if layer in boxes:
            min_x, max_x, min_y, max_y = boxes[layer]
            return ((min_x + max_x) / 2, (min_y + max_y) / 2)
    return None


def predicted_body_center_board_mm(row: dict[str, Any]) -> tuple[float, float] | None:
    """Board-frame (file coordinates, mm) body centre predicted from the
    resolution row alone."""
    canonical_center = canonical_body_center(row["model_path"]) or (0.0, 0.0)
    local = _rotate_deg(canonical_center, float(row["model_rotate_z_deg"]))
    local = (local[0] + float(row["model_offset_x_mm"]), local[1] + float(row["model_offset_y_mm"]))
    rotation = math.radians(float(row["rotation_degrees"]))
    # footprint placement rotation in file coordinates (verified against GLB)
    return (
        row["x_mm"] + local[0] * math.cos(rotation) + local[1] * math.sin(rotation),
        row["y_mm"] - local[0] * math.sin(rotation) + local[1] * math.cos(rotation),
    )


def verify(resolution: dict[str, Any], glb: Path, tolerance_mm: float) -> dict[str, Any]:
    bounds = node_world_bounds(load_gltf_json(glb))
    rows = [r for r in resolution["components"] if r.get("model_path")]
    checked, missing, deviations = 0, [], []
    for row in rows:
        reference = row["reference"]
        box = bounds.get(reference)
        if box is None:
            missing.append(reference)
            continue
        measured = ((box[0] + box[1]) / 2 * 1000.0, (box[4] + box[5]) / 2 * 1000.0)
        predicted = predicted_body_center_board_mm(row)
        deviation = math.hypot(measured[0] - predicted[0], measured[1] - predicted[1])
        checked += 1
        deviations.append(
            {
                "reference": reference,
                "deviation_mm": round(deviation, 3),
                "predicted_mm": [round(predicted[0], 3), round(predicted[1], 3)],
                "measured_mm": [round(measured[0], 3), round(measured[1], 3)],
                "registration_method": (row.get("model_registration") or {}).get("method"),
            }
        )
    deviations.sort(key=lambda d: d["deviation_mm"], reverse=True)
    over = [d for d in deviations if d["deviation_mm"] > tolerance_mm]
    return {
        "glb": str(glb),
        "components_with_models": len(rows),
        "checked_in_glb": checked,
        "missing_glb_nodes": missing,
        "tolerance_mm": tolerance_mm,
        "over_tolerance_count": len(over),
        "over_tolerance": over,
        "worst_10": deviations[:10],
        "max_deviation_mm": deviations[0]["deviation_mm"] if deviations else None,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify every model body in a populated GLB against its authored placement.")
    parser.add_argument("--resolution-json", required=True, type=Path)
    parser.add_argument("--glb", required=True, type=Path)
    parser.add_argument("--tolerance-mm", type=float, default=2.5, help="max allowed body-centre deviation; fab-outline vs true 3D body authoring differences are ~1-2mm")
    args = parser.parse_args(argv)

    report = verify(json.loads(args.resolution_json.read_text(encoding="utf-8")), args.glb, args.tolerance_mm)
    print(json.dumps(report, indent=2))
    return 0 if report["over_tolerance_count"] == 0 and not report["missing_glb_nodes"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
