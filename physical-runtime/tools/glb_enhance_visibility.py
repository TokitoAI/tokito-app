#!/usr/bin/env python3
"""Produce a visibility-enhanced variant of the AR GLB for dark boards.

The physical FRDM-MCXN236 is nearly black and most component bodies are
dark plastic, so on the phone the AR overlay reads as black-on-black:
connectors and ICs are indistinguishable from the substrate. This tool
lifts the base colour of *component* materials whose luminance falls
below a threshold, leaving the board substrate and already-recognisable
finishes (gold pins, bright markings) untouched.

Deliberate constraints:

- geometry, node names, `ref:` selectors and per-node metadata are not
  touched -- highlightability is preserved by construction and re-checked
  by the caller;
- a material shared between a component and a non-component mesh is
  CLONED before lifting, so the substrate keeps its realistic colour;
- this is a presentation variant, not the engineering record: the output
  is a separate file, each lifted material is renamed with a
  `(visibility-lifted)` suffix, and the asset extras record the exact
  parameters, so nobody can mistake the lifted colours for authored ones.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from glb_embed_metadata import read_glb, write_glb


def _luminance(color: list[float]) -> float:
    return 0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2]


def _lift_color(color: list[float], lift: float) -> list[float]:
    """Blend toward white by `lift`: preserves hue ordering, guarantees a
    floor for near-black plastics without clipping brighter channels."""
    lifted = [1.0 - (1.0 - channel) * (1.0 - lift) for channel in color[:3]]
    return [*lifted, *color[3:]]


def component_mesh_indices(gltf: dict[str, Any], node_prefix: str) -> set[int]:
    nodes = gltf.get("nodes", [])
    meshes: set[int] = set()

    def walk(index: int) -> None:
        node = nodes[index]
        if "mesh" in node:
            meshes.add(node["mesh"])
        for child in node.get("children", []):
            walk(child)

    for index, node in enumerate(nodes):
        if str(node.get("name", "")).startswith(node_prefix):
            walk(index)
    return meshes


def enhance_visibility(
    glb_in: Path,
    glb_out: Path,
    node_prefix: str = "ref:",
    luminance_threshold: float = 0.25,
    lift: float = 0.38,
) -> dict[str, Any]:
    gltf, other_chunks = read_glb(glb_in)
    materials = gltf.setdefault("materials", [])
    meshes = gltf.get("meshes", [])
    component_meshes = component_mesh_indices(gltf, node_prefix)
    if not component_meshes:
        raise ValueError(f"no nodes with prefix {node_prefix!r}; refusing to produce an unenhanced copy")

    # which materials are referenced outside component subtrees?
    shared_with_board: set[int] = set()
    for mesh_index, mesh in enumerate(meshes):
        if mesh_index in component_meshes:
            continue
        for primitive in mesh.get("primitives", []):
            if "material" in primitive:
                shared_with_board.add(primitive["material"])

    clones: dict[int, int] = {}
    lifted: set[int] = set()

    def lift_material(index: int) -> int:
        """Returns the material index the primitive should use; clones when
        the material is shared with non-component geometry."""
        target = index
        if index in shared_with_board:
            if index not in clones:
                clone = json.loads(json.dumps(materials[index]))
                materials.append(clone)
                clones[index] = len(materials) - 1
            target = clones[index]
        material = materials[target]
        pbr = material.get("pbrMetallicRoughness")
        color = pbr.get("baseColorFactor") if pbr else None
        if color is None or _luminance(color) >= luminance_threshold:
            return target
        if target not in lifted:
            pbr["baseColorFactor"] = _lift_color(color, lift)
            material["name"] = f"{material.get('name', f'mat_{target}')} (visibility-lifted)"
            lifted.add(target)
        return target

    retargeted = 0
    for mesh_index in sorted(component_meshes):
        for primitive in meshes[mesh_index].get("primitives", []):
            if "material" not in primitive:
                continue
            new_index = lift_material(primitive["material"])
            if new_index != primitive["material"]:
                primitive["material"] = new_index
                retargeted += 1

    gltf.setdefault("asset", {}).setdefault("extras", {})["tokito_visibility"] = {
        "variant": "visibility_lifted_components",
        "luminance_threshold": luminance_threshold,
        "lift": lift,
        "materials_lifted": len(lifted),
        "materials_cloned": len(clones),
        "note": "presentation variant for dark boards; authored colours live in the unenhanced asset",
    }
    write_glb(glb_out, gltf, other_chunks)
    return {
        "input": str(glb_in),
        "output": str(glb_out),
        "component_meshes": len(component_meshes),
        "materials_lifted": len(lifted),
        "materials_cloned": len(clones),
        "primitives_retargeted": retargeted,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Lift dark component materials in an AR GLB for dark-board visibility.")
    parser.add_argument("--glb", required=True, type=Path)
    parser.add_argument("--output-glb", required=True, type=Path)
    parser.add_argument("--node-prefix", default="ref:")
    parser.add_argument("--luminance-threshold", type=float, default=0.25)
    parser.add_argument("--lift", type=float, default=0.38)
    args = parser.parse_args(argv)

    result = enhance_visibility(args.glb, args.output_glb, args.node_prefix, args.luminance_threshold, args.lift)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
