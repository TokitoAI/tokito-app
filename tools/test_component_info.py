#!/usr/bin/env python3
"""Tests for the per-component board information index."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from component_info import (
    _pad_name_polarity,
    build_component_index,
    extract_board_nets,
    find_components,
    get_component_info,
    reference_class,
)


BOARD_FIXTURE = """
(kicad_pcb
  (version 20250114)
  (footprint ""
    (layer "F.Cu")
    (at 10 -20 0)
    (property "Reference" "D1" (at 0 0 0) (layer "F.SilkS"))
    (pad "A" smd rect (at 0 0) (size 1 1) (layers "F.Cu") (net "P5V_USB"))
    (pad "C" smd rect (at 2 0) (size 1 1) (layers "F.Cu") (net "P5V0"))
  )
  (footprint ""
    (layer "F.Cu")
    (at 30 -20 0)
    (property "Reference" "R1" (at 0 0 0) (layer "F.SilkS"))
    (pad "1" smd rect (at 0 0) (size 1 1) (layers "F.Cu") (net 4 "P5V0"))
    (pad "2" smd rect (at 1 0) (size 1 1) (layers "F.Cu") (net 7 "GND"))
  )
)
"""


def make_reference_package() -> dict:
    return {
        "board_id": "frdm-mcxn236",
        "board_revision": "C",
        "components": [
            {
                "reference_designator": "D1",
                "centroid": {"x": 10.0, "y": -20.0},
                "rotation_degrees": 0.0,
                "side": "top",
                "polarity_or_pin_one": None,
            },
            {
                "reference_designator": "R1",
                "centroid": {"x": 30.0, "y": -20.0},
                "rotation_degrees": 0.0,
                "side": "top",
                "polarity_or_pin_one": None,
            },
        ],
    }


def make_resolution() -> dict:
    return {
        "components": [
            {"reference": "D1", "model_path": None, "model_kind": None, "status": "no_recognized_package_code"},
            {"reference": "R1", "model_path": "/lib/R_0603.step", "model_kind": "exact_family", "status": "matched_official_kicad_model"},
        ]
    }


def build_index() -> dict:
    with tempfile.TemporaryDirectory() as directory:
        board = Path(directory) / "board.kicad_pcb"
        board.write_text(BOARD_FIXTURE, encoding="utf-8")
        nets = extract_board_nets(board)
    bom = {
        "D1": {"Description": "DIODE SCH 1A 30V", "MFG": "VISHAY", "MFG_PN": "MSS1P3L", "VALUE": "MSS1P3L"},
        "R1": {"Description": "RES 10K 0603", "MFG": "YAGEO", "MFG_PN": "RC0603", "VALUE": "10K"},
    }
    return build_component_index(make_reference_package(), make_resolution(), bom, nets)


class ReferenceClassTest(unittest.TestCase):
    def test_prefixes(self) -> None:
        self.assertEqual(reference_class("U17"), "integrated_circuit")
        self.assertEqual(reference_class("JP3"), "jumper")
        self.assertEqual(reference_class("J7"), "connector_or_header")
        self.assertEqual(reference_class("SW2"), "switch")
        self.assertIsNone(reference_class("XX9"))


class ExtractBoardNetsTest(unittest.TestCase):
    def test_reads_both_net_forms(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            board = Path(directory) / "board.kicad_pcb"
            board.write_text(BOARD_FIXTURE, encoding="utf-8")
            nets = extract_board_nets(board)
        # importer form (net "NAME") and native form (net 4 "NAME") both land
        self.assertEqual(nets["pads_by_reference"]["D1"][1]["net"], "P5V0")
        self.assertEqual(nets["pads_by_reference"]["R1"][0]["net"], "P5V0")
        self.assertEqual(
            sorted(endpoint["reference"] for endpoint in nets["net_connectivity"]["P5V0"]),
            ["D1", "R1"],
        )


class PadNamePolarityTest(unittest.TestCase):
    def test_exact_anode_cathode_pads(self) -> None:
        result = _pad_name_polarity([{"number": "A", "net": None}, {"number": "C", "net": None}])
        self.assertEqual(result["anode_pad"], "A")
        self.assertEqual(result["source"], "vendor_footprint_pad_names")

    def test_anything_else_stays_null(self) -> None:
        self.assertIsNone(_pad_name_polarity([{"number": "1", "net": None}, {"number": "2", "net": None}]))
        self.assertIsNone(_pad_name_polarity([{"number": "A", "net": None}]))


class BuildAndQueryIndexTest(unittest.TestCase):
    def test_joined_component_record(self) -> None:
        index = build_index()
        info = get_component_info(index, "R1")
        self.assertEqual(info["component_class"], "resistor")
        self.assertEqual(info["bom"]["part_number"], "RC0603")
        self.assertEqual(info["glb_node_name"], "R1")
        self.assertEqual(info["model"]["file"], "R_0603.step")

    def test_no_model_means_no_glb_node_claim(self) -> None:
        index = build_index()
        info = get_component_info(index, "D1")
        self.assertIsNone(info["glb_node_name"])
        self.assertEqual(info["model"]["status"], "no_recognized_package_code")

    def test_connections_list_peers_on_same_net(self) -> None:
        index = build_index()
        info = get_component_info(index, "D1")
        by_pad = {c["pad"]: c for c in info["connections"]}
        self.assertEqual(by_pad["C"]["net"], "P5V0")
        self.assertEqual(by_pad["C"]["connected_to"], [{"reference": "R1", "pad": "1"}])
        self.assertEqual(by_pad["A"]["connected_to"], [])  # sole endpoint in fixture

    def test_diode_polarity_from_vendor_pad_names(self) -> None:
        index = build_index()
        info = get_component_info(index, "D1")
        self.assertEqual(info["polarity_or_pin_one"]["cathode_pad"], "C")

    def test_unknown_reference_returns_none(self) -> None:
        self.assertIsNone(get_component_info(build_index(), "Z99"))

    def test_search_matches_designator_and_bom_text(self) -> None:
        index = build_index()
        self.assertEqual(find_components(index, "diode"), ["D1"])
        self.assertEqual(find_components(index, "r1"), ["R1"])
        self.assertEqual(find_components(index, "vishay"), ["D1"])
        self.assertEqual(find_components(index, "nothing-here"), [])


if __name__ == "__main__":
    unittest.main()
