#!/usr/bin/env python3
"""Embed per-component metadata into a populated-board GLB.

glTF nodes carry an application-specific "extras" object; this tool writes
one onto every component node (named by reference designator) so the AR
runtime can answer "what is this node" from the GLB alone -- reference,
class, package, BOM identity, authored board position/rotation/side, model
quality and provenance -- without a side-channel lookup. The binary chunk
is untouched; only the JSON chunk is rewritten (with the header and chunk
lengths recomputed), so mesh data cannot be corrupted by this step.

Everything embedded comes from the component index and the resolver's
evidence-backed rows; nothing is invented here.
"""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path
from typing import Any

GLB_MAGIC = 0x46546C67
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942


def read_glb(path: Path) -> tuple[dict[str, Any], list[tuple[int, bytes]]]:
    data = path.read_bytes()
    magic, version, _length = struct.unpack_from("<III", data, 0)
    if magic != GLB_MAGIC:
        raise ValueError(f"{path} is not a binary glTF file")
    if version != 2:
        raise ValueError(f"unsupported glTF version {version}")
    chunks: list[tuple[int, bytes]] = []
    offset = 12
    while offset < len(data):
        chunk_length, chunk_type = struct.unpack_from("<II", data, offset)
        chunks.append((chunk_type, data[offset + 8 : offset + 8 + chunk_length]))
        offset += 8 + chunk_length
    if not chunks or chunks[0][0] != CHUNK_JSON:
        raise ValueError("first GLB chunk is not JSON")
    return json.loads(chunks[0][1].decode("utf-8")), chunks[1:]


def write_glb(path: Path, gltf: dict[str, Any], other_chunks: list[tuple[int, bytes]]) -> None:
    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    json_bytes += b" " * (-len(json_bytes) % 4)  # spec: JSON chunk padded with spaces
    parts = [struct.pack("<II", len(json_bytes), CHUNK_JSON), json_bytes]
    for chunk_type, payload in other_chunks:
        padded = payload + b"\x00" * (-len(payload) % 4)
        parts.append(struct.pack("<II", len(padded), chunk_type))
        parts.append(padded)
    body = b"".join(parts)
    path.write_bytes(struct.pack("<III", GLB_MAGIC, 2, 12 + len(body)) + body)


def component_extras(component: dict[str, Any], coverage_row: dict[str, Any] | None) -> dict[str, Any]:
    bom = component.get("bom") or {}
    return {
        "tokito_component": {
            "reference": component["reference"],
            "component_class": component["component_class"],
            "package": (coverage_row or {}).get("package_code") or None,
            "bom_description": bom.get("description"),
            "bom_manufacturer": bom.get("manufacturer"),
            "bom_part_number": bom.get("part_number"),
            "board_position_mm": component["board_position_mm"],
            "rotation_degrees": component["rotation_degrees"],
            "side": component["side"],
            "model_quality": (coverage_row or {}).get("model_quality"),
            "model_file": (coverage_row or {}).get("model_file"),
            "provenance": "NXP FRDM-MCXN236 Rev C design data (LAY-90828_C.brd + SCH-90828_C BOM); placement authored, not inferred",
        }
    }


def embed_metadata(
    glb_in: Path,
    glb_out: Path,
    index: dict[str, Any],
    resolution: dict[str, Any],
    node_prefix: str = "",
) -> dict[str, Any]:
    """Annotates component nodes with `extras` and, when `node_prefix` is
    given, renames them to `<prefix><reference>`.

    The prefix exists because the Android AR runtime's digital-twin manifest
    declares `componentNodePrefix` (default "ref:") and selects component
    nodes by that exact name; an unprefixed GLB loads but nothing can be
    selected. The prefix is applied here, not in the exporter, so the plain
    KiCad export stays untouched and the same GLB can be republished under a
    different prefix if the runtime contract changes."""
    gltf, other_chunks = read_glb(glb_in)
    resolution_by_reference = {row["reference"]: row for row in resolution["components"]}
    coverage_by_reference = {
        row["reference"]: {
            "package_code": row.get("package_code"),
            "model_quality": None,  # filled below from model_kind mapping
            "model_file": Path(row["model_path"]).name if row.get("model_path") else None,
            "model_kind": row.get("model_kind"),
        }
        for row in resolution["components"]
    }
    from model_coverage_report import model_quality

    for reference, row in coverage_by_reference.items():
        row["model_quality"] = model_quality(resolution_by_reference[reference])

    annotated = 0
    renamed = 0
    for node in gltf.get("nodes", []):
        name = node.get("name")
        component = index["components"].get(name) if name else None
        if component is None:
            continue
        node["extras"] = component_extras(component, coverage_by_reference.get(name))
        annotated += 1
        if node_prefix:
            node["name"] = f"{node_prefix}{name}"
            renamed += 1

    gltf.setdefault("asset", {}).setdefault("extras", {})["tokito_board"] = {
        "board_id": index.get("board_id"),
        "board_revision": index.get("board_revision"),
        "component_count": index.get("component_count"),
        "node_metadata_key": "tokito_component",
        "component_node_prefix": node_prefix,
    }

    write_glb(glb_out, gltf, other_chunks)
    return {
        "input": str(glb_in),
        "output": str(glb_out),
        "nodes_annotated": annotated,
        "nodes_renamed": renamed,
        "component_node_prefix": node_prefix,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Embed per-component metadata into a populated-board GLB's node extras.")
    parser.add_argument("--glb", required=True, type=Path)
    parser.add_argument("--index-json", required=True, type=Path)
    parser.add_argument("--resolution-json", required=True, type=Path)
    parser.add_argument("--output-glb", required=True, type=Path)
    parser.add_argument(
        "--node-prefix",
        default="",
        help="rename component nodes to <prefix><reference>; must match the digital-twin manifest's componentNodePrefix (the Android AR runtime defaults to 'ref:')",
    )
    args = parser.parse_args(argv)

    result = embed_metadata(
        args.glb,
        args.output_glb,
        json.loads(args.index_json.read_text(encoding="utf-8")),
        json.loads(args.resolution_json.read_text(encoding="utf-8")),
        args.node_prefix,
    )
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
