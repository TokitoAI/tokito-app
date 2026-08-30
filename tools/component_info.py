#!/usr/bin/env python3
"""Deterministic per-component information index for the FRDM-MCXN236.

Joins, for every component on the board, only facts that exist in real
artifacts -- authored placement (reference package), the vendor BOM
(description/manufacturer/part number), the resolved 3D model and its GLB
node name, and per-pad net connectivity parsed from the imported board
file. This is the query surface the product features stand on:

- "where is the main chip" -> position on the board, GLB node name to
  isolate/highlight in the 3D model and AR overlay, plus the BOM facts an
  LLM can ground its explanation in (the explanation itself is the LLM
  lane's job, not this module's);
- connection guidance -> each pad's net and every other (reference, pad)
  on that net, so "what does this pin connect to" is answered from copper,
  not guesswork.

Nothing here is inferred beyond the standard IPC reference-designator
prefix convention, and that single convention-based field is labelled
with its source. Pin polarity semantics (anode/cathode and the like) are
NOT invented: the field is present but null until verified evidence
exists, mirroring the reference package's own polarity_or_pin_one
stance.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from kicad_pcb_placements import (
    extract_footprint_pads,
    extract_footprint_placement,
    find_all,
    parse_pcb_tree,
)
from xlsx_bom_parser import expand_by_reference_designator, parse_bom_records

# Standard IPC/industry reference-designator prefix classes. Board-independent
# convention, the only non-artifact-sourced field in the index (and labelled).
REFERENCE_DESIGNATOR_CLASSES = {
    "R": "resistor",
    "C": "capacitor",
    "L": "inductor_or_ferrite",
    "D": "diode_or_led",
    "Q": "transistor",
    "U": "integrated_circuit",
    "Y": "crystal_or_oscillator",
    "J": "connector_or_header",
    "JP": "jumper",
    "SW": "switch",
    "TP": "test_point",
    "F": "fuse",
    "T": "transformer",
    "H": "mounting_hole",
}


def reference_class(reference: str) -> str | None:
    prefix = ""
    for char in reference:
        if char.isalpha():
            prefix += char
        else:
            break
    # longest prefix first so "JP1" is a jumper, not a connector
    for length in range(len(prefix), 0, -1):
        if prefix[:length] in REFERENCE_DESIGNATOR_CLASSES and length == len(prefix):
            return REFERENCE_DESIGNATOR_CLASSES[prefix]
    return REFERENCE_DESIGNATOR_CLASSES.get(prefix[:1]) if prefix else None


def _pad_name_polarity(pads: list[dict[str, Any]]) -> dict[str, str] | None:
    """Polarity read directly from the vendor footprint's own pad names --
    the imported Allegro footprints name diode pads literally "A"/"C".
    Only that exact, unambiguous case is reported; anything else stays
    null rather than guessed."""
    numbers = {pad["number"] for pad in pads}
    if numbers == {"A", "C"}:
        return {
            "anode_pad": "A",
            "cathode_pad": "C",
            "source": "vendor_footprint_pad_names",
        }
    return None


def extract_board_nets(kicad_pcb: Path) -> dict[str, Any]:
    """Per-reference pad->net table and full net connectivity, parsed from
    the imported board's copper definitions."""
    pads_by_reference: dict[str, list[dict[str, Any]]] = {}
    net_connectivity: dict[str, list[dict[str, str]]] = {}
    for footprint in find_all(parse_pcb_tree(kicad_pcb), "footprint"):
        placement = extract_footprint_placement(footprint)
        if placement["is_placeholder_reference"]:
            continue
        reference = placement["reference"]
        pad_rows = []
        for pad in extract_footprint_pads(footprint):
            if not pad["number"]:
                continue
            net = pad["net"]
            pad_rows.append({"number": str(pad["number"]), "net": net})
            if net:
                net_connectivity.setdefault(net, []).append({"reference": reference, "pad": str(pad["number"])})
        pads_by_reference[reference] = pad_rows
    return {"pads_by_reference": pads_by_reference, "net_connectivity": net_connectivity}


