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


_BOX_DIMS_PATTERN = __import__("re").compile(r"(\d+(?:\.\d+)?)x(\d+(?:\.\d+)?)x(\d+(?:\.\d+)?)mm")


def expected_body_extent_mm(row: dict[str, Any]) -> tuple[float, float] | None:
    """Expected footprint-local body extent (width, depth): the canonical
    footprint's fab outline for library models, or the dimensions encoded in
    a generated placeholder's provenance file name. None when neither source
    exists (extent check is then skipped, reported as such)."""
    model_path = row["model_path"]
    footprint = canonical_footprint_path(model_path)
    if footprint is not None:
        tree = parse(tokenize(footprint.read_text(encoding="utf-8")))
        boxes = _graphics_bbox(tree)
        for layer in ("F.Fab", "B.Fab", "F.CrtYd", "B.CrtYd"):
            if layer in boxes:
                min_x, max_x, min_y, max_y = boxes[layer]
                return (max_x - min_x, max_y - min_y)
        return None
    match = _BOX_DIMS_PATTERN.search(Path(model_path).name)
    if match:
        return (float(match.group(1)), float(match.group(2)))
    return None


def count_named_nodes(gltf: dict[str, Any]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for node in gltf.get("nodes", []):
        name = node.get("name")
        if name:
            counts[name] = counts.get(name, 0) + 1
    return counts


def component_checks(
    row: dict[str, Any],
    box: list[float],
    node_count: int,
    board_thickness_mm: float,
    center_tolerance_mm: float,
) -> dict[str, Any]:
    """Per-component PASS/WARN/FAIL checks: centre position, body extent
    (which catches rotation and scale errors together), board side, contact
    with the board surface, height sanity, and node-name uniqueness."""
    measured_center = ((box[0] + box[1]) / 2 * 1000.0, (box[4] + box[5]) / 2 * 1000.0)
    predicted_center = predicted_body_center_board_mm(row)
    deviation = math.hypot(measured_center[0] - predicted_center[0], measured_center[1] - predicted_center[1])
    checks: dict[str, Any] = {
        "position": "PASS" if deviation <= center_tolerance_mm else "FAIL",
        "position_deviation_mm": round(deviation, 3),
        "node_unique": "PASS" if node_count == 1 else "FAIL",
    }

    # extent: measured GLB x/z spans vs expected footprint-local extent,
    # swapped when total in-plane rotation is an odd multiple of 90.
    expected = expected_body_extent_mm(row)
    if expected is None:
        checks["extent"] = "SKIP_no_expected_dimensions"
    else:
        total_rotation = (float(row["rotation_degrees"]) + float(row["model_rotate_z_deg"])) % 180.0
        if min(abs(total_rotation - 90.0), abs(total_rotation + 90.0 - 180.0)) < 1.0:
            expected = (expected[1], expected[0])
        measured_extent = ((box[1] - box[0]) * 1000.0, (box[5] - box[4]) * 1000.0)
        worst = max(abs(measured_extent[0] - expected[0]), abs(measured_extent[1] - expected[1]))
        checks["extent_deviation_mm"] = round(worst, 3)
        # fab outlines vs true bodies (and protruding leads) legitimately
        # differ by a millimetre or two; larger says wrong rotation or model
        checks["extent"] = "PASS" if worst <= 2.0 else ("WARN" if worst <= 4.0 else "FAIL")

    # side + board contact + height sanity, in GLB metres (y up,
    # board bottom at y=0, top at y=board thickness)
    top_mm, bottom_mm = box[3] * 1000.0, box[2] * 1000.0
    board_top = board_thickness_mm
    if row["side"] == "top":
        checks["side"] = "PASS" if top_mm > board_top - 0.1 else "FAIL"
        checks["board_contact"] = "PASS" if bottom_mm <= board_top + 1.0 else "FAIL"
        height_above_board = top_mm - board_top
    else:
        checks["side"] = "PASS" if bottom_mm < 0.1 else "FAIL"
        checks["board_contact"] = "PASS" if top_mm >= -1.0 else "FAIL"
        height_above_board = -bottom_mm
    checks["height_above_board_mm"] = round(height_above_board, 3)
    checks["height_sane"] = "PASS" if 0.05 <= height_above_board <= 30.0 else "FAIL"

    statuses = [v for v in checks.values() if v in ("PASS", "WARN", "FAIL") or (isinstance(v, str) and v.startswith("SKIP"))]
    checks["verdict"] = "FAIL" if "FAIL" in statuses else ("WARN" if "WARN" in statuses else "PASS")
    return checks


def verify(
    resolution: dict[str, Any],
    glb: Path,
    tolerance_mm: float,
    board_thickness_mm: float = 1.6,
) -> dict[str, Any]:
    gltf = load_gltf_json(glb)
    bounds = node_world_bounds(gltf)
    node_counts = count_named_nodes(gltf)
    rows = [r for r in resolution["components"] if r.get("model_path")]
    checked, missing, deviations, validations = 0, [], [], []
    for row in rows:
        reference = row["reference"]
        box = bounds.get(reference)
        if box is None:
            missing.append(reference)
            validations.append({"reference": reference, "verdict": "FAIL", "position": "FAIL_node_missing"})
            continue
        checks = component_checks(row, box, node_counts.get(reference, 0), board_thickness_mm, tolerance_mm)
        checked += 1
        validations.append({"reference": reference, **checks})
        deviations.append(
            {
                "reference": reference,
                "deviation_mm": checks["position_deviation_mm"],
                "registration_method": (row.get("model_registration") or {}).get("method"),
            }
        )
    deviations.sort(key=lambda d: d["deviation_mm"], reverse=True)
    over = [d for d in deviations if d["deviation_mm"] > tolerance_mm]
    verdict_counts: dict[str, int] = {}
    for validation in validations:
        verdict_counts[validation["verdict"]] = verdict_counts.get(validation["verdict"], 0) + 1
    return {
        "glb": str(glb),
        "board_thickness_mm": board_thickness_mm,
        "components_with_models": len(rows),
        "checked_in_glb": checked,
        "missing_glb_nodes": missing,
        "tolerance_mm": tolerance_mm,
        "over_tolerance_count": len(over),
        "over_tolerance": over,
        "worst_10": deviations[:10],
        "max_deviation_mm": deviations[0]["deviation_mm"] if deviations else None,
        "verdict_counts": verdict_counts,
        "failures": [v for v in validations if v["verdict"] == "FAIL"],
        "warnings": [v for v in validations if v["verdict"] == "WARN"],
        "validations": sorted(validations, key=lambda v: v["reference"]),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify every model body in a populated GLB against its authored placement.")
    parser.add_argument("--resolution-json", required=True, type=Path)
    parser.add_argument("--glb", required=True, type=Path)
    parser.add_argument("--tolerance-mm", type=float, default=2.5, help="max allowed body-centre deviation; fab-outline vs true 3D body authoring differences are ~1-2mm")
    parser.add_argument("--board-thickness-mm", type=float, default=1.6, help="board thickness from the .kicad_pcb (general thickness)")
    parser.add_argument("--validation-output", type=Path, help="write the full per-component validation report here")
    args = parser.parse_args(argv)

    report = verify(json.loads(args.resolution_json.read_text(encoding="utf-8")), args.glb, args.tolerance_mm, args.board_thickness_mm)
    if args.validation_output:
        args.validation_output.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
        print(json.dumps({k: report[k] for k in ("components_with_models", "checked_in_glb", "max_deviation_mm", "verdict_counts", "missing_glb_nodes")}, indent=2))
        print(json.dumps({"failures": report["failures"], "warnings": [w["reference"] for w in report["warnings"]]}, indent=2))
    else:
        print(json.dumps(report, indent=2))
    return 0 if report["verdict_counts"].get("FAIL", 0) == 0 and not report["missing_glb_nodes"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
