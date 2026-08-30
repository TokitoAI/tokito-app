#!/usr/bin/env python3
"""Build the AR digital-twin package: manifest + canonical component map.

The Android AR runtime consumes a GLB only through the manifest defined by
`schemas/digital-twin.asset-manifest.schema.json` (metre units, right-handed
Y-up axes, board origin and bounds, byte length, SHA-256 digest, and the
`componentNodePrefix` its selector uses). This tool derives every one of
those fields from the artifacts themselves -- the exported GLB's real bytes
and measured node bounds, and the board identity/revision from the
reference package -- so the manifest cannot drift from the asset it
describes.

It also emits `component_map.json`, the canonical identity bridge shared by
the vision (YOLO), dialogue (LLM) and AR lanes: one stable `component_id`
per component, plus the GLB selector, board placement, package/BOM identity
and model quality. Giving all three lanes one identifier avoids them having
to agree on raw string formats independently.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from glb_inspect import load_gltf_json, node_world_bounds

SCHEMA_VERSION = "1.0"
DEFAULT_NODE_PREFIX = "ref:"


def component_id(board_id: str, revision: str, reference: str) -> str:
    """Canonical cross-lane identifier, e.g. `nxp-frdm-mcxn236:C:U1`.

    Board identity and revision come from the reference package (which
    derives them from the vendor design data), never from a literal here."""
    return f"{board_id}:{revision}:{reference}"


def _vector(x: float, y: float, z: float) -> dict[str, float]:
    return {"x": round(x, 6), "y": round(y, 6), "z": round(z, 6)}


def asset_bounds_metres(gltf: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any] | None]:
    """Overall asset bounds and, separately, the board substrate's own
    bounds -- the runtime takes its metric corner landmarks from the latter,
    so component bodies standing proud of the board must not inflate it."""
    bounds = node_world_bounds(gltf)
    if not bounds:
        raise ValueError("GLB contains no bounded named nodes")
    min_x = min(b[0] for b in bounds.values())
    max_x = max(b[1] for b in bounds.values())
    min_y = min(b[2] for b in bounds.values())
    max_y = max(b[3] for b in bounds.values())
    min_z = min(b[4] for b in bounds.values())
    max_z = max(b[5] for b in bounds.values())
    overall = {"minimum": _vector(min_x, min_y, min_z), "maximum": _vector(max_x, max_y, max_z)}

    # The board substrate is the widest-area node in the X/Z (board) plane
    # that also has real thickness: the export contains coincident
    # zero-thickness layer nodes (soldermask/silkscreen) with an identical
    # footprint, and a degenerate Y extent fails the runtime's own
    # Bounds3Contract.isValid() (which requires maximum > minimum on every
    # axis), so those are excluded rather than shipped and rejected.
    substrate_candidates = [b for b in bounds.values() if b[3] - b[2] > 1e-6]
    if not substrate_candidates:
        raise ValueError("no node in the GLB has a non-degenerate vertical extent; cannot identify the board substrate")
    board_box = max(substrate_candidates, key=lambda b: (b[1] - b[0]) * (b[5] - b[4]))
    board_plane = {
        "minimum": _vector(board_box[0], board_box[2], board_box[4]),
        "maximum": _vector(board_box[1], board_box[3], board_box[5]),
    }
    return overall, board_plane


def corner_points_from_order(board_plane: dict[str, Any], corner_order: str) -> list[dict[str, float]]:
    """Explicit registration landmarks in the vision model's keypoint order.

    The detector owns the semantics of its four corners (which physical
    corner is "top_left"), so the manifest must state the board-plane
    corners in that convention rather than a bounds-derived default. The
    order string names four corners as `<minx|maxx>_<minz|maxz>` tokens,
    e.g. `minx_maxz,minx_minz,maxx_minz,maxx_maxz` -- established from a
    calibration observation of the actual model, never guessed. Values come
    from the authored board plane; only the ordering is model-specific."""
    tokens = [token.strip() for token in corner_order.split(",")]
    if len(tokens) != 4 or len(set(tokens)) != 4:
        raise ValueError(f"corner order must name four distinct corners, got {corner_order!r}")
    lo, hi = board_plane["minimum"], board_plane["maximum"]
    points = []
    for token in tokens:
        try:
            x_part, z_part = token.split("_")
            x = {"minx": lo["x"], "maxx": hi["x"]}[x_part]
            z = {"minz": lo["z"], "maxz": hi["z"]}[z_part]
        except (ValueError, KeyError):
            raise ValueError(f"corner token {token!r} must be <minx|maxx>_<minz|maxz>") from None
        points.append(_vector(x, lo["y"], z))
    return points


def build_manifest(
    glb_path: Path,
    reference_package: dict[str, Any],
    asset_id: str,
    assembly_context_revision: str,
    node_prefix: str,
    default_opacity: float,
    corner_order: str | None = None,
) -> dict[str, Any]:
    glb_bytes = glb_path.read_bytes()
    gltf = load_gltf_json(glb_path)
    overall, board_plane = asset_bounds_metres(gltf)
    board = reference_package.get("board") or {}
    board_id = board.get("board_id")
    revision = board.get("revision")
    if not board_id or not revision:
        raise ValueError("reference package is missing board.board_id or board.revision")

    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "assetId": asset_id,
        "boardIdentity": board_id,
        "boardRevision": revision,
        "assemblyContextRevision": assembly_context_revision,
        "glbSha256": "sha256:" + hashlib.sha256(glb_bytes).hexdigest(),
        "glbBytes": len(glb_bytes),
        "units": "METRE",
        "axes": "RIGHT_HANDED_Y_UP",
        # KiCad's GLB export places the board at the world origin already;
        # the origin is stated explicitly rather than assumed by the runtime.
        "boardOriginMetres": _vector(0.0, 0.0, 0.0),
        "boundsMetres": overall,
        "boardPlaneBoundsMetres": board_plane,
        "defaultOpacity": default_opacity,
        "componentNodePrefix": node_prefix,
    }
    if corner_order:
        manifest["boardPlaneCornerPointsMetres"] = corner_points_from_order(board_plane, corner_order)
    return manifest


def build_component_map(
    index: dict[str, Any],
    coverage: dict[str, Any],
    node_prefix: str,
    asset_id: str,
) -> dict[str, Any]:
    board_id = index["board_id"]
    revision = index["board_revision"]
    coverage_by_reference = {row["reference"]: row for row in coverage["references"]}

    entries = []
    for reference, component in index["components"].items():
        row = coverage_by_reference.get(reference, {})
        renderable = bool(component["glb_node_name"])
        bom = component.get("bom") or {}
        entries.append(
            {
                "component_id": component_id(board_id, revision, reference),
                "reference": reference,
                "component_class": component["component_class"],
                "category": row.get("category"),
                "needs_3d_body": row.get("needs_3d_body"),
                "package": row.get("package_code"),
                "bom_part_number": bom.get("part_number"),
                "bom_manufacturer": bom.get("manufacturer"),
                "bom_description": bom.get("description"),
                "board_position_mm": component["board_position_mm"],
                "rotation_degrees": component["rotation_degrees"],
                "side": component["side"],
                "glb_selector": f"{node_prefix}{reference}" if renderable else None,
                "model_quality": row.get("model_quality"),
                "model_file": row.get("model_file"),
                "provenance": "NXP FRDM-MCXN236 Rev C design data (LAY-90828_C.brd placement + SCH-90828_C BOM identity)",
            }
        )

    entries.sort(key=lambda e: e["reference"])
    return {
        "schema_version": "1.0",
        "board_id": board_id,
        "board_revision": revision,
        "asset_id": asset_id,
        "component_node_prefix": node_prefix,
        "component_id_format": "<board_id>:<board_revision>:<reference_designator>",
        "component_count": len(entries),
        "renderable_count": sum(1 for e in entries if e["glb_selector"]),
        "components": entries,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build the AR digital-twin manifest and canonical component map.")
    parser.add_argument("--glb", required=True, type=Path)
    parser.add_argument("--reference-package", required=True, type=Path)
    parser.add_argument("--index-json", required=True, type=Path)
    parser.add_argument("--coverage-json", required=True, type=Path)
    parser.add_argument("--asset-id", required=True)
    parser.add_argument("--assembly-context-revision", required=True, help="revision of the assembly context this asset is bound to")
    parser.add_argument("--node-prefix", default=DEFAULT_NODE_PREFIX)
    parser.add_argument("--default-opacity", type=float, default=0.42)
    parser.add_argument(
        "--corner-order",
        help="emit explicit registration landmarks in the vision model's keypoint order, as four <minx|maxx>_<minz|maxz> tokens (e.g. minx_maxz,minx_minz,maxx_minz,maxx_maxz); establish from a calibration observation, never guess",
    )
    parser.add_argument("--output-manifest", required=True, type=Path)
    parser.add_argument("--output-component-map", required=True, type=Path)
    args = parser.parse_args(argv)

    reference_package = json.loads(args.reference_package.read_text(encoding="utf-8"))
    manifest = build_manifest(
        args.glb, reference_package, args.asset_id, args.assembly_context_revision, args.node_prefix, args.default_opacity, args.corner_order
    )
    component_map = build_component_map(
        json.loads(args.index_json.read_text(encoding="utf-8")),
        json.loads(args.coverage_json.read_text(encoding="utf-8")),
        args.node_prefix,
        args.asset_id,
    )
    args.output_manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")
    args.output_component_map.write_text(json.dumps(component_map, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({**manifest, "component_map_entries": component_map["component_count"], "renderable": component_map["renderable_count"]}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
