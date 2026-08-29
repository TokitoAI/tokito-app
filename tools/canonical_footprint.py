#!/usr/bin/env python3
"""Register an imported footprint's pads against the KiCad-authored
canonical footprint for the same library model.

KiCad's library 3D bodies are authored to sit over their own footprint's
pad layout (each `.kicad_mod` attaches its model with zero transform).
Allegro-imported footprints keep pin 1 at the origin like KiCad's, but
their pad fields extend in per-footprint directions (rows along +X, -X,
or +Y), so no single family-level rotation is correct. This module
computes the exact per-footprint model transform by matching *numbered*
pads: try z-rotations {0, 90, 180, 270} of the canonical pad layout and
pick the one that lands each canonical pad on the imported pad with the
same number. The rotation and any residual translation both come from
real data on both sides -- nothing is guessed, and a footprint whose pads
cannot be registered (average residual above tolerance) is reported
unregistered rather than silently mis-attached.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

from kicad_pcb_placements import find_all, find_direct, node_head, parse, tokenize


def canonical_footprint_path(model_path: str) -> Path | None:
    """Maps an installed KiCad 3D model path to its authored footprint:
    .../<Lib>.3dshapes/<Name>.step -> .../footprints/<Lib>.pretty/<Name>.kicad_mod
    Returns None when the mapping does not exist on disk."""
    model = Path(model_path)
    library_dir = model.parent.name
    if not library_dir.endswith(".3dshapes"):
        return None
    share_root = model.parent.parent.parent  # .../share/kicad
    footprint = share_root / "footprints" / (library_dir[: -len(".3dshapes")] + ".pretty") / (model.stem + ".kicad_mod")
    return footprint if footprint.is_file() else None


def parse_footprint_pads(path: Path) -> dict[str, tuple[float, float]]:
    """Numbered pad positions from a .kicad_mod file (footprint-local mm)."""
    tree = parse(tokenize(path.read_text(encoding="utf-8")))
    pads: dict[str, tuple[float, float]] = {}
    for pad in find_all(tree, "pad"):
        number = pad[1] if len(pad) > 1 else ""
        at = find_direct(pad, "at")
        if number and at is not None and len(at) >= 3:
            pads[str(number)] = (float(at[1]), float(at[2]))
    return pads


def _rotate(point: tuple[float, float], degrees: int) -> tuple[float, float]:
    if degrees == 0:
        return point
    if degrees == 90:
        return (-point[1], point[0])
    if degrees == 180:
        return (-point[0], -point[1])
    if degrees == 270:
        return (point[1], -point[0])
    raise ValueError(degrees)


def register_pads(
    our_pads: dict[str, tuple[float, float]],
    canonical_pads: dict[str, tuple[float, float]],
    tolerance_mm: float = 0.5,
) -> dict[str, Any] | None:
    """Finds the z-rotation (0/90/180/270) plus translation mapping the
    canonical numbered pads onto ours. Returns None when no rotation
    registers every shared pad within tolerance."""
    shared = sorted(set(our_pads) & set(canonical_pads))
    if len(shared) == 1 and len(our_pads) == 1 and len(canonical_pads) == 1:
        # One pad cannot constrain rotation, but a single-pad part (test
        # point, 1x01 pin) is rotationally symmetric about it; pin-to-pin
        # translation is still exact.
        number = shared[0]
        return {
            "method": "single_pad",
            "rotate_z_deg": 0,
            "offset_x_mm": our_pads[number][0] - canonical_pads[number][0],
            "offset_y_mm": our_pads[number][1] - canonical_pads[number][1],
            "max_residual_mm": 0.0,
            "matched_pads": 1,
        }
    if len(shared) < 2:
        return None
    best: dict[str, Any] | None = None
    for degrees in (0, 90, 180, 270):
        rotated = {n: _rotate(canonical_pads[n], degrees) for n in shared}
        # translation that maps rotated canonical pad field onto ours
        dx = sum(our_pads[n][0] - rotated[n][0] for n in shared) / len(shared)
        dy = sum(our_pads[n][1] - rotated[n][1] for n in shared) / len(shared)
        residual = max(
            math.hypot(our_pads[n][0] - (rotated[n][0] + dx), our_pads[n][1] - (rotated[n][1] + dy))
            for n in shared
        )
        if residual <= tolerance_mm and (best is None or residual < best["max_residual_mm"]):
            best = {
                "method": "numbered_pads",
                "rotate_z_deg": degrees,
                "offset_x_mm": dx,
                "offset_y_mm": dy,
                "max_residual_mm": residual,
                "matched_pads": len(shared),
            }
    return best


def _match_point_sets(
    our_points: list[tuple[float, float]],
    candidate_points: list[tuple[float, float]],
    tolerance_mm: float,
) -> float | None:
    """Max nearest-neighbour distance of a bijective matching between the two
    equal-sized point sets, or None if any point has no partner within
    tolerance. Pads sit on grids whose pitch far exceeds the tolerance, so
    greedy nearest-neighbour matching is unambiguous here."""
    if len(our_points) != len(candidate_points):
        return None
    remaining = list(candidate_points)
    worst = 0.0
    for point in our_points:
        distances = [math.hypot(point[0] - c[0], point[1] - c[1]) for c in remaining]
        best_index = min(range(len(distances)), key=distances.__getitem__)
        if distances[best_index] > tolerance_mm:
            return None
        worst = max(worst, distances[best_index])
        remaining.pop(best_index)
    return worst


def _centroid(points: list[tuple[float, float]]) -> tuple[float, float]:
    return (
        sum(p[0] for p in points) / len(points),
        sum(p[1] for p in points) / len(points),
    )


def register_pad_geometry(
    our_pads: dict[str, tuple[float, float]],
    canonical_pads: dict[str, tuple[float, float]],
    our_body_overhang: tuple[float, float] | None,
    canonical_body_overhang: tuple[float, float] | None,
    tolerance_mm: float = 0.5,
    overhang_meaningful_mm: float = 0.8,
) -> dict[str, Any] | None:
    """Registration by pad positions alone, for footprints whose pin
    NUMBERING differs from KiCad's (mirrored numbering cannot be produced by
    any z-rotation, but the physical pad field still matches one). When
    several rotations map the pad field onto itself (e.g. a 2xN grid under
    0/180), the body-overhang vector (body outline centre minus pad centroid,
    from each side's own fab/courtyard graphics) picks the physically correct
    one. Returns None rather than guessing when the evidence cannot decide."""
    if len(our_pads) < 2 or len(our_pads) != len(canonical_pads):
        return None
    our_points = list(our_pads.values())
    our_centroid = _centroid(our_points)
    candidates: list[dict[str, Any]] = []
    for degrees in (0, 90, 180, 270):
        rotated = [_rotate(p, degrees) for p in canonical_pads.values()]
        rotated_centroid = _centroid(rotated)
        dx = our_centroid[0] - rotated_centroid[0]
        dy = our_centroid[1] - rotated_centroid[1]
        translated = [(p[0] + dx, p[1] + dy) for p in rotated]
        residual = _match_point_sets(our_points, translated, tolerance_mm)
        if residual is not None:
            candidates.append(
                {
                    "method": "pad_geometry",
                    "rotate_z_deg": degrees,
                    "offset_x_mm": dx,
                    "offset_y_mm": dy,
                    "max_residual_mm": residual,
                    "matched_pads": len(our_points),
                }
            )
    if not candidates:
        return None
    if len(candidates) == 1:
        candidates[0]["method"] = "pad_geometry_unique"
        return candidates[0]
    # several rotations fit the pad field -- disambiguate by body side
    if our_body_overhang is None or canonical_body_overhang is None:
        return None
    our_magnitude = math.hypot(*our_body_overhang)
    canonical_magnitude = math.hypot(*canonical_body_overhang)
    if our_magnitude < overhang_meaningful_mm or canonical_magnitude < overhang_meaningful_mm:
        # body is (near) symmetric over the pad field: every candidate places
        # the body identically for practical purposes; take the smallest
        # rotation deterministically.
        chosen = min(candidates, key=lambda c: (c["max_residual_mm"], c["rotate_z_deg"]))
        chosen["method"] = "pad_geometry_symmetric_body"
        return chosen
    scored = []
    for candidate in candidates:
        rotated_overhang = _rotate(canonical_body_overhang, candidate["rotate_z_deg"])
        dot = rotated_overhang[0] * our_body_overhang[0] + rotated_overhang[1] * our_body_overhang[1]
        scored.append((dot, candidate))
    scored.sort(key=lambda item: item[0], reverse=True)
    if scored[0][0] <= 0 or (len(scored) > 1 and scored[0][0] <= scored[1][0]):
        return None
    chosen = scored[0][1]
    chosen["method"] = "pad_geometry_with_body_side"
    return chosen


_BODY_LAYERS = ("F.Fab", "B.Fab", "F.CrtYd", "B.CrtYd")


def _graphics_bbox(node: list[Any]) -> dict[str, tuple[float, float, float, float]]:
    """Per-layer bounding boxes of a footprint's line/rect/poly/arc graphics."""
    points_by_layer: dict[str, list[tuple[float, float]]] = {}
    for child in node:
        if not isinstance(child, list):
            continue
        head = node_head(child)
        if head not in ("fp_line", "fp_rect", "fp_arc", "fp_poly", "fp_circle"):
            continue
        layer_node = find_direct(child, "layer")
        layer = str(layer_node[1]) if layer_node and len(layer_node) > 1 else ""
        points = points_by_layer.setdefault(layer, [])
        for key in ("start", "end", "mid", "center"):
            coordinate = find_direct(child, key)
            if coordinate is not None and len(coordinate) >= 3:
                points.append((float(coordinate[1]), float(coordinate[2])))
        pts_node = find_direct(child, "pts")
        if pts_node is not None:
            for xy in pts_node:
                if isinstance(xy, list) and node_head(xy) == "xy" and len(xy) >= 3:
                    points.append((float(xy[1]), float(xy[2])))
    return {
        layer: (
            min(p[0] for p in points),
            max(p[0] for p in points),
            min(p[1] for p in points),
            max(p[1] for p in points),
        )
        for layer, points in points_by_layer.items()
        if points
    }


def body_overhang_vector(
    footprint_node: list[Any], pads: dict[str, tuple[float, float]]
) -> tuple[float, float] | None:
    """Body-outline centre minus pad centroid, from the footprint's own fab
    (preferred) or courtyard graphics. Points toward the side the physical
    body extends beyond its pins; None when the footprint has no such
    graphics."""
    if not pads:
        return None
    boxes = _graphics_bbox(footprint_node)
    for layer in _BODY_LAYERS:
        if layer in boxes:
            min_x, max_x, min_y, max_y = boxes[layer]
            pad_centroid = _centroid(list(pads.values()))
            return ((min_x + max_x) / 2 - pad_centroid[0], (min_y + max_y) / 2 - pad_centroid[1])
    return None


def parse_canonical_footprint(path: Path) -> dict[str, Any]:
    """Numbered pads and body-overhang vector of a KiCad-authored footprint."""
    tree = parse(tokenize(path.read_text(encoding="utf-8")))
    pads: dict[str, tuple[float, float]] = {}
    for pad in find_all(tree, "pad"):
        number = pad[1] if len(pad) > 1 else ""
        at = find_direct(pad, "at")
        if number and at is not None and len(at) >= 3:
            pads[str(number)] = (float(at[1]), float(at[2]))
    return {"pads": pads, "body_overhang": body_overhang_vector(tree, pads)}


def load_board_footprint_geometry(kicad_pcb: Path) -> dict[str, dict[str, Any]]:
    """Footprint-local geometry for every real-reference footprint on a
    board: numbered pad positions, body-overhang vector, and pad-field
    bbox centre (the anchor for models authored centred at their origin)."""
    from kicad_pcb_placements import (
        extract_footprint_pads,
        extract_footprint_placement,
        parse_pcb_tree,
    )

    geometry: dict[str, dict[str, Any]] = {}
    for footprint in find_all(parse_pcb_tree(kicad_pcb), "footprint"):
        placement = extract_footprint_placement(footprint)
        if placement["is_placeholder_reference"]:
            continue
        pads = {
            str(pad["number"]): (pad["at_x_mm"], pad["at_y_mm"])
            for pad in extract_footprint_pads(footprint)
            if pad["number"]
        }
        pad_field_center = None
        if pads:
            xs = [p[0] for p in pads.values()]
            ys = [p[1] for p in pads.values()]
            pad_field_center = ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2)
        geometry[placement["reference"]] = {
            "pads": pads,
            "body_overhang": body_overhang_vector(footprint, pads),
            "pad_field_center": pad_field_center,
        }
    return geometry


def register_model(
    our_pads: dict[str, tuple[float, float]],
    our_body_overhang: tuple[float, float] | None,
    model_path: str,
) -> dict[str, Any] | None:
    """Full registration of an installed library model against one of our
    imported footprints: exact numbered-pad match first, then pad-geometry
    match with body-side disambiguation. Returns None (caller keeps its
    heuristic and reports the footprint as unregistered) when neither
    succeeds."""
    footprint_path = canonical_footprint_path(model_path)
    if footprint_path is None:
        return None
    canonical = parse_canonical_footprint(footprint_path)
    result = register_pads(our_pads, canonical["pads"])
    if result is None:
        result = register_pad_geometry(
            our_pads, canonical["pads"], our_body_overhang, canonical["body_overhang"]
        )
    if result is not None:
        result["canonical_footprint"] = str(footprint_path)
    return result
