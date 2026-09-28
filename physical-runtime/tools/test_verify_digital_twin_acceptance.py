#!/usr/bin/env python3
"""Tests for the Android acceptance-rule port.

Each test corrupts exactly one aspect of a valid package and asserts the
matching runtime rejection code, so the port cannot silently drift from
DigitalTwinValidator.
"""

from __future__ import annotations

import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path

from glb_embed_metadata import CHUNK_BIN, write_glb
from verify_digital_twin_acceptance import check_package, is_glb_v2

CONTEXT_REVISION = "ctx-rev-1"


def write_valid_glb(path: Path) -> bytes:
    gltf = {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "ref:U1", "mesh": 0}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
        "accessors": [{"min": [0.0, 0.0, 0.0], "max": [0.1, 0.01, 0.05]}],
    }
    write_glb(path, gltf, [(CHUNK_BIN, b"\x00" * 8)])
    return path.read_bytes()


def valid_manifest(glb: bytes) -> dict:
    return {
        "schemaVersion": "1.0",
        "assetId": "asset-v1",
        "boardIdentity": "nxp-frdm-mcxn236",
        "boardRevision": "C",
        "assemblyContextRevision": CONTEXT_REVISION,
        "glbSha256": "sha256:" + hashlib.sha256(glb).hexdigest(),
        "glbBytes": len(glb),
        "units": "METRE",
        "axes": "RIGHT_HANDED_Y_UP",
        "boardOriginMetres": {"x": 0.0, "y": 0.0, "z": 0.0},
        "boundsMetres": {"minimum": {"x": 0.0, "y": 0.0, "z": 0.0}, "maximum": {"x": 0.12, "y": 0.01, "z": 0.06}},
        "boardPlaneBoundsMetres": {"minimum": {"x": 0.0, "y": 0.0, "z": 0.0}, "maximum": {"x": 0.118, "y": 0.0016, "z": 0.055}},
        "defaultOpacity": 0.42,
        "componentNodePrefix": "ref:",
    }


class AcceptanceTest(unittest.TestCase):
    def _run(self, mutate=None, glb_mutate=None, expected_context=CONTEXT_REVISION):
        with tempfile.TemporaryDirectory() as directory:
            glb_path = Path(directory) / "a.glb"
            glb = write_valid_glb(glb_path)
            manifest = valid_manifest(glb)
            if mutate:
                mutate(manifest)
            if glb_mutate:
                glb_mutate(glb_path)
            manifest_path = Path(directory) / "m.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            return check_package(manifest_path, glb_path, expected_context)

    def codes(self, rejections):
        return {r["code"] for r in rejections}

    def test_valid_package_is_accepted(self) -> None:
        self.assertEqual(self._run(), [])

    def test_boundary_touching_board_plane_is_accepted(self) -> None:
        # contains() is inclusive: a board plane sharing an edge with the
        # overall bounds must pass (our real asset does exactly this)
        def mutate(m):
            m["boardPlaneBoundsMetres"]["maximum"]["x"] = m["boundsMetres"]["maximum"]["x"]
            m["boardPlaneBoundsMetres"]["minimum"]["z"] = m["boundsMetres"]["minimum"]["z"]

        self.assertEqual(self._run(mutate), [])

    def test_bad_schema_major_rejected(self) -> None:
        self.assertIn("INCOMPATIBLE_SCHEMA", self.codes(self._run(lambda m: m.update(schemaVersion="2.0"))))

    def test_blank_identity_rejected(self) -> None:
        self.assertIn("INVALID_MANIFEST", self.codes(self._run(lambda m: m.update(boardIdentity="  "))))

    def test_prefix_too_long_rejected(self) -> None:
        self.assertIn("INVALID_MANIFEST", self.codes(self._run(lambda m: m.update(componentNodePrefix="x" * 33))))

    def test_context_mismatch_rejected(self) -> None:
        self.assertIn("CONTEXT_MISMATCH", self.codes(self._run(expected_context="other-rev")))

    def test_size_mismatch_rejected(self) -> None:
        self.assertIn("ASSET_TOO_LARGE", self.codes(self._run(lambda m: m.update(glbBytes=m["glbBytes"] + 4))))

    def test_degenerate_board_plane_rejected(self) -> None:
        # the zero-thickness silkscreen trap: y extent collapses
        def mutate(m):
            m["boardPlaneBoundsMetres"]["maximum"]["y"] = m["boardPlaneBoundsMetres"]["minimum"]["y"]

        self.assertIn("INVALID_GEOMETRY", self.codes(self._run(mutate)))

    def test_board_plane_outside_overall_bounds_rejected(self) -> None:
        def mutate(m):
            m["boardPlaneBoundsMetres"]["maximum"]["x"] = m["boundsMetres"]["maximum"]["x"] + 0.01

        self.assertIn("INVALID_GEOMETRY", self.codes(self._run(mutate)))

    def test_opacity_out_of_range_rejected(self) -> None:
        self.assertIn("INVALID_GEOMETRY", self.codes(self._run(lambda m: m.update(defaultOpacity=0.95))))

    def test_bounds_beyond_ten_metres_rejected(self) -> None:
        self.assertIn("INVALID_GEOMETRY", self.codes(self._run(lambda m: m["boundsMetres"]["maximum"].update(x=11.0))))

    def test_digest_mismatch_rejected(self) -> None:
        self.assertIn("DIGEST_MISMATCH", self.codes(self._run(lambda m: m.update(glbSha256="sha256:" + "a" * 64))))


class IsGlbV2Test(unittest.TestCase):
    def test_accepts_written_glb(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(is_glb_v2(write_valid_glb(Path(directory) / "a.glb")))

    def test_rejects_truncated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            data = write_valid_glb(Path(directory) / "a.glb")
        self.assertFalse(is_glb_v2(data[:-4]))

    def test_rejects_wrong_declared_length(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            data = bytearray(write_valid_glb(Path(directory) / "a.glb"))
        struct.pack_into("<I", data, 8, len(data) + 8)
        self.assertFalse(is_glb_v2(bytes(data)))

    def test_rejects_too_small(self) -> None:
        self.assertFalse(is_glb_v2(b"glTF"))


if __name__ == "__main__":
    unittest.main()
