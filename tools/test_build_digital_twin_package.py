#!/usr/bin/env python3
"""Tests for the AR digital-twin manifest and canonical component map.

These encode the Android runtime's actual acceptance rules (from
`schemas/digital-twin.asset-manifest.schema.json` and the Kotlin
`Bounds3Contract`/`DigitalTwinValidator`), so an asset that would be
rejected on device fails here first.
"""

from __future__ import annotations

import json
import struct
import tempfile
import unittest
from pathlib import Path

from build_digital_twin_package import (
    asset_bounds_metres,
    build_component_map,
    build_manifest,
    component_id,
)
from glb_embed_metadata import CHUNK_BIN, write_glb

# mirrors schemas/digital-twin.asset-manifest.schema.json on main
REQUIRED_MANIFEST_FIELDS = (
    "schemaVersion",
    "assetId",
    "boardIdentity",
    "boardRevision",
    "assemblyContextRevision",
    "glbSha256",
    "glbBytes",
    "units",
    "axes",
    "boardOriginMetres",
    "boundsMetres",
    "defaultOpacity",
    "componentNodePrefix",
)
MAX_GLB_BYTES = 67108864


def write_test_glb(path: Path) -> None:
    """A board substrate with real thickness plus a coincident zero-thickness
    layer node (as KiCad exports soldermask/silkscreen) and one component."""
    gltf = {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0, 1, 2]}],
        "nodes": [
            {"name": "substrate", "mesh": 0},
            {"name": "soldermask", "mesh": 1},
            {"name": "ref:U1", "mesh": 2},
        ],
        "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0}}]},
            {"primitives": [{"attributes": {"POSITION": 1}}]},
            {"primitives": [{"attributes": {"POSITION": 2}}]},
        ],
        "accessors": [
            # substrate: 118 x 55 mm, 1.6 mm thick
            {"min": [0.0, 0.0, -0.055], "max": [0.118, 0.0016, 0.0]},
            # zero-thickness layer with the SAME footprint (must not be chosen)
            {"min": [0.0, 0.0016, -0.055], "max": [0.118, 0.0016, 0.0]},
            # a component body standing proud of the board
            {"min": [0.05, 0.0016, -0.03], "max": [0.06, 0.005, -0.02]},
        ],
    }
    write_glb(path, gltf, [(CHUNK_BIN, b"\x00" * 8)])


def make_reference_package() -> dict:
    return {"board": {"board_id": "nxp-frdm-mcxn236", "revision": "C"}}


def make_index() -> dict:
    return {
        "board_id": "nxp-frdm-mcxn236",
        "board_revision": "C",
        "components": {
            "U1": {
                "reference": "U1",
                "component_class": "integrated_circuit",
                "bom": {"description": "MCU", "manufacturer": "NXP", "part_number": "MCXN236VDFT", "value": None},
                "board_position_mm": {"x": 60.0, "y": -24.5},
                "rotation_degrees": 0.0,
                "side": "top",
                "glb_node_name": "U1",
            },
            "TP5": {
                "reference": "TP5",
                "component_class": "test_point",
                "bom": None,
                "board_position_mm": {"x": 1.0, "y": -2.0},
                "rotation_degrees": 0.0,
                "side": "top",
                "glb_node_name": None,
            },
        },
    }


def make_coverage() -> dict:
    return {
        "references": [
            {"reference": "U1", "category": "physical_component", "needs_3d_body": True, "model_quality": "dimension_accurate_placeholder", "model_file": "box.wrl", "package_code": "VFBGA184"},
            {"reference": "TP5", "category": "non_populated_reference", "needs_3d_body": False, "model_quality": None, "model_file": None, "package_code": None},
        ]
    }


class ComponentIdTest(unittest.TestCase):
    def test_canonical_format(self) -> None:
        self.assertEqual(component_id("nxp-frdm-mcxn236", "C", "U1"), "nxp-frdm-mcxn236:C:U1")


