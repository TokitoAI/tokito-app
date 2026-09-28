#!/usr/bin/env python3
"""Numeric verification of exported GLB scenes: world-space bounding box per
named node, computed from glTF node transforms and the POSITION accessor
min/max that the glTF spec requires -- no mesh decoding, no rendering, pure
stdlib. Used to check, component by component, that each 3D body actually
sits over its authored board placement instead of trusting a visual skim.
"""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path
from typing import Any


def load_gltf_json(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    magic, _version, _length = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67:  # b"glTF"
        raise ValueError(f"{path} is not a binary glTF file")
    chunk_length, chunk_type = struct.unpack_from("<II", data, 12)
    if chunk_type != 0x4E4F534A:  # b"JSON"
        raise ValueError("first GLB chunk is not JSON")
    return json.loads(data[20 : 20 + chunk_length].decode("utf-8"))


def _identity() -> list[float]:
    return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def _matrix_multiply(a: list[float], b: list[float]) -> list[float]:
    # column-major 4x4 (glTF convention): result = a * b
    result = [0.0] * 16
    for column in range(4):
        for row in range(4):
            result[column * 4 + row] = sum(a[k * 4 + row] * b[column * 4 + k] for k in range(4))
    return result


def _node_local_matrix(node: dict[str, Any]) -> list[float]:
    if "matrix" in node:
        return list(node["matrix"])
    tx, ty, tz = node.get("translation", [0, 0, 0])
    qx, qy, qz, qw = node.get("rotation", [0, 0, 0, 1])
    sx, sy, sz = node.get("scale", [1, 1, 1])
    # rotation matrix from unit quaternion, column-major with scale then translation
    m = [
        (1 - 2 * (qy * qy + qz * qz)) * sx, (2 * (qx * qy + qz * qw)) * sx, (2 * (qx * qz - qy * qw)) * sx, 0,
        (2 * (qx * qy - qz * qw)) * sy, (1 - 2 * (qx * qx + qz * qz)) * sy, (2 * (qy * qz + qx * qw)) * sy, 0,
        (2 * (qx * qz + qy * qw)) * sz, (2 * (qy * qz - qx * qw)) * sz, (1 - 2 * (qx * qx + qy * qy)) * sz, 0,
        tx, ty, tz, 1,
    ]
    return m


def _transform_point(m: list[float], p: tuple[float, float, float]) -> tuple[float, float, float]:
    return (
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
    )


def _merge_bounds(target: list[float] | None, corners: list[tuple[float, float, float]]) -> list[float]:
    xs = [c[0] for c in corners]
    ys = [c[1] for c in corners]
    zs = [c[2] for c in corners]
    bounds = [min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)]
    if target is None:
        return bounds
    return [
        min(target[0], bounds[0]), max(target[1], bounds[1]),
        min(target[2], bounds[2]), max(target[3], bounds[3]),
        min(target[4], bounds[4]), max(target[5], bounds[5]),
    ]


def node_world_bounds(gltf: dict[str, Any]) -> dict[str, list[float]]:
    """World-space [min_x, max_x, min_y, max_y, min_z, max_z] per NAMED node,
    covering that node's own meshes and its whole subtree."""
    nodes = gltf.get("nodes", [])
    meshes = gltf.get("meshes", [])
    accessors = gltf.get("accessors", [])
    scene = gltf["scenes"][gltf.get("scene", 0)]

    def mesh_corners(mesh_index: int, world: list[float]) -> list[tuple[float, float, float]]:
        corners = []
        for primitive in meshes[mesh_index].get("primitives", []):
            accessor = accessors[primitive["attributes"]["POSITION"]]
            lo, hi = accessor.get("min"), accessor.get("max")
            if lo is None or hi is None:
                continue
            for x in (lo[0], hi[0]):
                for y in (lo[1], hi[1]):
                    for z in (lo[2], hi[2]):
                        corners.append(_transform_point(world, (x, y, z)))
        return corners

    results: dict[str, list[float]] = {}

    def subtree_corners(node_index: int, parent: list[float]) -> list[tuple[float, float, float]]:
        node = nodes[node_index]
        world = _matrix_multiply(parent, _node_local_matrix(node))
        corners: list[tuple[float, float, float]] = []
        if "mesh" in node:
            corners.extend(mesh_corners(node["mesh"], world))
        for child in node.get("children", []):
            corners.extend(subtree_corners(child, world))
        name = node.get("name")
        if name and corners:
            merged = results.get(name)
            results[name] = _merge_bounds(merged, corners)
        return corners

    for root in scene.get("nodes", []):
        subtree_corners(root, _identity())
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Per-named-node world AABBs of a GLB file.")
    parser.add_argument("glb", type=Path)
    parser.add_argument("--json", action="store_true", help="emit JSON instead of a table")
    args = parser.parse_args(argv)

    bounds = node_world_bounds(load_gltf_json(args.glb))
    if args.json:
        print(json.dumps(bounds, indent=2, sort_keys=True))
        return 0
    for name in sorted(bounds):
        b = bounds[name]
        print(
            f"{name:24s} x[{b[0]:9.4f},{b[1]:9.4f}] y[{b[2]:9.4f},{b[3]:9.4f}] z[{b[4]:9.4f},{b[5]:9.4f}]"
            f"  center=({(b[0]+b[1])/2:9.4f},{(b[2]+b[3])/2:9.4f})"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