def build_component_index(
    reference_package: dict[str, Any],
    resolution: dict[str, Any],
    bom_by_reference: dict[str, dict[str, Any]],
    board_nets: dict[str, Any],
) -> dict[str, Any]:
    resolution_by_reference = {row["reference"]: row for row in resolution["components"]}
    components: dict[str, dict[str, Any]] = {}
    for component in reference_package["components"]:
        reference = component["reference_designator"]
        resolution_row = resolution_by_reference.get(reference)
        bom_record = bom_by_reference.get(reference)
        has_model = bool(resolution_row and resolution_row.get("model_path"))
        components[reference] = {
            "reference": reference,
            "component_class": reference_class(reference),
            "component_class_source": "ipc_reference_designator_convention",
            "bom": {
                "description": bom_record.get("Description") if bom_record else None,
                "manufacturer": bom_record.get("MFG") if bom_record else None,
                "part_number": bom_record.get("MFG_PN") if bom_record else None,
                "value": bom_record.get("VALUE") if bom_record else None,
            }
            if bom_record
            else None,
            "board_position_mm": component["centroid"],
            "rotation_degrees": component["rotation_degrees"],
            "side": component["side"],
            # GLB component nodes are named by reference designator (the
            # attach step repairs the importer's ${REFERENCE} placeholders),
            # so the node name for highlighting IS the reference -- but only
            # when a model was actually attached.
            "glb_node_name": reference if has_model else None,
            "model": {
                "file": Path(resolution_row["model_path"]).name if has_model else None,
                "kind": resolution_row.get("model_kind") if resolution_row else None,
                "status": resolution_row.get("status") if resolution_row else None,
            },
            "pads": board_nets["pads_by_reference"].get(reference, []),
            "polarity_or_pin_one": _pad_name_polarity(board_nets["pads_by_reference"].get(reference, []))
            or component.get("polarity_or_pin_one"),
        }
    board = reference_package.get("board") or {}
    return {
        "board_id": board.get("board_id") or reference_package.get("board_id"),
        "board_revision": board.get("revision") or reference_package.get("board_revision"),
        "component_count": len(components),
        "components": components,
        "net_connectivity": board_nets["net_connectivity"],
    }


def get_component_info(index: dict[str, Any], reference: str, max_peers_per_net: int = 40) -> dict[str, Any] | None:
    """A single component's record with, per pad, the other (reference, pad)
    endpoints on the same copper net -- the deterministic answer to 'what
    does this pin connect to'."""
    component = index["components"].get(reference)
    if component is None:
        return None
    result = dict(component)
    connections = []
    for pad in component["pads"]:
        net = pad["net"]
        peers = []
        if net:
            peers = [
                endpoint
                for endpoint in index["net_connectivity"].get(net, [])
                if endpoint["reference"] != reference
            ][:max_peers_per_net]
        connections.append({"pad": pad["number"], "net": net, "connected_to": peers})
    result["connections"] = connections
    return result


