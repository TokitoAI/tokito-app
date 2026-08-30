#!/usr/bin/env python3
"""Tests for independent-highlightability verification.

Each test builds a glTF whose defect is exactly one of the ways an AR
highlight silently misbehaves, and asserts the checker catches it -- node
naming alone must never be enough for a PASS.
"""

from __future__ import annotations

import unittest

from verify_component_highlightability import verify_highlightability


def gltf(nodes: list[dict], mesh_count: int = 4) -> dict:
    return {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": list(range(len(nodes)))}],
        "nodes": nodes,
        "meshes": [{"primitives": [{"attributes": {"POSITION": i}}]} for i in range(mesh_count)],
        "accessors": [{"min": [0.0, 0.0, 0.0], "max": [0.01, 0.01, 0.01]} for _ in range(mesh_count)],
    }


class VerifyHighlightabilityTest(unittest.TestCase):
    def test_clean_parent_named_component_passes(self) -> None:
        # the real export shape: refdes on the parent, geometry in a child
        model = gltf([
            {"name": "ref:U1", "children": [1]},
            {"name": "inner", "mesh": 0},
            {"name": "ref:R1", "mesh": 1},
        ])
        report = verify_highlightability(model, ["U1", "R1"], "ref:")
        self.assertEqual(report["fail_count"], 0)
        self.assertEqual(report["highlightable_pct"], 100.0)
        u1 = next(c for c in report["components"] if c["reference"] == "U1")
        self.assertTrue(u1["independently_selectable"])
        self.assertEqual(u1["mesh_ids"], [0])

    def test_missing_selector_fails(self) -> None:
        report = verify_highlightability(gltf([{"name": "U1", "mesh": 0}]), ["U1"], "ref:")
        self.assertEqual(report["fail_count"], 1)
        self.assertIn("no node carries this selector", report["failures"][0]["reason"])

    def test_duplicate_selector_is_ambiguous(self) -> None:
        model = gltf([{"name": "ref:U1", "mesh": 0}, {"name": "ref:U1", "mesh": 1}])
        report = verify_highlightability(model, ["U1"], "ref:")
        self.assertEqual(report["fail_count"], 1)
        self.assertIn("ambiguous", report["failures"][0]["reason"])

    def test_empty_subtree_fails(self) -> None:
        # a named node with no geometry anywhere beneath it highlights nothing
        model = gltf([{"name": "ref:U1", "children": [1]}, {"name": "empty"}])
        report = verify_highlightability(model, ["U1"], "ref:")
        self.assertEqual(report["fail_count"], 1)
        self.assertIn("no geometry", report["failures"][0]["reason"])

    def test_shared_mesh_between_components_fails(self) -> None:
        # both components draw mesh 0: a material override would bleed
        model = gltf([{"name": "ref:U1", "mesh": 0}, {"name": "ref:U2", "mesh": 0}])
        report = verify_highlightability(model, ["U1", "U2"], "ref:")
        self.assertEqual(report["fail_count"], 2)
        self.assertIn("meshes shared with", report["failures"][0]["reason"])

    def test_shared_subtree_node_fails(self) -> None:
        # node 2 is a child of both components' nodes
        model = gltf([
            {"name": "ref:U1", "children": [2]},
            {"name": "ref:U2", "children": [2]},
            {"name": "shared", "mesh": 0},
        ])
        report = verify_highlightability(model, ["U1", "U2"], "ref:")
        self.assertEqual(report["fail_count"], 2)
        self.assertTrue(any("shared" in f["reason"] for f in report["failures"]))

    def test_prefixless_mode_still_works(self) -> None:
        report = verify_highlightability(gltf([{"name": "U1", "mesh": 0}]), ["U1"], "")
        self.assertEqual(report["fail_count"], 0)


if __name__ == "__main__":
    unittest.main()
