#!/usr/bin/env python3
"""Tests for the reference-package builder (issue #45)."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from build_frdm_reference_package import build_reference_package, parse_bom


PCB_FIXTURE = """
(kicad_pcb
  (version 20250114)
  (gr_line
    (start 0.0 0.0)
    (end 10.0 0.0)
    (layer "Edge.Cuts")
    (uuid "line1")
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "aaa")
    (at 5.0 5.0 0)
    (property "Reference" "R1"
      (at 0 0 0)
      (layer "F.SilkS")
    )
    (property "Value" "10k"
      (at 0 0 0)
      (layer "F.Fab")
    )
    (pad "1" smd rect
      (at -0.5 0 0)
      (size 0.5 0.6)
      (layers "F.Cu")
      (net 1 "N1")
    )
    (pad "2" smd rect
      (at 0.5 0 0)
      (size 0.5 0.6)
      (layers "F.Cu")
      (net 2 "N2")
    )
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "bbb")
    (at 8.0 2.0 0)
    (pad "1" thru_hole circle
      (at 0 0 0)
      (size 2.5 2.5)
      (layers "*.Cu")
      (net 0 "")
    )
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "ccc")
    (at 1.0 1.0 0)
    (pad "1" smd circle
      (at 0 0 0)
      (size 0.5 0.5)
      (layers "F.Cu")
      (net 0 "")
    )
  )
)
"""

BOM_FIXTURE = "1\t1\t123-45678\t\t\tR1\n"


class BuildFrdmReferencePackageTest(unittest.TestCase):
    def test_parse_bom_maps_refdes_to_part_number(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bom_path = Path(directory) / "bom.txt"
            bom_path.write_text(
                "1\t4\t510-75297\t\t\t\tBH1,BH2,BH3,BH4\n"
                "2\t6\t150-80128\t\t\t\tC1,C5,C32,C36,C40,C41\n",
                encoding="utf-8",
            )

            result = parse_bom(bom_path)

            self.assertEqual(result["BH1"], "510-75297")
            self.assertEqual(result["C41"], "150-80128")
            self.assertEqual(len(result), 10)

    def test_build_reference_package_from_real_shaped_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            pcb_path = Path(directory) / "board.kicad_pcb"
            pcb_path.write_text(PCB_FIXTURE, encoding="utf-8")
            bom_path = Path(directory) / "bom.txt"
            bom_path.write_text(BOM_FIXTURE, encoding="utf-8")
            brd_path = Path(directory) / "board.brd"
            brd_path.write_bytes(b"fake-allegro-board-bytes")

            bundle = build_reference_package(pcb_path, bom_path, brd_path, "test-board", "Rev X")

            self.assertEqual(bundle["schema_version"], {"major": 1, "minor": 0})
            self.assertEqual(bundle["board"]["board_id"], "test-board")
            self.assertEqual(bundle["board"]["revision"], "Rev X")
            self.assertEqual(len(bundle["board"]["outline"]), 1)

            self.assertEqual(len(bundle["components"]), 1)
            component = bundle["components"][0]
            self.assertEqual(component["reference_designator"], "R1")
            self.assertEqual(component["centroid"], {"x": 5.0, "y": 5.0})
            self.assertTrue(component["bom_cross_check"])
            self.assertEqual(component["bom_part_number"], "123-45678")

            counts = bundle["footprint_classification_report"]["counts_by_category"]
            self.assertEqual(counts.get("mounting_hole_candidate"), 1)
            self.assertEqual(counts.get("fiducial_or_test_point_candidate"), 1)

            landmark_categories = {landmark["category"] for landmark in bundle["landmarks"]}
            self.assertIn("mounting_hole_candidate", landmark_categories)
            self.assertIn("fiducial_or_test_point_candidate", landmark_categories)

            self.assertTrue(bundle["integrity"]["payload_sha256"].startswith("sha256:"))
            self.assertEqual(bundle["status"], "candidate_pending_importer_and_registration_validation")

    def test_unresolved_component_like_footprint_is_not_a_landmark(self) -> None:
        pcb_with_two_pad_unresolved = """
(kicad_pcb
  (version 20250114)
  (footprint ""
    (layer "F.Cu")
    (uuid "ddd")
    (at 3.0 3.0 0)
    (pad "1" smd rect
      (at -0.3 0 0)
      (size 0.4 0.4)
      (layers "F.Cu")
      (net 0 "")
    )
    (pad "2" smd rect
      (at 0.3 0 0)
      (size 0.4 0.4)
      (layers "F.Cu")
      (net 0 "")
    )
  )
)
"""
        with tempfile.TemporaryDirectory() as directory:
            pcb_path = Path(directory) / "board.kicad_pcb"
            pcb_path.write_text(pcb_with_two_pad_unresolved, encoding="utf-8")
            bom_path = Path(directory) / "bom.txt"
            bom_path.write_text(BOM_FIXTURE, encoding="utf-8")
            brd_path = Path(directory) / "board.brd"
            brd_path.write_bytes(b"fake-allegro-board-bytes")

            bundle = build_reference_package(pcb_path, bom_path, brd_path, "test-board", "Rev X")

            counts = bundle["footprint_classification_report"]["counts_by_category"]
            self.assertEqual(counts.get("unresolved_component_like"), 1)
            landmark_categories = {landmark["category"] for landmark in bundle["landmarks"]}
            self.assertNotIn("unresolved_component_like", landmark_categories)
            self.assertEqual(bundle["components"], [])


if __name__ == "__main__":
    unittest.main()
