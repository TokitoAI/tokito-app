#!/usr/bin/env python3
"""Tests for GLB metadata embedding: annotation lands on the right nodes and
the binary payload survives byte-identically."""

from __future__ import annotations

import struct
import tempfile
import unittest
from pathlib import Path

from glb_embed_metadata import CHUNK_BIN, embed_metadata, read_glb, write_glb


def make_test_glb(path: Path) -> bytes:
    gltf = {
        "asset": {"version": "2.0"},
        "scenes": [{"nodes": [0]}],
        "scene": 0,
        "nodes": [
            {"name": "board", "children": [1, 2]},
            {"name": "U1"},
            {"name": "unrelated"},
        ],
    }
    binary_payload = b"\x01\x02\x03\x04\x05\x06\x07\x08"
    write_glb(path, gltf, [(CHUNK_BIN, binary_payload)])
    return binary_payload


def make_index() -> dict:
    return {
        "board_id": "frdm-mcxn236",
        "board_revision": "C",
        "component_count": 1,
        "components": {
            "U1": {
                "reference": "U1",
                "component_class": "integrated_circuit",
                "bom": {"description": "MCU", "manufacturer": "NXP", "part_number": "MCXN236VDFT", "value": None},
                "board_position_mm": {"x": 1.0, "y": -2.0},
                "rotation_degrees": 0.0,
                "side": "top",
            }
        },
    }


def make_resolution() -> dict:
    return {
        "components": [
            {
                "reference": "U1",
                "bom_description": "MCU",
                "package_code": "VFBGA184",
                "model_path": "/m/box.wrl",
                "model_kind": "dimension_accurate_placeholder_not_true_package_shape",
            }
        ]
    }


class GlbRoundTripTest(unittest.TestCase):
    def test_write_read_round_trip_preserves_chunks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "t.glb"
            payload = make_test_glb(path)
            gltf, chunks = read_glb(path)
        self.assertEqual(gltf["nodes"][1]["name"], "U1")
        self.assertEqual(chunks[0][0], CHUNK_BIN)
        self.assertEqual(chunks[0][1][: len(payload)], payload)

    def test_total_length_header_matches_file_size(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "t.glb"
            make_test_glb(path)
            data = path.read_bytes()
        _, _, declared = struct.unpack_from("<III", data, 0)
        self.assertEqual(declared, len(data))


class EmbedMetadataTest(unittest.TestCase):
    def test_annotates_only_component_nodes_and_keeps_binary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "in.glb"
            output = Path(directory) / "out.glb"
            payload = make_test_glb(source)

            result = embed_metadata(source, output, make_index(), make_resolution())
            gltf, chunks = read_glb(output)

        self.assertEqual(result["nodes_annotated"], 1)
        u1 = next(n for n in gltf["nodes"] if n.get("name") == "U1")
        extras = u1["extras"]["tokito_component"]
        self.assertEqual(extras["bom_part_number"], "MCXN236VDFT")
        self.assertEqual(extras["package"], "VFBGA184")
        self.assertEqual(extras["model_quality"], "dimension_accurate_placeholder")
        self.assertNotIn("extras", next(n for n in gltf["nodes"] if n.get("name") == "unrelated"))
        self.assertEqual(gltf["asset"]["extras"]["tokito_board"]["board_id"], "frdm-mcxn236")
        self.assertEqual(chunks[0][1][: len(payload)], payload)


if __name__ == "__main__":
    unittest.main()
