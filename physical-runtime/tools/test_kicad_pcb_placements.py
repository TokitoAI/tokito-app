#!/usr/bin/env python3
"""Tests for KiCad PCB placement/reference-designator extraction."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from kicad_pcb_placements import extract_board_outline, extract_footprint_pads, extract_placements, parse_pcb_tree


FIXTURE = """
(kicad_pcb
  (version 20250114)
  (gr_line
    (start 0.0 0.0)
    (end 100.0 0.0)
    (layer "Edge.Cuts")
    (uuid "line1")
  )
  (gr_arc
    (start 100.0 0.0)
    (mid 105.0 5.0)
    (end 100.0 10.0)
    (layer "Edge.Cuts")
    (uuid "arc1")
  )
  (gr_line
    (start 100.0 10.0)
    (end 0.0 10.0)
    (layer "F.SilkS")
    (uuid "line-not-outline")
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "aaa")
    (at 35.797007 -12.689002 180)
    (property "Reference" "R87"
      (at 0.635 1.93675 0)
      (layer "F.SilkS")
    )
    (property "Value" "10k"
      (at 0 0 180)
      (layer "F.Fab")
    )
    (pad "1" smd rect
      (at -0.5 0 0)
      (size 0.5 0.6)
      (layers "F.Cu")
      (net 3 "NET1")
    )
    (pad "2" smd rect
      (at 0.5 0 0)
      (size 0.5 0.6)
      (layers "F.Cu")
      (net 4 "NET2")
    )
  )
  (footprint ""
    (layer "B.Cu")
    (uuid "bbb")
    (at 56.752007 27.548002 90)
    (property "Reference" "U7"
      (at -1.651 0.03175 0)
      (layer "B.SilkS")
    )
    (property "Reference" "${REFERENCE}"
      (at -0.4064 0.00635 180)
      (layer "B.Fab")
      (hide yes)
    )
    (property "Value" ""
      (at 0 0 180)
      (layer "B.Fab")
    )
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "ccc")
    (at 10.0 20.0 0)
    (pad "1" thru_hole circle
      (at 0 0 0)
      (size 2.0 2.0)
      (layers "*.Cu")
      (net 0 "")
    )
  )
)
"""


class KicadPcbPlacementsTest(unittest.TestCase):
    def test_extracts_real_reference_and_skips_placeholder(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "board.kicad_pcb"
            path.write_text(FIXTURE, encoding="utf-8")

            placements = extract_placements(path)

            self.assertEqual(len(placements), 3)

            first = placements[0]
            self.assertEqual(first["reference"], "R87")
            self.assertFalse(first["is_placeholder_reference"])
            self.assertEqual(first["value"], "10k")
            self.assertEqual(first["side"], "top")
            self.assertAlmostEqual(first["x_mm"], 35.797007)
            self.assertAlmostEqual(first["y_mm"], -12.689002)
            self.assertAlmostEqual(first["rotation_deg"], 180)

            second = placements[1]
            self.assertEqual(second["reference"], "U7")
            self.assertFalse(second["is_placeholder_reference"])
            self.assertEqual(second["side"], "bottom")

    def test_missing_reference_is_marked_placeholder(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "board.kicad_pcb"
            path.write_text(FIXTURE, encoding="utf-8")

            placements = extract_placements(path)

            third = placements[2]
            self.assertEqual(third["reference"], "")
            self.assertTrue(third["is_placeholder_reference"])
            self.assertEqual(third["x_mm"], 10.0)
            self.assertEqual(third["y_mm"], 20.0)

    def test_extracts_board_outline_from_edge_cuts_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "board.kicad_pcb"
            path.write_text(FIXTURE, encoding="utf-8")

            tree = parse_pcb_tree(path)
            outline = extract_board_outline(tree)

            self.assertEqual(len(outline), 2)
            self.assertEqual(outline[0]["type"], "gr_line")
            self.assertEqual(outline[0]["start"], {"x": 0.0, "y": 0.0})
            self.assertEqual(outline[0]["end"], {"x": 100.0, "y": 0.0})
            self.assertEqual(outline[1]["type"], "gr_arc")
            self.assertEqual(outline[1]["mid"], {"x": 105.0, "y": 5.0})

    def test_extracts_pads_per_footprint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "board.kicad_pcb"
            path.write_text(FIXTURE, encoding="utf-8")

            tree = parse_pcb_tree(path)
            footprints = [
                node for node in tree if isinstance(node, list) and node and node[0] == "footprint"
            ]

            first_pads = extract_footprint_pads(footprints[0])
            self.assertEqual(len(first_pads), 2)
            self.assertEqual(first_pads[0]["shape"], "rect")
            self.assertAlmostEqual(first_pads[0]["size_w_mm"], 0.5)
            self.assertAlmostEqual(first_pads[0]["at_x_mm"], -0.5)
            self.assertAlmostEqual(first_pads[1]["at_x_mm"], 0.5)

            third_pads = extract_footprint_pads(footprints[2])
            self.assertEqual(len(third_pads), 1)
            self.assertEqual(third_pads[0]["pad_type"], "thru_hole")
            self.assertEqual(third_pads[0]["shape"], "circle")
            self.assertAlmostEqual(third_pads[0]["size_w_mm"], 2.0)


if __name__ == "__main__":
    unittest.main()
