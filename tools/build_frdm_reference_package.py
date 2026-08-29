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
from pathlib import Path
from typing import Any, Sequence

from kicad_pcb_placements import (
    extract_board_outline,
    extract_footprint_pads,
    extract_footprint_placement,
    find_all,
    parse_pcb_tree,
)


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
        if pad["shape"] == "circle" and pad["pad_type"] in ("thru_hole", "np_thru_hole") and diameter >= 1.5:
            return (
                "mounting_hole_candidate",
                f"1 {pad['pad_type']} circular pad, {diameter:.2f}mm diameter",
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
        if row["category"] in ("mounting_hole_candidate", "fiducial_or_test_point_candidate"):
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
) -> dict[str, Any]:
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
    outline = extract_board_outline(tree)

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
            "handedness": "not_yet_verified_against_physical_board",
            "outline": outline,
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
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)

    bundle = build_reference_package(args.kicad_pcb, args.bom, args.brd, args.board_id, args.board_revision)
    args.output.write_text(json.dumps(bundle, indent=2, sort_keys=True), encoding="utf-8")

    print(
        json.dumps(
            {
                "output": str(args.output),
                "component_count": len(bundle["components"]),
                "landmark_count": len(bundle["landmarks"]),
                "outline_segment_count": len(bundle["board"]["outline"]),
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
