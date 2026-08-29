#!/usr/bin/env python3
"""Extract real per-footprint placement and reference designators from a
.kicad_pcb file.

kicad-cli's own ``pcb export pos`` does not reliably carry reference
designators for Allegro-imported boards: the importer writes the real
designator as the first "Reference" property text object (usually on a
silkscreen/fab layer) but does not always populate the footprint's internal
reference field that other KiCad export tools read, and some footprints also
carry a second, unresolved "${REFERENCE}" placeholder text object. This
module parses the board file's own s-expression tree directly so placement
data feeding the assembly-context/AR coordinate pipeline uses the real
vendor-authored designators instead of blank or template text.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any, Sequence

Node = list[Any] | str


def tokenize(text: str) -> list[str]:
    tokens: list[str] = []
    i = 0
    length = len(text)
    while i < length:
        char = text[i]
        if char in "()":
            tokens.append(char)
            i += 1
        elif char.isspace():
            i += 1
        elif char == '"':
            j = i + 1
            buffer = []
            while j < length:
                if text[j] == "\\" and j + 1 < length:
                    buffer.append(text[j + 1])
                    j += 2
                    continue
                if text[j] == '"':
                    break
                buffer.append(text[j])
                j += 1
            tokens.append('"' + "".join(buffer))
            i = j + 1
        else:
            j = i
            while j < length and not text[j].isspace() and text[j] not in "()":
                j += 1
            tokens.append(text[i:j])
            i = j
    return tokens


def parse(tokens: list[str]) -> Node:
    position = 0

    def parse_node() -> Node:
        nonlocal position
        token = tokens[position]
        if token == "(":
            position += 1
            items: list[Any] = []
            while tokens[position] != ")":
                items.append(parse_node())
            position += 1
            return items
        position += 1
        if token.startswith('"'):
            return token[1:]
        return token

    node = parse_node()
    return node


def node_head(node: Node) -> str | None:
    if isinstance(node, list) and node and isinstance(node[0], str):
        return node[0]
    return None


def find_all(node: Node, head: str) -> list[list[Any]]:
    if not isinstance(node, list):
        return []
    matches = []
    if node_head(node) == head:
        matches.append(node)
    for child in node:
        matches.extend(find_all(child, head))
    return matches


def find_direct(node: list[Any], head: str) -> list[Any] | None:
    for child in node:
        if isinstance(child, list) and node_head(child) == head:
            return child
    return None


def side_from_layer(layer: str | None) -> str:
    if layer is None:
        return "unknown"
    if layer.startswith("F."):
        return "top"
    if layer.startswith("B."):
        return "bottom"
    return "unknown"


def extract_footprint_placement(footprint: list[Any]) -> dict[str, Any]:
    layer_node = find_direct(footprint, "layer")
    at_node = find_direct(footprint, "at")
    x = y = 0.0
    rotation = 0.0
    if at_node is not None:
        values = at_node[1:]
        if len(values) >= 2:
            x = float(values[0])
            y = float(values[1])
        if len(values) >= 3:
            rotation = float(values[2])

    reference = ""
    value = ""
    for child in footprint:
        if not isinstance(child, list) or node_head(child) != "property":
            continue
        key = child[1] if len(child) > 1 else ""
        text = child[2] if len(child) > 2 else ""
        if key == "Reference" and (not reference or reference == "${REFERENCE}"):
            reference = text
        elif key == "Value" and not value:
            value = text

    return {
        "reference": reference,
        "is_placeholder_reference": reference == "${REFERENCE}" or reference == "",
        "value": value,
        "layer": layer_node[1] if layer_node and len(layer_node) > 1 else None,
        "side": side_from_layer(layer_node[1] if layer_node and len(layer_node) > 1 else None),
        "x_mm": x,
        "y_mm": y,
        "rotation_deg": rotation,
    }


def extract_placements(path: Path) -> list[dict[str, Any]]:
    text = path.read_text(encoding="utf-8")
    tree = parse(tokenize(text))
    footprints = find_all(tree, "footprint")
    return [extract_footprint_placement(footprint) for footprint in footprints]


def write_csv(path: Path, placements: Sequence[dict[str, Any]]) -> None:
    fieldnames = ["reference", "is_placeholder_reference", "value", "side", "layer", "x_mm", "y_mm", "rotation_deg"]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for placement in placements:
            writer.writerow(placement)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Extract footprint placements/reference designators from a .kicad_pcb file.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-csv", type=Path)
    parser.add_argument("--output-json", type=Path)
    args = parser.parse_args(argv)

    placements = extract_placements(args.input)
    usable = [item for item in placements if not item["is_placeholder_reference"]]

    summary = {
        "source": str(args.input),
        "footprint_count": len(placements),
        "usable_reference_count": len(usable),
        "placeholder_or_missing_reference_count": len(placements) - len(usable),
    }

    if args.output_csv:
        write_csv(args.output_csv, placements)
    if args.output_json:
        args.output_json.write_text(json.dumps({"summary": summary, "placements": placements}, indent=2, sort_keys=True), encoding="utf-8")

    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