class AssetBoundsTest(unittest.TestCase):
    def test_board_plane_skips_zero_thickness_layers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "t.glb"
            write_test_glb(path)
            from glb_inspect import load_gltf_json

            overall, board_plane = asset_bounds_metres(load_gltf_json(path))
        # the coincident zero-thickness layer must not be chosen: the
        # runtime rejects bounds whose maximum is not strictly greater
        self.assertGreater(board_plane["maximum"]["y"], board_plane["minimum"]["y"])
        # overall bounds must contain the component standing proud of the board
        self.assertGreaterEqual(overall["maximum"]["y"], 0.005)


class BuildManifestTest(unittest.TestCase):
    def _manifest(self, directory: str) -> dict:
        path = Path(directory) / "t.glb"
        write_test_glb(path)
        return build_manifest(path, make_reference_package(), "asset-v1", "ctx-rev-1", "ref:", 0.42)

    def test_manifest_has_every_required_field(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = self._manifest(directory)
        for field in REQUIRED_MANIFEST_FIELDS:
            self.assertIn(field, manifest)

    def test_units_axes_and_digest_match_runtime_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "t.glb"
            write_test_glb(path)
            manifest = build_manifest(path, make_reference_package(), "asset-v1", "ctx-rev-1", "ref:", 0.42)
            raw = path.read_bytes()
        self.assertEqual(manifest["units"], "METRE")
        self.assertEqual(manifest["axes"], "RIGHT_HANDED_Y_UP")
        self.assertEqual(manifest["glbBytes"], len(raw))
        self.assertRegex(manifest["glbSha256"], r"^sha256:[a-f0-9]{64}$")
        self.assertRegex(manifest["schemaVersion"], r"^1\.[0-9]+$")

    def test_bounds_stay_within_schema_limits_and_are_valid(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = self._manifest(directory)
        for bounds_key in ("boundsMetres", "boardPlaneBoundsMetres"):
            bounds = manifest[bounds_key]
            for axis in ("x", "y", "z"):
                # schema constrains every coordinate to [-10, 10] metres
                self.assertLessEqual(abs(bounds["minimum"][axis]), 10)
                self.assertLessEqual(abs(bounds["maximum"][axis]), 10)
                # Kotlin Bounds3Contract.isValid() requires strict inequality
                self.assertGreater(bounds["maximum"][axis], bounds["minimum"][axis], f"{bounds_key}.{axis}")
        self.assertLessEqual(manifest["glbBytes"], MAX_GLB_BYTES)
        self.assertGreaterEqual(manifest["defaultOpacity"], 0.05)
        self.assertLessEqual(manifest["defaultOpacity"], 0.85)

    def test_missing_board_identity_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "t.glb"
            write_test_glb(path)
            with self.assertRaises(ValueError):
                build_manifest(path, {"board": {"board_id": "x"}}, "a", "c", "ref:", 0.42)


class BuildComponentMapTest(unittest.TestCase):
    def test_selector_only_for_renderable_components(self) -> None:
        component_map = build_component_map(make_index(), make_coverage(), "ref:", "asset-v1")
        by_reference = {c["reference"]: c for c in component_map["components"]}
        self.assertEqual(by_reference["U1"]["glb_selector"], "ref:U1")
        self.assertEqual(by_reference["U1"]["component_id"], "nxp-frdm-mcxn236:C:U1")
        # a non-populated test point has no body, so it must not claim a node
        self.assertIsNone(by_reference["TP5"]["glb_selector"])
        self.assertFalse(by_reference["TP5"]["needs_3d_body"])
        self.assertEqual(component_map["renderable_count"], 1)
        self.assertEqual(component_map["component_count"], 2)

    def test_carries_identity_and_placement_for_all_lanes(self) -> None:
        component_map = build_component_map(make_index(), make_coverage(), "ref:", "asset-v1")
        u1 = next(c for c in component_map["components"] if c["reference"] == "U1")
        self.assertEqual(u1["bom_part_number"], "MCXN236VDFT")
        self.assertEqual(u1["package"], "VFBGA184")
        self.assertEqual(u1["board_position_mm"], {"x": 60.0, "y": -24.5})
        self.assertEqual(u1["model_quality"], "dimension_accurate_placeholder")


if __name__ == "__main__":
    unittest.main()
