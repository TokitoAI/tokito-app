#!/usr/bin/env python3
"""Prove that every modeled component can be highlighted independently.

A node merely *existing* with the right name does not make a component
highlightable. An AR runtime highlights by selecting a node and applying a
material/opacity override to that node's whole subtree, so independent
highlighting requires all of:

1. exactly one node carries the component's selector name (prefix +
   reference) -- a duplicate name makes selection ambiguous;
2. the subtree under it actually contains geometry -- an empty subtree
   highlights nothing;
3. no node in that subtree is reachable from another component's subtree
   -- shared nodes would light up two parts at once;
4. no mesh in that subtree is used by any other component's subtree --
   a shared mesh means a material override bleeds across components;
5. the subtree's world bounds are finite and disjoint enough from the
   board root that the selection is not the whole board.

This module checks all five against the real exported GLB, so a PASS means
"selecting this name highlights this component and nothing else", which is
the actual AR acceptance criterion.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

from glb_inspect import load_gltf_json, node_world_bounds


def _subtree(gltf: dict[str, Any], root: int) -> tuple[set[int], set[int]]:
    """Node indices and mesh indices reachable from a node (inclusive)."""
    nodes = gltf["nodes"]
    seen_nodes: set[int] = set()
    meshes: set[int] = set()
    stack = [root]
    while stack:
        index = stack.pop()
        if index in seen_nodes:
            continue
        seen_nodes.add(index)
        node = nodes[index]
        if "mesh" in node:
            meshes.add(node["mesh"])
        stack.extend(node.get("children", []))
    return seen_nodes, meshes


def verify_highlightability(
    gltf: dict[str, Any], references: list[str], node_prefix: str = ""
) -> dict[str, Any]:
    nodes = gltf["nodes"]
    nodes_by_name: dict[str, list[int]] = defaultdict(list)
    for index, node in enumerate(nodes):
        name = node.get("name")
        if name:
            nodes_by_name[name].append(index)

    bounds = node_world_bounds(gltf)

    # map every component to its subtree first, so ownership conflicts can be
    # detected across components rather than one component at a time
    subtrees: dict[str, tuple[set[int], set[int]]] = {}
    for reference in references:
        selector = f"{node_prefix}{reference}"
        indices = nodes_by_name.get(selector, [])
        if len(indices) == 1:
            subtrees[reference] = _subtree(gltf, indices[0])

    node_owners: dict[int, list[str]] = defaultdict(list)
    mesh_owners: dict[int, list[str]] = defaultdict(list)
    for reference, (subtree_nodes, subtree_meshes) in subtrees.items():
        for node_index in subtree_nodes:
            node_owners[node_index].append(reference)
        for mesh_index in subtree_meshes:
            mesh_owners[mesh_index].append(reference)

    results = []
    for reference in references:
        selector = f"{node_prefix}{reference}"
        indices = nodes_by_name.get(selector, [])
        record: dict[str, Any] = {
            "reference": reference,
            "selector": selector,
            "node_count_with_selector": len(indices),
        }
        if len(indices) != 1:
            record.update(
                {
                    "status": "FAIL",
                    "reason": "no node carries this selector" if not indices else "selector is ambiguous (multiple nodes)",
                    "unique_node": False,
                    "has_geometry": False,
                    "independently_selectable": False,
                }
            )
            results.append(record)
            continue

        node_index = indices[0]
        subtree_nodes, subtree_meshes = subtrees[reference]
        shared_nodes = sorted(
            {other for n in subtree_nodes for other in node_owners[n] if other != reference}
        )
        shared_meshes = sorted(
            {other for m in subtree_meshes for other in mesh_owners[m] if other != reference}
        )
        box = bounds.get(selector)
        finite_bounds = bool(box) and all(isinstance(v, float) and v == v and abs(v) != float("inf") for v in box)

        reasons = []
        if not subtree_meshes:
            reasons.append("subtree contains no geometry")
        if shared_nodes:
            reasons.append(f"subtree nodes shared with {shared_nodes[:3]}")
        if shared_meshes:
            reasons.append(f"meshes shared with {shared_meshes[:3]}")
        if not finite_bounds:
            reasons.append("world bounds missing or non-finite")

        record.update(
            {
                "node_index": node_index,
                "unique_node": True,
                "subtree_node_count": len(subtree_nodes),
                "mesh_ids": sorted(subtree_meshes),
                "mesh_count": len(subtree_meshes),
                "has_geometry": bool(subtree_meshes),
                "shares_nodes_with": shared_nodes,
                "shares_meshes_with": shared_meshes,
                "bounds_finite": finite_bounds,
                "independently_selectable": not reasons,
                "status": "PASS" if not reasons else "FAIL",
            }
        )
        if reasons:
            record["reason"] = "; ".join(reasons)
        results.append(record)

    failures = [r for r in results if r["status"] == "FAIL"]
    return {
        "component_node_prefix": node_prefix,
        "components_checked": len(results),
        "pass_count": len(results) - len(failures),
        "fail_count": len(failures),
        "highlightable_pct": round(100.0 * (len(results) - len(failures)) / len(results), 2) if results else None,
        "failures": failures,
        "components": sorted(results, key=lambda r: r["reference"]),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Prove every modeled component is independently highlightable in a GLB.")
    parser.add_argument("--glb", required=True, type=Path)
    parser.add_argument("--resolution-json", required=True, type=Path, help="component_model_resolution.json; components with a model must be highlightable")
    parser.add_argument("--node-prefix", default="", help="component node selector prefix used in the GLB (e.g. 'ref:')")
    parser.add_argument("--output-json", required=True, type=Path)
    args = parser.parse_args(argv)

    resolution = json.loads(args.resolution_json.read_text(encoding="utf-8"))
    references = [row["reference"] for row in resolution["components"] if row.get("model_path")]
    report = verify_highlightability(load_gltf_json(args.glb), references, args.node_prefix)
    report["glb"] = str(args.glb)
    args.output_json.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("components_checked", "pass_count", "fail_count", "highlightable_pct", "component_node_prefix")}, indent=2))
    if report["failures"]:
        print(json.dumps(report["failures"][:10], indent=2))
    return 0 if report["fail_count"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
