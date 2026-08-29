#!/usr/bin/env python3
"""Attach resolved 3D models to real footprints in a .kicad_pcb file by
direct text splicing, not full parse/reserialize.

kicad_pcb_placements.py's tokenizer discards the distinction between a
quoted string and a bare symbol once parsed, so reserializing the full
tree back to text is ambiguous and risks corrupting a 9MB file. Instead,
this module finds each top-level (footprint ...) block's exact character
span in the ORIGINAL text (via paren-depth scanning with the same
quote-escaping rules as the tokenizer) and splices a new
(model "path" ...) s-expression in just before that footprint's closing
paren, leaving every other byte of the file untouched.

Footprint order from this scan and from kicad_pcb_placements.py's full
parse are the same left-to-right document order, so the i-th span here
corresponds to the i-th parsed footprint node; that pairing is how a
resolved model (keyed by real reference designator, itself only
recoverable via the full parser) is matched to a raw-text span.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from kicad_pcb_placements import extract_footprint_placement, find_all, parse, tokenize


def find_footprint_spans(text: str) -> list[tuple[int, int]]:
    """Returns (start, end) character offsets for every top-level
    "(footprint " block, where end is the index of that block's closing
    ')'. Skips over parens inside quoted strings using the same escaping
    rule as kicad_pcb_placements.tokenize."""
    spans: list[tuple[int, int]] = []
    i = 0
    length = len(text)
    depth = 0
    footprint_start: int | None = None
    footprint_depth: int | None = None
    while i < length:
        char = text[i]
        if char == '"':
            i += 1
            while i < length:
                if text[i] == "\\" and i + 1 < length:
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if char == "(":
            if footprint_start is None and text[i : i + 11] == "(footprint ":
                footprint_start = i
                footprint_depth = depth
            depth += 1
            i += 1
            continue
        if char == ")":
            depth -= 1
            if footprint_start is not None and depth == footprint_depth:
                spans.append((footprint_start, i))
                footprint_start = None
                footprint_depth = None
            i += 1
            continue
        i += 1
    return spans


def build_model_sexpr(model_path: str, rotate_z_deg: float = 0.0, indent: str = "\t\t") -> str:
    escaped = model_path.replace("\\", "\\\\").replace('"', '\\"')
    return (
        f'\n{indent}(model "{escaped}"\n'
        f"{indent}\t(offset (xyz 0 0 0))\n"
        f"{indent}\t(scale (xyz 1 1 1))\n"
        f"{indent}\t(rotate (xyz 0 0 {rotate_z_deg:g}))\n"
        f"{indent})"
    )


PLACEHOLDER_REFERENCE_PROPERTY = '(property "Reference" "${REFERENCE}"'


def attach_models(input_path: Path, resolution_rows: list[dict[str, Any]], output_path: Path) -> dict[str, Any]:
    text = input_path.read_text(encoding="utf-8")

    tree = parse(tokenize(text))
    footprint_nodes = find_all(tree, "footprint")
    placements = [extract_footprint_placement(node) for node in footprint_nodes]

    spans = find_footprint_spans(text)
    if len(spans) != len(placements):
        raise ValueError(f"footprint span count ({len(spans)}) does not match parsed footprint count ({len(placements)}); refusing to splice")

    rows_by_reference = {row["reference"]: row for row in resolution_rows if row["model_path"] is not None}

    pieces = []
    previous = 0
    attached = 0
    references_repaired = 0
    for placement, (start, end) in zip(placements, spans):
        pieces.append(text[previous:start])
        span_text = text[start:end]
        # The Allegro importer leaves a literal "${REFERENCE}" placeholder as
        # the footprint's canonical Reference property, which then names GLB
        # nodes "${REFERENCE}" and breaks per-component mesh lookup downstream.
        # The real designator was already recovered from this same footprint's
        # own Reference text, so rewrite the placeholder to it.
        if not placement["is_placeholder_reference"] and PLACEHOLDER_REFERENCE_PROPERTY in span_text:
            span_text = span_text.replace(
                PLACEHOLDER_REFERENCE_PROPERTY,
                f'(property "Reference" "{placement["reference"]}"',
            )
            references_repaired += 1
        pieces.append(span_text)
        row = rows_by_reference.get(placement["reference"])
        if row is not None:
            pieces.append(build_model_sexpr(row["model_path"], float(row.get("model_rotate_z_deg", 0.0))))
            attached += 1
        previous = end
    pieces.append(text[previous:])

    output_path.write_text("".join(pieces), encoding="utf-8")

    return {
        "input": str(input_path),
        "output": str(output_path),
        "footprint_count": len(placements),
        "models_attached": attached,
        "placeholder_references_repaired": references_repaired,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Splice resolved 3D model references into a .kicad_pcb file.")
    parser.add_argument("--kicad-pcb", required=True, type=Path)
    parser.add_argument("--resolution-json", required=True, type=Path)
    parser.add_argument("--output-kicad-pcb", required=True, type=Path)
    args = parser.parse_args(argv)

    resolution = json.loads(args.resolution_json.read_text(encoding="utf-8"))
    result = attach_models(args.kicad_pcb, resolution["components"], args.output_kicad_pcb)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
