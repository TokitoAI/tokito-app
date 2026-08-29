#!/usr/bin/env python3
"""Build the NXP FRDM-MCXN236 reference package for issue #45.

This tool is deliberately generic: it never branches on a board name. It
takes an imported .kicad_pcb, a vendor BOM text export, and source-file
digests, then produces:

1. a footprint classification report for footprints with no usable
   reference designator, based only on their real pad geometry;
2. a candidate assembly-context bundle JSON matching the field shape
   consumed by the native importer (`import_assembly_context_from_json`,
   issue #44) -- built entirely from parsed source data, with no invented
   coordinates, footprints, or identities.

The emitted bundle is explicitly a *candidate*, not an imported/validated
context: this tool does not run the Rust importer, and does not claim
schema or integrity validation that only that importer can perform.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Sequence

from kicad_pcb_placements import (
    extract_board_outline,
    extract_footprint_pads,
    extract_footprint_placement,
    find_all,
    parse_pcb_tree,
)


def _circumcenter(p1: tuple[float, float], p2: tuple[float, float], p3: tuple[float, float]) -> tuple[float, float] | None:
    """Center of the circle through three real points, or None if they are
    (near-)collinear."""
    ax, ay = p1
    bx, by = p2
    cx, cy = p3
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-9:
        return None
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return (ux, uy)


def _arc_intermediate_points(start: tuple[float, float], mid: tuple[float, float], end: tuple[float, float], samples: int = 8) -> list[tuple[float, float]]:
    """Points sampled along the true circular arc through three real points
    (KiCad gr_arc start/mid/end), from start to end passing through mid,
    excluding both endpoints. Derived deterministically from the arc's own
    real geometry -- nothing invented. Collinear degenerate arcs yield no
    intermediate points (a straight segment)."""
    center = _circumcenter(start, mid, end)
    if center is None:
        return []
    cx, cy = center
    radius = math.hypot(start[0] - cx, start[1] - cy)
    a_start = math.atan2(start[1] - cy, start[0] - cx)
    a_mid = math.atan2(mid[1] - cy, mid[0] - cx)
    a_end = math.atan2(end[1] - cy, end[0] - cx)

    def ccw_delta(from_angle: float, to_angle: float) -> float:
        delta = (to_angle - from_angle) % (2 * math.pi)
        return delta

    # Pick the sweep direction in which the mid angle is actually passed.
    if ccw_delta(a_start, a_mid) <= ccw_delta(a_start, a_end):
        sweep = ccw_delta(a_start, a_end)
        direction = 1.0
    else:
        sweep = ccw_delta(a_end, a_start)
        direction = -1.0

    points = []
    for i in range(1, samples + 1):
        angle = a_start + direction * sweep * (i / (samples + 1))
        points.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
    return points


def build_outline_polygon(segments: Sequence[dict[str, Any]], tolerance_mm: float = 0.01, arc_samples: int = 8) -> list[dict[str, float]]:
    """Chains real Edge.Cuts segments into one ordered closed polygon of
    {x, y} points -- the exact shape the canonical assembly-context
    importer requires (ordered, >=3 finite points, non-zero signed area).
    Segments are joined by endpoint matching within tolerance; arc segments
    contribute intermediate points sampled along their true circle. Raises
    rather than silently emitting a broken polygon if the segments do not
    form a single closed loop."""
    remaining = []
    for segment in segments:
        if "start" not in segment or "end" not in segment:
            raise ValueError(f"outline segment lacks start/end: {segment}")
        remaining.append(segment)
    if not remaining:
        raise ValueError("no outline segments")

    def pt(node: dict[str, float]) -> tuple[float, float]:
        return (node["x"], node["y"])

    def close_enough(a: tuple[float, float], b: tuple[float, float]) -> bool:
        return math.hypot(a[0] - b[0], a[1] - b[1]) <= tolerance_mm

    def segment_points(segment: dict[str, Any], flipped: bool) -> list[tuple[float, float]]:
        start, end = pt(segment["start"]), pt(segment["end"])
        if flipped:
            start, end = end, start
        if segment.get("type") == "gr_arc" and "mid" in segment:
            intermediate = _arc_intermediate_points(start, pt(segment["mid"]), end, arc_samples)
        else:
            intermediate = []
        return [start, *intermediate, end]

    first = remaining.pop(0)
    chain = segment_points(first, flipped=False)
    while remaining:
        current_end = chain[-1]
        for index, candidate in enumerate(remaining):
            if close_enough(pt(candidate["start"]), current_end):
                chain.extend(segment_points(candidate, flipped=False)[1:])
                remaining.pop(index)
                break
            if close_enough(pt(candidate["end"]), current_end):
                chain.extend(segment_points(candidate, flipped=True)[1:])
                remaining.pop(index)
                break
        else:
            raise ValueError(f"outline does not chain into a single loop; {len(remaining)} segment(s) unreachable from {current_end}")

    if not close_enough(chain[0], chain[-1]):
        raise ValueError("outline chain does not close back to its starting point")
    chain = chain[:-1]  # drop the duplicated closing point

    if len(chain) < 3:
        raise ValueError("closed outline has fewer than 3 points")
    twice_area = 0.0
    for (x1, y1), (x2, y2) in zip(chain, chain[1:] + chain[:1]):
        twice_area += x1 * y2 - x2 * y1
    if not math.isfinite(twice_area) or twice_area == 0.0:
        raise ValueError("outline polygon is degenerate (zero signed area)")

    return [{"x": x, "y": y} for x, y in chain]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def parse_bom(path: Path) -> dict[str, str]:
    """Parse a generic NXP-style fixed-column BOM text export.

    Returns a reference-designator -> vendor-part-number map. A record
    starts on a line whose first tab-separated field is a plain integer
    (the BOM item number); continuation lines carry only wrapped reference
    designators for the same item.
    """
    refdes_to_part: dict[str, str] = {}
    current_part = ""
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        fields = raw_line.split("\t")
        if not any(field.strip() for field in fields):
            continue
        first = fields[0].strip()
        if first.isdigit() and len(fields) >= 3:
            current_part = fields[2].strip()
            refs_field = fields[-1].strip()
        elif current_part and len(fields) >= 1:
            refs_field = fields[-1].strip()
        else:
            continue
        for refdes in refs_field.split(","):
            refdes = refdes.strip()
            if refdes:
                refdes_to_part[refdes] = current_part
    return refdes_to_part


def pad_signature(pads: Sequence[dict[str, Any]]) -> str:
    if not pads:
        return "0-pads"
    shapes = sorted({pad["shape"] for pad in pads})
    return f"{len(pads)}-pad:{'+'.join(shapes)}"


def classify_unresolved_footprint(pads: Sequence[dict[str, Any]]) -> tuple[str, str]:
    """Classify a footprint with no usable reference designator using only
    its real pad geometry. Returns (category, evidence). This never asserts
    a specific component identity -- only a geometric category and the
    measurement that produced it."""
    if not pads:
        return "graphic_or_mechanical_no_pads", "0 pads found in footprint"
    if len(pads) == 1:
        pad = pads[0]
        diameter = max(pad["size_w_mm"], pad["size_h_mm"])
        no_net = not pad["net"]
        no_pad_number = not pad["number"]
        if pad["shape"] == "circle" and pad["pad_type"] in ("thru_hole", "np_thru_hole") and diameter >= 1.5:
            return (
                "mounting_hole_candidate",
                f"1 {pad['pad_type']} circular pad, {diameter:.2f}mm diameter",
            )
        if pad["shape"] == "circle" and pad["pad_type"] == "smd" and no_net and no_pad_number:
            return (
                "fiducial_candidate",
                f"1 unnamed SMD circular pad, {diameter:.2f}mm diameter, no net connection "
                "(optical alignment marker signature: no electrical connection, no pad number)",
            )
        if diameter < 1.5:
            return (
                "fiducial_or_test_point_candidate",
                f"1 {pad['shape']} pad, {diameter:.2f}mm max dimension",
            )
        return "single_pad_unclassified", f"1 {pad['shape']} pad, {diameter:.2f}mm max dimension"
    return (
        "unresolved_component_like",
        f"{len(pads)} pads present ({pad_signature(pads)}); likely a real component whose reference text was not recovered",
    )


def synthetic_footprint_descriptor(pads: Sequence[dict[str, Any]]) -> str:
    """A geometry-derived footprint label. This is NOT an authoritative
    KiCad/JEDEC footprint name -- Allegro import does not preserve one --
    it is a deterministic descriptor of the real measured pad geometry."""
    if not pads:
        return "unknown-no-pads"
    widths = sorted(pad["size_w_mm"] for pad in pads)
    heights = sorted(pad["size_h_mm"] for pad in pads)
    return f"derived-{len(pads)}pad-{widths[-1]:.2f}x{heights[-1]:.2f}mm"


def footprint_bounding_box_mm(pads: Sequence[dict[str, Any]]) -> dict[str, float]:
    """The real measured envelope of every pad in this footprint's own
    (unrotated) local coordinate frame -- the union of each pad's axis-
    aligned extent (its local center +/- half its size). This ignores each
    pad's own individual rotation, which is a real approximation, not a
    fabricated value: it is the same simplification any bounding-box-based
    ROI has to make without per-pad rotation data.  Applying the
    footprint's own rotation_degrees happens later, when a consumer places
    this box in board or image coordinates."""
    if not pads:
        return {"width": 0.0, "height": 0.0}
    min_x = min(pad["at_x_mm"] - pad["size_w_mm"] / 2 for pad in pads)
    max_x = max(pad["at_x_mm"] + pad["size_w_mm"] / 2 for pad in pads)
    min_y = min(pad["at_y_mm"] - pad["size_h_mm"] / 2 for pad in pads)
    max_y = max(pad["at_y_mm"] + pad["size_h_mm"] / 2 for pad in pads)
    return {"width": max_x - min_x, "height": max_y - min_y}


def build_components(placements: Sequence[dict[str, Any]], bom: dict[str, str], source_id: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    components = []
    footprint_classification_rows = []
    for placement in placements:
        pads = placement["_pads"]
        if placement["is_placeholder_reference"]:
            category, evidence = classify_unresolved_footprint(pads)
            footprint_classification_rows.append(
                {
                    "footprint_uuid": placement["footprint_uuid"],
                    "x_mm": placement["x_mm"],
                    "y_mm": placement["y_mm"],
                    "side": placement["side"],
                    "category": category,
                    "evidence": evidence,
                    "pad_count": len(pads),
                }
            )
            continue
        components.append(
            {
                "reference_designator": placement["reference"],
                "footprint": synthetic_footprint_descriptor(pads),
                "footprint_bbox_mm": footprint_bounding_box_mm(pads),
                "centroid": {"x": placement["x_mm"], "y": placement["y_mm"]},
                "rotation_degrees": placement["rotation_deg"],
                "side": placement["side"],
                "polarity_or_pin_one": None,
                "alternates": [],
                "placement_source_id": source_id,
                "bom_part_number": bom.get(placement["reference"]),
                "bom_cross_check": placement["reference"] in bom,
            }
        )
    return components, footprint_classification_rows


def build_landmarks(footprint_classification_rows: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    landmarks = []
    for index, row in enumerate(footprint_classification_rows):
        if row["category"] in ("mounting_hole_candidate", "fiducial_or_test_point_candidate", "fiducial_candidate"):
            landmarks.append(
                {
                    "landmark_id": f"{row['category']}-{index}",
                    "position": {"x": row["x_mm"], "y": row["y_mm"]},
                    "category": row["category"],
                    "evidence": row["evidence"],
                }
            )
    return landmarks


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def build_reference_package(
    kicad_pcb: Path,
    bom: Path,
    brd: Path,
    board_id: str,
    board_revision: str,
    handedness: str,
    handedness_evidence: str,
) -> dict[str, Any]:
    if handedness not in ("left", "right"):
        raise ValueError("handedness must be the verified declaration 'left' or 'right'")
    if not handedness_evidence.strip():
        raise ValueError("a handedness declaration requires the evidence that verified it")
    tree = parse_pcb_tree(kicad_pcb)
    footprints = find_all(tree, "footprint")
    placements = []
    for footprint in footprints:
        placement = extract_footprint_placement(footprint)
        placement["_pads"] = extract_footprint_pads(footprint)
        placements.append(placement)

    bom_map = parse_bom(bom)
    source_id = f"{board_id}-allegro-brd"
    components, footprint_classification_rows = build_components(placements, bom_map, source_id)
    landmarks = build_landmarks(footprint_classification_rows)
    outline_segments = extract_board_outline(tree)
    outline = build_outline_polygon(outline_segments)

    classification_counts: dict[str, int] = {}
    for row in footprint_classification_rows:
        classification_counts[row["category"]] = classification_counts.get(row["category"], 0) + 1

    bundle = {
        "schema_version": {"major": 1, "minor": 0},
        "board": {
            "board_id": board_id,
            "revision": board_revision,
            "coordinate_frame_id": "kicad-import-native-mm",
            "units": "mm",
            "handedness": handedness,
            "outline": outline,
            "outline_segments": outline_segments,
        },
        "components": components,
        "landmarks": landmarks,
        "sources": [
            {
                "source_id": source_id,
                "role": "authored_placement",
                "license": "nxp_account_gated_vendor_design_file_redistribution_not_independently_verified",
                "digest": sha256_file(brd),
            },
            {
                "source_id": f"{board_id}-bom",
                "role": "datasheet",
                "license": "nxp_account_gated_vendor_design_file_redistribution_not_independently_verified",
                "digest": sha256_file(bom),
            },
        ],
        "integrity": {
            "payload_sha256": "",
            "signature_id": "not_yet_signed",
        },
        "status": "candidate_pending_importer_and_registration_validation",
        "status_notes": [
            "This bundle has not been run through the native import_assembly_context_from_json "
            "importer (issue #44 / PR #68 is not yet merged). Structural field names follow that "
            "PR's golden fixture, but acceptance is not proven here.",
            "coordinate_frame_id records KiCad's raw imported PCB coordinate space; it has not been "
            "calibrated or verified against physical board registration (issue #13, owned separately).",
            "footprint values are geometry-derived descriptors, not authoritative KiCad/JEDEC footprint "
            "names -- the Allegro importer does not preserve a footprint library identity.",
            "board.outline is an ordered closed polygon chained from the real Edge.Cuts segments, with "
            "arc segments sampled along their true circle (computed from each arc's own real "
            "start/mid/end points); the raw segments are preserved in board.outline_segments for "
            "provenance.",
            f"handedness evidence: {handedness_evidence}",
            "integrity.signature_id remains 'not_yet_signed': the platform integration owns the "
            "signature scheme and trusted-key policy (docs/ASSEMBLY_CONTEXT.md), so this candidate "
            "MUST NOT be packaged into a production APK or activated by the runtime until it is "
            "signed and verifier-accepted.",
        ],
        "footprint_classification_report": {
            "counts_by_category": classification_counts,
            "rows": footprint_classification_rows,
        },
    }

    payload_for_digest = {k: v for k, v in bundle.items() if k != "integrity"}
    digest = hashlib.sha256(canonical_json(payload_for_digest).encode("utf-8")).hexdigest()
    bundle["integrity"]["payload_sha256"] = f"sha256:{digest}"

    return bundle


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build the NXP FRDM-MCXN236 reference package candidate bundle.")
    parser.add_argument("--kicad-pcb", required=True, type=Path)
    parser.add_argument("--bom", required=True, type=Path)
    parser.add_argument("--brd", required=True, type=Path)
    parser.add_argument("--board-id", required=True)
    parser.add_argument("--board-revision", required=True)
    parser.add_argument("--handedness", required=True, choices=("left", "right"), help="The verified handedness declaration; the canonical importer rejects anything else.")
    parser.add_argument("--handedness-evidence", required=True, help="What verified the declaration (e.g. a photo-validation document reference).")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)

    bundle = build_reference_package(
        args.kicad_pcb, args.bom, args.brd, args.board_id, args.board_revision,
        args.handedness, args.handedness_evidence,
    )
    args.output.write_text(json.dumps(bundle, indent=2, sort_keys=True), encoding="utf-8")

    print(
        json.dumps(
            {
                "output": str(args.output),
                "component_count": len(bundle["components"]),
                "landmark_count": len(bundle["landmarks"]),
                "outline_point_count": len(bundle["board"]["outline"]),
                "outline_segment_count": len(bundle["board"]["outline_segments"]),
                "footprint_classification_counts": bundle["footprint_classification_report"]["counts_by_category"],
                "status": bundle["status"],
            },
            indent=2,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