def get_connection_guidance(
    index: dict[str, Any], reference: str, max_endpoints_per_net: int = 25
) -> dict[str, Any]:
    """Deterministic connection-guidance facts for one component: per
    terminal, the copper net, its role (anode/cathode only when the source
    artifact names the pads), and every other endpoint on that net with its
    board position, GLB node, and the geometric direction from this
    component to it. Everything is read from the board's own net
    assignments and authored placements -- the LLM lane phrases this, the
    AR lane draws it; neither is allowed to invent any of it.

    Explicit statuses instead of guesses: unknown_reference for a bad
    designator; per-terminal net null (with no endpoints) when the pad has
    no copper net in the source; polarity_status says whether orientation
    guidance is source-verified or must not be given."""
    component = index["components"].get(reference)
    if component is None:
        return {"status": "unknown_reference", "reference": reference}

    polarity = component.get("polarity_or_pin_one")
    pad_roles: dict[str, str] = {}
    if isinstance(polarity, dict):
        if polarity.get("anode_pad"):
            pad_roles[polarity["anode_pad"]] = "anode"
        if polarity.get("cathode_pad"):
            pad_roles[polarity["cathode_pad"]] = "cathode"

    own_position = component["board_position_mm"]
    terminals = []
    for pad in component["pads"]:
        net = pad["net"]
        all_endpoints = [
            endpoint
            for endpoint in (index["net_connectivity"].get(net, []) if net else [])
            if endpoint["reference"] != reference
        ]
        endpoints = []
        for endpoint in all_endpoints[:max_endpoints_per_net]:
            peer = index["components"].get(endpoint["reference"])
            peer_position = peer["board_position_mm"] if peer else None
            endpoints.append(
                {
                    "reference": endpoint["reference"],
                    "pad": endpoint["pad"],
                    "component_class": peer["component_class"] if peer else None,
                    "board_position_mm": peer_position,
                    "glb_node_name": peer["glb_node_name"] if peer else None,
                    # direction from this component's centroid to the peer's,
                    # in board mm -- the AR overlay's arrow, from placement
                    # data alone
                    "direction_from_component_mm": {
                        "dx": round(peer_position["x"] - own_position["x"], 3),
                        "dy": round(peer_position["y"] - own_position["y"], 3),
                    }
                    if peer_position
                    else None,
                }
            )
        terminals.append(
            {
                "pad": pad["number"],
                "role": pad_roles.get(pad["number"]),
                "net": net,
                "endpoint_count_total": len(all_endpoints),
                "endpoints_truncated": len(all_endpoints) > max_endpoints_per_net,
                "connected_endpoints": endpoints,
            }
        )

    return {
        "status": "ok",
        "board_id": index.get("board_id"),
        "reference": reference,
        "component_class": component["component_class"],
        "bom": component["bom"],
        "side": component["side"],
        "board_position_mm": own_position,
        "glb_node_name": component["glb_node_name"],
        "polarity_status": "source_verified" if pad_roles else "not_available_from_source",
        "polarity_or_pin_one": polarity,
        "terminals": terminals,
    }


def find_components(index: dict[str, Any], query: str) -> list[str]:
    """References whose designator, BOM description, or part number contains
    the query (case-insensitive). Substring match only -- semantic queries
    ('the main chip') are the LLM lane's job, grounded on this index."""
    needle = query.lower()
    matches = []
    for reference, component in index["components"].items():
        bom = component.get("bom") or {}
        haystack = " ".join(
            str(value)
            for value in (reference, bom.get("description"), bom.get("part_number"), bom.get("manufacturer"))
            if value
        ).lower()
        if needle in haystack:
            matches.append(reference)
    return sorted(matches)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build or query the per-component board information index.")
    parser.add_argument("--reference-package", type=Path)
    parser.add_argument("--resolution-json", type=Path)
    parser.add_argument("--bom-xlsx", type=Path)
    parser.add_argument("--kicad-pcb", type=Path)
    parser.add_argument("--output-json", type=Path, help="write the built index here")
    parser.add_argument("--index-json", type=Path, help="query an already-built index")
    parser.add_argument("--component", help="reference designator to query")
    parser.add_argument("--connection-guidance", help="reference designator to build connection guidance for")
    parser.add_argument("--search", help="substring to search designators/BOM text for")
    args = parser.parse_args(argv)

    if args.index_json:
        index = json.loads(args.index_json.read_text(encoding="utf-8"))
    else:
        required = (args.reference_package, args.resolution_json, args.bom_xlsx, args.kicad_pcb)
        if any(value is None for value in required):
            parser.error("building an index requires --reference-package, --resolution-json, --bom-xlsx and --kicad-pcb")
        index = build_component_index(
            json.loads(args.reference_package.read_text(encoding="utf-8")),
            json.loads(args.resolution_json.read_text(encoding="utf-8")),
            expand_by_reference_designator(parse_bom_records(args.bom_xlsx)),
            extract_board_nets(args.kicad_pcb),
        )
        if args.output_json:
            args.output_json.write_text(json.dumps(index, indent=2, sort_keys=True), encoding="utf-8")
            print(f"wrote {args.output_json} ({index['component_count']} components, {len(index['net_connectivity'])} nets)")

    if args.component:
        info = get_component_info(index, args.component)
        if info is None:
            print(json.dumps({"error": f"unknown reference {args.component!r}"}))
            return 1
        print(json.dumps(info, indent=2))
    if args.connection_guidance:
        guidance = get_connection_guidance(index, args.connection_guidance)
        print(json.dumps(guidance, indent=2))
        if guidance["status"] != "ok":
            return 1
    if args.search:
        print(json.dumps(find_components(index, args.search), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
