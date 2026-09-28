#!/usr/bin/env python3
"""Tests for the dark-board visibility variant."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from glb_embed_metadata import CHUNK_BIN, read_glb, write_glb
from glb_enhance_visibility import enhance_visibility


def make_glb(path: Path) -> None:
    """One dark shared material (substrate + component), one dark exclusive
    component material, one bright component material."""
    gltf = {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0, 1, 2]}],
        "nodes": [
            {"name": "substrate", "mesh": 0},
            {"name": "ref:U1", "mesh": 1},
            {"name": "ref:J1", "mesh": 2},
        ],
        "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0}, "material": 0}]},  # board, shared dark
            {
                "primitives": [
                    {"attributes": {"POSITION": 1}, "material": 0},  # component using SHARED dark
                    {"attributes": {"POSITION": 2}, "material": 1},  # exclusive dark
                ]
            },
            {"primitives": [{"attributes": {"POSITION": 3}, "material": 2}]},  # bright gold
        ],
        "materials": [
            {"name": "dark_shared", "pbrMetallicRoughness": {"baseColorFactor": [0.02, 0.02, 0.02, 1.0]}},
            {"name": "dark_exclusive", "pbrMetallicRoughness": {"baseColorFactor": [0.05, 0.05, 0.05, 1.0]}},
            {"name": "gold", "pbrMetallicRoughness": {"baseColorFactor": [0.71, 0.5, 0.21, 1.0]}},
        ],
        "accessors": [{"min": [0, 0, 0], "max": [0.01, 0.01, 0.01]} for _ in range(4)],
    }
    write_glb(path, gltf, [(CHUNK_BIN, b"\x00" * 8)])


class EnhanceVisibilityTest(unittest.TestCase):
    def _enhance(self, directory: str) -> tuple[dict, dict]:
        source = Path(directory) / "in.glb"
        output = Path(directory) / "out.glb"
        make_glb(source)
        result = enhance_visibility(source, output)
        gltf, _ = read_glb(output)
        return result, gltf

    def test_shared_material_is_cloned_and_substrate_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result, gltf = self._enhance(directory)
        # the substrate primitive still points at the ORIGINAL dark material
        board_material = gltf["materials"][gltf["meshes"][0]["primitives"][0]["material"]]
        self.assertEqual(board_material["pbrMetallicRoughness"]["baseColorFactor"][:3], [0.02, 0.02, 0.02])
        # the component's shared-material primitive was retargeted to a lifted clone
        clone_index = gltf["meshes"][1]["primitives"][0]["material"]
        self.assertNotEqual(clone_index, gltf["meshes"][0]["primitives"][0]["material"])
        clone = gltf["materials"][clone_index]
        self.assertGreater(clone["pbrMetallicRoughness"]["baseColorFactor"][0], 0.3)
        self.assertIn("visibility-lifted", clone["name"])
        self.assertEqual(result["materials_cloned"], 1)

    def test_exclusive_dark_material_lifted_in_place(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            _, gltf = self._enhance(directory)
        exclusive = gltf["materials"][gltf["meshes"][1]["primitives"][1]["material"]]
        self.assertGreater(exclusive["pbrMetallicRoughness"]["baseColorFactor"][0], 0.3)

    def test_bright_material_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            _, gltf = self._enhance(directory)
        gold = gltf["materials"][gltf["meshes"][2]["primitives"][0]["material"]]
        self.assertEqual(gold["pbrMetallicRoughness"]["baseColorFactor"][:3], [0.71, 0.5, 0.21])
        self.assertNotIn("visibility-lifted", gold["name"])

    def test_nodes_and_geometry_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            _, gltf = self._enhance(directory)
        self.assertEqual([n["name"] for n in gltf["nodes"]], ["substrate", "ref:U1", "ref:J1"])
        self.assertEqual(len(gltf["accessors"]), 4)

    def test_provenance_recorded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            _, gltf = self._enhance(directory)
        extras = gltf["asset"]["extras"]["tokito_visibility"]
        self.assertEqual(extras["variant"], "visibility_lifted_components")
        self.assertIn("presentation variant", extras["note"])

    def test_refuses_when_no_component_nodes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "in.glb"
            make_glb(source)
            with self.assertRaises(ValueError):
                enhance_visibility(source, Path(directory) / "out.glb", node_prefix="zzz:")


if __name__ == "__main__":
    unittest.main()
